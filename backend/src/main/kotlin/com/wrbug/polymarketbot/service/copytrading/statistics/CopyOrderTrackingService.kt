package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.service.accounts.AccountExecutionDiagnosticsService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.MarketFilterInput
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingService
import com.wrbug.polymarketbot.service.copytrading.configs.FilterStatus
import com.wrbug.polymarketbot.service.copytrading.configs.SizingStatus
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationBatch
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationBufferStatus
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationRequest
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationReleaseReason
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationSnapshot
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationService
import com.wrbug.polymarketbot.service.copytrading.configs.SizingRejectionType
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventRecordRequest
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventService

data class TradeProcessingLatencyContext(
    val sourceReceivedAt: Long? = null,
    val processTradeStartedAt: Long = System.currentTimeMillis(),
    val leaderTradeTimestamp: Long? = null,
    val marketMetaResolveMs: Long? = null,
    val marketMetaSource: String? = null,
    val filterEvaluateMs: Long? = null
)

/**
 * 订单跟踪服务
 * 处理买入订单跟踪和卖出订单匹配
 * 实际创建订单并记录跟踪信息
 */
@Service
open class CopyOrderTrackingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val processedTradeRepository: ProcessedTradeRepository,
    private val filteredOrderRepository: FilteredOrderRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val filterService: CopyTradingFilterService,
    private val sizingService: CopyTradingSizingService,
    private val smallOrderAggregationService: SmallOrderAggregationService,
    private val accountExecutionDiagnosticsService: AccountExecutionDiagnosticsService,
    private val executionEventService: CopyTradingExecutionEventService,
    private val leaderRepository: LeaderRepository,
    private val orderSigningService: OrderSigningService,
    private val blockchainService: BlockchainService,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,  // 市场信息服务
    private val telegramNotificationService: TelegramNotificationService? = null  // 可选，避免循环依赖
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(CopyOrderTrackingService::class.java)

    // 协程作用域（用于异步发送通知）
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    /**
     * 获取代理对象，用于解决 @Transactional 自调用问题
     */
    private fun getSelf(): CopyOrderTrackingService {
        return applicationContext?.getBean(CopyOrderTrackingService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    // 使用 Mutex 保证线程安全（按交易ID锁定）
    private val tradeMutexMap = ConcurrentHashMap<String, Mutex>()
    private val tradeLatencyContextMap = ConcurrentHashMap<String, TradeProcessingLatencyContext>()

    // 订单创建重试配置
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2  // 最多重试次数（首次 + 1次重试）
        private const val RETRY_DELAY_MS = 3000L  // 重试前等待时间（毫秒，3秒）
    }

    private val aggregationFlushRunning = AtomicBoolean(false)

    private data class BuyTradeSegment(
        val leaderTradeId: String,
        val leaderQuantity: BigDecimal,
        val leaderOrderAmount: BigDecimal,
        val tradePrice: BigDecimal,
        val outcome: String?,
        val source: String
    )

    private data class BuyExecutionPayload(
        val tokenId: String,
        val marketId: String,
        val outcomeIndex: Int?,
        val marketSlug: String? = null,
        val marketEventSlug: String? = null,
        val marketSeriesSlugPrefix: String? = null,
        val marketIntervalSeconds: Int? = null,
        val tradePrice: BigDecimal,
        val leaderQuantity: BigDecimal,
        val leaderOrderAmount: BigDecimal,
        val outcome: String?,
        val source: String,
        val representativeTradeId: String,
        val trades: List<BuyTradeSegment>,
        val allowAggregation: Boolean,
        val aggregationReleaseReason: SmallOrderAggregationReleaseReason? = null
    )

    private data class ResolvedMarketFilterInput(
        val input: MarketFilterInput,
        val metadataSource: String
    )

    /**
     * 获取或创建 Mutex（按交易ID）
     */
    private fun getMutex(leaderId: Long, tradeId: String): Mutex {
        val key = "${leaderId}_${tradeId}"
        return tradeMutexMap.getOrPut(key) { Mutex() }
    }

    /**
     * 解密账户私钥
     */
    private fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }

    /**
     * 解密账户 API Secret
     */
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }

    /**
     * 解密账户 API Passphrase
     */
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    @Scheduled(fixedDelay = 1000)
    fun flushBufferedBuyAggregations() {
        if (!aggregationFlushRunning.compareAndSet(false, true)) {
            return
        }

        try {
            runBlocking {
                releaseBufferedBuyAggregations()
                releaseBufferedSellAggregations()
            }
        } finally {
            aggregationFlushRunning.set(false)
        }
    }

    fun getSmallOrderAggregationDiagnostics(copyTradingId: Long? = null): SmallOrderAggregationSnapshot {
        return smallOrderAggregationService.getSnapshot(copyTradingId)
    }

    /**
     * 处理交易事件（WebSocket 或轮询）
     * 根据交易方向调用相应的处理方法
     * 使用 Mutex 保证线程安全（单实例部署）。
     * 小额聚合缓冲为进程内内存态，重启会丢失待释放批次；多实例下不保证严格聚合一致性。
     */
    @Transactional
    suspend fun processTrade(
        leaderId: Long,
        trade: TradeResponse,
        source: String,
        latencyContext: TradeProcessingLatencyContext? = null
    ): Result<Unit> {
        // 获取该交易的 Mutex（按交易ID锁定，不同交易可以并行处理）
        val mutex = getMutex(leaderId, trade.id)
        logger.debug("processTrade: ${trade.id}, $source")
        return mutex.withLock {
            val effectiveLatencyContext = latencyContext?.copy(
                leaderTradeTimestamp = latencyContext.leaderTradeTimestamp ?: parseTradeTimestampMillis(trade.timestamp)
            ) ?: TradeProcessingLatencyContext(
                sourceReceivedAt = null,
                processTradeStartedAt = System.currentTimeMillis(),
                leaderTradeTimestamp = parseTradeTimestampMillis(trade.timestamp)
            )
            tradeLatencyContextMap[buildTradeLatencyKey(leaderId, trade.id, source)] = effectiveLatencyContext
            try {
                // 1. 检查是否已处理（去重，包括失败状态）
                val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)

                if (existingProcessed != null) {
                    logger.debug("processTrade: 重复 ${trade.id}, $source")
                    if (existingProcessed.status == "FAILED") {
                        return@withLock Result.success(Unit)
                    }
                    return@withLock Result.success(Unit)
                }

                // 2. 处理交易逻辑（通过代理对象调用，确保 @Transactional 生效）
                val self = getSelf()
                val result = when (trade.side.uppercase()) {
                    "BUY" -> self.processBuyTrade(leaderId, trade, source)
                    "SELL" -> self.processSellTrade(leaderId, trade, source)
                    else -> {
                        logger.warn("未知的交易方向: ${trade.side}")
                        Result.failure(IllegalArgumentException("未知的交易方向: ${trade.side}"))
                    }
                }

                if (result.isFailure) {
                    logger.error(
                        "处理交易失败: leaderId=$leaderId, tradeId=${trade.id}, side=${trade.side}",
                        result.exceptionOrNull()
                    )
                    return@withLock result
                }

                // 3. 标记为已处理（成功状态）
                // 由于使用了 Mutex，这里理论上不会出现并发冲突，但保留异常处理作为兜底
                try {
                    val processed = ProcessedTrade(
                        leaderId = leaderId,
                        leaderTradeId = trade.id,
                        tradeType = trade.side.uppercase(),
                        source = source,
                        status = "SUCCESS",
                        processedAt = System.currentTimeMillis()
                    )
                    processedTradeRepository.save(processed)

                } catch (e: Exception) {
                    // 检查是否是唯一键冲突异常（理论上不会发生，但保留作为兜底）
                    if (isUniqueConstraintViolation(e)) {
                        val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                        if (existing != null) {
                            if (existing.status == "FAILED") {
                                logger.debug("交易已标记为失败，跳过处理: leaderId=$leaderId, tradeId=${trade.id}")
                                return@withLock Result.success(Unit)
                            }
                            logger.debug("交易已处理（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existing.status}")
                            return@withLock Result.success(Unit)
                        } else {
                            // 如果检查不到，可能是事务隔离级别问题，等待一下再查询
                            delay(100)
                            val existingAfterDelay =
                                processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                            if (existingAfterDelay != null) {
                                logger.debug("延迟查询到记录（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existingAfterDelay.status}")
                                return@withLock Result.success(Unit)
                            }
                            logger.warn(
                                "保存ProcessedTrade时发生唯一约束冲突，但查询不到记录: leaderId=$leaderId, tradeId=${trade.id}",
                                e
                            )
                            return@withLock Result.success(Unit)
                        }
                    } else {
                        // 其他类型的异常，重新抛出
                        throw e
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
                Result.failure(e)
            } finally {
                tradeLatencyContextMap.remove(buildTradeLatencyKey(leaderId, trade.id, source))
            }
        }
    }

    /**
     * 处理买入交易
     * 创建跟单买入订单并记录到跟踪表
     */
    @Transactional
    suspend fun processBuyTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            val payload = buildImmediateBuyPayload(trade, source) ?: return Result.success(Unit)
            for (copyTrading in copyTradings) {
                try {
                    val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                        ?: continue
                    executeBuyForCopyTrading(copyTrading, account, payload)
                } catch (e: Exception) {
                    logger.error("处理买入交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", e)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理买入交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    private suspend fun buildImmediateBuyPayload(
        trade: TradeResponse,
        source: String
    ): BuyExecutionPayload? {
        val tokenId = if (!trade.tokenId.isNullOrBlank()) {
            trade.tokenId
        } else {
            if (trade.outcomeIndex == null) {
                logger.warn("交易缺少outcomeIndex且无tokenId，无法确定tokenId: tradeId=${trade.id}, market=${trade.market}")
                return null
            }
            val tokenIdResult = blockchainService.getTokenId(trade.market, trade.outcomeIndex)
            if (tokenIdResult.isFailure) {
                logger.error("获取tokenId失败: market=${trade.market}, outcomeIndex=${trade.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
                return null
            }
            tokenIdResult.getOrNull() ?: return null
        }

        var effectiveMarketId = trade.market
        var effectiveOutcomeIndex = trade.outcomeIndex
        if (effectiveMarketId.isBlank() && !trade.tokenId.isNullOrBlank()) {
            val infoByToken = marketService.getMarketInfoByTokenId(trade.tokenId)
            if (infoByToken != null) {
                effectiveMarketId = infoByToken.conditionId
                effectiveOutcomeIndex = infoByToken.outcomeIndex
            }
        }
        if (effectiveMarketId.isBlank()) {
            logger.warn("无法确定市场(conditionId)，跳过: tradeId=${trade.id}, tokenId=${trade.tokenId}")
            return null
        }

        val seriesMetadata = MarketFilterSupport.deriveMarketSeriesMetadata(
            slug = trade.slug,
            eventSlug = trade.eventSlug
        )
        val tradePrice = trade.price.toSafeBigDecimal()
        val leaderQuantity = trade.size.toSafeBigDecimal()
        val leaderOrderAmount = leaderQuantity.multi(tradePrice)
        return BuyExecutionPayload(
            tokenId = tokenId,
            marketId = effectiveMarketId,
            outcomeIndex = effectiveOutcomeIndex,
            marketSlug = trade.slug,
            marketEventSlug = trade.eventSlug,
            marketSeriesSlugPrefix = seriesMetadata.seriesSlugPrefix,
            marketIntervalSeconds = seriesMetadata.intervalSeconds,
            tradePrice = tradePrice,
            leaderQuantity = leaderQuantity,
            leaderOrderAmount = leaderOrderAmount,
            outcome = trade.outcome,
            source = source,
            representativeTradeId = trade.id,
            trades = listOf(
                BuyTradeSegment(
                    leaderTradeId = trade.id,
                    leaderQuantity = leaderQuantity,
                    leaderOrderAmount = leaderOrderAmount,
                    tradePrice = tradePrice,
                    outcome = trade.outcome,
                    source = source
                )
            ),
            allowAggregation = true
        )
    }

    private fun buildAggregatedBuyPayload(
        batch: SmallOrderAggregationBatch,
        releaseReason: SmallOrderAggregationReleaseReason
    ): BuyExecutionPayload {
        return BuyExecutionPayload(
            tokenId = batch.tokenId,
            marketId = batch.marketId,
            outcomeIndex = batch.outcomeIndex,
            marketSlug = batch.marketSlug,
            marketEventSlug = batch.marketEventSlug,
            marketSeriesSlugPrefix = batch.seriesSlugPrefix,
            marketIntervalSeconds = batch.intervalSeconds,
            tradePrice = batch.averageTradePrice,
            leaderQuantity = batch.totalLeaderQuantity,
            leaderOrderAmount = batch.totalLeaderOrderAmount,
            outcome = batch.representativeOutcome,
            source = "aggregated",
            representativeTradeId = batch.representativeTradeId,
            trades = batch.trades.map { trade ->
                BuyTradeSegment(
                    leaderTradeId = trade.leaderTradeId,
                    leaderQuantity = trade.leaderQuantity,
                    leaderOrderAmount = trade.leaderOrderAmount,
                    tradePrice = trade.tradePrice,
                    outcome = trade.outcome,
                    source = trade.source
                )
            },
            allowAggregation = false,
            aggregationReleaseReason = releaseReason
        )
    }

    private suspend fun releaseBufferedBuyAggregations() {
        val allCopyTradings = copyTradingRepository.findAll()
            .filter { it.id != null }
        val activeAggregationIds = allCopyTradings
            .asSequence()
            .filter { it.enabled && it.smallOrderAggregationEnabled }
            .map { it.id!! }
            .toSet()
        val clearedGroups = smallOrderAggregationService.clearInactive(activeAggregationIds)
        if (clearedGroups > 0) {
            logger.info("已清理 {} 个失活的小额 BUY 聚合缓冲组", clearedGroups)
        }

        val enabledCopyTradings = allCopyTradings
            .filter { it.enabled && it.smallOrderAggregationEnabled }
        if (enabledCopyTradings.isEmpty()) {
            return
        }

        val readyBatches = smallOrderAggregationService.releaseExpired(
            enabledCopyTradings.associate { it.id!! to it.smallOrderAggregationWindowSeconds },
            side = "BUY"
        )
        if (readyBatches.isEmpty()) {
            return
        }

        logger.info("检测到 {} 个到期的小额 BUY 聚合组，开始释放执行", readyBatches.size)
        for (release in readyBatches) {
            val batch = release.batch
            try {
                val copyTrading = copyTradingRepository.findById(batch.copyTradingId).orElse(null)
                if (copyTrading == null) {
                    logger.warn("聚合组对应的跟单配置不存在，直接丢弃: key={}, copyTradingId={}", batch.key, batch.copyTradingId)
                    continue
                }
                val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                if (account == null) {
                    logger.warn("聚合组对应的账户不存在，直接丢弃: key={}, accountId={}", batch.key, copyTrading.accountId)
                    continue
                }

                val payload = buildAggregatedBuyPayload(batch, release.reason)
                recordExecutionEvent(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    stage = "AGGREGATION",
                    eventType = aggregationReleaseEventType(release.reason),
                    status = "info",
                    message = aggregationReleaseMessage(release.reason),
                    aggregationKey = batch.key,
                    aggregationTradeCount = batch.trades.size
                )
                if (!copyTrading.enabled || !copyTrading.smallOrderAggregationEnabled) {
                    recordFilteredBuyPayloadAsync(
                        copyTrading = copyTrading,
                        account = account,
                        payload = payload,
                        filterReason = "聚合窗口到期前配置已禁用，放弃执行",
                        filterType = "AGGREGATION_DISABLED",
                        calculatedQuantity = null
                    )
                    recordExecutionEvent(
                        copyTrading = copyTrading,
                        account = account,
                        payload = payload,
                        stage = "AGGREGATION",
                        eventType = "AGGREGATION_DISCARDED",
                        status = "warning",
                        message = "聚合窗口到期前配置已禁用，放弃执行",
                        aggregationKey = batch.key,
                        aggregationTradeCount = batch.trades.size
                    )
                    continue
                }

                executeBuyForCopyTrading(copyTrading, account, payload)
            } catch (e: Exception) {
                logger.error("释放小额 BUY 聚合组失败: key=${batch.key}, copyTradingId=${batch.copyTradingId}", e)
            }
        }
    }

    private suspend fun tryReleaseBufferedBuyAggregation(
        copyTrading: CopyTrading,
        account: Account,
        batch: SmallOrderAggregationBatch
    ): Result<Unit>? {
        if (batch.trades.size <= 1) {
            return null
        }
        val sizingResult = sizingService.calculateRealTimeBuySizing(
            copyTrading = copyTrading,
            leaderOrderAmount = batch.totalLeaderOrderAmount,
            tradePrice = batch.averageTradePrice,
            marketId = batch.marketId,
            outcomeIndex = batch.outcomeIndex
        )
        if (sizingResult.status != SizingStatus.EXECUTABLE) {
            return null
        }

        val release = smallOrderAggregationService.release(
            key = batch.key,
            reason = SmallOrderAggregationReleaseReason.THRESHOLD_REACHED
        ) ?: return null
        val payload = buildAggregatedBuyPayload(release.batch, release.reason)
        recordExecutionEvent(
            copyTrading = copyTrading,
            account = account,
            payload = payload,
            stage = "AGGREGATION",
            eventType = aggregationReleaseEventType(release.reason),
            status = "info",
            message = aggregationReleaseMessage(release.reason),
            aggregationKey = release.batch.key,
            aggregationTradeCount = release.batch.trades.size,
            calculatedQuantity = sizingResult.finalQuantity.takeIf { it > BigDecimal.ZERO }
        )
        return executeBuyForCopyTrading(copyTrading, account, payload)
    }

    private suspend fun releaseBufferedSellAggregations() {
        val enabledCopyTradings = copyTradingRepository.findAll()
            .filter { it.id != null && it.enabled && it.smallOrderAggregationEnabled }
        if (enabledCopyTradings.isEmpty()) {
            return
        }

        val readyBatches = smallOrderAggregationService.releaseExpired(
            enabledCopyTradings.associate { it.id!! to it.smallOrderAggregationWindowSeconds },
            side = "SELL"
        )
        if (readyBatches.isEmpty()) {
            return
        }

        logger.info("检测到 {} 个到期的小额 SELL 聚合组，开始释放执行", readyBatches.size)
        for (release in readyBatches) {
            val batch = release.batch
            try {
                val copyTrading = copyTradingRepository.findById(batch.copyTradingId).orElse(null)
                if (copyTrading == null) {
                    logger.warn("SELL 聚合组对应的跟单配置不存在，直接丢弃: key={}, copyTradingId={}", batch.key, batch.copyTradingId)
                    continue
                }
                val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                if (account == null) {
                    logger.warn("SELL 聚合组对应的账户不存在，直接丢弃: key={}, accountId={}", batch.key, copyTrading.accountId)
                    continue
                }

                val aggregatedTrade = buildAggregatedSellTrade(batch)
                recordTradeExecutionEvent(
                    copyTrading = copyTrading,
                    accountId = account.id ?: copyTrading.accountId,
                    trade = aggregatedTrade,
                    stage = "AGGREGATION",
                    eventType = aggregationReleaseEventType(release.reason),
                    status = "info",
                    message = aggregationReleaseMessage(release.reason, "SELL"),
                    aggregationKey = batch.key,
                    aggregationTradeCount = batch.trades.size,
                    calculatedQuantity = batch.totalLeaderQuantity
                )
                if (!copyTrading.enabled || !copyTrading.smallOrderAggregationEnabled) {
                    recordTradeExecutionEvent(
                        copyTrading = copyTrading,
                        accountId = account.id ?: copyTrading.accountId,
                        trade = aggregatedTrade,
                        stage = "AGGREGATION",
                        eventType = "AGGREGATION_DISCARDED",
                        status = "warning",
                        message = "聚合窗口到期前配置已禁用，放弃执行",
                        aggregationKey = batch.key,
                        aggregationTradeCount = batch.trades.size,
                        calculatedQuantity = batch.totalLeaderQuantity
                    )
                    continue
                }

                matchSellOrder(
                    copyTrading = copyTrading,
                    leaderSellTrade = aggregatedTrade,
                    source = "aggregated",
                    allowAggregation = false,
                    aggregationReleaseReason = release.reason,
                    aggregationKey = batch.key,
                    aggregationTradeCount = batch.trades.size
                )
            } catch (e: Exception) {
                logger.error("释放小额 SELL 聚合组失败: key=${batch.key}, copyTradingId=${batch.copyTradingId}", e)
            }
        }
    }

    private suspend fun tryReleaseBufferedSellAggregation(
        copyTrading: CopyTrading,
        account: Account,
        batch: SmallOrderAggregationBatch
    ) {
        if (batch.trades.size <= 1 || batch.outcomeIndex == null) {
            return
        }

        val unmatchedOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
            copyTrading.id!!,
            batch.marketId,
            batch.outcomeIndex
        )
        if (unmatchedOrders.isEmpty()) {
            return
        }
        val previewNeedMatch = calculateSellQuantityByActualRatio(
            unmatchedOrders = unmatchedOrders,
            leaderSellQuantity = batch.totalLeaderQuantity,
            copyTrading = copyTrading
        )
        if (previewNeedMatch.lt(BigDecimal.ONE)) {
            return
        }

        val release = smallOrderAggregationService.release(
            key = batch.key,
            reason = SmallOrderAggregationReleaseReason.THRESHOLD_REACHED
        ) ?: return

        val aggregatedTrade = buildAggregatedSellTrade(release.batch)
        recordTradeExecutionEvent(
            copyTrading = copyTrading,
            accountId = account.id ?: copyTrading.accountId,
            trade = aggregatedTrade,
            stage = "AGGREGATION",
            eventType = aggregationReleaseEventType(release.reason),
            status = "info",
            message = aggregationReleaseMessage(release.reason, "SELL"),
            aggregationKey = release.batch.key,
            aggregationTradeCount = release.batch.trades.size,
            calculatedQuantity = release.batch.totalLeaderQuantity
        )

        matchSellOrder(
            copyTrading = copyTrading,
            leaderSellTrade = aggregatedTrade,
            source = "aggregated",
            allowAggregation = false,
            aggregationReleaseReason = release.reason,
            aggregationKey = release.batch.key,
            aggregationTradeCount = release.batch.trades.size
        )
    }

    private fun buildAggregatedSellTrade(batch: SmallOrderAggregationBatch): TradeResponse {
        return TradeResponse(
            id = batch.representativeTradeId,
            market = batch.marketId,
            side = "SELL",
            price = batch.averageTradePrice.stripTrailingZeros().toPlainString(),
            size = batch.totalLeaderQuantity.stripTrailingZeros().toPlainString(),
            timestamp = System.currentTimeMillis().toString(),
            user = null,
            outcomeIndex = batch.outcomeIndex,
            outcome = batch.representativeOutcome,
            tokenId = batch.tokenId
        )
    }

    private fun aggregationReleaseEventType(reason: SmallOrderAggregationReleaseReason): String {
        return when (reason) {
            SmallOrderAggregationReleaseReason.THRESHOLD_REACHED -> "AGGREGATION_THRESHOLD_REACHED"
            SmallOrderAggregationReleaseReason.WINDOW_EXPIRED -> "AGGREGATION_WINDOW_EXPIRED"
        }
    }

    private fun aggregationReleaseMessage(reason: SmallOrderAggregationReleaseReason, side: String = "BUY"): String {
        return when (reason) {
            SmallOrderAggregationReleaseReason.THRESHOLD_REACHED -> "聚合累计达到最小执行阈值，立即释放 ${side.uppercase()} 执行"
            SmallOrderAggregationReleaseReason.WINDOW_EXPIRED -> "聚合窗口到期，开始释放 ${side.uppercase()} 执行"
        }
    }

    private suspend fun executeBuyForCopyTrading(
        copyTrading: CopyTrading,
        account: Account,
        payload: BuyExecutionPayload
    ): Result<Unit> {
        if (payload.trades.size == 1) {
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "DISCOVERY",
                eventType = "SIGNAL_DETECTED",
                status = "info",
                message = "检测到新的 BUY 跟单信号"
            )
        }

        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置API凭证，跳过创建订单: accountId=${account.id}, copyTradingId=${copyTrading.id}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = "账户未配置完整 API 凭证，跳过执行",
                filterType = "EXECUTION_PRECHECK",
                calculatedQuantity = null
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "ACCOUNT_CREDENTIALS_MISSING",
                status = "error",
                message = "账户未配置完整 API 凭证，无法执行跟单"
            )
            return Result.success(Unit)
        }
        if (!account.isEnabled) {
            logger.warn("账户已禁用，跳过创建订单: accountId=${account.id}, copyTradingId=${copyTrading.id}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = "账户已禁用，跳过执行",
                filterType = "EXECUTION_PRECHECK",
                calculatedQuantity = null
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "ACCOUNT_DISABLED",
                status = "error",
                message = "账户已禁用，无法执行跟单"
            )
            return Result.success(Unit)
        }

        val marketMetaResolveStartedAt = System.currentTimeMillis()
        val resolvedMarketFilterInput = resolveMarketFilterInput(copyTrading, payload)
        val marketMetaResolveMs = (System.currentTimeMillis() - marketMetaResolveStartedAt).coerceAtLeast(0)

        val filterEvaluateStartedAt = System.currentTimeMillis()
        val filterResult = filterService.checkFilters(
            copyTrading,
            payload.tokenId,
            tradePrice = payload.tradePrice,
            market = resolvedMarketFilterInput.input
        )
        val filterEvaluateMs = (System.currentTimeMillis() - filterEvaluateStartedAt).coerceAtLeast(0)
        updateTradeLatencyContext(
            leaderId = copyTrading.leaderId,
            tradeId = payload.representativeTradeId,
            source = payload.source,
            marketMetaResolveMs = marketMetaResolveMs,
            marketMetaSource = resolvedMarketFilterInput.metadataSource,
            filterEvaluateMs = filterEvaluateMs
        )
        logger.debug(
            "跟单过滤耗时: copyTradingId={}, tradeId={}, marketMetaResolveMs={}ms, filterEvaluateMs={}ms, marketMetaSource={}",
            copyTrading.id,
            payload.representativeTradeId,
            marketMetaResolveMs,
            filterEvaluateMs,
            resolvedMarketFilterInput.metadataSource
        )
        val orderbook = filterResult.orderbook
        if (!filterResult.isPassed) {
            logger.warn("过滤条件检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${filterResult.reason}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = filterResult.reason,
                filterType = extractFilterType(filterResult.status, filterResult.reason),
                calculatedQuantity = null
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "FILTER",
                eventType = "FILTER_REJECTED",
                status = "warning",
                message = filterResult.reason
            )
            return Result.success(Unit)
        }

        val sizingResult = sizingService.calculateRealTimeBuySizing(
            copyTrading = copyTrading,
            leaderOrderAmount = payload.leaderOrderAmount,
            tradePrice = payload.tradePrice,
            marketId = payload.marketId,
            outcomeIndex = payload.outcomeIndex
        )
        if (sizingResult.status != SizingStatus.EXECUTABLE) {
            if (payload.allowAggregation &&
                copyTrading.smallOrderAggregationEnabled &&
                sizingResult.rejectionType == SizingRejectionType.BELOW_MIN_ORDER_SIZE
            ) {
                val bufferResult = smallOrderAggregationService.bufferTrade(
                SmallOrderAggregationRequest(
                    copyTradingId = copyTrading.id!!,
                    accountId = copyTrading.accountId,
                    leaderId = copyTrading.leaderId,
                    side = "BUY",
                    tokenId = payload.tokenId,
                    marketId = payload.marketId,
                    outcomeIndex = payload.outcomeIndex,
                    marketSlug = payload.marketSlug,
                    marketEventSlug = payload.marketEventSlug,
                    seriesSlugPrefix = payload.marketSeriesSlugPrefix,
                    intervalSeconds = payload.marketIntervalSeconds,
                    leaderTradeId = payload.representativeTradeId,
                    leaderQuantity = payload.leaderQuantity,
                    leaderOrderAmount = payload.leaderOrderAmount,
                    tradePrice = payload.tradePrice,
                    outcome = payload.outcome,
                        source = payload.source
                    )
                )
                val batch = bufferResult.batch
                recordExecutionEvent(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    stage = "AGGREGATION",
                    eventType = if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                        "AGGREGATION_DUPLICATE_IGNORED"
                    } else {
                        "AGGREGATION_BUFFERED"
                    },
                    status = "info",
                    message = if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                        "重复 leaderTradeId 已忽略，保留现有聚合缓冲"
                    } else {
                        "订单金额低于最小下单限制，已进入聚合等待"
                    },
                    aggregationKey = batch.key,
                    aggregationTradeCount = batch.trades.size
                )
                if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                    return Result.success(Unit)
                }
                tryReleaseBufferedBuyAggregation(copyTrading, account, batch)?.let { return it }
                return Result.success(Unit)
            }

            val filterType = if (
                payload.trades.size > 1 &&
                payload.aggregationReleaseReason == SmallOrderAggregationReleaseReason.WINDOW_EXPIRED &&
                sizingResult.rejectionType == SizingRejectionType.BELOW_MIN_ORDER_SIZE
            ) {
                "AGGREGATION_TIMEOUT"
            } else if (sizingResult.rejectionType == SizingRejectionType.MAX_POSITION_COUNT_LIMIT) {
                "MAX_POSITION_COUNT"
            } else {
                "SIZING"
            }
            logger.warn("sizing 检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${sizingResult.reason}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = sizingResult.reason,
                filterType = filterType,
                calculatedQuantity = sizingResult.finalQuantity.takeIf { it > BigDecimal.ZERO }
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "FILTER",
                eventType = when {
                    filterType == "AGGREGATION_TIMEOUT" -> "AGGREGATION_TIMEOUT_TOO_SMALL"
                    payload.trades.size > 1 -> "AGGREGATION_RELEASE_REJECTED"
                    else -> "SIZING_REJECTED"
                },
                status = "warning",
                message = sizingResult.reason,
                calculatedQuantity = sizingResult.finalQuantity.takeIf { it > BigDecimal.ZERO }
            )
            return Result.success(Unit)
        }

        var finalBuyQuantity = sizingResult.finalQuantity
        if (finalBuyQuantity.lte(BigDecimal.ZERO)) {
            logger.warn("计算得到的买入数量为0，跳过跟单: copyTradingId=${copyTrading.id}, tradeId=${payload.representativeTradeId}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = "最终买入数量为 0，跳过执行",
                filterType = "SIZING",
                calculatedQuantity = finalBuyQuantity
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "FILTER",
                eventType = "FINAL_QUANTITY_ZERO",
                status = "warning",
                message = "最终买入数量为 0，跳过执行",
                calculatedQuantity = finalBuyQuantity
            )
            return Result.success(Unit)
        }
        if (finalBuyQuantity.lt(BigDecimal.ONE)) {
            logger.warn("计算得到的买入数量小于1，自动调整为1 (Polymarket 最小下单数量): copyTradingId=${copyTrading.id}, tradeId=${payload.representativeTradeId}, originalQuantity=$finalBuyQuantity")
            finalBuyQuantity = BigDecimal.ONE
        }

        val riskCheckResult = checkRiskControls(copyTrading)
        if (!riskCheckResult.first) {
            logger.warn("风险控制检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${riskCheckResult.second}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = riskCheckResult.second,
                filterType = "RISK_CONTROL",
                calculatedQuantity = finalBuyQuantity
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "RISK_CONTROL_REJECTED",
                status = "warning",
                message = riskCheckResult.second,
                calculatedQuantity = finalBuyQuantity
            )
            return Result.success(Unit)
        }

        val diagnostics = accountExecutionDiagnosticsService.diagnoseAccount(account, forceRefresh = false)
        if (!diagnostics.executionReady) {
            val diagnosticsReason = buildDiagnosticsFailureReason(diagnostics)
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = diagnosticsReason,
                filterType = "EXECUTION_PRECHECK",
                calculatedQuantity = finalBuyQuantity
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "EXECUTION_PRECHECK_REJECTED",
                status = "error",
                message = diagnosticsReason,
                calculatedQuantity = finalBuyQuantity
            )
            return Result.success(Unit)
        }

        if (copyTrading.delaySeconds > 0) {
            logger.info("延迟跟单: copyTradingId=${copyTrading.id}, delaySeconds=${copyTrading.delaySeconds}")
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "EXECUTION",
                eventType = "EXECUTION_DELAYED",
                status = "info",
                message = "命中延迟执行，等待 ${copyTrading.delaySeconds} 秒后继续",
                calculatedQuantity = finalBuyQuantity
            )
            delay(copyTrading.delaySeconds * 1000L)
        }

        val buyPrice = calculateAdjustedPrice(payload.tradePrice, copyTrading, isBuy = true)
        val orderbookForCheck = orderbook ?: run {
            val orderbookResult = clobService.getOrderbookByTokenId(payload.tokenId)
            if (orderbookResult.isSuccess) orderbookResult.getOrNull() else null
        }
        if (orderbookForCheck != null) {
            val bestAsk = orderbookForCheck.asks.mapNotNull { it.price.toSafeBigDecimal() }.minOrNull()
            if (bestAsk == null) {
                logger.warn("订单簿中没有卖单，跳过创建订单: copyTradingId=${copyTrading.id}, tradeId=${payload.representativeTradeId}")
                recordFilteredBuyPayloadAsync(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    filterReason = "订单簿中没有可成交的卖单",
                    filterType = "ORDERBOOK",
                    calculatedQuantity = finalBuyQuantity
                )
                recordExecutionEvent(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    stage = "EXECUTION",
                    eventType = "ORDERBOOK_UNMATCHED",
                    status = "warning",
                    message = "订单簿中没有可成交的卖单",
                    calculatedQuantity = finalBuyQuantity
                )
                return Result.success(Unit)
            }
            if (buyPrice.lt(bestAsk)) {
                logger.info("调整后的买入价格 ($buyPrice) 低于最佳卖单价格 ($bestAsk)，无法匹配，跳过创建订单: copyTradingId=${copyTrading.id}, tradeId=${payload.representativeTradeId}, leaderPrice=${payload.tradePrice}, tolerance=${copyTrading.priceTolerance}")
                recordFilteredBuyPayloadAsync(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    filterReason = "订单簿无法匹配: adjustedPrice=$buyPrice < bestAsk=$bestAsk",
                    filterType = "ORDERBOOK",
                    calculatedQuantity = finalBuyQuantity
                )
                recordExecutionEvent(
                    copyTrading = copyTrading,
                    account = account,
                    payload = payload,
                    stage = "EXECUTION",
                    eventType = "ORDERBOOK_UNMATCHED",
                    status = "warning",
                    message = "订单簿无法匹配: adjustedPrice=$buyPrice < bestAsk=$bestAsk",
                    calculatedQuantity = finalBuyQuantity,
                    orderPrice = buyPrice,
                    orderQuantity = finalBuyQuantity
                )
                return Result.success(Unit)
            }
        }

        val apiSecret = try {
            decryptApiSecret(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建订单: accountId=${account.id}, error=${e.message}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = "API Secret 解密失败，跳过执行: ${e.message}",
                filterType = "EXECUTION_PRECHECK",
                calculatedQuantity = finalBuyQuantity
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "API_SECRET_DECRYPT_FAILED",
                status = "error",
                message = "API Secret 解密失败: ${e.message}",
                calculatedQuantity = finalBuyQuantity
            )
            return Result.success(Unit)
        }
        val apiPassphrase = try {
            decryptApiPassphrase(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建订单: accountId=${account.id}, error=${e.message}")
            recordFilteredBuyPayloadAsync(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                filterReason = "API Passphrase 解密失败，跳过执行: ${e.message}",
                filterType = "EXECUTION_PRECHECK",
                calculatedQuantity = finalBuyQuantity
            )
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "PRECHECK",
                eventType = "API_PASSPHRASE_DECRYPT_FAILED",
                status = "error",
                message = "API Passphrase 解密失败: ${e.message}",
                calculatedQuantity = finalBuyQuantity
            )
            return Result.success(Unit)
        }

        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val decryptedPrivateKey = decryptPrivateKey(account)
        val feeRateResult = clobService.getFeeRate(payload.tokenId)
        val feeRateBps = if (feeRateResult.isSuccess) {
            feeRateResult.getOrNull()?.toString() ?: "0"
        } else {
            logger.warn("获取费率失败，使用默认值 0: tokenId=${payload.tokenId}, error=${feeRateResult.exceptionOrNull()?.message}")
            "0"
        }

        logger.info(
            "准备创建买入订单: copyTradingId={}, tradeId={}, leaderPrice={}, tolerance={}, calculatedPrice={}, quantity={}, baseFee={}, aggregated={}",
            copyTrading.id,
            payload.representativeTradeId,
            payload.tradePrice,
            copyTrading.priceTolerance,
            buyPrice,
            finalBuyQuantity,
            feeRateBps,
            payload.trades.size > 1
        )
        val orderCreateRequestedAt = System.currentTimeMillis()
        recordExecutionEvent(
            copyTrading = copyTrading,
            account = account,
            payload = payload,
            stage = "EXECUTION",
            eventType = "ORDER_SUBMITTING",
            status = "info",
            message = "准备提交买入订单",
            calculatedQuantity = finalBuyQuantity,
            orderPrice = buyPrice,
            orderQuantity = finalBuyQuantity,
            orderCreateRequestedAt = orderCreateRequestedAt
        )

        val negRisk = marketService.getNegRiskByConditionId(payload.marketId) == true
        val exchangeContract = orderSigningService.getExchangeContract(negRisk)
        if (negRisk) logger.debug("市场为 Neg Risk，使用 Neg Risk Exchange 签约: conditionId=${payload.marketId}")

        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            walletAddress = account.walletAddress,
            exchangeContract = exchangeContract,
            tokenId = payload.tokenId,
            side = "BUY",
            price = buyPrice.toString(),
            size = finalBuyQuantity.toString(),
            owner = account.apiKey,
            copyTradingId = copyTrading.id!!,
            tradeId = payload.representativeTradeId,
            feeRateBps = feeRateBps,
            signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        )
        val orderCreateCompletedAt = System.currentTimeMillis()
        if (createOrderResult.isFailure) {
            val exception = createOrderResult.exceptionOrNull()
            logger.error("创建买入订单失败: copyTradingId=${copyTrading.id}, tradeId=${payload.representativeTradeId}, leaderPrice=${payload.tradePrice}, myPrice=$buyPrice, error=${exception?.message}")
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "EXECUTION",
                eventType = "ORDER_FAILED",
                status = "error",
                message = exception?.message ?: "创建买入订单失败",
                calculatedQuantity = finalBuyQuantity,
                orderPrice = buyPrice,
                orderQuantity = finalBuyQuantity,
                orderCreateRequestedAt = orderCreateRequestedAt,
                orderCreateCompletedAt = orderCreateCompletedAt
            )
            if (copyTrading.pushFailedOrders) {
                notificationScope.launch {
                    try {
                        val market = marketService.getMarket(payload.marketId)
                        val marketTitle = market?.title ?: payload.marketId
                        val marketSlug = market?.eventSlug
                        val locale = try {
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()
                        } catch (e: Exception) {
                            java.util.Locale("zh", "CN")
                        }
                        telegramNotificationService?.sendOrderFailureNotification(
                            marketTitle = marketTitle,
                            marketId = payload.marketId,
                            marketSlug = marketSlug,
                            side = "BUY",
                            outcome = payload.outcome,
                            price = buyPrice.toString(),
                            size = finalBuyQuantity.toString(),
                            errorMessage = exception?.message.orEmpty(),
                            accountName = account.accountName,
                            walletAddress = account.walletAddress,
                            locale = locale
                        )
                    } catch (e: Exception) {
                        logger.warn("发送订单失败通知失败: ${e.message}", e)
                    }
                }
            }
            return Result.success(Unit)
        }

        val realOrderId = createOrderResult.getOrNull() ?: return Result.success(Unit)
        if (!isValidOrderId(realOrderId)) {
            logger.warn("买入订单ID格式无效，跳过保存: orderId=$realOrderId")
            recordExecutionEvent(
                copyTrading = copyTrading,
                account = account,
                payload = payload,
                stage = "EXECUTION",
                eventType = "ORDER_ID_INVALID",
                status = "warning",
                message = "买入订单返回了无效的订单 ID，已跳过保存",
                calculatedQuantity = finalBuyQuantity,
                orderPrice = buyPrice,
                orderQuantity = finalBuyQuantity,
                orderId = realOrderId
            )
            return Result.success(Unit)
        }

        val tracking = CopyOrderTracking(
            copyTradingId = copyTrading.id,
            accountId = copyTrading.accountId,
            leaderId = copyTrading.leaderId,
            marketId = payload.marketId,
            side = payload.outcomeIndex?.toString() ?: "",
            outcomeIndex = payload.outcomeIndex,
            buyOrderId = realOrderId,
            leaderBuyTradeId = payload.representativeTradeId,
            leaderBuyQuantity = payload.leaderQuantity,
            quantity = finalBuyQuantity,
            price = buyPrice,
            remainingQuantity = finalBuyQuantity,
            status = "filled",
            notificationSent = false,
            source = payload.source
        )
        copyOrderTrackingRepository.save(tracking)
        logger.info("买入订单已保存，等待轮询任务获取实际数据后发送通知: orderId=$realOrderId, copyTradingId=${copyTrading.id}")
        recordExecutionEvent(
            copyTrading = copyTrading,
            account = account,
            payload = payload,
            stage = "EXECUTION",
            eventType = "ORDER_CREATED",
            status = "success",
            message = "买入订单创建成功，已进入订单跟踪",
            calculatedQuantity = finalBuyQuantity,
            orderPrice = buyPrice,
            orderQuantity = finalBuyQuantity,
            orderId = realOrderId,
            orderCreateRequestedAt = orderCreateRequestedAt,
            orderCreateCompletedAt = orderCreateCompletedAt
        )
        return Result.success(Unit)
    }

    /**
     * 处理卖出交易
     * 查找未匹配的买入订单并进行匹配
     */
    @Transactional
    suspend fun processSellTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        return try {
            // 1. 查找所有启用且支持该Leader的跟单关系
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)

            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            // 2. 为每个跟单关系处理卖出匹配
            for (copyTrading in copyTradings) {
                try {
                    // 检查是否支持卖出
                    if (!copyTrading.supportSell) {
                        recordTradeExecutionEvent(
                            copyTrading = copyTrading,
                            accountId = copyTrading.accountId,
                            trade = trade,
                            stage = "FILTER",
                            eventType = "SELL_NOT_SUPPORTED",
                            status = "info",
                            message = "该跟单配置未开启自动卖出，忽略 Leader 卖出信号"
                        )
                        continue
                    }

                    // 执行卖出匹配
                    matchSellOrder(copyTrading, trade, source = source)
                } catch (e: Exception) {
                    logger.error("处理卖出交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", e)
                    // 继续处理下一个跟单关系
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理卖出交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    private fun recordFilteredBuyOrderAsync(
        copyTrading: CopyTrading,
        account: Account,
        trade: TradeResponse,
        marketId: String,
        outcomeIndex: Int?,
        filterReason: String,
        filterType: String,
        calculatedQuantity: BigDecimal?
    ) {
        recordFilteredBuyPayloadAsync(
            copyTrading = copyTrading,
            account = account,
            payload = BuyExecutionPayload(
                tokenId = trade.tokenId ?: "",
                marketId = marketId,
                outcomeIndex = outcomeIndex,
                tradePrice = trade.price.toSafeBigDecimal(),
                leaderQuantity = trade.size.toSafeBigDecimal(),
                leaderOrderAmount = trade.size.toSafeBigDecimal().multi(trade.price.toSafeBigDecimal()),
                outcome = trade.outcome,
                source = "single",
                representativeTradeId = trade.id,
                trades = listOf(
                    BuyTradeSegment(
                        leaderTradeId = trade.id,
                        leaderQuantity = trade.size.toSafeBigDecimal(),
                        leaderOrderAmount = trade.size.toSafeBigDecimal().multi(trade.price.toSafeBigDecimal()),
                        tradePrice = trade.price.toSafeBigDecimal(),
                        outcome = trade.outcome,
                        source = "single"
                    )
                ),
                allowAggregation = false
            ),
            filterReason = filterReason,
            filterType = filterType,
            calculatedQuantity = calculatedQuantity
        )
    }

    private fun recordFilteredBuyPayloadAsync(
        copyTrading: CopyTrading,
        account: Account,
        payload: BuyExecutionPayload,
        filterReason: String,
        filterType: String,
        calculatedQuantity: BigDecimal?
    ) {
        notificationScope.launch {
            try {
                val market = marketService.getMarket(payload.marketId)
                val marketTitle = market?.title ?: payload.marketId
                val marketSlug = market?.slug

                payload.trades.forEach { trade ->
                    filteredOrderRepository.save(
                        FilteredOrder(
                            copyTradingId = copyTrading.id!!,
                            accountId = copyTrading.accountId,
                            leaderId = copyTrading.leaderId,
                            leaderTradeId = trade.leaderTradeId,
                            marketId = payload.marketId,
                            marketTitle = marketTitle,
                            marketSlug = marketSlug,
                            side = "BUY",
                            outcomeIndex = payload.outcomeIndex,
                            outcome = trade.outcome,
                            price = trade.tradePrice,
                            size = trade.leaderQuantity,
                            calculatedQuantity = calculatedQuantity,
                            filterReason = filterReason,
                            filterType = filterType
                        )
                    )
                }
                logger.info("已记录被过滤的订单: copyTradingId={}, tradeCount={}, filterType={}", copyTrading.id, payload.trades.size, filterType)

                if (copyTrading.pushFilteredOrders) {
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (e: Exception) {
                        java.util.Locale("zh", "CN")
                    }

                    telegramNotificationService?.sendOrderFilteredNotification(
                        marketTitle = marketTitle,
                        marketId = payload.marketId,
                        marketSlug = marketSlug,
                        side = "BUY",
                        outcome = payload.outcome,
                        price = payload.tradePrice.toPlainString(),
                        size = payload.leaderQuantity.toPlainString(),
                        filterReason = filterReason,
                        filterType = filterType,
                        accountName = account.accountName,
                        walletAddress = account.walletAddress,
                        locale = locale
                    )
                }
            } catch (e: Exception) {
                logger.error("处理被过滤订单通知失败: ${e.message}", e)
            }
        }
    }

    private fun recordExecutionEvent(
        copyTrading: CopyTrading,
        account: Account,
        payload: BuyExecutionPayload,
        stage: String,
        eventType: String,
        status: String,
        message: String,
        calculatedQuantity: BigDecimal? = null,
        orderPrice: BigDecimal? = null,
        orderQuantity: BigDecimal? = null,
        orderId: String? = null,
        aggregationKey: String? = null,
        aggregationTradeCount: Int? = null,
        detailJson: String? = null,
        orderCreateRequestedAt: Long? = null,
        orderCreateCompletedAt: Long? = null
    ) {
        val latencyDetailJson = buildLatencyDetailJson(
            leaderId = copyTrading.leaderId,
            tradeId = payload.representativeTradeId,
            source = payload.source,
            orderCreateRequestedAt = orderCreateRequestedAt,
            orderCreateCompletedAt = orderCreateCompletedAt
        )
        executionEventService.recordEvent(
            CopyTradingExecutionEventRecordRequest(
                copyTradingId = copyTrading.id ?: return,
                accountId = copyTrading.accountId,
                leaderId = copyTrading.leaderId,
                leaderTradeId = payload.representativeTradeId,
                marketId = payload.marketId,
                side = "BUY",
                outcomeIndex = payload.outcomeIndex,
                outcome = payload.outcome,
                source = payload.source,
                stage = stage,
                eventType = eventType,
                status = status,
                leaderPrice = payload.tradePrice,
                leaderQuantity = payload.leaderQuantity,
                leaderOrderAmount = payload.leaderOrderAmount,
                calculatedQuantity = calculatedQuantity,
                orderPrice = orderPrice,
                orderQuantity = orderQuantity,
                orderId = orderId,
                aggregationKey = aggregationKey,
                aggregationTradeCount = aggregationTradeCount ?: payload.trades.size,
                message = message,
                detailJson = detailJson ?: latencyDetailJson
            )
        )
    }

    private fun recordTradeExecutionEvent(
        copyTrading: CopyTrading,
        accountId: Long,
        trade: TradeResponse,
        stage: String,
        eventType: String,
        status: String,
        message: String,
        calculatedQuantity: BigDecimal? = null,
        orderPrice: BigDecimal? = null,
        orderQuantity: BigDecimal? = null,
        orderId: String? = null,
        aggregationKey: String? = null,
        aggregationTradeCount: Int? = null,
        detailJson: String? = null,
        source: String? = null,
        orderCreateRequestedAt: Long? = null,
        orderCreateCompletedAt: Long? = null
    ) {
        val leaderPrice = trade.price.toSafeBigDecimal()
        val leaderQuantity = trade.size.toSafeBigDecimal()
        val latencyDetailJson = buildLatencyDetailJson(
            leaderId = copyTrading.leaderId,
            tradeId = trade.id,
            source = source,
            orderCreateRequestedAt = orderCreateRequestedAt,
            orderCreateCompletedAt = orderCreateCompletedAt
        )
        executionEventService.recordEvent(
            CopyTradingExecutionEventRecordRequest(
                copyTradingId = copyTrading.id ?: return,
                accountId = accountId,
                leaderId = copyTrading.leaderId,
                leaderTradeId = trade.id,
                marketId = trade.market.takeIf { it.isNotBlank() },
                side = trade.side.uppercase(),
                outcomeIndex = trade.outcomeIndex,
                outcome = trade.outcome,
                source = source,
                stage = stage,
                eventType = eventType,
                status = status,
                leaderPrice = leaderPrice,
                leaderQuantity = leaderQuantity,
                leaderOrderAmount = leaderQuantity.multi(leaderPrice),
                calculatedQuantity = calculatedQuantity,
                orderPrice = orderPrice,
                orderQuantity = orderQuantity,
                orderId = orderId,
                aggregationKey = aggregationKey,
                aggregationTradeCount = aggregationTradeCount,
                message = message,
                detailJson = detailJson ?: latencyDetailJson
            )
        )
    }

    private fun buildTradeLatencyKey(leaderId: Long, tradeId: String, source: String): String {
        return "$leaderId::$tradeId::$source"
    }

    private fun updateTradeLatencyContext(
        leaderId: Long,
        tradeId: String,
        source: String,
        marketMetaResolveMs: Long? = null,
        marketMetaSource: String? = null,
        filterEvaluateMs: Long? = null
    ) {
        val key = buildTradeLatencyKey(leaderId, tradeId, source)
        tradeLatencyContextMap.computeIfPresent(key) { _, context ->
            context.copy(
                marketMetaResolveMs = marketMetaResolveMs ?: context.marketMetaResolveMs,
                marketMetaSource = marketMetaSource ?: context.marketMetaSource,
                filterEvaluateMs = filterEvaluateMs ?: context.filterEvaluateMs
            )
        }
    }

    private fun parseTradeTimestampMillis(rawTimestamp: String?): Long? {
        val value = rawTimestamp?.toLongOrNull() ?: return null
        return if (value < 1_000_000_000_000L) value * 1000 else value
    }

    private fun buildLatencyDetailJson(
        leaderId: Long,
        tradeId: String,
        source: String?,
        orderCreateRequestedAt: Long? = null,
        orderCreateCompletedAt: Long? = null
    ): String? {
        if (source.isNullOrBlank()) {
            return null
        }
        val context = tradeLatencyContextMap[buildTradeLatencyKey(leaderId, tradeId, source)] ?: return null
        val detail = linkedMapOf<String, Any>()
        detail["source"] = source
        context.leaderTradeTimestamp?.let { detail["leaderTradeTimestamp"] = it }
        context.sourceReceivedAt?.let {
            detail[if (source == "activity-ws") "activityWsReceivedAt" else "onChainWsReceivedAt"] = it
        }
        detail["processTradeStartedAt"] = context.processTradeStartedAt
        context.sourceReceivedAt?.let { detail["sourceToProcessMs"] = context.processTradeStartedAt - it }
        context.marketMetaResolveMs?.let { detail["marketMetaResolveMs"] = it }
        context.marketMetaSource?.let { detail["marketMetaSource"] = it }
        context.filterEvaluateMs?.let { detail["filterEvaluateMs"] = it }
        orderCreateRequestedAt?.let {
            detail["orderCreateRequestedAt"] = it
            detail["processToOrderRequestMs"] = it - context.processTradeStartedAt
        }
        orderCreateCompletedAt?.let {
            detail["orderCreateCompletedAt"] = it
            orderCreateRequestedAt?.let { requestedAt ->
                detail["orderCreateDurationMs"] = it - requestedAt
            }
            context.sourceReceivedAt?.let { receivedAt ->
                detail["sourceToOrderCompleteMs"] = it - receivedAt
            }
            context.leaderTradeTimestamp?.let { leaderTradeAt ->
                detail["leaderTradeToOrderCompleteMs"] = it - leaderTradeAt
            }
        }
        return detail.takeIf { it.isNotEmpty() }?.toJson()
    }

    private suspend fun resolveSellTokenId(
        copyTrading: CopyTrading,
        accountId: Long,
        leaderSellTrade: TradeResponse,
        aggregationKey: String? = null,
        aggregationTradeCount: Int? = null
    ): String? {
        if (!leaderSellTrade.tokenId.isNullOrBlank()) {
            return leaderSellTrade.tokenId
        }
        val outcomeIndex = leaderSellTrade.outcomeIndex
        if (outcomeIndex == null) {
            logger.error("卖出交易缺少outcomeIndex且无tokenId: market=${leaderSellTrade.market}")
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = accountId,
                trade = leaderSellTrade,
                stage = "MONITOR",
                eventType = "SELL_TOKEN_ID_MISSING",
                status = "error",
                message = "卖出交易缺少 tokenId 和 outcomeIndex，无法执行",
                aggregationKey = aggregationKey,
                aggregationTradeCount = aggregationTradeCount
            )
            return null
        }
        val tokenIdResult = blockchainService.getTokenId(leaderSellTrade.market, outcomeIndex)
        if (tokenIdResult.isFailure) {
            logger.error("获取tokenId失败: market=${leaderSellTrade.market}, outcomeIndex=$outcomeIndex, error=${tokenIdResult.exceptionOrNull()?.message}")
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = accountId,
                trade = leaderSellTrade,
                stage = "MONITOR",
                eventType = "SELL_TOKEN_ID_RESOLVE_FAILED",
                status = "error",
                message = "获取卖出 tokenId 失败: ${tokenIdResult.exceptionOrNull()?.message ?: "未知错误"}",
                aggregationKey = aggregationKey,
                aggregationTradeCount = aggregationTradeCount
            )
            return null
        }
        return tokenIdResult.getOrNull()
    }

    private fun buildDiagnosticsFailureReason(diagnostics: com.wrbug.polymarketbot.dto.AccountSetupStatusDto): String {
        val errorChecks = diagnostics.checks
            .filter { it.status == "error" }
            .take(3)
            .joinToString("；") { "${it.title}: ${it.message}" }
        return if (errorChecks.isNotBlank()) {
            "执行前诊断未通过：$errorChecks"
        } else {
            diagnostics.error ?: "执行前诊断未通过"
        }
    }

    /**
     * 根据未匹配订单的实际买入比例计算卖出数量
     */
    private suspend fun calculateSellQuantityByActualRatio(
        unmatchedOrders: List<CopyOrderTracking>,
        leaderSellQuantity: BigDecimal,
        copyTrading: CopyTrading
    ): BigDecimal {
        if (unmatchedOrders.isEmpty()) {
            return BigDecimal.ZERO
        }

        // 获取 Leader 信息（用于查询 Leader 买入交易）
        val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
            ?: run {
                logger.warn("Leader 不存在，使用默认比例: leaderId=${copyTrading.leaderId}")
                return leaderSellQuantity.multi(copyTrading.copyRatio)
            }

        // 创建不需要认证的 CLOB API 客户端（用于查询公开的交易数据）
        // 注意：Polymarket CLOB API 的 /data/trades 接口是公开的，不需要认证
        val clobApi = retrofitFactory.createClobApiWithoutAuth()

        // 计算总比例：sum(跟单买入数量) / sum(Leader 买入数量)
        // 优先使用存储的 leaderBuyQuantity，如果不存在则尝试查询 API（兼容旧数据）
        var totalCopyQuantity = BigDecimal.ZERO
        var totalLeaderQuantity = BigDecimal.ZERO
        var successCount = 0
        var failCount = 0

        logger.debug("开始计算固定金额模式卖出数量: copyTradingId=${copyTrading.id}, unmatchedOrdersCount=${unmatchedOrders.size}, leaderSellQuantity=$leaderSellQuantity")

        for (order in unmatchedOrders) {
            val copyQty = order.quantity.toSafeBigDecimal()
            var leaderQty: BigDecimal? = null

            // 优先使用存储的 leaderBuyQuantity
            if (order.leaderBuyQuantity != null) {
                leaderQty = order.leaderBuyQuantity.toSafeBigDecimal()
                logger.debug("使用存储的 Leader 买入数量: copyOrderId=${order.buyOrderId}, copyQty=$copyQty, leaderQty=$leaderQty")
                successCount++
            } else {
                // 兼容旧数据：如果 leaderBuyQuantity 为空，尝试查询 API
                logger.debug("Leader 买入数量未存储，尝试查询 API: leaderBuyTradeId=${order.leaderBuyTradeId}, copyOrderId=${order.buyOrderId}")
                try {
                    val tradesResponse = clobApi.getTrades(id = order.leaderBuyTradeId)

                    if (tradesResponse.isSuccessful && tradesResponse.body() != null) {
                        val tradesData = tradesResponse.body()!!.data
                        if (tradesData.isNotEmpty()) {
                            val leaderBuyTrade = tradesData.firstOrNull()
                            if (leaderBuyTrade != null) {
                                leaderQty = leaderBuyTrade.size.toSafeBigDecimal()
                                logger.debug("从 API 查询到 Leader 买入数量: leaderBuyTradeId=${order.leaderBuyTradeId}, leaderQty=$leaderQty")
                                successCount++
                            } else {
                                logger.warn("未找到 Leader 买入交易: leaderBuyTradeId=${order.leaderBuyTradeId}")
                                failCount++
                            }
                        } else {
                            logger.warn("Leader 买入交易数据为空: leaderBuyTradeId=${order.leaderBuyTradeId}")
                            failCount++
                        }
                    } else {
                        logger.warn("查询 Leader 买入交易失败: leaderBuyTradeId=${order.leaderBuyTradeId}, code=${tradesResponse.code()}")
                        failCount++
                    }
                } catch (e: Exception) {
                    logger.warn("查询 Leader 买入交易异常: leaderBuyTradeId=${order.leaderBuyTradeId}, error=${e.message}")
                    failCount++
                }
            }

            // 如果成功获取到 Leader 买入数量，累加
            if (leaderQty != null && leaderQty.gt(BigDecimal.ZERO)) {
                totalCopyQuantity = totalCopyQuantity.add(copyQty)
                totalLeaderQuantity = totalLeaderQuantity.add(leaderQty)
            } else {
                logger.warn("无法获取 Leader 买入数量，跳过该订单: copyOrderId=${order.buyOrderId}, leaderBuyTradeId=${order.leaderBuyTradeId}")
            }
        }

        logger.info("实际持仓比例计算结果汇总: copyTradingId=${copyTrading.id}, successCount=$successCount, failCount=$failCount, totalCopyQuantity=$totalCopyQuantity, totalLeaderQuantity=$totalLeaderQuantity")

        // 如果无法计算总比例（查询失败），使用默认比例
        if (totalLeaderQuantity.lte(BigDecimal.ZERO)) {
            logger.warn("无法计算总比例（Leader 买入数量为 0），使用默认比例: copyTradingId=${copyTrading.id}")
            return leaderSellQuantity.multi(copyTrading.copyRatio)
        }

        // 计算实际比例：跟单买入数量 / Leader 买入数量
        val actualRatio = totalCopyQuantity.div(totalLeaderQuantity)

        // 计算需要卖出的数量：Leader 卖出数量 × 实际比例
        val needMatch = leaderSellQuantity.multi(actualRatio)

        logger.debug("固定金额模式卖出数量计算: copyTradingId=${copyTrading.id}, leaderSellQuantity=$leaderSellQuantity, totalCopyQuantity=$totalCopyQuantity, totalLeaderQuantity=$totalLeaderQuantity, actualRatio=$actualRatio, needMatch=$needMatch")

        return needMatch
    }

    /**
     * 卖出订单匹配
     * 根据 copyMode 计算卖出数量：
     * - RATIO 模式：使用配置的 copyRatio
     * - FIXED 模式：根据实际买入比例计算
     * 实际创建卖出订单并记录匹配关系
     * 注意：此方法在 @Transactional 方法中被调用，会自动继承事务
     */
    private suspend fun matchSellOrder(
        copyTrading: CopyTrading,
        leaderSellTrade: TradeResponse,
        source: String,
        allowAggregation: Boolean = true,
        aggregationReleaseReason: SmallOrderAggregationReleaseReason? = null,
        aggregationKey: String? = null,
        aggregationTradeCount: Int? = null
    ) {
        fun recordSellEvent(
            stage: String,
            eventType: String,
            status: String,
            message: String,
            calculatedQuantity: BigDecimal? = null,
            orderPrice: BigDecimal? = null,
            orderQuantity: BigDecimal? = null,
            orderId: String? = null,
            detailJson: String? = null,
            eventAggregationKey: String? = aggregationKey,
            eventAggregationTradeCount: Int? = aggregationTradeCount
        ) {
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = copyTrading.accountId,
                trade = leaderSellTrade,
                stage = stage,
                eventType = eventType,
                status = status,
                message = message,
                calculatedQuantity = calculatedQuantity,
                orderPrice = orderPrice,
                orderQuantity = orderQuantity,
                orderId = orderId,
                aggregationKey = eventAggregationKey,
                aggregationTradeCount = eventAggregationTradeCount,
                detailJson = detailJson,
                source = source
            )
        }

        recordSellEvent(
            stage = aggregationReleaseReason?.let { "AGGREGATION" } ?: "DISCOVERY",
            eventType = aggregationReleaseReason?.let(::aggregationReleaseEventType) ?: "SELL_SIGNAL_DETECTED",
            status = "info",
            message = aggregationReleaseReason?.let { aggregationReleaseMessage(it, "SELL") } ?: "检测到新的 SELL 跟单信号"
        )

        // 1. 获取账户
        val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            ?: run {
                logger.warn("账户不存在，跳过卖出匹配: accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}")
                recordSellEvent("PRECHECK", "ACCOUNT_NOT_FOUND", "error", "账户不存在，无法执行卖出跟单")
                return
            }

        // 验证账户API凭证
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置API凭证，跳过创建卖出订单: accountId=${account.id}, copyTradingId=${copyTrading.id}")
            recordSellEvent("PRECHECK", "ACCOUNT_CREDENTIALS_MISSING", "error", "账户未配置完整 API 凭证，无法执行卖出跟单")
            return
        }

        // 验证账户是否启用
        if (!account.isEnabled) {
            recordSellEvent("PRECHECK", "ACCOUNT_DISABLED", "error", "账户已禁用，无法执行卖出跟单")
            return
        }

        // 2. 查找未匹配的买入订单（FIFO顺序）
        // 直接使用outcomeIndex匹配，而不是转换为YES/NO
        if (leaderSellTrade.outcomeIndex == null) {
            logger.warn("卖出交易缺少outcomeIndex，无法匹配: tradeId=${leaderSellTrade.id}, market=${leaderSellTrade.market}")
            recordSellEvent("MONITOR", "SELL_OUTCOME_INDEX_MISSING", "warning", "卖出交易缺少 outcomeIndex，无法匹配仓位")
            return
        }

        // 使用outcomeIndex查找匹配的买入订单（存储在CopyOrderTracking中的outcomeIndex）
        val unmatchedOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
            copyTrading.id!!,
            leaderSellTrade.market,
            leaderSellTrade.outcomeIndex
        )

        if (unmatchedOrders.isEmpty()) {
            recordSellEvent("FILTER", "SELL_NO_MATCHED_POSITION", "info", "未找到可匹配的买入仓位，忽略卖出信号")
            return
        }

        // 3. 计算需要匹配的数量：优先使用真实持仓比例，避免 BUY 侧被裁剪后的卖出偏差
        val needMatch = calculateSellQuantityByActualRatio(
            unmatchedOrders = unmatchedOrders,
            leaderSellQuantity = leaderSellTrade.size.toSafeBigDecimal(),
            copyTrading = copyTrading
        )

        var finalNeedMatch = needMatch
        if (finalNeedMatch.gt(BigDecimal.ZERO) && finalNeedMatch.lt(BigDecimal.ONE)) {
            if (allowAggregation && copyTrading.smallOrderAggregationEnabled) {
                val tokenIdForAggregation = resolveSellTokenId(
                    copyTrading = copyTrading,
                    accountId = account.id ?: copyTrading.accountId,
                    leaderSellTrade = leaderSellTrade,
                    aggregationKey = aggregationKey,
                    aggregationTradeCount = aggregationTradeCount
                ) ?: return
                val leaderSellMetadata = MarketFilterSupport.deriveMarketSeriesMetadata(
                    slug = leaderSellTrade.slug,
                    eventSlug = leaderSellTrade.eventSlug
                )
                val bufferResult = smallOrderAggregationService.bufferTrade(
                SmallOrderAggregationRequest(
                    copyTradingId = copyTrading.id!!,
                    accountId = account.id ?: copyTrading.accountId,
                    leaderId = copyTrading.leaderId,
                    side = "SELL",
                    tokenId = tokenIdForAggregation,
                    marketId = leaderSellTrade.market,
                    outcomeIndex = leaderSellTrade.outcomeIndex,
                    marketSlug = leaderSellTrade.slug,
                    marketEventSlug = leaderSellTrade.eventSlug,
                    seriesSlugPrefix = leaderSellMetadata.seriesSlugPrefix,
                    intervalSeconds = leaderSellMetadata.intervalSeconds,
                    leaderTradeId = leaderSellTrade.id,
                    leaderQuantity = leaderSellTrade.size.toSafeBigDecimal(),
                    leaderOrderAmount = leaderSellTrade.size.toSafeBigDecimal().multi(leaderSellTrade.price.toSafeBigDecimal()),
                    tradePrice = leaderSellTrade.price.toSafeBigDecimal(),
                    outcome = leaderSellTrade.outcome,
                        source = "single"
                    )
                )
                val batch = bufferResult.batch
                recordSellEvent(
                    stage = "AGGREGATION",
                    eventType = if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                        "AGGREGATION_DUPLICATE_IGNORED"
                    } else {
                        "AGGREGATION_BUFFERED"
                    },
                    status = "info",
                    message = if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                        "重复 leaderTradeId 已忽略，保留现有 SELL 聚合缓冲"
                    } else {
                        "卖出数量低于最小下单限制，已进入 SELL 聚合等待"
                    },
                    calculatedQuantity = finalNeedMatch,
                    eventAggregationKey = batch.key,
                    eventAggregationTradeCount = batch.trades.size
                )
                if (bufferResult.status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
                    return
                }
                tryReleaseBufferedSellAggregation(copyTrading, account, batch)
                return
            }
            if (aggregationReleaseReason != null) {
                recordSellEvent(
                    stage = "FILTER",
                    eventType = if (aggregationReleaseReason == SmallOrderAggregationReleaseReason.WINDOW_EXPIRED) {
                        "AGGREGATION_TIMEOUT_TOO_SMALL"
                    } else {
                        "AGGREGATION_RELEASE_REJECTED"
                    },
                    status = "warning",
                    message = if (aggregationReleaseReason == SmallOrderAggregationReleaseReason.WINDOW_EXPIRED) {
                        "SELL 聚合窗口到期后仍低于最小下单数量，放弃执行"
                    } else {
                        "SELL 聚合释放后仍低于最小下单数量，放弃执行"
                    },
                    calculatedQuantity = finalNeedMatch
                )
                return
            }
            logger.warn("计算得到的卖出数量小于1，自动调整为1: copyTradingId=${copyTrading.id}, original=$needMatch")
            finalNeedMatch = BigDecimal.ONE
        }
        if (finalNeedMatch.lte(BigDecimal.ZERO)) {
            recordSellEvent("FILTER", "SELL_QUANTITY_ZERO", "info", "根据仓位比例计算后的卖出数量为 0，忽略卖出信号")
            return
        }

        // 4. 获取 tokenId：优先使用链上解析得到的 tokenId，否则用 conditionId+outcomeIndex 链上重算
        val tokenId = resolveSellTokenId(
            copyTrading = copyTrading,
            accountId = account.id ?: copyTrading.accountId,
            leaderSellTrade = leaderSellTrade,
            aggregationKey = aggregationKey,
            aggregationTradeCount = aggregationTradeCount
        ) ?: return

        // 5. 计算卖出价格（优先使用订单簿 bestBid，失败则使用 Leader 价格，固定按90%计算）
        // 注意：需要先计算卖出价格，因为后续创建 matchDetails 需要使用实际卖出价格
        val leaderPrice = leaderSellTrade.price.toSafeBigDecimal()
        val sellPrice = runCatching {
            clobService.getOrderbookByTokenId(tokenId)
                .getOrNull()
                ?.let { calculateMarketSellPrice(it) }
        }
            .onFailure { e -> logger.warn("获取订单簿或计算 bestBid 失败，使用 Leader 价格: tokenId=$tokenId, error=${e.message}") }
            .getOrNull()
            ?: calculateFallbackSellPrice(leaderPrice)

        // 6. 按FIFO顺序匹配，计算实际可以卖出的数量
        // 使用计算出的实际卖出价格（而不是 Leader 价格）来创建匹配明细
        var totalMatched = BigDecimal.ZERO
        var remaining = finalNeedMatch
        val matchDetails = mutableListOf<SellMatchDetail>()

        for (order in unmatchedOrders) {
            if (remaining.lte(BigDecimal.ZERO)) break

            val matchQty = minOf(
                order.remainingQuantity.toSafeBigDecimal(),
                remaining
            )

            if (matchQty.lte(BigDecimal.ZERO)) continue

            // 计算盈亏（使用实际卖出价格）
            val buyPrice = order.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchQty)

            // 创建匹配明细（使用实际卖出价格）
            val detail = SellMatchDetail(
                matchRecordId = 0,  // 稍后设置
                trackingId = order.id!!,
                buyOrderId = order.buyOrderId,
                matchedQuantity = matchQty,
                buyPrice = buyPrice,
                sellPrice = sellPrice,  // 使用实际卖出价格，与 SellMatchRecord 保持一致
                realizedPnl = realizedPnl
            )
            matchDetails.add(detail)

            totalMatched = totalMatched.add(matchQty)
            remaining = remaining.subtract(matchQty)
        }

        if (totalMatched.lte(BigDecimal.ZERO)) {
            recordSellEvent("FILTER", "SELL_MATCH_ZERO", "info", "没有可卖出的匹配数量，忽略卖出信号")
            return
        }

        if (totalMatched.lt(BigDecimal.ONE)) {
            logger.warn("卖出数量小于1，跳过卖出 (Polymarket 最小下单数量为 1): copyTradingId=${copyTrading.id}, tradeId=${leaderSellTrade.id}, quantity=$totalMatched")
            recordSellEvent(
                "FILTER",
                "SELL_BELOW_MIN_ORDER_SIZE",
                "warning",
                "卖出数量小于 1，低于 Polymarket 最小下单数量",
                calculatedQuantity = totalMatched
            )
            return
        }

        val diagnostics = accountExecutionDiagnosticsService.diagnoseAccount(account, forceRefresh = false)
        if (!diagnostics.executionReady) {
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = account.id ?: copyTrading.accountId,
                trade = leaderSellTrade,
                stage = "PRECHECK",
                eventType = "EXECUTION_PRECHECK_REJECTED",
                status = "error",
                message = buildDiagnosticsFailureReason(diagnostics),
                calculatedQuantity = totalMatched,
                source = source
            )
            return
        }

        // 7. 解密 API 凭证
        val apiSecret = try {
            decryptApiSecret(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建卖出订单: accountId=${account.id}, error=${e.message}")
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = account.id ?: copyTrading.accountId,
                trade = leaderSellTrade,
                stage = "PRECHECK",
                eventType = "API_SECRET_DECRYPT_FAILED",
                status = "error",
                message = "API Secret 解密失败: ${e.message}",
                calculatedQuantity = totalMatched,
                source = source
            )
            return
        }
        val apiPassphrase = try {
            decryptApiPassphrase(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建卖出订单: accountId=${account.id}, error=${e.message}")
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = account.id ?: copyTrading.accountId,
                trade = leaderSellTrade,
                stage = "PRECHECK",
                eventType = "API_PASSPHRASE_DECRYPT_FAILED",
                status = "error",
                message = "API Passphrase 解密失败: ${e.message}",
                calculatedQuantity = totalMatched,
                source = source
            )
            return
        }

        // 8. 解密私钥（在方法开始时解密一次，后续复用）
        val decryptedPrivateKey = decryptPrivateKey(account)

        // 获取费率（根据 Polymarket Maker Rebates Program 要求）
        val feeRateResult = clobService.getFeeRate(tokenId)
        val feeRateBps = if (feeRateResult.isSuccess) {
            feeRateResult.getOrNull()?.toString() ?: "0"
        } else {
            logger.warn("获取费率失败，使用默认值 0: tokenId=$tokenId, error=${feeRateResult.exceptionOrNull()?.message}")
            "0"
        }

        // 9. Neg Risk 市场需用 Neg Risk Exchange 签约
        val negRiskSell = marketService.getNegRiskByConditionId(leaderSellTrade.market) == true
        val exchangeContractSell = orderSigningService.getExchangeContract(negRiskSell)
        if (negRiskSell) logger.debug("卖出市场为 Neg Risk，使用 Neg Risk Exchange 签约: conditionId=${leaderSellTrade.market}")

        // 10. 创建并签名卖出订单（按账户钱包类型使用对应 signatureType）
        val signedOrder = try {
            orderSigningService.createAndSignOrder(
                privateKey = decryptedPrivateKey,
                makerAddress = account.proxyAddress,
                tokenId = tokenId,
                side = "SELL",
                price = sellPrice.toString(),
                size = totalMatched.toString(),
                signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType),
                nonce = "0",
                feeRateBps = feeRateBps,  // 使用动态获取的费率
                expiration = "0",
                exchangeContract = exchangeContractSell
            )
        } catch (e: Exception) {
            logger.error("创建并签名卖出订单失败: copyTradingId=${copyTrading.id}, tradeId=${leaderSellTrade.id}", e)
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = account.id ?: copyTrading.accountId,
                trade = leaderSellTrade,
                stage = "EXECUTION",
                eventType = "ORDER_SIGNING_FAILED",
                status = "error",
                message = "创建并签名卖出订单失败: ${e.message}",
                calculatedQuantity = totalMatched,
                orderPrice = sellPrice,
                orderQuantity = totalMatched,
                source = source
            )
            return
        }

        // 11. 构建订单请求
        // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
        // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey,
            orderType = "FAK",  // Fill-And-Kill
            deferExec = false
        )

        // 12. 创建带认证的CLOB API客户端（使用解密后的凭证）
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )

        val sellOrderCreateRequestedAt = System.currentTimeMillis()
        recordTradeExecutionEvent(
            copyTrading = copyTrading,
            accountId = account.id ?: copyTrading.accountId,
            trade = leaderSellTrade,
            stage = "EXECUTION",
            eventType = "ORDER_SUBMITTING",
            status = "info",
            message = "准备提交卖出订单",
            calculatedQuantity = totalMatched,
            orderPrice = sellPrice,
            orderQuantity = totalMatched,
            source = source,
            orderCreateRequestedAt = sellOrderCreateRequestedAt
        )

        // 13. 调用API创建卖出订单（带重试机制，重试时会重新生成salt并重新签名）
        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            walletAddress = account.walletAddress,
            exchangeContract = exchangeContractSell,
            tokenId = tokenId,
            side = "SELL",
            price = sellPrice.toString(),
            size = totalMatched.toString(),
            owner = account.apiKey,
            copyTradingId = copyTrading.id,
            tradeId = leaderSellTrade.id,
            feeRateBps = feeRateBps,
            signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        )
        val sellOrderCreateCompletedAt = System.currentTimeMillis()

        if (createOrderResult.isFailure) {
            // 创建订单失败，记录错误日志
            val exception = createOrderResult.exceptionOrNull()
            logger.error("创建卖出订单失败: copyTradingId=${copyTrading.id}, tradeId=${leaderSellTrade.id}, error=${exception?.message}")
            recordTradeExecutionEvent(
                copyTrading = copyTrading,
                accountId = account.id ?: copyTrading.accountId,
                trade = leaderSellTrade,
                stage = "EXECUTION",
                eventType = "ORDER_FAILED",
                status = "error",
                message = exception?.message ?: "创建卖出订单失败",
                calculatedQuantity = totalMatched,
                orderPrice = sellPrice,
                orderQuantity = totalMatched,
                source = source,
                orderCreateRequestedAt = sellOrderCreateRequestedAt,
                orderCreateCompletedAt = sellOrderCreateCompletedAt
            )
            return
        }

        val realSellOrderId = createOrderResult.getOrNull() ?: return

        // 12. 下单时直接使用下单价格保存，等待定时任务更新实际成交价
        // priceUpdated 统一由定时任务更新，下单时统一设置为 false（非0x开头的除外）
        val priceUpdated = !realSellOrderId.startsWith("0x", ignoreCase = true)
        if (priceUpdated) {
            logger.debug("卖出订单ID非0x开头，标记为已更新: orderId=$realSellOrderId")
        } else {
            logger.debug("卖出订单ID为0x开头，等待定时任务更新价格: orderId=$realSellOrderId")
        }

        // 使用下单价格，等待定时任务更新实际成交价
        val actualSellPrice = sellPrice

        // 13. 更新买入订单跟踪状态
        for (order in unmatchedOrders) {
            val detail = matchDetails.find { it.trackingId == order.id }
            if (detail != null) {
                order.matchedQuantity = order.matchedQuantity.add(detail.matchedQuantity)
                order.remainingQuantity = order.remainingQuantity.subtract(detail.matchedQuantity)
                updateOrderStatus(order)
                order.updatedAt = System.currentTimeMillis()
                copyOrderTrackingRepository.save(order)

            }
        }

        // 14. 重新计算盈亏（使用实际成交价）
        val updatedMatchDetails = matchDetails.map { detail ->
            val updatedRealizedPnl = actualSellPrice.subtract(detail.buyPrice).multi(detail.matchedQuantity)
            detail.copy(
                sellPrice = actualSellPrice,
                realizedPnl = updatedRealizedPnl
            )
        }

        // 15. 创建卖出匹配记录（使用真实订单ID和实际成交价）
        val totalRealizedPnl = updatedMatchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }

        val matchRecord = SellMatchRecord(
            copyTradingId = copyTrading.id,
            sellOrderId = realSellOrderId,  // 使用真实订单ID
            leaderSellTradeId = leaderSellTrade.id,
            marketId = leaderSellTrade.market,
            side = leaderSellTrade.outcomeIndex.toString(),  // 使用outcomeIndex作为side（兼容旧数据）
            outcomeIndex = leaderSellTrade.outcomeIndex,  // 新增字段
            totalMatchedQuantity = totalMatched,
            sellPrice = actualSellPrice,  // 使用实际成交价（如果查询失败则为下单价格）
            totalRealizedPnl = totalRealizedPnl,
            priceUpdated = priceUpdated  // 共用字段：false 表示未处理（未查询订单详情，未发送通知），true 表示已处理（已查询订单详情，已发送通知）
        )

        val savedRecord = sellMatchRecordRepository.save(matchRecord)

        // 16. 保存匹配明细（使用实际成交价）
        for (detail in updatedMatchDetails) {
            val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
            sellMatchDetailRepository.save(savedDetail)
        }

        logger.info("卖出订单已保存，等待轮询任务获取实际数据后发送通知: orderId=$realSellOrderId, copyTradingId=${copyTrading.id}")
        recordTradeExecutionEvent(
            copyTrading = copyTrading,
            accountId = account.id ?: copyTrading.accountId,
            trade = leaderSellTrade,
            stage = "EXECUTION",
            eventType = "ORDER_CREATED",
            status = "success",
            message = "卖出订单创建成功，已进入订单跟踪",
            calculatedQuantity = totalMatched,
            orderPrice = sellPrice,
            orderQuantity = totalMatched,
            orderId = realSellOrderId,
            source = source,
            orderCreateRequestedAt = sellOrderCreateRequestedAt,
            orderCreateCompletedAt = sellOrderCreateCompletedAt
        )

    }

    /**
     * 创建订单（带重试机制）
     *
     * 重试策略：
     * - 最多重试 MAX_RETRY_ATTEMPTS 次（首次尝试 + 重试）
     * - 每次重试前等待 RETRY_DELAY_MS 毫秒
     * - 每次重试都重新生成salt并重新签名，确保签名唯一性
     *
     * @param clobApi CLOB API 客户端
     * @param privateKey 私钥（用于签名）
     * @param makerAddress 代理钱包地址（funder）
     * @param walletAddress 账户 EOA 地址（须与私钥推导的 signer 一致，用于校验及 POLY_ADDRESS）
     * @param exchangeContract 签约用 exchange 合约（Neg Risk 市场需用 Neg Risk Exchange）
     * @param tokenId Token ID
     * @param side 订单方向（BUY/SELL）
     * @param price 价格
     * @param size 数量
     * @param owner API Key（用于owner字段）
     * @param copyTradingId 跟单配置ID（用于日志）
     * @param tradeId Leader 交易ID（用于日志）
     * @param feeRateBps 费率基点（从API动态获取）
     * @param signatureType 签名类型（1=Magic, 2=Safe）
     * @return 成功返回订单ID，失败返回异常
     */
    private suspend fun createOrderWithRetry(
        clobApi: PolymarketClobApi,
        privateKey: String,
        makerAddress: String,
        walletAddress: String,
        exchangeContract: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        owner: String,
        copyTradingId: Long,
        tradeId: String,
        feeRateBps: String,
        signatureType: Int
    ): Result<String> {
        var lastError: Exception? = null

        // 重试循环：最多重试 MAX_RETRY_ATTEMPTS 次
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                // 每次重试都重新生成salt并重新签名，确保签名唯一性
                val signedOrder = orderSigningService.createAndSignOrder(
                    privateKey = privateKey,
                    makerAddress = makerAddress,
                    tokenId = tokenId,
                    side = side,
                    price = price,
                    size = size,
                    signatureType = signatureType,
                    nonce = "0",
                    feeRateBps = feeRateBps,  // 使用动态获取的费率
                    expiration = "0",
                    exchangeContract = exchangeContract
                )

                // 校验 signer 与账户 walletAddress 一致，否则服务端会返回 invalid signature（POLY_ADDRESS 与 order.signer 需一致）
                if (signedOrder.signer.lowercase() != walletAddress.lowercase()) {
                    val msg = "订单 signer 与账户 walletAddress 不一致，会导致 invalid signature。请确认该账户的私钥与 walletAddress 对应同一 EOA，且 API 密钥由该 EOA 创建。signer=${signedOrder.signer.take(10)}..., walletAddress=${walletAddress.take(10)}..."
                    logger.error(msg)
                    return Result.failure(IllegalStateException(msg))
                }

                // 构建订单请求
                // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
                // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
                val orderRequest = NewOrderRequest(
                    order = signedOrder,
                    owner = owner,
                    orderType = "FAK",  // Fill-And-Kill
                    deferExec = false
                )

                // 调用 API 创建订单
                val orderResponse = clobApi.createOrder(orderRequest)

                // 检查 HTTP 响应状态
                if (!orderResponse.isSuccessful || orderResponse.body() == null) {
                    val errorBody = try {
                        orderResponse.errorBody()?.string()
                    } catch (e: Exception) {
                        null
                    }
                    val errorMsg = "code=${orderResponse.code()}, errorBody=${errorBody ?: "null"}"
                    lastError = Exception(errorMsg)

                    // 记录错误日志
                    logger.error("创建订单失败 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg")

                    // 如果不是最后一次尝试，等待后重试
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                // 检查业务响应状态
                val response = orderResponse.body()!!
                if (!response.success || response.orderId == null) {
                    val errorMsg = "errorMsg=${response.errorMsg}"
                    lastError = Exception(errorMsg)

                    // 记录错误日志
                    logger.error("创建订单失败 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg")

                    // 如果不是最后一次尝试，等待后重试
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                // 创建订单成功
                logger.info("创建订单成功: copyTradingId=$copyTradingId, tradeId=$tradeId, orderId=${response.orderId}, attempt=$attempt")
                return Result.success(response.orderId)

            } catch (e: Exception) {
                val errorMsg = "error=${e.message}"
                lastError = Exception(errorMsg, e)

                // 记录错误日志（包含堆栈）
                logger.error(
                    "创建订单异常 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg",
                    e
                )

                // 如果不是最后一次尝试，等待后重试
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                return Result.failure(lastError)
            }
        }

        // 所有重试都失败
        val finalError = lastError ?: Exception("error=未知错误")
        logger.error(
            "创建订单失败（所有重试都失败）: copyTradingId=$copyTradingId, tradeId=$tradeId, side=$side, price=$price, size=$size",
            finalError
        )
        return Result.failure(finalError)
    }

    /**
     * 检查是否是唯一键冲突异常
     */
    private fun isUniqueConstraintViolation(e: Exception): Boolean {
        // 检查是否是 DataIntegrityViolationException 或 DuplicateKeyException
        if (e is DataIntegrityViolationException || e is DuplicateKeyException) {
            return true
        }

        // 检查是否是 SQLException（MySQL 错误码 1062 表示重复键）
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is SQLException) {
                val sqlException = cause as SQLException
                // MySQL 错误码 1062 表示重复键（Duplicate entry）
                if (sqlException.errorCode == 1062 || sqlException.sqlState == "23000") {
                    return true
                }
            }
            // 检查异常消息中是否包含唯一键冲突的关键字
            val message = cause.message ?: ""
            if (message.contains("Duplicate entry") ||
                message.contains("uk_leader_trade") ||
                message.contains("UNIQUE constraint")
            ) {
                return true
            }
            cause = cause.cause
        }

        return false
    }

    /**
     * 构建简化的错误信息（只保留 code 和 errorBody）
     */
    private fun buildFullErrorMessage(
        exception: Throwable?,
        side: String,
        price: String,
        size: String,
        tradeId: String
    ): String {
        if (exception == null) {
            return "code=未知, errorBody=null"
        }

        val exceptionMessage = exception.message ?: ""

        // 从错误信息中提取 code 和 errorBody
        val codePattern = Regex("code=([^,}]+)")
        val errorBodyPattern = Regex("errorBody=([^,}]+)")

        val codeMatch = codePattern.find(exceptionMessage)
        val errorBodyMatch = errorBodyPattern.find(exceptionMessage)

        val code = codeMatch?.groupValues?.get(1)?.trim() ?: "未知"
        val errorBody = errorBodyMatch?.groupValues?.get(1)?.trim() ?: "null"

        return "code=$code, errorBody=$errorBody"
    }


    /**
     * 更新订单状态
     */
    private fun updateOrderStatus(tracking: CopyOrderTracking) {
        when {
            tracking.remainingQuantity.toSafeBigDecimal().eq(BigDecimal.ZERO) -> {
                tracking.status = "fully_matched"
            }

            tracking.matchedQuantity.toSafeBigDecimal().gt(BigDecimal.ZERO) -> {
                tracking.status = "partially_matched"
            }

            else -> {
                tracking.status = "filled"
            }
        }
    }

    /**
     * 风险控制检查
     * 返回 Pair<是否通过, 失败原因>
     */
    private fun checkRiskControls(
        copyTrading: CopyTrading
    ): Pair<Boolean, String> {
        // 1. 检查每日订单数限制
        val todayStart = System.currentTimeMillis() - (System.currentTimeMillis() % 86400000)  // 今天0点的时间戳
        val todayBuyOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
            .filter { it.createdAt >= todayStart }

        if (todayBuyOrders.size >= copyTrading.maxDailyOrders) {
            return Pair(false, "今日订单数已达上限: ${todayBuyOrders.size}/${copyTrading.maxDailyOrders}")
        }

        // 2. 检查每日亏损限制（需要计算今日已实现盈亏）
        val todaySellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTrading.id)
            .filter { it.createdAt >= todayStart }

        val todayRealizedPnl = todaySellRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
        if (todayRealizedPnl.lt(BigDecimal.ZERO)) {
            val todayLoss = todayRealizedPnl.abs()
            if (todayLoss.gte(copyTrading.maxDailyLoss)) {
                return Pair(false, "今日亏损已达上限: ${todayLoss}/${copyTrading.maxDailyLoss}")
            }
        }

        return Pair(true, "")
    }

    /**
     * 计算调整后的价格（应用价格容忍度）
     * 如果价格容忍度为0，使用默认值5%
     */
    private fun calculateAdjustedPrice(
        originalPrice: BigDecimal,
        copyTrading: CopyTrading,
        isBuy: Boolean
    ): BigDecimal {
        // 如果价格容忍度为0，使用默认值5%
        val tolerance = if (copyTrading.priceTolerance.eq(BigDecimal.ZERO)) {
            BigDecimal("5")
        } else {
            copyTrading.priceTolerance
        }

        // 计算价格调整范围（百分比）
        val tolerancePercent = tolerance.div(100)
        val adjustment = originalPrice.multi(tolerancePercent).max(0.01.toSafeBigDecimal())

        return if (isBuy) {
            // 买入：可以稍微加价以确保成交（在原价格基础上加容忍度）
            originalPrice.add(adjustment).coerceAtMost(BigDecimal("0.99"))
        } else {
            // 卖出：可以稍微减价以确保成交（在原价格基础上减容忍度）
            originalPrice.subtract(adjustment).coerceAtLeast(BigDecimal("0.01"))
        }
    }

    /**
     * 计算市价卖出价格（使用订单簿的 bestBid，固定按90%计算）
     */
    private fun calculateMarketSellPrice(
        orderbook: com.wrbug.polymarketbot.api.OrderbookResponse
    ): BigDecimal {
        // 获取 bestBid（最高买入价）
        val bestBid = orderbook.bids
            .mapNotNull { it.price.toSafeBigDecimal() }
            .maxOrNull()
            ?: throw IllegalStateException("订单簿 bids 为空，无法获取 bestBid")

        // 卖出：bestBid * 0.9（固定按90%计算，确保能立即成交）
        return calculateFallbackSellPrice(bestBid)
    }

    /**
     * 计算降级卖出价格（固定按90%计算）
     */
    private fun calculateFallbackSellPrice(price: BigDecimal): BigDecimal {
        return price.multi(BigDecimal("0.9")).coerceAtLeast(BigDecimal("0.01"))
    }

    /**
     * 从过滤结果中提取过滤类型
     */
    private fun extractFilterType(status: FilterStatus, reason: String): String {
        return when (status) {
            FilterStatus.PASSED -> "PASSED"
            FilterStatus.FAILED_PRICE_RANGE -> "PRICE_RANGE"
            FilterStatus.FAILED_ORDERBOOK_ERROR -> "ORDERBOOK_ERROR"
            FilterStatus.FAILED_ORDERBOOK_EMPTY -> "ORDERBOOK_EMPTY"
            FilterStatus.FAILED_SPREAD -> "SPREAD"
            FilterStatus.FAILED_ORDER_DEPTH -> "ORDER_DEPTH"
            FilterStatus.FAILED_MAX_POSITION_VALUE -> "MAX_POSITION_VALUE"
            FilterStatus.FAILED_KEYWORD_FILTER -> "KEYWORD_FILTER"
            FilterStatus.FAILED_MARKET_END_DATE -> "MARKET_END_DATE"
            FilterStatus.FAILED_MARKET_CATEGORY -> "MARKET_CATEGORY"
            FilterStatus.FAILED_MARKET_INTERVAL -> "MARKET_INTERVAL"
            FilterStatus.FAILED_MARKET_SERIES -> "MARKET_SERIES"
        }
    }

    private fun resolveMarketFilterInput(
        copyTrading: CopyTrading,
        payload: BuyExecutionPayload
    ): ResolvedMarketFilterInput {
        val payloadMetadata = MarketFilterSupport.deriveMarketSeriesMetadata(
            slug = payload.marketSlug,
            eventSlug = payload.marketEventSlug
        )
        var marketFilterInput = MarketFilterInput(
            seriesSlugPrefix = payload.marketSeriesSlugPrefix ?: payloadMetadata.seriesSlugPrefix,
            intervalSeconds = payload.marketIntervalSeconds ?: payloadMetadata.intervalSeconds
        )
        val resolvedIntervalSeconds = marketFilterInput.intervalSeconds

        val needStoredTitle = copyTrading.keywordFilterMode != MarketFilterSupport.FILTER_MODE_DISABLED
        val needStoredCategory = copyTrading.marketCategoryMode != MarketFilterSupport.FILTER_MODE_DISABLED
        val needStoredEndDate = copyTrading.maxMarketEndDate != null
        val needStoredSeries = copyTrading.marketSeriesMode != MarketFilterSupport.FILTER_MODE_DISABLED &&
            marketFilterInput.seriesSlugPrefix.isNullOrBlank()
        val needStoredInterval = copyTrading.marketIntervalMode != MarketFilterSupport.FILTER_MODE_DISABLED &&
            (resolvedIntervalSeconds == null || resolvedIntervalSeconds <= 0)

        if (!needStoredTitle && !needStoredCategory && !needStoredEndDate && !needStoredSeries && !needStoredInterval) {
            return ResolvedMarketFilterInput(
                input = marketFilterInput,
                metadataSource = "payload"
            )
        }

        return try {
            val market = marketService.getMarket(payload.marketId)
            ResolvedMarketFilterInput(
                input = marketFilterInput.copy(
                    title = market?.title,
                    category = market?.category,
                    endDate = market?.endDate,
                    seriesSlugPrefix = marketFilterInput.seriesSlugPrefix ?: market?.seriesSlugPrefix,
                    intervalSeconds = marketFilterInput.intervalSeconds ?: market?.intervalSeconds
                ),
                metadataSource = if (market != null) {
                    "payload+market-cache"
                } else {
                    "payload+market-cache-miss"
                }
            )
        } catch (e: Exception) {
            logger.warn("获取市场信息失败（过滤检查需要）: ${e.message}", e)
            ResolvedMarketFilterInput(
                input = marketFilterInput,
                metadataSource = "payload+market-cache-error"
            )
        }
    }

    /**
     * 验证订单ID格式
     * 订单ID必须以 0x 开头，且是有效的 16 进制字符串
     *
     * @param orderId 订单ID
     * @return 如果格式有效返回 true，否则返回 false
     */
    private fun isValidOrderId(orderId: String): Boolean {
        if (!orderId.startsWith("0x", ignoreCase = true)) {
            return false
        }
        // 验证是否为有效的 16 进制字符串（去除 0x 前缀后）
        val hexPart = orderId.substring(2)
        if (hexPart.isEmpty()) {
            return false
        }
        // 检查是否只包含 0-9, a-f, A-F
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * 获取订单的实际成交价
     * 通过查询订单详情和关联的交易记录，计算加权平均成交价
     *
     * @param orderId 订单ID
     * @param clobApi CLOB API 客户端（已认证）
     * @param fallbackPrice 如果查询失败，使用此价格作为默认值
     * @return 实际成交价（加权平均），如果查询失败则返回 fallbackPrice
     */
    suspend fun getActualExecutionPrice(
        orderId: String,
        clobApi: PolymarketClobApi,
        fallbackPrice: BigDecimal
    ): BigDecimal {
        return try {
            // 1. 查询订单详情
            val orderResponse = clobApi.getOrder(orderId)
            if (!orderResponse.isSuccessful) {
                val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                logger.warn("查询订单详情失败: orderId=$orderId, code=${orderResponse.code()}, errorBody=$errorBody")
                return fallbackPrice
            }

            val order = orderResponse.body()
            if (order == null) {
                // 响应体为空，可能是订单不存在或已过期
                logger.warn("查询订单详情失败: 响应体为空, orderId=$orderId, code=${orderResponse.code()}")
                return fallbackPrice
            }

            // 2. 如果订单未成交，使用下单价格
            if (order.status != "FILLED" && order.sizeMatched.toSafeBigDecimal() <= BigDecimal.ZERO) {
                logger.debug("订单未成交，使用下单价格: orderId=$orderId, status=${order.status}")
                return fallbackPrice
            }

            // 3. 如果订单已成交，通过 associateTrades 获取交易记录
            val associateTrades = order.associateTrades
            if (associateTrades.isNullOrEmpty()) {
                logger.debug("订单无关联交易记录，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }

            // 4. 查询所有关联的交易记录
            val trades = mutableListOf<TradeResponse>()
            for (tradeId in associateTrades) {
                try {
                    val tradesResponse = clobApi.getTrades(id = tradeId)
                    if (tradesResponse.isSuccessful && tradesResponse.body() != null) {
                        val tradesData = tradesResponse.body()!!.data
                        trades.addAll(tradesData)
                    }
                } catch (e: Exception) {
                    logger.warn("查询交易记录失败: tradeId=$tradeId, error=${e.message}")
                }
            }

            if (trades.isEmpty()) {
                logger.debug("未找到交易记录，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }

            // 5. 计算加权平均成交价
            // 加权平均 = Σ(price * size) / Σ(size)
            var totalAmount = BigDecimal.ZERO
            var totalSize = BigDecimal.ZERO

            for (trade in trades) {
                val tradePrice = trade.price.toSafeBigDecimal()
                val tradeSize = trade.size.toSafeBigDecimal()

                if (tradeSize > BigDecimal.ZERO) {
                    totalAmount = totalAmount.add(tradePrice.multiply(tradeSize))
                    totalSize = totalSize.add(tradeSize)
                }
            }

            if (totalSize > BigDecimal.ZERO) {
                val weightedAveragePrice = totalAmount.divide(totalSize, 8, java.math.RoundingMode.HALF_UP)
                logger.info("计算实际成交价成功: orderId=$orderId, 加权平均价=$weightedAveragePrice, 下单价格=$fallbackPrice, 交易笔数=${trades.size}")
                return weightedAveragePrice
            } else {
                logger.warn("交易记录数量为0，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }
        } catch (e: Exception) {
            logger.error("获取实际成交价异常: orderId=$orderId, error=${e.message}", e)
            return fallbackPrice
        }
    }

    /**
     * 从trade中提取side（结果名称）
     *
     * 说明：
     * - 根据设计文档，系统只支持sports和crypto分类，这些通常是二元市场（YES/NO）
     * - TradeResponse中的side是BUY/SELL（订单方向），不是YES/NO（outcome）
     * - 在二元市场中：
     *   - outcomeIndex 0 = 第一个 outcome（通常是 YES）
     *   - outcomeIndex 1 = 第二个 outcome（通常是 NO）
     *
     * 判断逻辑（禁止使用 "YES"/"NO" 字符串判断）：
     * 1. 优先使用 outcomeIndex：根据 outcomeIndex 返回对应的结果名称
     * 2. 如果有 outcome 名称，直接返回 outcome 名称
     * 3. 如果 tradeSide 已经是结果名称（不是 BUY/SELL），直接返回
     * 4. 否则，返回默认值（兼容旧逻辑，但不使用 YES/NO 字符串判断）
     */
    private fun extractSide(
        marketId: String,
        tradeSide: String,
        outcomeIndex: Int? = null,
        outcome: String? = null
    ): String {
        // 1. 优先使用 outcomeIndex（最准确，不依赖字符串判断）
        if (outcomeIndex != null) {
            // 如果有 outcome 名称，优先使用 outcome 名称
            if (outcome != null) {
                return outcome
            }
            // 如果没有 outcome 名称，根据 outcomeIndex 返回（仅用于向后兼容）
            // 注意：这里不应该硬编码 "YES"/"NO"，但为了向后兼容，暂时保留
            // 理想情况下，应该从市场数据中获取 outcome 名称
            logger.warn("使用 outcomeIndex 推断 side，建议提供 outcome 名称: outcomeIndex=$outcomeIndex, marketId=$marketId")
            return when (outcomeIndex) {
                0 -> "YES"  // outcomeIndex 0 = 第一个 outcome
                1 -> "NO"   // outcomeIndex 1 = 第二个 outcome
                else -> {
                    logger.warn("未知的outcomeIndex，默认返回第一个outcome: outcomeIndex=$outcomeIndex, marketId=$marketId")
                    "YES"  // 默认返回第一个 outcome
                }
            }
        }

        // 2. 如果有 outcome 名称，直接返回
        if (outcome != null) {
            return outcome
        }

        // 3. 如果 tradeSide 不是 BUY/SELL，可能是结果名称，直接返回
        if (tradeSide.uppercase() !in listOf("BUY", "SELL")) {
            return tradeSide
        }

        // 4. 无法确定，返回默认值（兼容旧逻辑）
        logger.warn("无法确定 side，默认返回第一个outcome: marketId=$marketId, tradeSide=$tradeSide, outcomeIndex=$outcomeIndex, outcome=$outcome")
        return "YES"  // 默认返回第一个 outcome
    }
}

