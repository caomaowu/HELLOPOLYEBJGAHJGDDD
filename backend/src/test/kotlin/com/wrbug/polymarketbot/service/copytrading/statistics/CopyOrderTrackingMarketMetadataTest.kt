package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.accounts.AccountExecutionDiagnosticsService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingService
import com.wrbug.polymarketbot.service.copytrading.configs.MarketFilterInput
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.math.BigDecimal
import java.lang.reflect.Method

class CopyOrderTrackingMarketMetadataTest {

    @Test
    fun `resolveMarketFilterInput should use payload metadata when interval can be derived from event slug`() {
        val context = createContext()
        val payload = createPayload(slug = null, eventSlug = "btc-updown-15m-1710000000")

        val resolved = resolveMarketFilterInput(
            service = context.service,
            copyTrading = copyTrading(marketIntervalMode = "WHITELIST", marketIntervals = "[900]"),
            payload = payload
        )

        assertEquals("payload", resolved.metadataSource)
        assertEquals("btc-updown-15m", resolved.input.seriesSlugPrefix)
        assertEquals(900, resolved.input.intervalSeconds)
        verifyNoInteractions(context.marketService)
    }

    @Test
    fun `resolveMarketFilterInput should fetch market cache when category filter requires stored metadata`() {
        val context = createContext()
        val payload = createPayload(slug = "btc-updown-15m-1710000000", eventSlug = "btc-updown-15m-1710000000")
        `when`(context.marketService.getMarket("market-1")).thenReturn(
            Market(
                marketId = "market-1",
                title = "BTC 15m",
                category = "crypto",
                endDate = 1_710_000_000_000,
                seriesSlugPrefix = "btc-updown-15m",
                intervalSeconds = 900
            )
        )

        val resolved = resolveMarketFilterInput(
            service = context.service,
            copyTrading = copyTrading(marketCategoryMode = "WHITELIST", marketCategories = """["crypto"]"""),
            payload = payload
        )

        assertEquals("payload+market-cache", resolved.metadataSource)
        assertEquals("crypto", resolved.input.category)
        assertEquals("BTC 15m", resolved.input.title)
        assertEquals(1_710_000_000_000, resolved.input.endDate)
        assertEquals("btc-updown-15m", resolved.input.seriesSlugPrefix)
        assertEquals(900, resolved.input.intervalSeconds)
        verify(context.marketService).getMarket("market-1")
    }

    @Test
    fun `resolveMarketFilterInput should report cache miss when stored metadata is absent`() {
        val context = createContext()
        val payload = createPayload(slug = "btc-updown-15m-1710000000")
        `when`(context.marketService.getMarket("market-1")).thenReturn(null)

        val resolved = resolveMarketFilterInput(
            service = context.service,
            copyTrading = copyTrading(marketCategoryMode = "WHITELIST", marketCategories = """["crypto"]"""),
            payload = payload
        )

        assertEquals("payload+market-cache-miss", resolved.metadataSource)
        assertNull(resolved.input.category)
        assertEquals("btc-updown-15m", resolved.input.seriesSlugPrefix)
        assertEquals(900, resolved.input.intervalSeconds)
        verify(context.marketService).getMarket("market-1")
    }

    @Test
    fun `resolveMarketFilterInput should fall back to payload when market cache lookup fails`() {
        val context = createContext()
        val payload = createPayload(slug = "btc-updown-15m-1710000000")
        `when`(context.marketService.getMarket("market-1")).thenThrow(RuntimeException("boom"))

        val resolved = resolveMarketFilterInput(
            service = context.service,
            copyTrading = copyTrading(marketCategoryMode = "WHITELIST", marketCategories = """["crypto"]"""),
            payload = payload
        )

        assertEquals("payload+market-cache-error", resolved.metadataSource)
        assertNull(resolved.input.category)
        assertEquals("btc-updown-15m", resolved.input.seriesSlugPrefix)
        assertEquals(900, resolved.input.intervalSeconds)
        verify(context.marketService).getMarket("market-1")
    }

