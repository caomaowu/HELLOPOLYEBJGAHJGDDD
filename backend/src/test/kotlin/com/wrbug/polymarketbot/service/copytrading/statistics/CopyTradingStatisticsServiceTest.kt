package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.ExecutionLatencySummary
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.CopyTradingExecutionEvent
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingExecutionEventRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.common.MarketService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.Optional

class CopyTradingStatisticsServiceTest {

    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val copyTradingExecutionEventRepository = mock(CopyTradingExecutionEventRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val marketService = mock(MarketService::class.java)

    private val service = CopyTradingStatisticsService(
        copyTradingRepository = copyTradingRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        copyTradingExecutionEventRepository = copyTradingExecutionEventRepository,
        accountRepository = accountRepository,
        leaderRepository = leaderRepository,
        marketService = marketService
    )

    @Test
    fun `getStatistics should include execution latency summary from recent events`() = runTest {
        `when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(copyTrading()))
        `when`(accountRepository.findById(1L)).thenReturn(Optional.empty())
        `when`(leaderRepository.findById(2L)).thenReturn(Optional.empty())
        `when`(copyOrderTrackingRepository.findByCopyTradingId(1L)).thenReturn(
            listOf(
                CopyOrderTracking(
                    id = 1L,
                    copyTradingId = 1L,
                    accountId = 1L,
                    leaderId = 2L,
                    marketId = "market-1",
                    side = "YES",
                    buyOrderId = "order-1",
                    leaderBuyTradeId = "trade-1",
                    quantity = BigDecimal("10"),
                    price = BigDecimal("0.5"),
                    remainingQuantity = BigDecimal("10"),
                    status = "filled",
                    source = "activity-ws"
                )
            )
        )
        `when`(sellMatchRecordRepository.findByCopyTradingId(1L)).thenReturn(emptyList())
        `when`(sellMatchDetailRepository.findByCopyTradingId(1L)).thenReturn(emptyList())
        `when`(copyTradingExecutionEventRepository.findTop100ByCopyTradingIdOrderByCreatedAtDesc(1L)).thenReturn(
            listOf(
                event(101L, """{"sourceToOrderCompleteMs":250,"marketMetaResolveMs":40,"filterEvaluateMs":5}"""),
                event(102L, """{"sourceToOrderCompleteMs":800,"marketMetaResolveMs":"120","filterEvaluateMs":20}"""),
                event(103L, """{"sourceToOrderCompleteMs":1500,"filterEvaluateMs":35}"""),
                event(104L, """{"marketMetaResolveMs":15}""")
            )
        )

        val response = service.getStatistics(1L).getOrThrow()

        assertEquals(
            ExecutionLatencySummary(
                sampleSize = 4,
                totalLatencyEventCount = 3,
                slowEventCount = 2,
                verySlowEventCount = 1,
                avgTotalLatencyMs = 850,
                maxTotalLatencyMs = 1500,
                maxMarketMetaResolveMs = 120,
                maxFilterEvaluateMs = 35
            ),
            response.executionLatencySummary
        )
    }

    @Test
    fun `getStatistics should return null latency summary when no execution events exist`() = runTest {
        `when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(copyTrading()))
        `when`(accountRepository.findById(1L)).thenReturn(Optional.empty())
        `when`(leaderRepository.findById(2L)).thenReturn(Optional.empty())
        `when`(copyOrderTrackingRepository.findByCopyTradingId(1L)).thenReturn(emptyList())
        `when`(sellMatchRecordRepository.findByCopyTradingId(1L)).thenReturn(emptyList())
        `when`(sellMatchDetailRepository.findByCopyTradingId(1L)).thenReturn(emptyList())
        `when`(copyTradingExecutionEventRepository.findTop100ByCopyTradingIdOrderByCreatedAtDesc(1L)).thenReturn(emptyList())

        val response = service.getStatistics(1L).getOrThrow()

        assertNull(response.executionLatencySummary)
    }

    private fun copyTrading() = CopyTrading(
        id = 1L,
        accountId = 1L,
        leaderId = 2L
    )

    private fun event(id: Long, detailJson: String) = CopyTradingExecutionEvent(
        id = id,
        copyTradingId = 1L,
        accountId = 1L,
        leaderId = 2L,
        stage = "EXECUTION",
        eventType = "ORDER_CREATED",
        status = "success",
        message = "ok",
        detailJson = detailJson
    )
}
