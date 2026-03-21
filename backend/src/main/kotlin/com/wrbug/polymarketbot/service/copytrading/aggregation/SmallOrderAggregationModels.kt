package com.wrbug.polymarketbot.service.copytrading.aggregation

import java.math.BigDecimal
import java.math.RoundingMode

data class SmallOrderAggregationRequest(
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
    val side: String = "BUY",
    val tokenId: String,
    val marketId: String,
    val outcomeIndex: Int?,
    val leaderTradeId: String,
    val leaderQuantity: BigDecimal,
    val leaderOrderAmount: BigDecimal,
    val tradePrice: BigDecimal,
    val outcome: String?,
    val source: String,
    val bufferedAt: Long = System.currentTimeMillis()
)

enum class SmallOrderAggregationBufferStatus {
    BUFFERED,
    DUPLICATE_IGNORED
}

enum class SmallOrderAggregationReleaseReason {
    THRESHOLD_REACHED,
    WINDOW_EXPIRED
}

data class AggregatedTradeItem(
    val leaderTradeId: String,
    val leaderQuantity: BigDecimal,
    val leaderOrderAmount: BigDecimal,
    val tradePrice: BigDecimal,
    val outcome: String?,
    val source: String
)

data class SmallOrderAggregationBufferResult(
    val batch: SmallOrderAggregationBatch,
    val status: SmallOrderAggregationBufferStatus
)

data class SmallOrderAggregationReleaseResult(
    val batch: SmallOrderAggregationBatch,
    val reason: SmallOrderAggregationReleaseReason,
    val releasedAt: Long = System.currentTimeMillis()
)

data class SmallOrderAggregationBatch(
    val key: String,
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
    val side: String = "BUY",
    val tokenId: String,
    val marketId: String,
    val outcomeIndex: Int?,
    val trades: List<AggregatedTradeItem>,
    val firstBufferedAt: Long,
    val lastBufferedAt: Long,
    val duplicateIgnoredCount: Int = 0
) {
    val totalLeaderQuantity: BigDecimal = trades.fold(BigDecimal.ZERO) { sum, trade ->
        sum.add(trade.leaderQuantity)
    }

    val totalLeaderOrderAmount: BigDecimal = trades.fold(BigDecimal.ZERO) { sum, trade ->
        sum.add(trade.leaderOrderAmount)
    }

    val averageTradePrice: BigDecimal = if (totalLeaderQuantity > BigDecimal.ZERO) {
        totalLeaderOrderAmount.divide(totalLeaderQuantity, 8, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    val representativeTradeId: String = trades.first().leaderTradeId
    val representativeOutcome: String? = trades.firstOrNull()?.outcome
}

data class SmallOrderAggregationGroupSnapshot(
    val key: String,
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
    val side: String,
    val tokenId: String,
    val marketId: String,
    val outcomeIndex: Int?,
    val tradeCount: Int,
    val totalLeaderQuantity: BigDecimal,
    val totalLeaderOrderAmount: BigDecimal,
    val averageTradePrice: BigDecimal,
    val firstBufferedAt: Long,
    val lastBufferedAt: Long,
    val duplicateIgnoredCount: Int,
    val sampleLeaderTradeIds: List<String>
)

data class SmallOrderAggregationSnapshot(
    val totalGroupCount: Int,
    val totalTradeCount: Int,
    val totalDuplicateIgnoredCount: Int,
    val groups: List<SmallOrderAggregationGroupSnapshot>
)

object SmallOrderAggregationSupport {
    const val DEFAULT_WINDOW_SECONDS = 300
    const val MIN_WINDOW_SECONDS = 1
    const val MAX_WINDOW_SECONDS = 3600

    fun validateConfig(enabled: Boolean, windowSeconds: Int): List<String> {
        if (!enabled) {
            return emptyList()
        }

        val errors = mutableListOf<String>()
        if (windowSeconds < MIN_WINDOW_SECONDS) {
            errors += "smallOrderAggregationWindowSeconds 必须大于 0"
        }
        if (windowSeconds > MAX_WINDOW_SECONDS) {
            errors += "smallOrderAggregationWindowSeconds 不能超过 $MAX_WINDOW_SECONDS 秒"
        }
        return errors
    }
}
