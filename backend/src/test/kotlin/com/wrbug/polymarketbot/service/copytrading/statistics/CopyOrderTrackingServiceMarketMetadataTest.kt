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
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationReleaseReason
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingService
import com.wrbug.polymarketbot.service.copytrading.configs.MarketFilterInput
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.lang.reflect.Method
import java.math.BigDecimal

class CopyOrderTrackingServiceMarketMetadataTest {

    @Test
    fun `resolveMarketFilterInput should stay on payload path when payload metadata is sufficient`() {
        val context = createContext()
        val copyTrading = CopyTrading(
            accountId = 1L,
            leaderId = 1L,
            marketIntervalMode = "WHITELIST",
            marketIntervals = "[900]"
        )
        val payload = context.createPayload(
            marketSlug = "btc-updown-15m-1710000000",
            marketEventSlug = "btc-updown-15m-1710000000"
        )

        val result = context.resolve(copyTrading, payload)

        assertEquals("payload", result.metadataSource)
        assertEquals("btc-updown-15m", result.input.seriesSlugPrefix)
        assertEquals(900, result.input.intervalSeconds)
        assertNull(result.input.title)
        verifyNoInteractions(context.marketService)
    }

    @Test
    fun `resolveMarketFilterInput should fallback to market cache when category metadata is needed`() {
        val context = createContext()
        val copyTrading = CopyTrading(
            accountId = 1L,
            leaderId = 1L,
            marketCategoryMode = "WHITELIST",
            marketCategories = """["crypto"]"""
        )
        val payload = context.createPayload(
            marketSlug = "btc-updown-15m-1710000000",
            marketEventSlug = "btc-updown-15m-1710000000"
        )
        `when`(context.marketService.getMarket("market-1")).thenReturn(
            Market(
                marketId = "market-1",
                title = "BTC Up or Down",
                category = "crypto",
                eventSlug = "btc-updown-15m-1710000000",
                seriesSlugPrefix = "btc-updown-15m",
                intervalSeconds = 900
            )
        )

        val result = context.resolve(copyTrading, payload)

        assertEquals("payload+market-cache", result.metadataSource)
        assertEquals("BTC Up or Down", result.input.title)
        assertEquals("crypto", result.input.category)
        assertEquals("btc-updown-15m", result.input.seriesSlugPrefix)
        assertEquals(900, result.input.intervalSeconds)
    }

    private fun createContext(): TestContext {
        val marketService = mock(MarketService::class.java)
        val service = CopyOrderTrackingService(
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
            telegramNotificationService = mock(TelegramNotificationService::class.java)
        )
        val resolveMethod = CopyOrderTrackingService::class.java.getDeclaredMethod(
            "resolveMarketFilterInput",
            CopyTrading::class.java,
            findNestedClass("BuyExecutionPayload")
        ).apply { isAccessible = true }

        return TestContext(
            service = service,
            marketService = marketService,
            resolveMethod = resolveMethod,
            buyExecutionPayloadClass = findNestedClass("BuyExecutionPayload"),
            resolvedMarketFilterInputClass = findNestedClass("ResolvedMarketFilterInput")
        )
    }

    private fun findNestedClass(simpleName: String): Class<*> {
        return CopyOrderTrackingService::class.java.declaredClasses.first { it.simpleName == simpleName }
    }

    private data class TestContext(
        val service: CopyOrderTrackingService,
        val marketService: MarketService,
        val resolveMethod: Method,
        val buyExecutionPayloadClass: Class<*>,
        val resolvedMarketFilterInputClass: Class<*>
    ) {
        fun createPayload(
            marketSlug: String?,
            marketEventSlug: String?
        ): Any {
            val constructor = buyExecutionPayloadClass.declaredConstructors.first().apply { isAccessible = true }
            return constructor.newInstance(
                "token-1",
                "market-1",
                0,
                marketSlug,
                marketEventSlug,
                null,
                null,
                BigDecimal("0.52"),
                BigDecimal("10"),
                BigDecimal("5.2"),
                "YES",
                "activity-ws",
                "trade-1",
                emptyList<Any>(),
                false,
                null as SmallOrderAggregationReleaseReason?
            )
        }

        fun resolve(copyTrading: CopyTrading, payload: Any): ResolvedResult {
            val resolved = resolveMethod.invoke(service, copyTrading, payload)
            val inputField = resolvedMarketFilterInputClass.getDeclaredField("input").apply { isAccessible = true }
            val metadataSourceField = resolvedMarketFilterInputClass.getDeclaredField("metadataSource").apply { isAccessible = true }
            return ResolvedResult(
                input = inputField.get(resolved) as MarketFilterInput,
                metadataSource = metadataSourceField.get(resolved) as String
            )
        }
    }

    private data class ResolvedResult(
        val input: MarketFilterInput,
        val metadataSource: String
    )
}
