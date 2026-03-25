package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionListResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.dto.MultiplierTierDto
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal

class CopyTradingSizingServiceTest {

    private val service = CopyTradingSizingService()

    @Test
    fun `ratio mode should keep legacy behavior when no new fields are provided`() {
        val result = service.calculate(
            config = config(),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = BigDecimal.ZERO,
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
            currentPositionCost = BigDecimal.ZERO,
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
            currentPositionCost = BigDecimal.ZERO,
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
            currentPositionCost = BigDecimal.ZERO,
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
            currentPositionCost = bd("20"),
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
            currentPositionCost = bd("10"),
            currentDailyVolume = BigDecimal.ZERO
        )

        assertEquals(SizingStatus.REJECTED, result.status)
        assertDecimalEquals("5", result.finalAmount)
        assertDecimalEquals("100", result.baseAmount)
    }

    @Test
    fun `sizing should reject new position when active position count reaches limit`() {
        val result = service.calculate(
            config = config(maxPositionCount = 2),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO,
            currentActivePositionCount = 2,
            hasActivePosition = false
        )

        assertEquals(SizingStatus.REJECTED, result.status)
        assertEquals(SizingRejectionType.MAX_POSITION_COUNT_LIMIT, result.rejectionType)
    }

    @Test
    fun `sizing should allow adding to existing position after reaching position count limit`() {
        val result = service.calculate(
            config = config(maxPositionCount = 2),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            currentActivePositionCount = 2,
            hasActivePosition = true
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("100", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should not affect first buy`() {
        val result = service.calculate(
            config = config(
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                repeatAddReductionPercent = bd("50")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = BigDecimal.ZERO,
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = false
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertNull(result.repeatAddReductionInfo)
        assertDecimalEquals("100", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should apply uniform percent from first buy amount`() {
        val result = service.calculate(
            config = config(
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                repeatAddReductionPercent = bd("50")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 1
            )
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertNotNull(result.repeatAddReductionInfo)
        assertEquals(2, result.repeatAddReductionInfo?.buyIndex)
        assertDecimalEquals("40", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should apply uniform fixed amount`() {
        val result = service.calculate(
            config = config(
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED,
                repeatAddReductionFixedAmount = bd("12")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 1
            )
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("12", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should apply progressive percent`() {
        val result = service.calculate(
            config = config(
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_PROGRESSIVE,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                repeatAddReductionPercent = bd("50")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 2
            )
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertEquals(3, result.repeatAddReductionInfo?.buyIndex)
        assertDecimalEquals("20", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should apply progressive fixed amount`() {
        val result = service.calculate(
            config = config(
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_PROGRESSIVE,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED,
                repeatAddReductionFixedAmount = bd("15")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 2
            )
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("50", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should still respect downstream caps`() {
        val result = service.calculate(
            config = config(
                maxPositionValue = bd("45"),
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                repeatAddReductionPercent = bd("50")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("10"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 1
            )
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("35", result.finalAmount)
    }

    @Test
    fun `repeat add reduction should reject when adjusted amount falls below min order size`() {
        val result = service.calculate(
            config = config(
                minOrderSize = bd("10"),
                repeatAddReductionEnabled = true,
                repeatAddReductionStrategy = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED,
                repeatAddReductionFixedAmount = bd("5")
            ),
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            currentPositionCost = bd("20"),
            currentDailyVolume = BigDecimal.ZERO,
            hasActivePosition = true,
            repeatAddReductionContext = RepeatAddReductionContext(
                firstBuyAmount = bd("80"),
                existingBuyCount = 1
            )
        )

        assertEquals(SizingStatus.REJECTED, result.status)
        assertEquals(SizingRejectionType.BELOW_MIN_ORDER_SIZE, result.rejectionType)
        assertDecimalEquals("5", result.finalAmount)
    }

    @Test
    fun `real time sizing should cap by current position cost for same market and outcome`() = runTest {
        val trackingRepository = mock(CopyOrderTrackingRepository::class.java)
        val accountService = mock(AccountService::class.java)
        val repeatAddStateService = mock(CopyTradingRepeatAddStateService::class.java)
        val service = CopyTradingSizingService(
            copyOrderTrackingRepository = trackingRepository,
            accountService = accountService,
            repeatAddStateService = repeatAddStateService
        )
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 11L,
            leaderId = 22L,
            copyMode = CopyTradingSizingSupport.COPY_MODE_FIXED,
            fixedAmount = bd("4"),
            maxPositionValue = bd("7")
        )
        `when`(trackingRepository.sumCurrentPositionCostByMarketAndOutcomeIndex(1L, "market-1", 1))
            .thenReturn(BigDecimal.ZERO)
        `when`(trackingRepository.countActivePositions(1L)).thenReturn(1)
        `when`(trackingRepository.existsActivePosition(1L, "market-1", 1)).thenReturn(false)
        `when`(
            trackingRepository.sumDailyBuyVolume(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
            )
        )
            .thenReturn(BigDecimal.ZERO)
        `when`(accountService.getAllPositions()).thenReturn(
            Result.success(
                PositionListResponse(
                    currentPositions = listOf(
                        position(
                            accountId = 11L,
                            marketId = "market-1",
                            outcomeIndex = 1,
                            currentValue = "1",
                            initialValue = "5"
                        ),
                        position(
                            accountId = 11L,
                            marketId = "market-1",
                            outcomeIndex = 2,
                            currentValue = "20",
                            initialValue = "20"
                        )
                    ),
                    historyPositions = emptyList()
                )
            )
        )

        val result = service.calculateRealTimeBuySizing(
            copyTrading = copyTrading,
            leaderOrderAmount = bd("100"),
            tradePrice = bd("0.5"),
            marketId = "market-1",
            outcomeIndex = 1
        )

        assertEquals(SizingStatus.EXECUTABLE, result.status)
        assertDecimalEquals("2", result.finalAmount)
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
        maxPositionCount: Int? = null,
        maxDailyVolume: BigDecimal? = null,
        repeatAddReductionEnabled: Boolean = false,
        repeatAddReductionStrategy: String = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
        repeatAddReductionValueType: String = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
        repeatAddReductionPercent: BigDecimal? = null,
        repeatAddReductionFixedAmount: BigDecimal? = null
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
        maxPositionCount = maxPositionCount,
        maxDailyVolume = maxDailyVolume,
        repeatAddReductionEnabled = repeatAddReductionEnabled,
        repeatAddReductionStrategy = repeatAddReductionStrategy,
        repeatAddReductionValueType = repeatAddReductionValueType,
        repeatAddReductionPercent = repeatAddReductionPercent,
        repeatAddReductionFixedAmount = repeatAddReductionFixedAmount
    )

    private fun bd(value: String) = BigDecimal(value)

    private fun position(
        accountId: Long,
        marketId: String,
        outcomeIndex: Int,
        currentValue: String,
        initialValue: String
    ) = AccountPositionDto(
        accountId = accountId,
        accountName = "account-$accountId",
        walletAddress = "0xwallet",
        proxyAddress = "0xproxy",
        marketId = marketId,
        marketTitle = "market",
        marketSlug = "market",
        marketIcon = null,
        side = "YES",
        outcomeIndex = outcomeIndex,
        quantity = "10",
        avgPrice = "0.5",
        currentPrice = "0.1",
        currentValue = currentValue,
        initialValue = initialValue,
        pnl = "0",
        percentPnl = "0",
        realizedPnl = null,
        percentRealizedPnl = null,
        redeemable = false,
        mergeable = false,
        endDate = null
    )

    private fun assertDecimalEquals(expected: String, actual: BigDecimal?) {
        assertTrue(actual != null && actual.compareTo(BigDecimal(expected)) == 0, "Expected $expected but was $actual")
    }
}
