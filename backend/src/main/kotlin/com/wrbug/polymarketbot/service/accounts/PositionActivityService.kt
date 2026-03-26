package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.PositionActivityItemDto
import com.wrbug.polymarketbot.dto.PositionActivityRequest
import com.wrbug.polymarketbot.dto.PositionActivityResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.PositionActivityLog
import com.wrbug.polymarketbot.entity.PositionActivitySyncState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.ManualPositionHistoryRepository
import com.wrbug.polymarketbot.repository.PositionActivityLogRepository
import com.wrbug.polymarketbot.repository.PositionActivitySyncStateRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.api.GetTradesResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

@Service
class PositionActivityService(
    private val accountRepository: AccountRepository,
    private val manualPositionHistoryRepository: ManualPositionHistoryRepository,
    private val positionActivityLogRepository: PositionActivityLogRepository,
    private val positionActivitySyncStateRepository: PositionActivitySyncStateRepository,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: com.wrbug.polymarketbot.util.CryptoUtils
) {
    private val logger = LoggerFactory.getLogger(PositionActivityService::class.java)
    private val syncMutexByAccount = ConcurrentHashMap<Long, Mutex>()

    companion object {
        const val SOURCE_CLOB_TRADE = "CLOB_TRADE"
        const val SOURCE_SYSTEM_ORDER = "SYSTEM_ORDER"
    }

    private data class PositionKey(
        val accountId: Long,
        val marketId: String,
        val outcomeIndex: Int?,
        val side: String
    )

    @Scheduled(fixedDelay = 60000)
    fun scheduledSyncPositionActivities() {
        runBlocking {
            try {
                val accountIds = accountRepository.findAll()
                    .mapNotNull { it.id }
                accountIds.forEach { accountId ->
                    try {
                        syncAccountActivitiesForAccountId(accountId, quick = false)
                    } catch (e: Exception) {
                        logger.warn("定时同步仓位流水失败: accountId=$accountId, error=${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.warn("定时同步仓位流水异常: ${e.message}", e)
            }
        }
    }

    suspend fun getPositionActivities(request: PositionActivityRequest): Result<PositionActivityResponse> {
        return try {
            if (request.accountId <= 0L) {
                return Result.failure(IllegalArgumentException("accountId 必须大于 0"))
            }
            if (request.marketId.isBlank()) {
                return Result.failure(IllegalArgumentException("marketId 不能为空"))
            }
            val normalizedSide = normalizePositionSide(request.side)
                ?: return Result.failure(IllegalArgumentException("side 不能为空"))
            val page = request.page.coerceAtLeast(1)
            val pageSize = request.pageSize.coerceIn(1, 200)

            // 查询前触发一次快速增量同步，保证数据新鲜度
            syncAccountActivitiesForAccountId(request.accountId, quick = true)

            val pageable = PageRequest.of(
                page - 1,
                pageSize,
                Sort.by(
                    Sort.Order.desc("eventTime"),
                    Sort.Order.desc("id")
                )
            )

            val dataPage = if (request.outcomeIndex != null) {
                positionActivityLogRepository.findByAccountIdAndMarketIdAndOutcomeIndexAndSide(
                    accountId = request.accountId,
                    marketId = request.marketId.trim(),
                    outcomeIndex = request.outcomeIndex,
                    side = normalizedSide,
                    pageable = pageable
                )
            } else {
                positionActivityLogRepository.findByAccountIdAndMarketIdAndOutcomeIndexIsNullAndSide(
                    accountId = request.accountId,
                    marketId = request.marketId.trim(),
                    side = normalizedSide,
                    pageable = pageable
                )
            }

            Result.success(
                PositionActivityResponse(
                    list = dataPage.content.map { it.toDto() },
                    total = dataPage.totalElements,
                    page = page,
                    pageSize = pageSize
                )
            )
        } catch (e: Exception) {
            logger.error("查询仓位流水失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun syncAccountActivitiesForAccountId(accountId: Long, quick: Boolean) {
        val account = accountRepository.findById(accountId).orElse(null) ?: return
        if (account.id == null) return
        val lock = syncMutexByAccount.getOrPut(account.id) { Mutex() }
        lock.withLock {
            syncAccountActivities(account, quick)
        }
    }

    private suspend fun syncAccountActivities(account: Account, quick: Boolean) {
        val accountId = account.id ?: return
        val now = System.currentTimeMillis()
        val state = positionActivitySyncStateRepository.findByAccountId(accountId)
            ?: run {
                // 首次初始化：从当前时刻开始，不回填历史
                positionActivitySyncStateRepository.save(
                    PositionActivitySyncState(
                        accountId = accountId,
                        initializedAt = now,
                        lastSyncedTradeTime = now,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                return
            }

        val touchedKeys = linkedSetOf<PositionKey>()
        syncManualPositionHistoryToActivityLog(accountId, state, touchedKeys)
        val maxTradeTime = syncClobTradesToActivityLog(account, state.lastSyncedTradeTime, touchedKeys, quick)

        val nextSyncedTradeTime = if (maxTradeTime != null) {
            maxOf(state.lastSyncedTradeTime, maxTradeTime)
        } else {
            state.lastSyncedTradeTime
        }

        if (nextSyncedTradeTime != state.lastSyncedTradeTime) {
            positionActivitySyncStateRepository.save(
                state.copy(
                    lastSyncedTradeTime = nextSyncedTradeTime,
                    updatedAt = now
                )
            )
        }

        touchedKeys.forEach { key ->
            recomputePositionTimeline(
                accountId = key.accountId,
                marketId = key.marketId,
                outcomeIndex = key.outcomeIndex,
                side = key.side
            )
        }
    }

    private fun syncManualPositionHistoryToActivityLog(
        accountId: Long,
        state: PositionActivitySyncState,
        touchedKeys: MutableSet<PositionKey>
    ) {
        val histories = manualPositionHistoryRepository.findByAccountIdOrderByCreatedAtAsc(accountId)
        if (histories.isEmpty()) return

        val toInsert = histories.asSequence()
            .filter { it.createdAt > state.initializedAt }
            .filter { !it.closeOrderId.isBlank() }
            .filter {
                !positionActivityLogRepository.existsByAccountIdAndOrderIdAndSource(
                    accountId = accountId,
                    orderId = it.closeOrderId,
                    source = SOURCE_SYSTEM_ORDER
                )
            }
            .mapNotNull { history ->
                val normalizedSide = normalizePositionSide(history.side) ?: return@mapNotNull null
                val quantity = history.closedQuantity.abs()
                if (quantity <= BigDecimal.ZERO) return@mapNotNull null
                val price = history.closePrice.toSafeBigDecimal()
                if (price <= BigDecimal.ZERO) return@mapNotNull null
                val actualAmount = price.multiply(quantity)
                val fee = BigDecimal.ZERO

                touchedKeys.add(
                    PositionKey(
                        accountId = accountId,
                        marketId = history.marketId,
                        outcomeIndex = history.outcomeIndex,
                        side = normalizedSide
                    )
                )

                PositionActivityLog(
                    accountId = accountId,
                    marketId = history.marketId,
                    outcomeIndex = history.outcomeIndex,
                    side = normalizedSide,
                    eventType = PositionActivityClassifier.EVENT_REDUCE,
                    tradeSide = PositionActivityClassifier.TRADE_SIDE_SELL,
                    eventTime = history.createdAt,
                    price = price,
                    quantity = quantity,
                    actualAmount = actualAmount,
                    fee = fee,
                    remainingQuantity = BigDecimal.ZERO,
                    source = SOURCE_SYSTEM_ORDER,
                    orderId = history.closeOrderId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            .toList()

        if (toInsert.isNotEmpty()) {
            positionActivityLogRepository.saveAll(toInsert)
        }
    }

    private suspend fun syncClobTradesToActivityLog(
        account: Account,
        lastSyncedTradeTime: Long,
        touchedKeys: MutableSet<PositionKey>,
        quick: Boolean
    ): Long? {
        val accountId = account.id ?: return null
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank() || account.apiPassphrase.isNullOrBlank()) {
            return null
        }

        val apiSecret = runCatching { cryptoUtils.decrypt(account.apiSecret) }.getOrNull() ?: return null
        val apiPassphrase = runCatching { cryptoUtils.decrypt(account.apiPassphrase) }.getOrNull() ?: return null
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )

        val maxPages = if (quick) 5 else 60
        var pageCount = 0
        var nextCursor: String? = null
        var maxTradeTime: Long? = null
        val logsToInsert = mutableListOf<PositionActivityLog>()

        while (pageCount < maxPages) {
            val response = try {
                clobApi.getTrades(
                    maker_address = account.proxyAddress,
                    next_cursor = nextCursor
                )
            } catch (e: Exception) {
                logger.warn("同步仓位流水时查询成交失败: accountId=$accountId, error=${e.message}")
                break
            }

            val body: GetTradesResponse = response.body() ?: break
            if (!response.isSuccessful) {
                break
            }
            if (body.data.isEmpty()) {
                break
            }

            val allOld = body.data.all { trade ->
                val tradeTime = parseTradeTimestampMillis(trade.timestamp)
                tradeTime != null && tradeTime <= lastSyncedTradeTime
            }

            body.data.forEach { trade ->
                val tradeTime = parseTradeTimestampMillis(trade.timestamp) ?: return@forEach
                if (tradeTime <= lastSyncedTradeTime) return@forEach
                if (trade.id.isBlank() || trade.market.isBlank()) return@forEach
                if (positionActivityLogRepository.existsByAccountIdAndTradeId(accountId, trade.id)) return@forEach

                val normalizedSide = resolvePositionSide(trade.outcome, trade.outcomeIndex) ?: return@forEach
                val tradeSide = normalizeTradeSide(trade.side) ?: return@forEach
                val price = trade.price.toSafeBigDecimal()
                val quantity = trade.size.toSafeBigDecimal().abs()
                if (price <= BigDecimal.ZERO || quantity <= BigDecimal.ZERO) return@forEach

                val actualAmount = price.multiply(quantity)
                val fee = trade.fee.toSafeBigDecimal().abs()
                logsToInsert.add(
                    PositionActivityLog(
                        accountId = accountId,
                        marketId = trade.market,
                        outcomeIndex = trade.outcomeIndex,
                        side = normalizedSide,
                        eventType = PositionActivityClassifier.EVENT_REDUCE,
                        tradeSide = tradeSide,
                        eventTime = tradeTime,
                        price = price,
                        quantity = quantity,
                        actualAmount = actualAmount,
                        fee = fee,
                        remainingQuantity = BigDecimal.ZERO,
                        source = SOURCE_CLOB_TRADE,
                        tradeId = trade.id,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                touchedKeys.add(
                    PositionKey(
                        accountId = accountId,
                        marketId = trade.market,
                        outcomeIndex = trade.outcomeIndex,
                        side = normalizedSide
                    )
                )
                maxTradeTime = if (maxTradeTime == null) tradeTime else maxOf(maxTradeTime!!, tradeTime)
            }

            if (allOld && quick) {
                break
            }

            nextCursor = body.next_cursor
            if (nextCursor.isNullOrBlank()) {
                break
            }
            pageCount++
        }

        if (logsToInsert.isNotEmpty()) {
            positionActivityLogRepository.saveAll(logsToInsert)
        }
        return maxTradeTime
    }

    private fun recomputePositionTimeline(
        accountId: Long,
        marketId: String,
        outcomeIndex: Int?,
        side: String
    ) {
        val events = if (outcomeIndex != null) {
            positionActivityLogRepository.findByAccountIdAndMarketIdAndOutcomeIndexAndSideOrderByEventTimeAscIdAsc(
                accountId = accountId,
                marketId = marketId,
                outcomeIndex = outcomeIndex,
                side = side
            )
        } else {
            positionActivityLogRepository.findByAccountIdAndMarketIdAndOutcomeIndexIsNullAndSideOrderByEventTimeAscIdAsc(
                accountId = accountId,
                marketId = marketId,
                side = side
            )
        }
        if (events.isEmpty()) return

        val computed = PositionActivityClassifier.compute(
            events.map {
                PositionActivityComputationInput(
                    tradeSide = it.tradeSide,
                    quantity = it.quantity
                )
            }
        )
        val now = System.currentTimeMillis()
        val updated = mutableListOf<PositionActivityLog>()
        events.forEachIndexed { index, event ->
            val computedItem = computed[index]
            if (event.eventType != computedItem.eventType || event.remainingQuantity != computedItem.remainingQuantity) {
                updated.add(
                    event.copy(
                        eventType = computedItem.eventType,
                        remainingQuantity = computedItem.remainingQuantity,
                        updatedAt = now
                    )
                )
            }
        }
        if (updated.isNotEmpty()) {
            positionActivityLogRepository.saveAll(updated)
        }
    }

    private fun resolvePositionSide(outcome: String?, outcomeIndex: Int?): String? {
        normalizePositionSide(outcome)?.let { return it }
        return when (outcomeIndex) {
            0 -> "YES"
            1 -> "NO"
            else -> null
        }
    }

    private fun normalizePositionSide(side: String?): String? {
        val normalized = side?.trim()?.uppercase()
        return normalized?.takeIf { it.isNotBlank() }
    }

    private fun normalizeTradeSide(side: String?): String? {
        val normalized = side?.trim()?.uppercase()
        return when (normalized) {
            PositionActivityClassifier.TRADE_SIDE_BUY -> PositionActivityClassifier.TRADE_SIDE_BUY
            PositionActivityClassifier.TRADE_SIDE_SELL -> PositionActivityClassifier.TRADE_SIDE_SELL
            else -> null
        }
    }

    private fun parseTradeTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        trimmed.toLongOrNull()?.let { ts ->
            return if (ts < 1_000_000_000_000L) ts * 1000 else ts
        }

        return runCatching { Instant.parse(trimmed).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun PositionActivityLog.toDto(): PositionActivityItemDto {
        return PositionActivityItemDto(
            eventType = eventType,
            tradeSide = tradeSide,
            eventTime = eventTime,
            price = price.toPlainString(),
            quantity = quantity.toPlainString(),
            actualAmount = actualAmount.toPlainString(),
            fee = fee.toPlainString(),
            remainingQuantity = remainingQuantity.toPlainString(),
            source = source,
            tradeId = tradeId,
            orderId = orderId
        )
    }
}
