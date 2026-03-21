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
    private val accountService: AccountService? = null
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
        val currentDailyVolume = getCurrentDailyVolume(copyTrading.id ?: return rejected("跟单配置不存在"))
        return calculate(
            config = copyTrading.toSizingConfig(),
            leaderOrderAmount = leaderOrderAmount,
            tradePrice = tradePrice,
            currentPositionValue = currentPositionValue,
            currentDailyVolume = currentDailyVolume
        )
    }

    fun calculateBacktestBuySizing(
        task: BacktestTask,
        leaderOrderAmount: BigDecimal,
        tradePrice: BigDecimal,
        currentPositionValue: BigDecimal,
        currentDailyVolume: BigDecimal
    ): CopyTradingSizingResult {
        return calculate(
            config = task.toSizingConfig(),
            leaderOrderAmount = leaderOrderAmount,
            tradePrice = tradePrice,
            currentPositionValue = currentPositionValue,
            currentDailyVolume = currentDailyVolume
        )
    }

    fun calculate(
        config: CopyTradingSizingConfig,
        leaderOrderAmount: BigDecimal,
        tradePrice: BigDecimal,
        currentPositionValue: BigDecimal,
        currentDailyVolume: BigDecimal
    ): CopyTradingSizingResult {
        if (tradePrice <= BigDecimal.ZERO) {
            return rejected("交易价格无效")
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
            else -> return rejected("不支持的 copyMode: ${config.copyMode}")
        }

        val appliedMultiplier = resolveMultiplier(config, leaderOrderAmount)
        val multipliedAmount = baseAmount.multi(appliedMultiplier)
        var finalAmount = multipliedAmount
        val reasons = mutableListOf<String>()

        if (appliedAdaptiveRatio != null) {
            reasons += "自适应比例=${appliedAdaptiveRatio.stripTrailingZeros().toPlainString()}x"
        }
        if (appliedMultiplier != BigDecimal.ONE) {
            reasons += "multiplier=${appliedMultiplier.stripTrailingZeros().toPlainString()}x"
        }

        if (finalAmount.gt(config.maxOrderSize)) {
            finalAmount = config.maxOrderSize
            reasons += "按单笔最大金额裁剪到 ${config.maxOrderSize.stripTrailingZeros().toPlainString()} USDC"
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
                    reason = "超过最大仓位金额限制: 当前仓位=${currentPositionValue.stripTrailingZeros().toPlainString()} USDC, 剩余额度不足最小下单金额"
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
                    reason = "超过每日最大成交额限制: 已用=${currentDailyVolume.stripTrailingZeros().toPlainString()} USDC, 剩余额度不足最小下单金额"
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
                reason = "金额低于最小下单限制: ${finalAmount.stripTrailingZeros().toPlainString()} < ${config.minOrderSize.stripTrailingZeros().toPlainString()} USDC"
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
                reason = "最终数量无效"
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
            }
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
            maxDailyVolume = maxDailyVolume
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
            maxDailyVolume = maxDailyVolume
        )
    }

    private fun rejected(reason: String): CopyTradingSizingResult {
        return buildResult(
            baseAmount = BigDecimal.ZERO,
            multipliedAmount = BigDecimal.ZERO,
            finalAmount = BigDecimal.ZERO,
            finalQuantity = BigDecimal.ZERO,
            appliedAdaptiveRatio = null,
            appliedMultiplier = BigDecimal.ONE,
            status = SizingStatus.REJECTED,
            reason = reason
        )
    }

    private fun buildResult(
        baseAmount: BigDecimal,
        multipliedAmount: BigDecimal,
        finalAmount: BigDecimal,
        finalQuantity: BigDecimal,
        appliedAdaptiveRatio: BigDecimal?,
        appliedMultiplier: BigDecimal,
        status: SizingStatus,
        reason: String
    ): CopyTradingSizingResult {
        return CopyTradingSizingResult(
            baseAmount = baseAmount,
            multipliedAmount = multipliedAmount,
            finalAmount = finalAmount,
            finalQuantity = finalQuantity,
            appliedAdaptiveRatio = appliedAdaptiveRatio,
            appliedMultiplier = appliedMultiplier,
            status = status,
            reason = reason
        )
    }
}
