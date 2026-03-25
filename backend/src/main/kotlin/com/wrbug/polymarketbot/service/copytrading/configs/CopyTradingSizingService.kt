package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

@Service
class CopyTradingSizingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository? = null,
    private val accountService: AccountService? = null,
    private val repeatAddStateService: CopyTradingRepeatAddStateService? = null
) {

    private val logger = LoggerFactory.getLogger(CopyTradingSizingService::class.java)

    suspend fun calculateRealTimeBuySizing(
        copyTrading: CopyTrading,
        leaderOrderAmount: BigDecimal,
        tradePrice: BigDecimal,
        marketId: String,
        outcomeIndex: Int?
    ): CopyTradingSizingResult {
        val currentPositionValue = getCurrentPositionValue(copyTrading, marketId, outcomeIndex)
        val currentActivePositionCount = getCurrentActivePositionCount(
            copyTrading.id ?: return rejected("跟单配置不存在", SizingRejectionType.INVALID_INPUT)
        )
        val hasActivePosition = hasActivePosition(
            copyTrading.id,
            marketId,
            outcomeIndex
        )
        val currentDailyVolume = getCurrentDailyVolume(
            copyTrading.id
        )
        val repeatAddReductionContext = if (hasActivePosition) {
            requireNotNull(repeatAddStateService) {
                "CopyTradingRepeatAddStateService 未初始化"
            }.getActiveState(copyTrading.id, marketId, outcomeIndex)?.let {
                RepeatAddReductionContext(
                    firstBuyAmount = it.firstBuyAmount,
                    existingBuyCount = it.buyCount
                )
            }
        } else {
            requireNotNull(repeatAddStateService) {
                "CopyTradingRepeatAddStateService 未初始化"
            }.clearIfNoActivePosition(copyTrading.id, marketId, outcomeIndex)
            null
        }
        return calculate(
            config = copyTrading.toSizingConfig(),
            leaderOrderAmount = leaderOrderAmount,
            tradePrice = tradePrice,
            currentPositionValue = currentPositionValue,
            currentDailyVolume = currentDailyVolume,
            currentActivePositionCount = currentActivePositionCount,
            hasActivePosition = hasActivePosition,
            repeatAddReductionContext = repeatAddReductionContext
        )
    }

    fun calculateBacktestBuySizing(
        task: BacktestTask,
        leaderOrderAmount: BigDecimal,
        tradePrice: BigDecimal,
        currentPositionValue: BigDecimal,
        currentDailyVolume: BigDecimal,
        repeatAddReductionContext: RepeatAddReductionContext? = null
    ): CopyTradingSizingResult {
        return calculate(
            config = task.toSizingConfig(),
            leaderOrderAmount = leaderOrderAmount,
            tradePrice = tradePrice,
            currentPositionValue = currentPositionValue,
            currentDailyVolume = currentDailyVolume,
            repeatAddReductionContext = repeatAddReductionContext
        )
    }

    fun calculate(
        config: CopyTradingSizingConfig,
        leaderOrderAmount: BigDecimal,
        tradePrice: BigDecimal,
        currentPositionValue: BigDecimal,
        currentDailyVolume: BigDecimal,
        currentActivePositionCount: Int? = null,
        hasActivePosition: Boolean = false,
        repeatAddReductionContext: RepeatAddReductionContext? = null
    ): CopyTradingSizingResult {
        if (tradePrice <= BigDecimal.ZERO) {
            return rejected("交易价格无效", SizingRejectionType.INVALID_INPUT)
        }

        val appliedAdaptiveRatio = when (config.copyMode) {
            CopyTradingSizingSupport.COPY_MODE_ADAPTIVE -> calculateAdaptiveRatio(config, leaderOrderAmount)
            else -> null
        }

        val baseAmount = when (config.copyMode) {
            CopyTradingSizingSupport.COPY_MODE_RATIO -> leaderOrderAmount.multi(config.copyRatio)
            CopyTradingSizingSupport.COPY_MODE_FIXED -> config.fixedAmount ?: BigDecimal.ZERO
            CopyTradingSizingSupport.COPY_MODE_ADAPTIVE -> {
                leaderOrderAmount.multi(appliedAdaptiveRatio ?: config.copyRatio)
            }
            else -> return rejected("不支持的 copyMode: ${config.copyMode}", SizingRejectionType.INVALID_INPUT)
        }

        val appliedMultiplier = resolveMultiplier(config, leaderOrderAmount)
        val multipliedAmount = baseAmount.multi(appliedMultiplier)
        var finalAmount = multipliedAmount
        val reasons = mutableListOf<String>()
        var repeatAddReductionInfo: RepeatAddReductionInfo? = null

        if (appliedAdaptiveRatio != null) {
            reasons += "自适应比例=${appliedAdaptiveRatio.stripTrailingZeros().toPlainString()}x"
        }
        if (appliedMultiplier != BigDecimal.ONE) {
            reasons += "multiplier=${appliedMultiplier.stripTrailingZeros().toPlainString()}x"
        }

        repeatAddReductionInfo = applyRepeatAddReduction(
            config = config,
            originalAmount = multipliedAmount,
            hasActivePosition = hasActivePosition,
            repeatAddReductionContext = repeatAddReductionContext
        )
        if (repeatAddReductionInfo != null) {
            finalAmount = repeatAddReductionInfo.adjustedAmount
            reasons += buildRepeatAddReductionReason(repeatAddReductionInfo)
        }

        if (finalAmount.gt(config.maxOrderSize)) {
            finalAmount = config.maxOrderSize
            reasons += "按单笔最大金额裁剪到 ${config.maxOrderSize.stripTrailingZeros().toPlainString()} USDC"
        }

        if (config.maxPositionCount != null && currentActivePositionCount != null && !hasActivePosition) {
            if (currentActivePositionCount >= config.maxPositionCount) {
                return buildResult(
                    baseAmount = baseAmount,
                    multipliedAmount = multipliedAmount,
                    finalAmount = finalAmount,
                    finalQuantity = BigDecimal.ZERO,
                    appliedAdaptiveRatio = appliedAdaptiveRatio,
                    appliedMultiplier = appliedMultiplier,
                    status = SizingStatus.REJECTED,
                    reason = appendReasons(
                        reasons,
                        "超过最大活跃仓位数量限制: 当前活跃仓位=${currentActivePositionCount}, 上限=${config.maxPositionCount}"
                    ),
                    repeatAddReductionInfo = repeatAddReductionInfo,
                    rejectionType = SizingRejectionType.MAX_POSITION_COUNT_LIMIT
                )
            }
        }

        if (config.maxPositionValue != null) {
            val remainingPosition = config.maxPositionValue.subtract(currentPositionValue).max(BigDecimal.ZERO)
            if (remainingPosition < config.minOrderSize) {
                return buildResult(
                    baseAmount = baseAmount,
                    multipliedAmount = multipliedAmount,
                    finalAmount = remainingPosition,
                    finalQuantity = BigDecimal.ZERO,
                    appliedAdaptiveRatio = appliedAdaptiveRatio,
                    appliedMultiplier = appliedMultiplier,
                    status = SizingStatus.REJECTED,
                    reason = appendReasons(
                        reasons,
                        "超过最大仓位金额限制: 当前仓位=${currentPositionValue.stripTrailingZeros().toPlainString()} USDC, 剩余额度不足最小下单金额"
                    ),
                    repeatAddReductionInfo = repeatAddReductionInfo,
                    rejectionType = SizingRejectionType.MAX_POSITION_LIMIT
                )
            }
            if (finalAmount > remainingPosition) {
                finalAmount = remainingPosition
                reasons += "按最大仓位金额裁剪到 ${remainingPosition.stripTrailingZeros().toPlainString()} USDC"
            }
        }

        if (config.maxDailyVolume != null) {
            val remainingDailyVolume = config.maxDailyVolume.subtract(currentDailyVolume).max(BigDecimal.ZERO)
            if (remainingDailyVolume < config.minOrderSize) {
                return buildResult(
                    baseAmount = baseAmount,
                    multipliedAmount = multipliedAmount,
                    finalAmount = remainingDailyVolume,
                    finalQuantity = BigDecimal.ZERO,
                    appliedAdaptiveRatio = appliedAdaptiveRatio,
                    appliedMultiplier = appliedMultiplier,
                    status = SizingStatus.REJECTED,
                    reason = appendReasons(
                        reasons,
                        "超过每日最大成交额限制: 已用=${currentDailyVolume.stripTrailingZeros().toPlainString()} USDC, 剩余额度不足最小下单金额"
                    ),
                    repeatAddReductionInfo = repeatAddReductionInfo,
                    rejectionType = SizingRejectionType.MAX_DAILY_VOLUME_LIMIT
                )
            }
            if (finalAmount > remainingDailyVolume) {
                finalAmount = remainingDailyVolume
                reasons += "按每日最大成交额裁剪到 ${remainingDailyVolume.stripTrailingZeros().toPlainString()} USDC"
            }
        }

        if (finalAmount.lt(config.minOrderSize)) {
            return buildResult(
                baseAmount = baseAmount,
                multipliedAmount = multipliedAmount,
                finalAmount = finalAmount,
                finalQuantity = BigDecimal.ZERO,
                appliedAdaptiveRatio = appliedAdaptiveRatio,
                appliedMultiplier = appliedMultiplier,
                status = SizingStatus.REJECTED,
                reason = appendReasons(
                    reasons,
                    "金额低于最小下单限制: ${finalAmount.stripTrailingZeros().toPlainString()} < ${config.minOrderSize.stripTrailingZeros().toPlainString()} USDC"
                ),
                repeatAddReductionInfo = repeatAddReductionInfo,
                rejectionType = SizingRejectionType.BELOW_MIN_ORDER_SIZE
            )
        }

        val finalQuantity = finalAmount.divide(tradePrice, 8, RoundingMode.DOWN)
        if (finalQuantity <= BigDecimal.ZERO) {
            return buildResult(
                baseAmount = baseAmount,
                multipliedAmount = multipliedAmount,
                finalAmount = finalAmount,
                finalQuantity = finalQuantity,
                appliedAdaptiveRatio = appliedAdaptiveRatio,
                appliedMultiplier = appliedMultiplier,
                status = SizingStatus.REJECTED,
                reason = appendReasons(reasons, "最终数量无效"),
                repeatAddReductionInfo = repeatAddReductionInfo,
                rejectionType = SizingRejectionType.INVALID_FINAL_QUANTITY
            )
        }

        return buildResult(
            baseAmount = baseAmount,
            multipliedAmount = multipliedAmount,
            finalAmount = finalAmount,
            finalQuantity = finalQuantity,
            appliedAdaptiveRatio = appliedAdaptiveRatio,
            appliedMultiplier = appliedMultiplier,
            status = SizingStatus.EXECUTABLE,
            reason = if (reasons.isEmpty()) {
                "通过 sizing 校验"
            } else {
                reasons.joinToString("；")
            },
            repeatAddReductionInfo = repeatAddReductionInfo
        )
    }

    private suspend fun getCurrentPositionValue(
        copyTrading: CopyTrading,
        marketId: String,
        outcomeIndex: Int?
    ): BigDecimal {
        val trackingRepository = requireNotNull(copyOrderTrackingRepository) {
            "CopyOrderTrackingRepository 未初始化"
        }
        val dbValue = if (outcomeIndex != null && copyTrading.id != null) {
            trackingRepository.sumCurrentPositionValueByMarketAndOutcomeIndex(
                copyTrading.id,
                marketId,
                outcomeIndex
            ) ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }

        return try {
            val positions = requireNotNull(accountService) {
                "AccountService 未初始化"
            }.getAllPositions().getOrNull()?.currentPositions.orEmpty()
            val extValue = positions
                .filter { it.accountId == copyTrading.accountId && it.marketId == marketId }
                .sumOf { it.currentValue.toSafeBigDecimal() }
            dbValue.max(extValue)
        } catch (e: Exception) {
            logger.warn("获取外部仓位失败，使用数据库仓位值: copyTradingId=${copyTrading.id}, marketId=$marketId", e)
            dbValue
        }
    }

    private fun getCurrentDailyVolume(copyTradingId: Long): BigDecimal {
        val trackingRepository = requireNotNull(copyOrderTrackingRepository) {
            "CopyOrderTrackingRepository 未初始化"
        }
        val zoneId = ZoneId.systemDefault()
        val now = Instant.now().atZone(zoneId)
        val start = now.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = now.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return trackingRepository.sumDailyBuyVolume(copyTradingId, start, end) ?: BigDecimal.ZERO
    }

    private fun getCurrentActivePositionCount(copyTradingId: Long): Int {
        val trackingRepository = requireNotNull(copyOrderTrackingRepository) {
            "CopyOrderTrackingRepository 未初始化"
        }
        return trackingRepository.countActivePositions(copyTradingId)
    }

    private fun hasActivePosition(copyTradingId: Long, marketId: String, outcomeIndex: Int?): Boolean {
        val trackingRepository = requireNotNull(copyOrderTrackingRepository) {
            "CopyOrderTrackingRepository 未初始化"
        }
        return trackingRepository.existsActivePosition(copyTradingId, marketId, outcomeIndex)
    }

    private fun calculateAdaptiveRatio(
        config: CopyTradingSizingConfig,
        leaderOrderAmount: BigDecimal
    ): BigDecimal {
        val threshold = config.adaptiveThreshold ?: return config.copyRatio
        val minRatio = config.adaptiveMinRatio ?: config.copyRatio
        val maxRatio = config.adaptiveMaxRatio ?: config.copyRatio

        if (leaderOrderAmount >= threshold) {
            val factor = (leaderOrderAmount.divide(threshold, 8, RoundingMode.HALF_UP) - BigDecimal.ONE)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
            return lerp(config.copyRatio, minRatio, factor)
        }

        val factor = leaderOrderAmount.divide(threshold, 8, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        return lerp(maxRatio, config.copyRatio, factor)
    }

    private fun resolveMultiplier(
        config: CopyTradingSizingConfig,
        leaderOrderAmount: BigDecimal
    ): BigDecimal {
        return when (config.multiplierMode) {
            CopyTradingSizingSupport.MULTIPLIER_MODE_NONE -> BigDecimal.ONE
            CopyTradingSizingSupport.MULTIPLIER_MODE_SINGLE -> config.tradeMultiplier ?: BigDecimal.ONE
            CopyTradingSizingSupport.MULTIPLIER_MODE_TIERED -> {
                val tiers = config.tieredMultipliers.sortedBy { it.min }
                if (tiers.isEmpty()) {
                    BigDecimal.ONE
                } else {
                    tiers.firstOrNull { tier ->
                        leaderOrderAmount >= tier.min && (tier.max == null || leaderOrderAmount < tier.max)
                    }?.multiplier ?: if (leaderOrderAmount < tiers.first().min) {
                        tiers.first().multiplier
                    } else {
                        tiers.last().multiplier
                    }
                }
            }
            else -> BigDecimal.ONE
        }
    }

    private fun lerp(start: BigDecimal, end: BigDecimal, factor: BigDecimal): BigDecimal {
        return start.add(end.subtract(start).multiply(factor)).setScale(8, RoundingMode.HALF_UP)
    }

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal {
        return when {
            this < min -> min
            this > max -> max
            else -> this
        }
    }

    private fun CopyTrading.toSizingConfig(): CopyTradingSizingConfig {
        return CopyTradingSizingConfig(
            copyMode = copyMode,
            copyRatio = copyRatio,
            fixedAmount = fixedAmount,
            adaptiveMinRatio = adaptiveMinRatio,
            adaptiveMaxRatio = adaptiveMaxRatio,
            adaptiveThreshold = adaptiveThreshold,
            multiplierMode = multiplierMode,
            tradeMultiplier = tradeMultiplier,
            tieredMultipliers = CopyTradingSizingSupport.parseTieredMultipliers(tieredMultipliers),
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
    }

    private fun BacktestTask.toSizingConfig(): CopyTradingSizingConfig {
        return CopyTradingSizingConfig(
            copyMode = copyMode,
            copyRatio = copyRatio,
            fixedAmount = fixedAmount,
            adaptiveMinRatio = adaptiveMinRatio,
            adaptiveMaxRatio = adaptiveMaxRatio,
            adaptiveThreshold = adaptiveThreshold,
            multiplierMode = multiplierMode,
            tradeMultiplier = tradeMultiplier,
            tieredMultipliers = CopyTradingSizingSupport.parseTieredMultipliers(tieredMultipliers),
            maxOrderSize = maxOrderSize,
            minOrderSize = minOrderSize,
            maxPositionValue = maxPositionValue,
            maxPositionCount = null,
            maxDailyVolume = maxDailyVolume,
            repeatAddReductionEnabled = repeatAddReductionEnabled,
            repeatAddReductionStrategy = repeatAddReductionStrategy,
            repeatAddReductionValueType = repeatAddReductionValueType,
            repeatAddReductionPercent = repeatAddReductionPercent,
            repeatAddReductionFixedAmount = repeatAddReductionFixedAmount
        )
    }

    private fun rejected(reason: String, rejectionType: SizingRejectionType): CopyTradingSizingResult {
        return buildResult(
            baseAmount = BigDecimal.ZERO,
            multipliedAmount = BigDecimal.ZERO,
            finalAmount = BigDecimal.ZERO,
            finalQuantity = BigDecimal.ZERO,
            appliedAdaptiveRatio = null,
            appliedMultiplier = BigDecimal.ONE,
            status = SizingStatus.REJECTED,
            reason = reason,
            repeatAddReductionInfo = null,
            rejectionType = rejectionType
        )
    }

    private fun applyRepeatAddReduction(
        config: CopyTradingSizingConfig,
        originalAmount: BigDecimal,
        hasActivePosition: Boolean,
        repeatAddReductionContext: RepeatAddReductionContext?
    ): RepeatAddReductionInfo? {
        if (!config.repeatAddReductionEnabled || !hasActivePosition || repeatAddReductionContext == null) {
            return null
        }

        val buyIndex = repeatAddReductionContext.existingBuyCount + 1
        if (buyIndex <= 1) {
            return null
        }

        val firstBuyAmount = repeatAddReductionContext.firstBuyAmount
        val adjustedAmount = when (config.repeatAddReductionStrategy) {
            CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM -> {
                when (config.repeatAddReductionValueType) {
                    CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT -> {
                        firstBuyAmount.multiply(normalizePercent(config.repeatAddReductionPercent))
                    }

                    CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED -> {
                        config.repeatAddReductionFixedAmount ?: BigDecimal.ZERO
                    }

                    else -> originalAmount
                }
            }

            CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_PROGRESSIVE -> {
                when (config.repeatAddReductionValueType) {
                    CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT -> {
                        firstBuyAmount.multiply(
                            pow(normalizePercent(config.repeatAddReductionPercent), buyIndex - 1)
                        )
                    }

                    CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED -> {
                        firstBuyAmount.subtract(
                            (config.repeatAddReductionFixedAmount ?: BigDecimal.ZERO)
                                .multiply(BigDecimal.valueOf((buyIndex - 1).toLong()))
                        ).max(BigDecimal.ZERO)
                    }

                    else -> originalAmount
                }
            }

            else -> originalAmount
        }.setScale(8, RoundingMode.HALF_UP)

        return RepeatAddReductionInfo(
            buyIndex = buyIndex,
            firstBuyAmount = firstBuyAmount,
            originalAmount = originalAmount,
            adjustedAmount = adjustedAmount,
            strategy = config.repeatAddReductionStrategy,
            valueType = config.repeatAddReductionValueType,
            percent = config.repeatAddReductionPercent,
            fixedAmount = config.repeatAddReductionFixedAmount
        )
    }

    private fun buildRepeatAddReductionReason(info: RepeatAddReductionInfo): String {
        val configValue = when (info.valueType) {
            CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT -> {
                "百分比=${info.percent?.stripTrailingZeros()?.toPlainString()}%"
            }

            CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED -> {
                "固定值=${info.fixedAmount?.stripTrailingZeros()?.toPlainString()} USDC"
            }

            else -> info.valueType
        }
        return "同市场再次加仓缩量: 第${info.buyIndex}笔, 首笔=${info.firstBuyAmount.stripTrailingZeros().toPlainString()} USDC, 原金额=${info.originalAmount.stripTrailingZeros().toPlainString()} USDC, 策略=${info.strategy}, 类型=${info.valueType}, ${configValue}, 调整后=${info.adjustedAmount.stripTrailingZeros().toPlainString()} USDC"
    }

    private fun appendReasons(reasons: List<String>, terminalReason: String): String {
        return (reasons + terminalReason).joinToString("；")
    }

    private fun normalizePercent(percent: BigDecimal?): BigDecimal {
        return (percent ?: BigDecimal.ZERO).divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
    }

    private fun pow(base: BigDecimal, exponent: Int): BigDecimal {
        if (exponent <= 0) {
            return BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
        }
        var result = BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
        repeat(exponent) {
            result = result.multiply(base).setScale(8, RoundingMode.HALF_UP)
        }
        return result
    }

    private fun buildResult(
        baseAmount: BigDecimal,
        multipliedAmount: BigDecimal,
        finalAmount: BigDecimal,
        finalQuantity: BigDecimal,
        appliedAdaptiveRatio: BigDecimal?,
        appliedMultiplier: BigDecimal,
        status: SizingStatus,
        reason: String,
        repeatAddReductionInfo: RepeatAddReductionInfo? = null,
        rejectionType: SizingRejectionType? = null
    ): CopyTradingSizingResult {
        return CopyTradingSizingResult(
            baseAmount = baseAmount,
            multipliedAmount = multipliedAmount,
            finalAmount = finalAmount,
            finalQuantity = finalQuantity,
            appliedAdaptiveRatio = appliedAdaptiveRatio,
            appliedMultiplier = appliedMultiplier,
            status = status,
            reason = reason,
            repeatAddReductionInfo = repeatAddReductionInfo,
            rejectionType = rejectionType
        )
    }
}
