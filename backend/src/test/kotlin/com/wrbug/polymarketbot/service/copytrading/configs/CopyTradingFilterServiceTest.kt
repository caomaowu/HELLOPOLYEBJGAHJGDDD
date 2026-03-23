package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.GsonBuilder
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class CopyTradingFilterServiceTest {

    @Test
    fun `category whitelist should pass after normalization`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(
                marketCategoryMode = "WHITELIST",
                marketCategories = """["crypto"]"""
            ),
            tokenId = "token-1",
            market = MarketFilterInput(category = "Cryptocurrency")
        )

        assertEquals(FilterStatus.PASSED, result.status)
        verifyNoInteractions(context.clobApi)
    }

    @Test
    fun `category blacklist should reject matched category`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(
                marketCategoryMode = "BLACKLIST",
                marketCategories = """["sports"]"""
            ),
            tokenId = "token-1",
            market = MarketFilterInput(category = "sports")
        )

        assertEquals(FilterStatus.FAILED_MARKET_CATEGORY, result.status)
        assertTrue(result.reason.contains("黑名单"))
        verifyNoInteractions(context.clobApi)
    }

    @Test
    fun `interval whitelist should pass when interval is matched`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(
                marketIntervalMode = "WHITELIST",
                marketIntervals = "[900]"
            ),
            tokenId = "token-1",
            market = MarketFilterInput(intervalSeconds = 900)
        )

        assertEquals(FilterStatus.PASSED, result.status)
        verifyNoInteractions(context.clobApi)
    }

    @Test
    fun `interval whitelist should reject when interval metadata is missing`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(
                marketIntervalMode = "WHITELIST",
                marketIntervals = "[900]"
            ),
            tokenId = "token-1",
            market = MarketFilterInput()
        )

        assertEquals(FilterStatus.FAILED_MARKET_INTERVAL, result.status)
        assertTrue(result.reason.contains("市场周期缺失"))
        verifyNoInteractions(context.clobApi)
    }

    @Test
    fun `series blacklist should reject matched normalized series`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(
                marketSeriesMode = "BLACKLIST",
                marketSeries = """["btc-updown-15m"]"""
            ),
            tokenId = "token-1",
            market = MarketFilterInput(seriesSlugPrefix = "BTC-UPDOWN-15M")
        )

        assertEquals(FilterStatus.FAILED_MARKET_SERIES, result.status)
        assertTrue(result.reason.contains("黑名单"))
        verifyNoInteractions(context.clobApi)
    }

    @Test
    fun `disabled market filters should ignore missing market metadata`() = runTest {
        val context = createContext()

        val result = context.service.checkFilters(
            copyTrading = config(),
            tokenId = "token-1",
            market = MarketFilterInput()
        )

        assertEquals(FilterStatus.PASSED, result.status)
        verifyNoInteractions(context.clobApi)
    }

    private fun createContext(): TestContext {
        val gson = GsonBuilder().setLenient().create()
        val clobApi = mock(PolymarketClobApi::class.java)
        val jsonUtils = JsonUtils(gson).apply { init() }
        val clobService = PolymarketClobService(
            clobApi = clobApi,
            retrofitFactory = RetrofitFactory(gson)
        )
        return TestContext(
            service = CopyTradingFilterService(clobService, jsonUtils),
            clobApi = clobApi
        )
    }

    private fun config(
        marketCategoryMode: String = "DISABLED",
        marketCategories: String? = null,
        marketIntervalMode: String = "DISABLED",
        marketIntervals: String? = null,
        marketSeriesMode: String = "DISABLED",
        marketSeries: String? = null
    ) = CopyTrading(
        accountId = 1L,
        leaderId = 1L,
        marketCategoryMode = marketCategoryMode,
        marketCategories = marketCategories,
        marketIntervalMode = marketIntervalMode,
        marketIntervals = marketIntervals,
        marketSeriesMode = marketSeriesMode,
        marketSeries = marketSeries
    )

    private data class TestContext(
        val service: CopyTradingFilterService,
        val clobApi: PolymarketClobApi
    )
}