    private fun resolveMarketFilterInput(
        service: CopyOrderTrackingService,
        copyTrading: CopyTrading,
        payload: Any
    ): ResolvedInput {
        val method = declaredMethod(
            CopyOrderTrackingService::class.java,
            "resolveMarketFilterInput",
            parameterCount = 2
        )
        val resolved = method.invoke(service, copyTrading, payload)
            ?: error("resolveMarketFilterInput returned null")
        val input = getter(resolved, "getInput") as MarketFilterInput
        val metadataSource = getter(resolved, "getMetadataSource") as String
        return ResolvedInput(input = input, metadataSource = metadataSource)
    }

    private fun declaredMethod(type: Class<*>, name: String, parameterCount: Int): Method {
        return type.declaredMethods.first {
            it.name == name && it.parameterCount == parameterCount
        }.apply {
            isAccessible = true
        }
    }

    private fun getter(target: Any, name: String): Any? {
        return target.javaClass.methods.first { it.name == name && it.parameterCount == 0 }.invoke(target)
    }

    private fun createPayload(
        slug: String? = "btc-updown-15m-1710000000",
        eventSlug: String? = "btc-updown-15m-1710000000"
    ): Any {
        val payloadClass = Class.forName(
            "com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService\$BuyExecutionPayload"
        )
        val constructor = payloadClass.declaredConstructors.first().apply {
            isAccessible = true
        }
        return constructor.newInstance(
            "token-1",
            "market-1",
            0,
            slug,
            eventSlug,
            null,
            null,
            BigDecimal("0.51"),
            BigDecimal("10"),
            BigDecimal("5.10"),
            "Yes",
            "activity-ws",
            "trade-1",
            emptyList<Any>(),
            false,
            null
        )
    }

    private fun copyTrading(
        keywordFilterMode: String = "DISABLED",
        marketCategoryMode: String = "DISABLED",
        marketCategories: String? = null,
        marketIntervalMode: String = "DISABLED",
        marketIntervals: String? = null,
        marketSeriesMode: String = "DISABLED",
        marketSeries: String? = null,
        maxMarketEndDate: Long? = null
    ) = CopyTrading(
        accountId = 1L,
        leaderId = 1L,
        keywordFilterMode = keywordFilterMode,
        marketCategoryMode = marketCategoryMode,
        marketCategories = marketCategories,
        marketIntervalMode = marketIntervalMode,
        marketIntervals = marketIntervals,
        marketSeriesMode = marketSeriesMode,
        marketSeries = marketSeries,
        maxMarketEndDate = maxMarketEndDate
    )

    private fun createContext(): TestContext {
        val marketService = mock(MarketService::class.java)
        return TestContext(
            service = CopyOrderTrackingService(
                copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java),
                sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java),
                sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java),
                processedTradeRepository = mock(ProcessedTradeRepository::class.java),
                filteredOrderRepository = mock(FilteredOrderRepository::class.java),
                copyTradingRepository = mock(CopyTradingRepository::class.java),
                accountRepository = mock(AccountRepository::class.java),
                filterService = mock(CopyTradingFilterService::class.java),
                sizingService = mock(CopyTradingSizingService::class.java),
                smallOrderAggregationService = mock(SmallOrderAggregationService::class.java),
                accountExecutionDiagnosticsService = mock(AccountExecutionDiagnosticsService::class.java),
                executionEventService = mock(CopyTradingExecutionEventService::class.java),
                leaderRepository = mock(LeaderRepository::class.java),
                orderSigningService = mock(OrderSigningService::class.java),
                blockchainService = mock(BlockchainService::class.java),
                clobService = mock(PolymarketClobService::class.java),
                retrofitFactory = mock(RetrofitFactory::class.java),
                cryptoUtils = mock(CryptoUtils::class.java),
                marketService = marketService,
                telegramNotificationService = null
            ),
            marketService = marketService
        )
    }

    private data class TestContext(
        val service: CopyOrderTrackingService,
        val marketService: MarketService
    )

    private data class ResolvedInput(
        val input: MarketFilterInput,
        val metadataSource: String
    )
}
