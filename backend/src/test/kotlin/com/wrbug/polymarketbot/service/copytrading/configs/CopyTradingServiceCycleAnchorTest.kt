package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.Gson
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.accounts.AccountExecutionDiagnosticsService
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationService
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitorService
import com.wrbug.polymarketbot.util.JsonUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class CopyTradingServiceCycleAnchorTest {

    private val service = CopyTradingService(
        copyTradingRepository = mock(CopyTradingRepository::class.java),
        accountRepository = mock(AccountRepository::class.java),
        templateRepository = mock(CopyTradingTemplateRepository::class.java),
        leaderRepository = mock(LeaderRepository::class.java),
        monitorService = mock(CopyTradingMonitorService::class.java),
        accountExecutionDiagnosticsService = mock(AccountExecutionDiagnosticsService::class.java),
        smallOrderAggregationService = mock(SmallOrderAggregationService::class.java),
        repeatAddStateService = mock(CopyTradingRepeatAddStateService::class.java),
        jsonUtils = mock(JsonUtils::class.java),
        gson = Gson()
    )

    @Test
    fun `resolveBuyCycleAnchorStartedAt should reset when enabling copy trading`() {
        val before = System.currentTimeMillis()
        val anchor = resolveBuyCycleAnchorStartedAt(
            previousEnabled = false,
            nextEnabled = true,
            previousBuyCycleEnabled = true,
            nextBuyCycleEnabled = true,
            previousAnchorStartedAt = 12345L
        )
        val after = System.currentTimeMillis()

        assertNotNull(anchor)
        assertTrue(anchor!! in before..after)
    }

    @Test
    fun `resolveBuyCycleAnchorStartedAt should clear when disabling copy trading`() {
        val anchor = resolveBuyCycleAnchorStartedAt(
            previousEnabled = true,
            nextEnabled = false,
            previousBuyCycleEnabled = true,
            nextBuyCycleEnabled = true,
            previousAnchorStartedAt = 12345L
        )

        assertNull(anchor)
    }

    @Test
    fun `resolveBuyCycleAnchorStartedAt should keep existing anchor when states unchanged`() {
        val anchor = resolveBuyCycleAnchorStartedAt(
            previousEnabled = true,
            nextEnabled = true,
            previousBuyCycleEnabled = true,
            nextBuyCycleEnabled = true,
            previousAnchorStartedAt = 12345L
        )

        assertEquals(12345L, anchor)
    }

    @Test
    fun `resolveBuyCycleAnchorStartedAt should reset when cycle toggles on`() {
        val before = System.currentTimeMillis()
        val anchor = resolveBuyCycleAnchorStartedAt(
            previousEnabled = true,
            nextEnabled = true,
            previousBuyCycleEnabled = false,
            nextBuyCycleEnabled = true,
            previousAnchorStartedAt = null
        )
        val after = System.currentTimeMillis()

        assertNotNull(anchor)
        assertTrue(anchor!! in before..after)
    }

    private fun resolveBuyCycleAnchorStartedAt(
        previousEnabled: Boolean,
        nextEnabled: Boolean,
        previousBuyCycleEnabled: Boolean,
        nextBuyCycleEnabled: Boolean,
        previousAnchorStartedAt: Long?
    ): Long? {
        val method = CopyTradingService::class.java.getDeclaredMethod(
            "resolveBuyCycleAnchorStartedAt",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaObjectType
        )
        method.isAccessible = true
        return method.invoke(
            service,
            previousEnabled,
            nextEnabled,
            previousBuyCycleEnabled,
            nextBuyCycleEnabled,
            previousAnchorStartedAt
        ) as Long?
    }
}

