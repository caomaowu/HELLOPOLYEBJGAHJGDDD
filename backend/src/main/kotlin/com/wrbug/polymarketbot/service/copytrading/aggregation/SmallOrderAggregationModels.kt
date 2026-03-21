package com.wrbug.polymarketbot.service.copytrading.aggregation

import java.math.BigDecimal
import java.math.RoundingMode

data class SmallOrderAggregationRequest(
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
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

data class AggregatedTradeItem(
    val leaderTradeId: String,
    val leaderQuantity: BigDecimal,
    val leaderOrderAmount: BigDecimal,
    val tradePrice: BigDecimal,
    val outcome: String?,
    val source: String
)

data class SmallOrderAggregationBatch(
    val key: String,
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
    val tokenId: String,
    val marketId: String,
    val outcomeIndex: Int?,
    val trades: List<AggregatedTradeItem>,
    val firstBufferedAt: Long,
    val lastBufferedAt: Long
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
