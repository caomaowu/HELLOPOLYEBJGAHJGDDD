package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.MultiplierTierDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyTradingSizingServiceTest {

    private val service = CopyTradingSizingService()

    @Test
    fun `ratio mode should keep legacy behavior when no new fields are provided`() {
        val result = service.calculate(
            config = config(),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionValue = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("100", result.baseAmount)
        assertDecimalEquals("200.00000000", result.finalQuantity)
        assertNull(result.appliedAdaptiveRatio)
        assertDecimalEquals("1", result.appliedMultiplier)
    }

    @Test
    fun `fixed mode should use fixed amount`() {
        val result = service.calculate(
            config = config(
                copyMode = CopyTradingSizingSupport.COPY_MODE_FIXED,
                fixedAmount = bd("25")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionValue = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("25", result.baseAmount)
        assertDecimalEquals("50.00000000", result.finalQuantity)
    }

    @Test
    fun `adaptive mode should interpolate for small threshold and large orders`() {
        val config = config(
            copyMode = CopyTradingSizingSupport.COPY_MODE_ADAPTIVE,
            adaptiveMinRatio = bd("0.5"),
            adaptiveMaxRatio = bd("1.5"),
            adaptiveThreshold = bd("100")
        )

        val small = service.calculate(config, bd("0"), bd("0.5"), BigDecimal.ZERO, BigDecimal.ZERO)
        val threshold = service.calculate(config, bd("100"), bd("0.5"), BigDecimal.ZERO, BigDecimal.ZERO)
        val large = service.calculate(config, bd("200"), bd("0.5"), BigDecimal.ZERO, BigDecimal.ZERO)

        assertDecimalEquals("1.50000000", small.appliedAdaptiveRatio)
        assertDecimalEquals("1.00000000", threshold.appliedAdaptiveRatio)
        assertDecimalEquals("0.50000000", large.appliedAdaptiveRatio)
    }

    @Test
    fun `single multiplier should amplify amount after base sizing`() {
        val result = service.calculate(
            config = config(
                multiplierMode = CopyTradingSizingSupport.MULTIPLIER_MODE_SINGLE,
                tradeMultiplier = bd("1.5")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionValue = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO
        )

        assertDecimalEquals("100", result.baseAmount)
        assertDecimalEquals("150", result.multipliedAmount)
        assertDecimalEquals("1.5", result.appliedMultiplier)
    }

    @Test
    fun `tiered multiplier should match leader order amount range`() {
        val result = service.calculate(
            config = config(
                multiplierMode = CopyTradingSizingSupport.MULTIPLIER_MODE_TIERED,
                tieredMultipliers = listOf(
                    SizingMultiplierTier(min = bd("0"), max = bd("100"), multiplier = bd("0.5")),
                    SizingMultiplierTier(min = bd("100"), max = null, multiplier = bd("1.2"))
                )
            ),
            leaderOrderAmount = bd("120"),
            tradePrice = bd("0.5"),
            currentPositionValue = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO
        )

        assertDecimalEquals("1.2", result.appliedMultiplier)
        assertDecimalEquals("120", result.baseAmount)
        assertDecimalEquals("144", result.finalAmount)
    }

    @Test
    fun `sizing should cap by max order position and daily volume`() {
        val result = service.calculate(
            config = config(
                maxOrderSize = bd("90"),
                maxPositionValue = bd("80"),
                maxDailyVolume = bd("70")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionValue = bd("20"),
            currentDailyVolume = bd("10")
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("60", result.finalAmount)
        assertDecimalEquals("120.00000000", result.finalQuantity)
    }

    @Test
    fun `sizing should reject when capped amount falls below min order size`() {
        val result = service.calculate(
            config = config(
                minOrderSize = bd("10"),
                maxPositionValue = bd("15")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionValue = bd("10"),
            currentDailyVolume = BigDecimal.ZERO
        )

        assertEquals(SizingStatus.REJECTED, result.status)
        assertDecimalEquals("5", result.finalAmount)
        assertDecimalEquals("100", result.baseAmount)
    }

    @Test
    fun `tiered multipliers should be serialized in ascending min order`() {
        val json = CopyTradingSizingSupport.serializeTieredMultipliers(
            listOf(
                MultiplierTierDto(min = "100", max = null, multiplier = "1.2"),
                MultiplierTierDto(min = "0", max = "100", multiplier = "0.8")
            )
        )

        val tiers = CopyTradingSizingSupport.toTierDtoList(json)

        assertEquals(listOf("0", "100"), tiers?.map { it.min })
    }

    private fun config(
        copyMode: String = CopyTradingSizingSupport.COPY_MODE_RATIO,
        copyRatio: BigDecimal = BigDecimal.ONE,
        fixedAmount: BigDecimal? = null,
        adaptiveMinRatio: BigDecimal? = null,
        adaptiveMaxRatio: BigDecimal? = null,
        adaptiveThreshold: BigDecimal? = null,
        multiplierMode: String = CopyTradingSizingSupport.MULTIPLIER_MODE_NONE,
        tradeMultiplier: BigDecimal? = null,
        tieredMultipliers: List<SizingMultiplierTier> = emptyList(),
        maxOrderSize: BigDecimal = bd("1000"),
        minOrderSize: BigDecimal = bd("1"),
        maxPositionValue: BigDecimal? = null,
        maxDailyVolume: BigDecimal? = null
    ) = CopyTradingSizingConfig(
        copyMode = copyMode,
        copyRatio = copyRatio,
        fixedAmount = fixedAmount,
        adaptiveMinRatio = adaptiveMinRatio,
        adaptiveMaxRatio = adaptiveMaxRatio,
        adaptiveThreshold = adaptiveThreshold,
        multiplierMode = multiplierMode,
        tradeMultiplier = tradeMultiplier,
        tieredMultipliers = tieredMultipliers,
        maxOrderSize = maxOrderSize,
        minOrderSize = minOrderSize,
        maxPositionValue = maxPositionValue,
        maxDailyVolume = maxDailyVolume
    )

    private fun bd(value: String) = BigDecimal(value)

    private fun assertDecimalEquals(expected: String, actual: BigDecimal?) {
        assertTrue(actual != null && actual.compareTo(BigDecimal(expected)) == 0, "Expected $expected but was $actual")
    }
}
