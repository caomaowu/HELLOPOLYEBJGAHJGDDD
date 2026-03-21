package com.wrbug.polymarketbot.service.copytrading.aggregation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SmallOrderAggregationServiceTest {

    private val service = SmallOrderAggregationService()

    @Test
    fun `buffered trades with same key should aggregate into one batch`() {
        service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(tradeId = "t2", bufferedAt = 2_000L))

        val batches = service.releaseExpired(mapOf(1L to 5), now = 6_500L)

        assertEquals(1, batches.size)
        val batch = batches.first()
        assertEquals(2, batch.trades.size)
        assertDecimalEquals("4", batch.totalLeaderQuantity)
        assertDecimalEquals("2", batch.totalLeaderOrderAmount)
        assertDecimalEquals("0.5", batch.averageTradePrice)
    }

    @Test
    fun `different copy trading ids should not share buffer`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t2", bufferedAt = 1_000L))

        val batches = service.releaseExpired(mapOf(1L to 1, 2L to 1), now = 2_500L)

        assertEquals(2, batches.size)
        assertTrue(batches.all { it.trades.size == 1 })
    }

    @Test
    fun `support validation should reject invalid window when enabled`() {
        val errors = SmallOrderAggregationSupport.validateConfig(enabled = true, windowSeconds = 0)

        assertTrue(errors.isNotEmpty())
    }

    private fun request(
        copyTradingId: Long = 1L,
        tradeId: String,
        bufferedAt: Long
    ) = SmallOrderAggregationRequest(
        copyTradingId = copyTradingId,
        accountId = 10L,
        leaderId = 20L,
        tokenId = "token-1",
        marketId = "market-1",
        outcomeIndex = 0,
        leaderTradeId = tradeId,
        leaderQuantity = BigDecimal("2"),
        leaderOrderAmount = BigDecimal("1"),
        tradePrice = BigDecimal("0.5"),
        outcome = "YES",
        source = "activity-ws",
        bufferedAt = bufferedAt
    )

    private fun assertDecimalEquals(expected: String, actual: BigDecimal) {
        assertTrue(actual.compareTo(BigDecimal(expected)) == 0, "Expected $expected but was $actual")
    }
}
