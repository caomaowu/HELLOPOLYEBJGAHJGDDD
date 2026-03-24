package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.api.ApiKeyResponse
import com.wrbug.polymarketbot.api.CancelOrderResponse
import com.wrbug.polymarketbot.api.CancelOrdersBatchRequest
import com.wrbug.polymarketbot.api.CancelOrdersBatchResponse
import com.wrbug.polymarketbot.api.CreateOrdersBatchRequest
import com.wrbug.polymarketbot.api.FeeRateResponse
import com.wrbug.polymarketbot.api.GetActiveOrdersResponse
import com.wrbug.polymarketbot.api.GetTradesResponse
import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.MarketTradeResponse
import com.wrbug.polymarketbot.api.MidpointResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.OrderResponse
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.PriceResponse
import com.wrbug.polymarketbot.api.SpreadsResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.dto.LeaderMarketScanRequest
import com.wrbug.polymarketbot.dto.LeaderTraderAnalysisRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupItemDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.RetrofitFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import retrofit2.Response

class LeaderDiscoveryServiceTest {

    private lateinit var leaderRepository: LeaderRepository
    private lateinit var marketService: MarketService
    private lateinit var retrofitFactory: RetrofitFactory
    private lateinit var traderCandidatePoolService: TraderCandidatePoolService

    private val evaluator = LeaderDiscoveryEvaluator()

    private lateinit var service: LeaderDiscoveryService

    @BeforeEach
    fun setUp() {
        leaderRepository = mock(LeaderRepository::class.java)
        marketService = mock(MarketService::class.java)
        retrofitFactory = mock(RetrofitFactory::class.java)
        traderCandidatePoolService = mock(TraderCandidatePoolService::class.java)

        service = LeaderDiscoveryService(
            leaderRepository = leaderRepository,
            marketService = marketService,
            retrofitFactory = retrofitFactory,
            evaluator = evaluator,
            traderCandidatePoolService = traderCandidatePoolService
        )

        `when`(leaderRepository.findAll()).thenReturn(emptyList())
        `when`(leaderRepository.findAllByOrderByCreatedAtAsc()).thenReturn(emptyList())
        `when`(traderCandidatePoolService.findBlacklistedAddresses(anyCollection())).thenReturn(emptySet())
        `when`(traderCandidatePoolService.getCandidateLabelSnapshots(anyCollection())).thenReturn(emptyMap())
    }

