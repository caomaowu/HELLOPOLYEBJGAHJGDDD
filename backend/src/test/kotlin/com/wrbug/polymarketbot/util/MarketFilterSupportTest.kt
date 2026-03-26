package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarketFilterSupportTest {

    @Test
    fun `deriveMarketSeriesMetadata should resolve hourly interval from series slug`() {
        val metadata = MarketFilterSupport.deriveMarketSeriesMetadata(
            slug = "bitcoin-up-or-down-march-26-2026-2am-et",
            eventSlug = "bitcoin-up-or-down-march-26-2026-2am-et",
            seriesSlug = "btc-up-or-down-hourly"
        )

        assertEquals("btc-up-or-down-hourly", metadata.seriesSlugPrefix)
        assertEquals(3600, metadata.intervalSeconds)
        assertEquals("BTC", metadata.coinSymbol)
        assertEquals("TIMED_SERIES", metadata.marketSourceType)
    }

    @Test
    fun `deriveMarketSeriesMetadata should resolve daily interval from series slug`() {
        val metadata = MarketFilterSupport.deriveMarketSeriesMetadata(
            slug = null,
            eventSlug = null,
            seriesSlug = "eth-up-or-down-daily"
        )

        assertEquals("eth-up-or-down-daily", metadata.seriesSlugPrefix)
        assertEquals(86400, metadata.intervalSeconds)
        assertEquals("ETH", metadata.coinSymbol)
        assertEquals("TIMED_SERIES", metadata.marketSourceType)
    }
}

