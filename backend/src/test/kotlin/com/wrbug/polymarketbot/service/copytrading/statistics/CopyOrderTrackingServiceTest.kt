package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.Account
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
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.math.BigDecimal

class CopyOrderTrackingServiceTest {

    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val processedTradeRepository = mock(ProcessedTradeRepository::class.java)
    private val filteredOrderRepository = mock(FilteredOrderRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val filterService = mock(CopyTradingFilterService::class.java)
    private val sizingService = mock(CopyTradingSizingService::class.java)
    private val smallOrderAggregationService = mock(SmallOrderAggregationService::class.java)
    private val accountExecutionDiagnosticsService = mock(AccountExecutionDiagnosticsService::class.java)
    private val executionEventService = mock(CopyTradingExecutionEventService::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val orderSigningService = mock(OrderSigningService::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val clobService = mock(PolymarketClobService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val marketService = mock(MarketService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)

    private val service = CopyOrderTrackingService(
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        processedTradeRepository = processedTradeRepository,
        filteredOrderRepository = filteredOrderRepository,
        copyTradingRepository = copyTradingRepository,
        accountRepository = accountRepository,
        filterService = filterService,
        sizingService = sizingService,
        smallOrderAggregationService = smallOrderAggregationService,
        accountExecutionDiagnosticsService = accountExecutionDiagnosticsService,
        executionEventService = executionEventService,
        leaderRepository = leaderRepository,
        orderSigningService = orderSigningService,
        blockchainService = blockchainService,
        clobService = clobService,
        retrofitFactory = retrofitFactory,
        cryptoUtils = cryptoUtils,
        marketService = marketService,
        telegramNotificationService = telegramNotificationService
    )

    @Test
    fun `resolveMarketFilterInput should use payload metadata without market lookup`() {
        val payload = buildBuyExecutionPayload(
            marketSlug = "btc-updown-15m-1712345678",
            marketEventSlug = "btc-updown-15m-1712345678"
        )
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 1L,
            leaderId = 2L
        )

        val resolved = resolveMarketFilterInput(copyTrading, payload)

        assertEquals("payload", resolved.metadataSource)
        assertEquals(
            MarketFilterInput(
                seriesSlugPrefix = "btc-updown-15m",
                intervalSeconds = 900
            ),
            resolved.input
        )
        verifyNoInteractions(marketService)
    }

    @Test
    fun `resolveMarketFilterInput should fall back to market cache when filter needs stored metadata`() {
        val payload = buildBuyExecutionPayload()
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 1L,
            leaderId = 2L,
            marketCategoryMode = "WHITELIST",
            marketCategories = """["crypto"]""",
            marketIntervalMode = "WHITELIST",
            marketIntervals = "[900]"
        )
        `when`(marketService.getMarket("market-1")).thenReturn(
            Market(
                marketId = "market-1",
                title = "BTC 15m",
                slug = "btc-updown-15m-1712345678",
                eventSlug = "btc-updown-15m-1712345678",
                seriesSlugPrefix = "btc-updown-15m",
                intervalSeconds = 900,
                category = "crypto",
                endDate = 1_712_345_678_000
            )
        )

        val resolved = resolveMarketFilterInput(copyTrading, payload)

        assertEquals("payload+market-cache", resolved.metadataSource)
        assertEquals("BTC 15m", resolved.input.title)
        assertEquals("crypto", resolved.input.category)
        assertEquals("btc-updown-15m", resolved.input.seriesSlugPrefix)
        assertEquals(900, resolved.input.intervalSeconds)
        assertEquals(1_712_345_678_000, resolved.input.endDate)
        verify(marketService).getMarket("market-1")
    }

    @Test
    fun `resolveMarketFilterInput should keep payload metadata when market lookup fails`() {
        val payload = buildBuyExecutionPayload()
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 1L,
            leaderId = 2L,
            marketIntervalMode = "WHITELIST",
            marketIntervals = "[900]"
        )
        `when`(marketService.getMarket("market-1")).thenThrow(RuntimeException("cache failed"))

        val resolved = resolveMarketFilterInput(copyTrading, payload)

        assertEquals("payload+market-cache-error", resolved.metadataSource)
        assertNull(resolved.input.title)
        assertNull(resolved.input.category)
        assertNull(resolved.input.seriesSlugPrefix)
        assertNull(resolved.input.intervalSeconds)
        verify(marketService).getMarket("market-1")
    }

    @Test
    fun `resolveBuyCyclePauseReason should return null during run window`() {
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 1L,
            leaderId = 2L,
            enabled = true,
            buyCycleEnabled = true,
            buyCycleRunSeconds = 2700,
            buyCyclePauseSeconds = 1800,
            buyCycleAnchorStartedAt = 1_000_000L
        )

        val reason = resolveBuyCyclePauseReason(copyTrading, 2_200_000L)

        assertNull(reason)
    }

    @Test
    fun `resolveBuyCyclePauseReason should return reason during pause window`() {
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 1L,
            leaderId = 2L,
            enabled = true,
            buyCycleEnabled = true,
            buyCycleRunSeconds = 2700,
            buyCyclePauseSeconds = 1800,
            buyCycleAnchorStartedAt = 1_000_000L
        )

        val reason = resolveBuyCyclePauseReason(copyTrading, 4_000_000L)

        assertNotNull(reason)
    }

    private fun buildBuyExecutionPayload(
        marketSlug: String? = null,
        marketEventSlug: String? = null
    ): Any {
        val payloadClass = CopyOrderTrackingService::class.java.declaredClasses.first { it.simpleName == "BuyExecutionPayload" }
        val constructor = payloadClass.declaredConstructors.first()
        constructor.isAccessible = true
        return constructor.newInstance(
            "token-1",
            "market-1",
            0,
            marketSlug,
            marketEventSlug,
            null,
            null,
            BigDecimal("0.55"),
            BigDecimal("10"),
            BigDecimal("5.5"),
            "Up",
            "activity-ws",
            "trade-1",
            emptyList<Any>(),
            true,
            null
        )
    }

    private fun resolveMarketFilterInput(copyTrading: CopyTrading, payload: Any): ResolvedMarketFilterInputView {
        val payloadClass = CopyOrderTrackingService::class.java.declaredClasses.first { it.simpleName == "BuyExecutionPayload" }
        val method = CopyOrderTrackingService::class.java.getDeclaredMethod(
            "resolveMarketFilterInput",
            CopyTrading::class.java,
            payloadClass
        )
        method.isAccessible = true
        val result = method.invoke(service, copyTrading, payload)
        val inputField = result.javaClass.getDeclaredField("input").apply { isAccessible = true }
        val metadataSourceField = result.javaClass.getDeclaredField("metadataSource").apply { isAccessible = true }
        return ResolvedMarketFilterInputView(
            input = inputField.get(result) as MarketFilterInput,
            metadataSource = metadataSourceField.get(result) as String
        )
    }

    private fun resolveBuyCyclePauseReason(copyTrading: CopyTrading, nowMillis: Long): String? {
        val method = CopyOrderTrackingService::class.java.getDeclaredMethod(
            "resolveBuyCyclePauseReason",
            CopyTrading::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(service, copyTrading, nowMillis) as String?
    }

    private data class ResolvedMarketFilterInputView(
        val input: MarketFilterInput,
        val metadataSource: String
    )
}
