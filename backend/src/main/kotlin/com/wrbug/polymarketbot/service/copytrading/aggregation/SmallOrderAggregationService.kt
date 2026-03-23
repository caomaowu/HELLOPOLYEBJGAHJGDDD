package com.wrbug.polymarketbot.service.copytrading.aggregation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
class SmallOrderAggregationService {

    private val logger = LoggerFactory.getLogger(SmallOrderAggregationService::class.java)
    private val runtimeSemanticsLogged = AtomicBoolean(false)

    private data class BufferedAggregation(
        val key: String,
        val copyTradingId: Long,
        val accountId: Long,
        val leaderId: Long,
        val side: String,
        val tokenId: String,
        val marketId: String,
        val outcomeIndex: Int?,
        var marketSlug: String?,
        var marketEventSlug: String?,
        var seriesSlugPrefix: String?,
        var intervalSeconds: Int?,
        val trades: MutableList<AggregatedTradeItem>,
        val leaderTradeIds: MutableSet<String>,
        val firstBufferedAt: Long,
        var lastBufferedAt: Long,
        var duplicateIgnoredCount: Int = 0
    )

    // Buffer is intentionally in-memory only. Pending groups are lost on process restart and
    // aggregation semantics are only strictly consistent in a single-instance deployment.
    private val buffer = ConcurrentHashMap<String, BufferedAggregation>()

    fun bufferTrade(request: SmallOrderAggregationRequest): SmallOrderAggregationBufferResult {
        logRuntimeSemanticsOnce()
        val normalizedSide = request.side.uppercase()
        val key = buildKey(
            copyTradingId = request.copyTradingId,
            marketId = request.marketId,
            outcomeIndex = request.outcomeIndex,
            tokenId = request.tokenId,
            side = normalizedSide
        )
        val tradeItem = AggregatedTradeItem(
            leaderTradeId = request.leaderTradeId,
            leaderQuantity = request.leaderQuantity,
            leaderOrderAmount = request.leaderOrderAmount,
            tradePrice = request.tradePrice,
            outcome = request.outcome,
            source = request.source
        )
        var status = SmallOrderAggregationBufferStatus.BUFFERED

        val aggregation = buffer.compute(key) { _, existing ->
            if (existing == null) {
                BufferedAggregation(
                    key = key,
                    copyTradingId = request.copyTradingId,
                    accountId = request.accountId,
                    leaderId = request.leaderId,
                    side = normalizedSide,
                    tokenId = request.tokenId,
                    marketId = request.marketId,
                    outcomeIndex = request.outcomeIndex,
                    marketSlug = request.marketSlug,
                    marketEventSlug = request.marketEventSlug,
                    seriesSlugPrefix = request.seriesSlugPrefix,
                    intervalSeconds = request.intervalSeconds,
                    trades = mutableListOf(tradeItem),
                    leaderTradeIds = linkedSetOf(request.leaderTradeId),
                    firstBufferedAt = request.bufferedAt,
                    lastBufferedAt = request.bufferedAt
                )
            } else {
                if (!existing.leaderTradeIds.add(request.leaderTradeId)) {
                    status = SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED
                    existing.duplicateIgnoredCount += 1
                } else {
                    existing.trades += tradeItem
                    existing.lastBufferedAt = request.bufferedAt
                    existing.marketSlug = existing.marketSlug ?: request.marketSlug
                    existing.marketEventSlug = existing.marketEventSlug ?: request.marketEventSlug
                    existing.seriesSlugPrefix = existing.seriesSlugPrefix ?: request.seriesSlugPrefix
                    existing.intervalSeconds = existing.intervalSeconds ?: request.intervalSeconds
                }
                existing
            }
        } ?: error("small order aggregation buffer compute returned null")

        val snapshot = aggregation.toBatch()
        if (status == SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED) {
            logger.info(
                "忽略重复的小额 {} 聚合缓冲请求: key={}, copyTradingId={}, leaderTradeId={}, tradeCount={}",
                normalizedSide,
                key,
                request.copyTradingId,
                request.leaderTradeId,
                snapshot.trades.size
            )
        } else {
            logger.info(
                "小额 {} 已进入聚合缓冲: key={}, copyTradingId={}, tradeCount={}, totalLeaderAmount={}",
                normalizedSide,
                key,
                request.copyTradingId,
                snapshot.trades.size,
                snapshot.totalLeaderOrderAmount.stripTrailingZeros().toPlainString()
            )
        }
        return SmallOrderAggregationBufferResult(
            batch = snapshot,
            status = status
        )
    }