    @Test
    fun `scan markets should filter existing leaders by default`() {
        val existingLeaderAddress = "0x1111111111111111111111111111111111111111"
        val activeAddress = "0x2222222222222222222222222222222222222222"
        prepareScanMarketsApis(
            markets = listOf(market("market-1", listOf("token-1"))),
            orderbooks = mapOf(
                "token-1" to success(
                    orderbook(
                        bidOwners = listOf(existingLeaderAddress, activeAddress),
                        askOwners = emptyList()
                    )
                )
            ),
            activityResponses = mapOf(
                existingLeaderAddress to successfulActivities(existingLeaderAddress, "market-1", 1),
                activeAddress to successfulActivities(activeAddress, "market-1", 2)
            )
        )
        `when`(
            leaderRepository.findAll()
        ).thenReturn(
            listOf(
                Leader(
                    id = 99L,
                    leaderAddress = existingLeaderAddress,
                    leaderName = "Existing Leader"
                )
            )
        )

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10)).getOrThrow()

        assertEquals(2, result.rawAddressCount)
        assertEquals(2, result.validatedAddressCount)
        assertEquals(1, result.finalCandidateCount)
        assertEquals(listOf(activeAddress), result.list.map { it.address })
        assertFalse(result.list.any { it.existingLeaderId != null })
    }

    @Test
    fun `scan markets should filter blacklisted traders by default`() {
        val blacklistedAddress = "0x3333333333333333333333333333333333333333"
        val activeAddress = "0x4444444444444444444444444444444444444444"
        prepareScanMarketsApis(
            markets = listOf(market("market-2", listOf("token-2"))),
            orderbooks = mapOf(
                "token-2" to success(
                    orderbook(
                        bidOwners = listOf(activeAddress, blacklistedAddress),
                        askOwners = emptyList()
                    )
                )
            ),
            activityResponses = mapOf(
                blacklistedAddress to successfulActivities(blacklistedAddress, "market-2", 1),
                activeAddress to successfulActivities(activeAddress, "market-2", 2)
            )
        )
        `when`(traderCandidatePoolService.findBlacklistedAddresses(anyCollection())).thenReturn(setOf(blacklistedAddress))

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10)).getOrThrow()

        assertEquals(2, result.validatedAddressCount)
        assertEquals(1, result.finalCandidateCount)
        assertEquals(listOf(activeAddress), result.list.map { it.address })
    }

    @Test
    fun `scan markets should return partial results when some orderbooks fail`() {
        val activeAddress = "0x5555555555555555555555555555555555555555"
        prepareScanMarketsApis(
            markets = listOf(
                market("market-3", listOf("token-3")),
                market("market-4", listOf("token-4"))
            ),
            orderbooks = mapOf(
                "token-3" to success(orderbook(bidOwners = listOf(activeAddress), askOwners = emptyList())),
                "token-4" to error(502)
            ),
            activityResponses = mapOf(
                activeAddress to successfulActivities(activeAddress, "market-3", 2)
            )
        )

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10)).getOrThrow()

        assertEquals(2, result.marketCount)
        assertEquals(2, result.tokenCount)
        assertEquals(1, result.rawAddressCount)
        assertEquals(1, result.finalCandidateCount)
        assertEquals(listOf(activeAddress), result.list.map { it.address })
    }

    @Test
    fun `scan markets should continue when orderbook request throws connection reset`() {
        val activeAddress = "0x5656565656565656565656565656565656565656"
        prepareScanMarketsApis(
            markets = listOf(
                market("market-throw-1", listOf("token-ok")),
                market("market-throw-2", listOf("token-throw"))
            ),
            orderbooks = mapOf(
                "token-ok" to success(orderbook(bidOwners = listOf(activeAddress), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                activeAddress to successfulActivities(activeAddress, "market-throw-1", 2)
            ),
            throwingOrderbookTokens = setOf("token-throw")
        )

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10)).getOrThrow()

        assertEquals(2, result.marketCount)
        assertEquals(2, result.tokenCount)
        assertEquals(1, result.rawAddressCount)
        assertEquals(1, result.finalCandidateCount)
        assertEquals(listOf(activeAddress), result.list.map { it.address })
    }

    @Test
    fun `scan markets should fail when gamma markets fetch throws`() {
        prepareScanMarketsApis(
            markets = emptyList(),
            orderbooks = emptyMap(),
            activityResponses = emptyMap(),
            throwingGamma = RuntimeException("Failed to connect to gamma-api.polymarket.com/[2a03::1]:443")
        )

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("获取开放市场失败") == true)
        assertTrue(result.exceptionOrNull()?.message?.contains("Gamma/代理网络链路失败") == true)
    }

    @Test
    fun `scan markets should fail when gamma markets fetch returns http error`() {
        prepareScanMarketsApis(
            markets = emptyList(),
            orderbooks = emptyMap(),
            activityResponses = emptyMap(),
            gammaResponse = error(502)
        )

        val result = service.scanMarkets(LeaderMarketScanRequest(traderLimit = 10))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("获取开放市场失败") == true)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 502") == true)
    }

    @Test
    fun `scan markets should persist candidates to pool when enabled`() {
        val activeAddress = "0x6666666666666666666666666666666666666666"
        prepareScanMarketsApis(
            markets = listOf(market("market-5", listOf("token-5"))),
            orderbooks = mapOf(
                "token-5" to success(orderbook(bidOwners = listOf(activeAddress), askOwners = listOf(activeAddress)))
            ),
            activityResponses = mapOf(
                activeAddress to successfulActivities(activeAddress, "market-5", 3)
            ),
            positionResponses = mapOf(
                activeAddress to success(
                    listOf(
                        PositionResponse(
                            proxyWallet = activeAddress,
                            conditionId = "market-5",
                            currentValue = 120.0,
                            totalBought = 100.0,
                            cashPnl = 10.0,
                            realizedPnl = 5.0,
                            title = "title-market-5",
                            slug = "slug-market-5",
                            outcome = "YES"
                        )
                    )
                )
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                traderLimit = 10,
                persistToPool = true,
                positionLimit = 50
            )
        ).getOrThrow()

        assertTrue(result.persistedToPool)
        assertEquals(1, result.finalCandidateCount)
        verify(traderCandidatePoolService).updateEvaluationSnapshots(anyList())
    }

    @Test
    fun `analyze trader should aggregate pnl from positions`() {
        val address = "0x7777777777777777777777777777777777777777"
        prepareScanMarketsApis(
            markets = emptyList(),
            orderbooks = emptyMap(),
            activityResponses = mapOf(
                address to successfulActivities(address, "market-analysis", 3)
            ),
            positionResponses = mapOf(
                address to success(
                    listOf(
                        PositionResponse(
                            proxyWallet = address,
                            conditionId = "market-analysis",
                            title = "title-market-analysis",
                            outcome = "YES",
                            size = 12.0,
                            avgPrice = 0.45,
                            currentValue = 72.0,
                            cashPnl = 5.0,
                            realizedPnl = 2.0,
                            percentPnl = 0.1,
                            curPrice = 0.52
                        )
                    )
                )
            )
        )

        val result = service.analyzeTrader(
            LeaderTraderAnalysisRequest(
                address = address,
                days = 14
            )
        ).getOrThrow()

        assertEquals(address, result.address)
        assertEquals("7", result.evaluation.estimatedTotalPnl)
        assertEquals("2", result.positions.first().realizedPnl)
        assertEquals("5", result.positions.first().unrealizedPnl)
        assertTrue(result.pnlHighlights.first().contains("7 USDC"))
        verify(traderCandidatePoolService).updateEvaluationSnapshots(anyList())
    }

    @Test
    fun `scan markets aggressive mode should expand related markets and discover new traders`() {
        val orderbookAddress = "0x7777777777777777777777777777777777777777"
        val expandedAddress = "0x8888888888888888888888888888888888888888"
        prepareScanMarketsApis(
            markets = listOf(market("market-root", listOf("token-root"))),
            orderbooks = mapOf(
                "token-root" to success(orderbook(bidOwners = listOf(orderbookAddress), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                orderbookAddress to success(
                    listOf(
                        activity(address = orderbookAddress, marketId = "market-root", index = 1),
                        activity(address = orderbookAddress, marketId = "market-expand", index = 2)
                    )
                ),
                expandedAddress to successfulActivities(expandedAddress, "market-expand", 2)
            ),
            marketTradeResponses = mapOf(
                "market-expand" to success(
                    listOf(
                        marketTrade(address = orderbookAddress, marketId = "market-expand", index = 1),
                        marketTrade(address = expandedAddress, marketId = "market-expand", index = 2),
                        marketTrade(address = expandedAddress, marketId = "market-expand", index = 3)
                    )
                )
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                mode = "AGGRESSIVE",
                traderLimit = 10,
                expansionRounds = 1,
                expansionSeedTraderLimit = 10,
                expansionMarketLimit = 10,
                expansionTradeLimitPerMarket = 20
            )
        ).getOrThrow()

        assertEquals("AGGRESSIVE", result.discoveryMode)
        assertEquals(1, result.expandedMarketCount)
        assertEquals(1, result.expandedTraderCount)
        assertEquals(1, result.sourceBreakdown["orderbook"])
        assertEquals(1, result.sourceBreakdown["market-expansion"])
        assertTrue(result.sources.contains("market-expansion"))
        assertTrue(result.list.any { it.address == expandedAddress })
        assertTrue(result.list.first { it.address == expandedAddress }.sourceType?.contains("market-expansion") == true)
    }

    @Test
    fun `scan markets aggressive mode should include seed addresses`() {
        val seedAddress = "0x9999999999999999999999999999999999999999"
        prepareScanMarketsApis(
            markets = listOf(market("market-seed", listOf("token-seed"))),
            orderbooks = mapOf(
                "token-seed" to success(orderbook(bidOwners = emptyList(), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                seedAddress to successfulActivities(seedAddress, "market-seed", 2)
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                mode = "AGGRESSIVE",
                traderLimit = 10,
                seedAddresses = listOf(seedAddress),
                expansionRounds = 0
            )
        ).getOrThrow()

        assertEquals(1, result.seedAddressCount)
        assertEquals(1, result.sourceBreakdown["seed"])
        assertTrue(result.sources.contains("seed"))
        assertTrue(result.list.any { it.address == seedAddress })
    }

    @Test
    fun `scan markets aggressive mode should ignore inactive seed addresses`() {
        val activeSeedAddress = "0xabababababababababababababababababababab"
        val inactiveSeedAddress = "0xcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
        prepareScanMarketsApis(
            markets = listOf(market("market-seed-filter", listOf("token-seed-filter"))),
            orderbooks = mapOf(
                "token-seed-filter" to success(orderbook(bidOwners = emptyList(), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                activeSeedAddress to successfulActivities(activeSeedAddress, "market-seed-filter", 2)
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                mode = "AGGRESSIVE",
                traderLimit = 10,
                seedAddresses = listOf(activeSeedAddress, inactiveSeedAddress),
                expansionRounds = 0
            )
        ).getOrThrow()

        assertEquals(1, result.seedAddressCount)
        assertTrue(result.list.any { it.address == activeSeedAddress })
        assertFalse(result.list.any { it.address == inactiveSeedAddress })
    }

    @Test
    fun `scan markets aggressive mode should bootstrap from seed addresses and expand market trades without orderbook hits`() {
        val seedAddress = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val expandedAddress = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        prepareScanMarketsApis(
            markets = listOf(market("market-root", listOf("token-root"))),
            orderbooks = mapOf(
                "token-root" to success(orderbook(bidOwners = emptyList(), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                seedAddress to success(
                    listOf(
                        activity(address = seedAddress, marketId = "market-root", index = 1),
                        activity(address = seedAddress, marketId = "market-branch", index = 2)
                    )
                ),
                expandedAddress to successfulActivities(expandedAddress, "market-branch", 2)
            ),
            marketTradeResponses = mapOf(
                "market-branch" to success(
                    listOf(
                        marketTrade(address = seedAddress, marketId = "market-branch", index = 1),
                        marketTrade(address = expandedAddress, marketId = "market-branch", index = 2),
                        marketTrade(address = expandedAddress, marketId = "market-branch", index = 3)
                    )
                )
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                mode = "AGGRESSIVE",
                traderLimit = 10,
                seedAddresses = listOf(seedAddress),
                expansionRounds = 1,
                expansionSeedTraderLimit = 10,
                expansionMarketLimit = 10,
                expansionTradeLimitPerMarket = 20
            )
        ).getOrThrow()

        val seedTrader = result.list.first { it.address == seedAddress }
        val expandedTrader = result.list.first { it.address == expandedAddress }

        assertEquals("AGGRESSIVE", result.discoveryMode)
        assertEquals(0, result.rawAddressCount)
        assertEquals(0, result.validatedAddressCount)
        assertEquals(1, result.seedAddressCount)
        assertEquals(1, result.expandedMarketCount)
        assertEquals(1, result.expandedTraderCount)
        assertEquals(1, result.sourceBreakdown["seed"])
        assertEquals(1, result.sourceBreakdown["market-expansion"])
        assertEquals(2, result.finalCandidateCount)
        assertEquals(expandedAddress, result.list.first().address)
        assertTrue(result.sources.contains("seed"))
        assertTrue(result.sources.contains("market-expansion"))
        assertTrue(seedTrader.sourceType?.contains("seed") == true)
        assertTrue(expandedTrader.sourceType?.contains("market-expansion") == true)
        assertEquals(listOf("market-branch"), expandedTrader.sourceMarketIds)
    }

    @Test
    fun `scan markets aggressive mode should report source breakdown counts`() {
        val orderbookAddress = "0xcdddddddddddddddddddddddddddddddddddddd"
        val seedAddress = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        val expandedAddress = "0xffffffffffffffffffffffffffffffffffffffff"
        prepareScanMarketsApis(
            markets = listOf(market("market-root", listOf("token-root"))),
            orderbooks = mapOf(
                "token-root" to success(orderbook(bidOwners = listOf(orderbookAddress), askOwners = emptyList()))
            ),
            activityResponses = mapOf(
                orderbookAddress to success(
                    listOf(
                        activity(address = orderbookAddress, marketId = "market-root", index = 1),
                        activity(address = orderbookAddress, marketId = "market-expand", index = 2)
                    )
                ),
                seedAddress to successfulActivities(seedAddress, "market-seed", 2),
                expandedAddress to successfulActivities(expandedAddress, "market-expand", 2)
            ),
            marketTradeResponses = mapOf(
                "market-expand" to success(
                    listOf(
                        marketTrade(address = orderbookAddress, marketId = "market-expand", index = 1),
                        marketTrade(address = expandedAddress, marketId = "market-expand", index = 2),
                        marketTrade(address = expandedAddress, marketId = "market-expand", index = 3)
                    )
                )
            )
        )

        val result = service.scanMarkets(
            LeaderMarketScanRequest(
                mode = "AGGRESSIVE",
                traderLimit = 10,
                seedAddresses = listOf(seedAddress),
                expansionRounds = 1,
                expansionSeedTraderLimit = 10,
                expansionMarketLimit = 10,
                expansionTradeLimitPerMarket = 20
            )
        ).getOrThrow()

        val breakdown = result.sourceBreakdown
        assertEquals(1, breakdown["orderbook"])
        assertEquals(1, breakdown["seed"])
        assertEquals(1, breakdown["market-expansion"])
    }

    @Test
    fun `scan markets should handle empty markets`() {
        prepareScanMarketsApis(
            markets = emptyList(),
            orderbooks = emptyMap(),
            activityResponses = emptyMap()
        )

        val result = service.scanMarkets(LeaderMarketScanRequest()).getOrThrow()

        assertEquals(0, result.marketCount)
        assertEquals(0, result.tokenCount)
        assertEquals(0, result.rawAddressCount)
        assertEquals(0, result.validatedAddressCount)
        assertEquals(0, result.finalCandidateCount)
        assertFalse(result.persistedToPool)
        assertTrue(result.list.isEmpty())
        verify(traderCandidatePoolService, never()).updateEvaluationSnapshots(anyList())
    }

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

    private fun prepareScanMarketsApis(
        markets: List<MarketResponse>,
        orderbooks: Map<String, Response<OrderbookResponse>>,
        activityResponses: Map<String, Response<List<UserActivityResponse>>>,
        marketTradeResponses: Map<String, Response<List<MarketTradeResponse>>> = emptyMap(),
        positionResponses: Map<String, Response<List<PositionResponse>>> = emptyMap(),
        throwingOrderbookTokens: Set<String> = emptySet(),
        throwingGamma: RuntimeException? = null,
        gammaResponse: Response<List<MarketResponse>>? = null
    ) {
        `when`(retrofitFactory.createGammaApi()).thenReturn(
            FakeGammaApi(
                marketsResponse = gammaResponse ?: success(markets),
                throwingGamma = throwingGamma
            )
        )
        `when`(retrofitFactory.createIsolatedGammaApi()).thenReturn(
            FakeGammaApi(
                marketsResponse = gammaResponse ?: success(markets),
                throwingGamma = throwingGamma
            )
        )
        `when`(retrofitFactory.createClobApiWithoutAuth()).thenReturn(FakeClobApi(orderbooks, throwingOrderbookTokens))
        `when`(retrofitFactory.createIsolatedClobApiWithoutAuth()).thenReturn(FakeClobApi(orderbooks, throwingOrderbookTokens))
        `when`(
            retrofitFactory.createDataApi()
        ).thenReturn(
            FakeDataApi(
                activityResponses = activityResponses.flatMap { (address, response) ->
                    listOf(
                        (address.lowercase() to 1) to response,
                        (address.lowercase() to 80) to response
                    )
                }.toMap(),
                marketTradeResponses = marketTradeResponses,
                positionResponses = positionResponses.mapKeys { (address, _) -> address.lowercase() to 50 }
            )
        )
        `when`(
            retrofitFactory.createIsolatedDataApi()
        ).thenReturn(
            FakeDataApi(
                activityResponses = activityResponses.flatMap { (address, response) ->
                    listOf(
                        (address.lowercase() to 1) to response,
                        (address.lowercase() to 80) to response
                    )
                }.toMap(),
                marketTradeResponses = marketTradeResponses,
                positionResponses = positionResponses.mapKeys { (address, _) -> address.lowercase() to 50 }
            )
        )
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

    private fun market(conditionId: String, tokenIds: List<String>): MarketResponse {
        val tokenJson = tokenIds.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        return MarketResponse(
            question = "title-$conditionId",
            conditionId = conditionId,
            slug = "slug-$conditionId",
            active = true,
            closed = false,
            archived = false,
            clobTokenIds = tokenJson
        )
    }

    private fun orderbook(
        bidOwners: List<String>,
        askOwners: List<String>
    ) = OrderbookResponse(
        bids = bidOwners.map { OrderbookEntry(price = "0.5", size = "10", owner = it) },
        asks = askOwners.map { OrderbookEntry(price = "0.6", size = "5", owner = it) }
    )

    private fun successfulActivities(
        address: String,
        marketId: String,
        count: Int
    ): Response<List<UserActivityResponse>> {
        val list = (1..count).map { index ->
            UserActivityResponse(
                proxyWallet = address,
                timestamp = 1_700_000_000L + index,
                conditionId = marketId,
                type = "TRADE",
                usdcSize = 25.0 * index,
                transactionHash = "0x${address.takeLast(6)}$index",
                side = if (index % 2 == 0) "SELL" else "BUY",
                title = "title-$marketId",
                slug = "slug-$marketId",
                pseudonym = "Trader-${address.takeLast(4)}"
            )
        }
        return success(list)
    }

    private fun activity(
        address: String,
        marketId: String,
        index: Int
    ) = UserActivityResponse(
        proxyWallet = address,
        timestamp = 1_700_000_000L + index,
        conditionId = marketId,
        type = "TRADE",
        usdcSize = 10.0 * index,
        transactionHash = "0x${address.takeLast(6)}activity$index",
        side = if (index % 2 == 0) "SELL" else "BUY",
        title = "title-$marketId",
        slug = "slug-$marketId",
        pseudonym = "Trader-${address.takeLast(4)}"
    )

    private fun marketTrade(
        address: String,
        marketId: String,
        index: Int
    ) = MarketTradeResponse(
        id = "trade-$marketId-$index",
        market = marketId,
        conditionId = marketId,
        side = if (index % 2 == 0) "SELL" else "BUY",
        usdcSize = 15.0 * index,
        timestamp = 1_700_000_100L + index,
        proxyWallet = address,
        title = "title-$marketId",
        slug = "slug-$marketId"
    )

    private fun <T> success(body: T): Response<T> = Response.success(body)

    private fun <T> error(code: Int): Response<T> = Response.error(
        code,
        "{}".toResponseBody("application/json".toMediaType())
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyCollection(): Collection<T> {
        ArgumentMatchers.anyCollection<T>()
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyList(): List<T> {
        ArgumentMatchers.anyList<T>()
        return emptyList()
    }

    private inner class FakeGammaApi(
        private val marketsResponse: Response<List<MarketResponse>>,
        private val throwingGamma: RuntimeException? = null
    ) : PolymarketGammaApi {
        override suspend fun listMarkets(
            conditionIds: List<String>?,
            clobTokenIds: List<String>?,
            includeTag: Boolean?,
            limit: Int?,
            closed: Boolean?
        ): Response<List<MarketResponse>> {
            throwingGamma?.let { throw it }
            return marketsResponse
        }

        override suspend fun getEventBySlug(slug: String): Response<GammaEventBySlugResponse> {
            throw UnsupportedOperationException("unused in tests")
        }
    }

    private inner class FakeDataApi(
        private val activityResponses: Map<Pair<String, Int>, Response<List<UserActivityResponse>>>,
        private val marketTradeResponses: Map<String, Response<List<MarketTradeResponse>>>,
        private val positionResponses: Map<Pair<String, Int>, Response<List<PositionResponse>>>
    ) : PolymarketDataApi {
        override suspend fun getPositions(
            user: String,
            market: String?,
            eventId: String?,
            sizeThreshold: Double?,
            redeemable: Boolean?,
            mergeable: Boolean?,
            limit: Int?,
            offset: Int?,
            sortBy: String?,
            sortDirection: String?,
            title: String?
        ): Response<List<PositionResponse>> {
            return positionResponses[user.lowercase() to (limit ?: 0)] ?: success(emptyList())
        }

        override suspend fun getTotalValue(
            user: String,
            market: List<String>?
        ): Response<List<ValueResponse>> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getUserActivity(
            user: String,
            limit: Int?,
            offset: Int?,
            market: List<String>?,
            eventId: List<Int>?,
            type: List<String>?,
            start: Long?,
            end: Long?,
            sortBy: String?,
            sortDirection: String?,
            side: String?
        ): Response<List<UserActivityResponse>> {
            return activityResponses[user.lowercase() to (limit ?: 0)] ?: success(emptyList())
        }

        override suspend fun getMarketTrades(
            market: String,
            limit: Int?,
            offset: Int?,
            sortBy: String?,
            sortDirection: String?
        ): Response<List<MarketTradeResponse>> {
            return marketTradeResponses[market] ?: success(emptyList())
        }
    }

    private inner class FakeClobApi(
        private val orderbooks: Map<String, Response<OrderbookResponse>>,
        private val throwingOrderbookTokens: Set<String> = emptySet()
    ) : PolymarketClobApi {
        override suspend fun getOrderbook(tokenId: String?, market: String?): Response<OrderbookResponse> {
            if (tokenId != null && tokenId in throwingOrderbookTokens) {
                throw RuntimeException("Connection reset")
            }
            return orderbooks[tokenId] ?: error(404)
        }

        override suspend fun getPrice(tokenId: String?, side: String?, market: String?): Response<PriceResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getMidpoint(market: String): Response<MidpointResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getSpreads(market: String): Response<SpreadsResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun createOrder(request: NewOrderRequest): Response<NewOrderResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun createOrdersBatch(request: CreateOrdersBatchRequest): Response<List<OrderResponse>> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getOrder(orderId: String): Response<OpenOrder> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getActiveOrders(
            id: String?,
            market: String?,
            asset_id: String?,
            next_cursor: String?
        ): Response<GetActiveOrdersResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun cancelOrder(orderId: String): Response<CancelOrderResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun cancelOrdersBatch(request: CancelOrdersBatchRequest): Response<CancelOrdersBatchResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getTrades(
            id: String?,
            maker_address: String?,
            market: String?,
            asset_id: String?,
            before: String?,
            after: String?,
            next_cursor: String?
        ): Response<GetTradesResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun createApiKey(): Response<ApiKeyResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun deriveApiKey(): Response<ApiKeyResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getFeeRate(tokenId: String): Response<FeeRateResponse> {
            throw UnsupportedOperationException("unused in tests")
        }

        override suspend fun getServerTime(): Response<ResponseBody> {
            throw UnsupportedOperationException("unused in tests")
        }
    }
}
