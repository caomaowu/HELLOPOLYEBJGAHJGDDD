package com.wrbug.polymarketbot.controller.copytrading.leaders

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.LeaderDiscoveryMarketDto
import com.wrbug.polymarketbot.dto.LeaderDiscoveredTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketScanRequest
import com.wrbug.polymarketbot.dto.LeaderMarketScanResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.leaders.LeaderDiscoveryService
import com.wrbug.polymarketbot.service.copytrading.leaders.LeaderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.MessageSource

class LeaderControllerTest {

    private val leaderService = mock(LeaderService::class.java)
    private val leaderDiscoveryService = mock(LeaderDiscoveryService::class.java)
    private val messageSource = mock(MessageSource::class.java)
    private val controller = LeaderController(
        leaderService = leaderService,
        leaderDiscoveryService = leaderDiscoveryService,
        messageSource = messageSource
    )

    @Test
    fun `scanMarkets should return success response when service succeeds`() {
        val request = LeaderMarketScanRequest(
            marketLimit = 20,
            traderLimit = 5
        )
        val responsePayload = LeaderMarketScanResponse(
            estimated = true,
            source = "gamma+clob+data-api",
            marketCount = 12,
            tokenCount = 24,
            rawAddressCount = 100,
            validatedAddressCount = 30,
            finalCandidateCount = 5,
            persistedToPool = true,
            durationMs = 1234,
            list = listOf(
                LeaderDiscoveredTraderDto(
                    address = "0x1111111111111111111111111111111111111111",
                    displayName = "Trader A",
                    recentTradeCount = 10,
                    recentBuyCount = 6,
                    recentSellCount = 4,
                    recentVolume = "1200",
                    distinctMarkets = 3,
                    sourceLeaderIds = emptyList(),
                    sampleMarkets = listOf(
                        LeaderDiscoveryMarketDto(
                            marketId = "market-1",
                            title = "title-1",
                            slug = "slug-1",
                            category = "crypto",
                            tradeCount = 4,
                            totalVolume = "400",
                            lastSeenAt = 1_700_000_000_000
                        )
                    ),
                    firstSeenAt = 1_700_000_000_000,
                    lastSeenAt = 1_700_000_100_000
                )
            )
        )
        `when`(leaderDiscoveryService.scanMarkets(request)).thenReturn(Result.success(responsePayload))

        val response = controller.scanMarkets(request)

        assertEquals(0, response.body?.code)
        assertEquals(12, response.body?.data?.marketCount)
        assertEquals(5, response.body?.data?.finalCandidateCount)
        assertEquals(true, response.body?.data?.persistedToPool)
        assertEquals(1, response.body?.data?.list?.size)
        verify(leaderDiscoveryService).scanMarkets(request)
    }

    @Test
    fun `scanMarkets should map IllegalArgumentException to PARAM_ERROR`() {
        val request = LeaderMarketScanRequest(marketLimit = 0)
        `when`(leaderDiscoveryService.scanMarkets(request))
            .thenReturn(Result.failure(IllegalArgumentException("invalid request")))

        val response = controller.scanMarkets(request)

        assertError(response.body, ErrorCode.PARAM_ERROR.code, "invalid request")
        verify(leaderDiscoveryService).scanMarkets(request)
    }

    @Test
    fun `scanMarkets should map unexpected exception to SERVER_ERROR`() {
        val request = LeaderMarketScanRequest(marketLimit = 10)
        `when`(leaderDiscoveryService.scanMarkets(request))
            .thenReturn(Result.failure(RuntimeException("downstream failed")))

        val response = controller.scanMarkets(request)

        assertError(response.body, ErrorCode.SERVER_ERROR.code, "downstream failed")
        verify(leaderDiscoveryService).scanMarkets(request)
    }

    @Test
    fun `scanMarkets should map thrown exception to SERVER_ERROR`() {
        val request = LeaderMarketScanRequest(marketLimit = 10)
        `when`(leaderDiscoveryService.scanMarkets(request))
            .thenThrow(RuntimeException("service crashed"))

        val response = controller.scanMarkets(request)

        assertError(response.body, ErrorCode.SERVER_ERROR.code, "service crashed")
        verify(leaderDiscoveryService).scanMarkets(request)
    }

    private fun assertError(body: ApiResponse<LeaderMarketScanResponse>?, code: Int, message: String) {
        assertNotNull(body)
        assertEquals(code, body?.code)
        assertEquals(message, body?.msg)
        assertEquals(null, body?.data)
    }
}
