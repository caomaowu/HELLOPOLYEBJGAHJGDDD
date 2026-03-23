package com.wrbug.polymarketbot.service.copytrading.observability

import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventListRequest
import com.wrbug.polymarketbot.entity.CopyTradingExecutionEvent
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingExecutionEventRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.util.Optional

class CopyTradingExecutionEventServiceTest {

    private val executionEventRepository = mock(CopyTradingExecutionEventRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val marketService = mock(MarketService::class.java)
    private val service = CopyTradingExecutionEventService(
        executionEventRepository = executionEventRepository,
        accountRepository = accountRepository,
        leaderRepository = leaderRepository,
        marketService = marketService
    )

    @Test
    fun `getEvents should use paged repository search when latency filters are absent`() {
        val pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        val event = event(id = 101L, createdAt = 1_700_000_100_000)
        `when`(
            executionEventRepository.search(
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                pageable
            )
        ).thenReturn(PageImpl(listOf(event), pageable, 1))
        stubCommonLookups()

        val response = service.getEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                page = 1,
                limit = 20
            )
        )

        assertEquals(1L, response.total)
        assertEquals(listOf(101L), response.list.map { it.id })
        verify(executionEventRepository).search(
            1L,
            null,
            null,
            null,
            null,
            null,
            null,
            pageable
        )
        verifyNoInteractions(marketService)
    }

    @Test
    fun `getEvents should filter and sort by selected latency metric`() {
        val lowLatency = event(
            id = 201L,
            createdAt = 1_700_000_100_000,
            detailJson = """{"sourceToOrderCompleteMs":150}"""
        )
        val highLatencyOlder = event(
            id = 202L,
            createdAt = 1_700_000_200_000,
            detailJson = """{"sourceToOrderCompleteMs":300}"""
        )
        val highLatencyNewer = event(
            id = 203L,
            createdAt = 1_700_000_300_000,
            detailJson = """{"sourceToOrderCompleteMs":"300"}"""
        )
        val missingMetric = event(
            id = 204L,
            createdAt = 1_700_000_400_000,
            detailJson = """{"filterEvaluateMs":40}"""
        )
        `when`(
            executionEventRepository.searchAll(
                1L,
                null,
                null,
                null,
                null,
                null,
                null
            )
        ).thenReturn(listOf(lowLatency, missingMetric, highLatencyOlder, highLatencyNewer))
        stubCommonLookups()

        val response = service.getEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                latencyMetric = "sourceToOrderCompleteMs",
                minLatencyMs = 200,
                page = 1,
                limit = 10
            )
        )

        assertEquals(2L, response.total)
        assertEquals(listOf(203L, 202L), response.list.map { it.id })
        verify(executionEventRepository).searchAll(
            1L,
            null,
            null,
            null,
            null,
            null,
            null
        )
        verifyNoInteractions(marketService)
    }

    @Test
    fun `getEvents should paginate after latency filtering`() {
        val event500 = event(
            id = 301L,
            createdAt = 1_700_000_100_000,
            detailJson = """{"sourceToOrderCompleteMs":500}"""
        )
        val event400 = event(
            id = 302L,
            createdAt = 1_700_000_200_000,
            detailJson = """{"sourceToOrderCompleteMs":400}"""
        )
        val event300 = event(
            id = 303L,
            createdAt = 1_700_000_300_000,
            detailJson = """{"sourceToOrderCompleteMs":300}"""
        )
        `when`(
            executionEventRepository.searchAll(
                1L,
                null,
                null,
                null,
                null,
                null,
                null
            )
        ).thenReturn(listOf(event300, event500, event400))
        stubCommonLookups()

        val response = service.getEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                latencyMetric = "sourceToOrderCompleteMs",
                page = 2,
                limit = 1
            )
        )

        assertEquals(3L, response.total)
        assertEquals(2, response.page)
        assertEquals(1, response.limit)
        assertEquals(listOf(302L), response.list.map { it.id })
        verify(executionEventRepository).searchAll(
            1L,
            null,
            null,
            null,
            null,
            null,
            null
        )
        verifyNoInteractions(marketService)
    }

    private fun stubCommonLookups() {
        `when`(accountRepository.findById(1L)).thenReturn(Optional.empty())
        `when`(leaderRepository.findById(1L)).thenReturn(Optional.empty())
    }

    private fun event(
        id: Long,
        createdAt: Long,
        detailJson: String? = null
    ) = CopyTradingExecutionEvent(
        id = id,
        copyTradingId = 1L,
        accountId = 1L,
        leaderId = 1L,
        stage = "EXECUTION",
        eventType = "ORDER_CREATED",
        status = "success",
        message = "ok",
        detailJson = detailJson,
        createdAt = createdAt
    )
}
