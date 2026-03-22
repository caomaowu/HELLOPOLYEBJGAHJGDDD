package com.wrbug.polymarketbot.service.copytrading.leaders

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.LeaderMarketTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupItemDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupResponse
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LeaderDiscoveryServiceTest {

    private val leaderRepository = mock(LeaderRepository::class.java)
    private val marketService = mock(MarketService::class.java)
    private val retrofitFactory = RetrofitFactory(Gson())
    private val evaluator = LeaderDiscoveryEvaluator()
    private val traderCandidatePoolService = mock(TraderCandidatePoolService::class.java)

    private val service = LeaderDiscoveryService(
        leaderRepository = leaderRepository,
        marketService = marketService,
        retrofitFactory = retrofitFactory,
        evaluator = evaluator,
        traderCandidatePoolService = traderCandidatePoolService
    )

    @Test
    fun `market lookup should filter blacklisted traders by default`() {
        val marketId = "market-1"
        val addressA = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val addressB = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val request = LeaderMarketTraderLookupRequest(marketIds = listOf(marketId))
        `when`(traderCandidatePoolService.getMarketLookupFromPool(request))
            .thenReturn(
                response(
                    marketId = marketId,
                    traders = listOf(
                        trader(address = addressA, displayName = "A"),
                        trader(address = addressB, displayName = "B")
                    )
                )
            )
        `when`(traderCandidatePoolService.getCandidateLabelSnapshots(listOf(addressA, addressB)))
            .thenReturn(
                mapOf(
                    addressA to snapshot(
                        address = addressA,
                        displayName = "A*",
                        favorite = true,
                        blacklisted = false,
                        manualTags = listOf("alpha")
                    ),
                    addressB to snapshot(
                        address = addressB,
                        displayName = "B*",
                        favorite = false,
                        blacklisted = true,
                        manualTags = listOf("beta")
                    )
                )
            )

        val result = service.lookupMarketTraders(request).getOrThrow()

        assertEquals("candidate-pool", result.source)
        assertEquals(1, result.list.first().traderCount)
        assertEquals(listOf(addressA), result.list.first().list.map { it.address })
        assertEquals("A*", result.list.first().list.first().displayName)
    }

    @Test
    fun `market lookup should respect favorite and tag filters`() {
        val marketId = "market-2"
        val addressA = "0xcccccccccccccccccccccccccccccccccccccccc"
        val addressB = "0xdddddddddddddddddddddddddddddddddddddddd"
        val addressC = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        val request = LeaderMarketTraderLookupRequest(
            marketIds = listOf(marketId),
            favoriteOnly = true,
            includeTags = listOf("alpha"),
            excludeTags = listOf("beta"),
            excludeBlacklistedTraders = false
        )
        `when`(traderCandidatePoolService.getMarketLookupFromPool(request))
            .thenReturn(
                response(
                    marketId = marketId,
                    traders = listOf(
                        trader(address = addressA),
                        trader(address = addressB),
                        trader(address = addressC)
                    )
                )
            )
        `when`(traderCandidatePoolService.getCandidateLabelSnapshots(listOf(addressA, addressB, addressC)))
            .thenReturn(
                mapOf(
                    addressA to snapshot(
                        address = addressA,
                        favorite = true,
                        blacklisted = false,
                        manualTags = listOf("alpha", "core")
                    ),
                    addressB to snapshot(
                        address = addressB,
                        favorite = true,
                        blacklisted = false,
                        manualTags = listOf("beta")
                    ),
                    addressC to snapshot(
                        address = addressC,
                        favorite = false,
                        blacklisted = false,
                        manualTags = listOf("alpha")
                    )
                )
            )

        val result = service.lookupMarketTraders(request).getOrThrow()

        assertEquals(1, result.list.first().traderCount)
        assertEquals(listOf(addressA), result.list.first().list.map { it.address })
        assertTrue(result.list.first().list.first().manualTags.contains("alpha"))
    }

    private fun response(
        marketId: String,
        traders: List<LeaderMarketTraderDto>
    ) = LeaderMarketTraderLookupResponse(
        source = "candidate-pool",
        list = listOf(
            LeaderMarketTraderLookupItemDto(
                marketId = marketId,
                marketTitle = "title-$marketId",
                marketSlug = "slug-$marketId",
                traderCount = traders.size,
                list = traders
            )
        )
    )

    private fun trader(
        address: String,
        displayName: String? = null
    ) = LeaderMarketTraderDto(
        address = address,
        displayName = displayName,
        tradeCount = 3,
        buyCount = 2,
        sellCount = 1,
        totalVolume = "100",
        firstSeenAt = 1_700_000_000_000,
        lastSeenAt = 1_700_000_100_000
    )

    private fun snapshot(
        address: String,
        displayName: String? = null,
        favorite: Boolean = false,
        blacklisted: Boolean = false,
        manualTags: List<String> = emptyList()
    ) = TraderCandidatePoolService.CandidateLabelSnapshot(
        address = address,
        displayName = displayName,
        profileImage = null,
        favorite = favorite,
        blacklisted = blacklisted,
        manualNote = null,
        manualTags = manualTags
    )
}
