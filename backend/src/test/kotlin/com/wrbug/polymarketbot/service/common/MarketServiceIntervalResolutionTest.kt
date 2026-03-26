package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class MarketServiceIntervalResolutionTest {

    @Test
    fun `resolveIntervalSeconds should derive interval from eventStartTime and endDate`() {
        val service = MarketService(
            marketRepository = mock(MarketRepository::class.java),
            retrofitFactory = mock(RetrofitFactory::class.java)
        )

        val method = MarketService::class.java.getDeclaredMethod(
            "resolveIntervalSeconds",
            Int::class.javaObjectType,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaObjectType
        ).apply {
            isAccessible = true
        }

        val resolved = method.invoke(
            service,
            null,
            "2026-03-26T07:00:00Z",
            "2026-03-26T06:00:00Z",
            null,
            null
        ) as Int?

        assertEquals(3600, resolved)
    }
}