    fun releaseExpired(
        windowSecondsByCopyTradingId: Map<Long, Int>,
        side: String? = null,
        now: Long = System.currentTimeMillis()
    ): List<SmallOrderAggregationReleaseResult> {
        if (buffer.isEmpty()) {
            return emptyList()
        }

        val normalizedSide = side?.uppercase()
        val expired = mutableListOf<SmallOrderAggregationReleaseResult>()
        for ((key, aggregation) in buffer.entries) {
            if (normalizedSide != null && aggregation.side != normalizedSide) {
                continue
            }
            val windowSeconds = windowSecondsByCopyTradingId[aggregation.copyTradingId]
                ?: SmallOrderAggregationSupport.DEFAULT_WINDOW_SECONDS
            val expiresAt = aggregation.firstBufferedAt + windowSeconds * 1000L
            if (now < expiresAt) {
                continue
            }

            val removed = buffer.remove(key, aggregation)
            if (removed) {
                expired += SmallOrderAggregationReleaseResult(
                    batch = aggregation.toBatch(),
                    reason = SmallOrderAggregationReleaseReason.WINDOW_EXPIRED,
                    releasedAt = now
                )
            }
        }
        return expired
    }

    fun release(
        key: String,
        reason: SmallOrderAggregationReleaseReason,
        now: Long = System.currentTimeMillis()
    ): SmallOrderAggregationReleaseResult? {
        val aggregation = buffer.remove(key) ?: return null
        return SmallOrderAggregationReleaseResult(
            batch = aggregation.toBatch(),
            reason = reason,
            releasedAt = now
        )
    }

    fun pendingGroupCount(): Int = buffer.size

    fun clear(copyTradingId: Long? = null): Int {
        if (copyTradingId == null) {
            val removed = buffer.size
            buffer.clear()
            return removed
        }

        val keysToRemove = buffer.values
            .filter { it.copyTradingId == copyTradingId }
            .map { it.key }
        keysToRemove.forEach { key -> buffer.remove(key) }
        return keysToRemove.size
    }

    fun clearInactive(activeCopyTradingIds: Set<Long>): Int {
        if (buffer.isEmpty()) {
            return 0
        }
        val keysToRemove = buffer.values
            .filter { it.copyTradingId !in activeCopyTradingIds }
            .map { it.key }
        keysToRemove.forEach { key -> buffer.remove(key) }
        return keysToRemove.size
    }

    fun getSnapshot(copyTradingId: Long? = null): SmallOrderAggregationSnapshot {
        val groups = buffer.values
            .asSequence()
            .filter { copyTradingId == null || it.copyTradingId == copyTradingId }
            .map { aggregation ->
                val batch = aggregation.toBatch()
                SmallOrderAggregationGroupSnapshot(
                    key = aggregation.key,
                    copyTradingId = aggregation.copyTradingId,
                    accountId = aggregation.accountId,
                    leaderId = aggregation.leaderId,
                    side = aggregation.side,
                    tokenId = aggregation.tokenId,
                    marketId = aggregation.marketId,
                    outcomeIndex = aggregation.outcomeIndex,
                    marketSlug = aggregation.marketSlug,
                    marketEventSlug = aggregation.marketEventSlug,
                    seriesSlugPrefix = aggregation.seriesSlugPrefix,
                    intervalSeconds = aggregation.intervalSeconds,
                    tradeCount = batch.trades.size,
                    totalLeaderQuantity = batch.totalLeaderQuantity,
                    totalLeaderOrderAmount = batch.totalLeaderOrderAmount,
                    averageTradePrice = batch.averageTradePrice,
                    firstBufferedAt = aggregation.firstBufferedAt,
                    lastBufferedAt = aggregation.lastBufferedAt,
                    duplicateIgnoredCount = aggregation.duplicateIgnoredCount,
                    sampleLeaderTradeIds = aggregation.trades
                        .asSequence()
                        .map { it.leaderTradeId }
                        .take(10)
                        .toList()
                )
            }
            .sortedWith(compareBy<SmallOrderAggregationGroupSnapshot> { it.copyTradingId }.thenBy { it.key })
            .toList()
        return SmallOrderAggregationSnapshot(
            totalGroupCount = groups.size,
            totalTradeCount = groups.sumOf { it.tradeCount },
            totalDuplicateIgnoredCount = groups.sumOf { it.duplicateIgnoredCount },
            groups = groups
        )
    }

    private fun logRuntimeSemanticsOnce() {
        if (runtimeSemanticsLogged.compareAndSet(false, true)) {
            logger.warn(
                "小额聚合缓冲为进程内内存态；服务重启会清空待释放批次，当前灰度仅保证单实例下的聚合一致性，多实例部署视为降级/不支持"
            )
        }
    }

    private fun buildKey(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int?,
        tokenId: String,
        side: String
    ): String {
        return "$copyTradingId:$marketId:${outcomeIndex ?: -1}:$tokenId:$side"
    }

    private fun BufferedAggregation.toBatch(): SmallOrderAggregationBatch {
        return SmallOrderAggregationBatch(
            key = key,
            copyTradingId = copyTradingId,
            accountId = accountId,
            leaderId = leaderId,
            side = side,
            tokenId = tokenId,
            marketId = marketId,
            outcomeIndex = outcomeIndex,
            marketSlug = marketSlug,
            marketEventSlug = marketEventSlug,
            seriesSlugPrefix = seriesSlugPrefix,
            intervalSeconds = intervalSeconds,
            trades = trades.toList(),
            firstBufferedAt = firstBufferedAt,
            lastBufferedAt = lastBufferedAt,
            duplicateIgnoredCount = duplicateIgnoredCount
        )
    }
}
