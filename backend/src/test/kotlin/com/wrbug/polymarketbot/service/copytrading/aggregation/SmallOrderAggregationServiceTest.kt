package com.wrbug.polymarketbot.service.copytrading.aggregation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SmallOrderAggregationServiceTest {

    private val service = SmallOrderAggregationService()

    @Test
    fun `buffered trades with same key should aggregate into one batch`() {
        service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(tradeId = "t2", bufferedAt = 2_000L))

        val releases = service.releaseExpired(mapOf(1L to 5), now = 6_500L)

        assertEquals(1, releases.size)
        val batch = releases.first().batch
        assertEquals(SmallOrderAggregationReleaseReason.WINDOW_EXPIRED, releases.first().reason)
        assertEquals(2, batch.trades.size)
        assertDecimalEquals("4", batch.totalLeaderQuantity)
        assertDecimalEquals("2", batch.totalLeaderOrderAmount)
        assertDecimalEquals("0.5", batch.averageTradePrice)
    }

    @Test
    fun `different copy trading ids should not share buffer`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t2", bufferedAt = 1_000L))

        val releases = service.releaseExpired(mapOf(1L to 1, 2L to 1), now = 2_500L)

        assertEquals(2, releases.size)
        assertTrue(releases.all { it.batch.trades.size == 1 })
    }

    @Test
    fun `buy and sell should not share the same aggregation group`() {
        service.bufferTrade(request(tradeId = "buy-1", bufferedAt = 1_000L, side = "BUY"))
        service.bufferTrade(request(tradeId = "sell-1", bufferedAt = 1_000L, side = "SELL"))

        val buyReleases = service.releaseExpired(mapOf(1L to 1), side = "BUY", now = 2_500L)
        val sellReleases = service.releaseExpired(mapOf(1L to 1), side = "SELL", now = 2_500L)

        assertEquals(1, buyReleases.size)
        assertEquals(1, sellReleases.size)
        assertEquals("BUY", buyReleases.first().batch.side)
        assertEquals("SELL", sellReleases.first().batch.side)
    }

    @Test
    fun `duplicate leader trade id should be ignored in buffer`() {
        val first = service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))
        val duplicate = service.bufferTrade(request(tradeId = "t1", bufferedAt = 2_000L))

        assertEquals(SmallOrderAggregationBufferStatus.BUFFERED, first.status)
        assertEquals(SmallOrderAggregationBufferStatus.DUPLICATE_IGNORED, duplicate.status)

        val snapshot = service.getSnapshot()
        assertEquals(1, snapshot.totalGroupCount)
        assertEquals(1, snapshot.totalTradeCount)
        assertEquals(1, snapshot.totalDuplicateIgnoredCount)
        assertEquals(1, snapshot.groups.first().duplicateIgnoredCount)
    }

    @Test
    fun `should support threshold release and inactive cleanup snapshots`() {
        val buffered = service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))

        val release = service.release(
            key = buffered.batch.key,
            reason = SmallOrderAggregationReleaseReason.THRESHOLD_REACHED,
            now = 1_500L
        )

        assertNotNull(release)
        assertEquals(SmallOrderAggregationReleaseReason.THRESHOLD_REACHED, release?.reason)
        assertEquals(0, service.pendingGroupCount())

        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t2", bufferedAt = 2_000L))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t3", bufferedAt = 2_000L))
        val cleared = service.clearInactive(setOf(1L))

        assertEquals(1, cleared)
        val snapshot = service.getSnapshot()
        assertEquals(1, snapshot.totalGroupCount)
        assertEquals(1L, snapshot.groups.first().copyTradingId)
    }

    @Test
    fun `clear should only remove groups for the target copy trading id`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t2", bufferedAt = 1_100L, tokenId = "token-2"))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t3", bufferedAt = 1_200L))

        val cleared = service.clear(1L)

        assertEquals(2, cleared)
        assertEquals(1, service.pendingGroupCount())
        val remaining = service.getSnapshot(2L)
        assertEquals(1, remaining.totalGroupCount)
        assertEquals(1, remaining.totalTradeCount)
        assertEquals(2L, remaining.groups.first().copyTradingId)
        assertEquals(1, service.getSnapshot().totalGroupCount)
    }

    @Test
    fun `clear should be idempotent for unknown copy trading id`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))

        val cleared = service.clear(999L)

        assertEquals(0, cleared)
        val snapshot = service.getSnapshot()
        assertEquals(1, snapshot.totalGroupCount)
        assertEquals(1, snapshot.totalTradeCount)
    }

    @Test
    fun `snapshot should support copy trading filtering and preserve totals`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_100L))
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t2", bufferedAt = 1_200L, tokenId = "token-2"))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t3", bufferedAt = 1_300L))

        val filtered = service.getSnapshot(1L)
        val all = service.getSnapshot()

        assertEquals(2, filtered.totalGroupCount)
        assertEquals(2, filtered.totalTradeCount)
        assertEquals(1, filtered.totalDuplicateIgnoredCount)
        assertTrue(filtered.groups.all { it.copyTradingId == 1L })
        assertEquals(filtered.totalGroupCount + service.getSnapshot(2L).totalGroupCount, all.totalGroupCount)
        assertEquals(filtered.totalTradeCount + service.getSnapshot(2L).totalTradeCount, all.totalTradeCount)
        assertEquals(
            filtered.totalDuplicateIgnoredCount + service.getSnapshot(2L).totalDuplicateIgnoredCount,
            all.totalDuplicateIgnoredCount
        )
    }

    @Test
    fun `release expired should honor boundary at expiresAt`() {
        service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))

        assertTrue(service.releaseExpired(mapOf(1L to 5), now = 5_999L).isEmpty())

        val releases = service.releaseExpired(mapOf(1L to 5), now = 6_000L)
        assertEquals(1, releases.size)
        assertEquals(SmallOrderAggregationReleaseReason.WINDOW_EXPIRED, releases.first().reason)
    }

    @Test
    fun `release expired should use first buffered time instead of last buffered time`() {
        service.bufferTrade(request(tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(tradeId = "t2", bufferedAt = 4_000L))

        val releases = service.releaseExpired(mapOf(1L to 5), now = 6_000L)

        assertEquals(1, releases.size)
        assertEquals(2, releases.first().batch.trades.size)
        assertEquals(0, service.pendingGroupCount())
    }

    @Test
    fun `release expired should fallback to default window when config is missing`() {
        service.bufferTrade(request(copyTradingId = 7L, tradeId = "t1", bufferedAt = 1_000L))

        assertTrue(service.releaseExpired(emptyMap(), now = 300_999L).isEmpty())

        val releases = service.releaseExpired(emptyMap(), now = 301_000L)
        assertEquals(1, releases.size)
        assertEquals(7L, releases.first().batch.copyTradingId)
    }

    @Test
    fun `clear all should reset snapshot and allow buffering again`() {
        service.bufferTrade(request(copyTradingId = 1L, tradeId = "t1", bufferedAt = 1_000L))
        service.bufferTrade(request(copyTradingId = 2L, tradeId = "t2", bufferedAt = 1_100L))

        val cleared = service.clear()

        assertEquals(2, cleared)
        assertEquals(0, service.pendingGroupCount())
        val snapshot = service.getSnapshot()
        assertEquals(0, snapshot.totalGroupCount)
        assertEquals(0, snapshot.totalTradeCount)
        assertEquals(0, snapshot.totalDuplicateIgnoredCount)
        assertTrue(snapshot.groups.isEmpty())

        service.bufferTrade(request(copyTradingId = 3L, tradeId = "t3", bufferedAt = 2_000L))
        val refilled = service.getSnapshot()
        assertEquals(1, refilled.totalGroupCount)
        assertEquals(1, refilled.totalTradeCount)
        assertEquals(3L, refilled.groups.first().copyTradingId)
    }

    @Test
    fun `support validation should reject invalid window when enabled`() {
        val errors = SmallOrderAggregationSupport.validateConfig(enabled = true, windowSeconds = 0)

        assertTrue(errors.isNotEmpty())
    }

    private fun request(
        copyTradingId: Long = 1L,
        tradeId: String,
        bufferedAt: Long,
        side: String = "BUY",
        tokenId: String = "token-1"
    ) = SmallOrderAggregationRequest(
        copyTradingId = copyTradingId,
        accountId = 10L,
        leaderId = 20L,
        side = side,
        tokenId = tokenId,
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
