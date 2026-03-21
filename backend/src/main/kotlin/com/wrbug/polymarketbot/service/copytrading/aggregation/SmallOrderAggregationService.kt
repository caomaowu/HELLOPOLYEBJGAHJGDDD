package com.wrbug.polymarketbot.service.copytrading.aggregation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class SmallOrderAggregationService {

    private val logger = LoggerFactory.getLogger(SmallOrderAggregationService::class.java)

    private data class BufferedAggregation(
        val key: String,
        val copyTradingId: Long,
        val accountId: Long,
        val leaderId: Long,
        val tokenId: String,
        val marketId: String,
        val outcomeIndex: Int?,
        val trades: MutableList<AggregatedTradeItem>,
        val firstBufferedAt: Long,
        var lastBufferedAt: Long
    )

    private val buffer = ConcurrentHashMap<String, BufferedAggregation>()

    fun bufferTrade(request: SmallOrderAggregationRequest): SmallOrderAggregationBatch {
        val key = buildKey(
            copyTradingId = request.copyTradingId,
            marketId = request.marketId,
            outcomeIndex = request.outcomeIndex,
            tokenId = request.tokenId
        )
        val tradeItem = AggregatedTradeItem(
            leaderTradeId = request.leaderTradeId,
            leaderQuantity = request.leaderQuantity,
            leaderOrderAmount = request.leaderOrderAmount,
            tradePrice = request.tradePrice,
            outcome = request.outcome,
            source = request.source
        )

        val aggregation = buffer.compute(key) { _, existing ->
            if (existing == null) {
                BufferedAggregation(
                    key = key,
                    copyTradingId = request.copyTradingId,
                    accountId = request.accountId,
                    leaderId = request.leaderId,
                    tokenId = request.tokenId,
                    marketId = request.marketId,
                    outcomeIndex = request.outcomeIndex,
                    trades = mutableListOf(tradeItem),
                    firstBufferedAt = request.bufferedAt,
                    lastBufferedAt = request.bufferedAt
                )
            } else {
                existing.trades += tradeItem
                existing.lastBufferedAt = request.bufferedAt
                existing
            }
        } ?: error("small order aggregation buffer compute returned null")

        val snapshot = aggregation.toBatch()
        logger.info(
            "小额 BUY 已进入聚合缓冲: key={}, copyTradingId={}, tradeCount={}, totalLeaderAmount={}",
            key,
            request.copyTradingId,
            snapshot.trades.size,
            snapshot.totalLeaderOrderAmount.stripTrailingZeros().toPlainString()
        )
        return snapshot
    }

    fun releaseExpired(windowSecondsByCopyTradingId: Map<Long, Int>, now: Long = System.currentTimeMillis()): List<SmallOrderAggregationBatch> {
        if (buffer.isEmpty()) {
            return emptyList()
        }

        val expired = mutableListOf<SmallOrderAggregationBatch>()
        for ((key, aggregation) in buffer.entries) {
            val windowSeconds = windowSecondsByCopyTradingId[aggregation.copyTradingId]
                ?: SmallOrderAggregationSupport.DEFAULT_WINDOW_SECONDS
            val expiresAt = aggregation.firstBufferedAt + windowSeconds * 1000L
            if (now < expiresAt) {
                continue
            }

            val removed = buffer.remove(key, aggregation)
            if (removed) {
                expired += aggregation.toBatch()
            }
        }
        return expired
    }

    fun pendingGroupCount(): Int = buffer.size

    fun clear(copyTradingId: Long? = null) {
        if (copyTradingId == null) {
            buffer.clear()
            return
        }

        val keysToRemove = buffer.values
            .filter { it.copyTradingId == copyTradingId }
            .map { it.key }
        keysToRemove.forEach { key -> buffer.remove(key) }
    }

    private fun buildKey(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int?,
        tokenId: String
    ): String {
        return "$copyTradingId:$marketId:${outcomeIndex ?: -1}:$tokenId:BUY"
    }

    private fun BufferedAggregation.toBatch(): SmallOrderAggregationBatch {
        return SmallOrderAggregationBatch(
            key = key,
            copyTradingId = copyTradingId,
            accountId = accountId,
            leaderId = leaderId,
            tokenId = tokenId,
            marketId = marketId,
            outcomeIndex = outcomeIndex,
            trades = trades.toList(),
            firstBufferedAt = firstBufferedAt,
            lastBufferedAt = lastBufferedAt
        )
    }
}
