package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.MultiplierTierDto
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toJson
import java.math.BigDecimal

data class SizingMultiplierTier(
    val min: BigDecimal,
    val max: BigDecimal? = null,
    val multiplier: BigDecimal
)

data class CopyTradingSizingConfig(
    val copyMode: String,
    val copyRatio: BigDecimal,
    val fixedAmount: BigDecimal?,
    val adaptiveMinRatio: BigDecimal?,
    val adaptiveMaxRatio: BigDecimal?,
    val adaptiveThreshold: BigDecimal?,
    val multiplierMode: String,
    val tradeMultiplier: BigDecimal?,
    val tieredMultipliers: List<SizingMultiplierTier>,
    val maxOrderSize: BigDecimal,
    val minOrderSize: BigDecimal,
    val maxPositionValue: BigDecimal?,
    val maxPositionCount: Int?,
    val maxDailyVolume: BigDecimal?,
    val repeatAddReductionEnabled: Boolean = false,
    val repeatAddReductionStrategy: String = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
    val repeatAddReductionValueType: String = CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
    val repeatAddReductionPercent: BigDecimal? = null,
    val repeatAddReductionFixedAmount: BigDecimal? = null
)

enum class SizingStatus {
    EXECUTABLE,
    REJECTED
}

enum class SizingRejectionType {
    INVALID_INPUT,
    MAX_POSITION_LIMIT,
    MAX_POSITION_COUNT_LIMIT,
    MAX_DAILY_VOLUME_LIMIT,
    BELOW_MIN_ORDER_SIZE,
    INVALID_FINAL_QUANTITY
}

data class CopyTradingSizingResult(
    val baseAmount: BigDecimal,
    val multipliedAmount: BigDecimal,
    val finalAmount: BigDecimal,
    val finalQuantity: BigDecimal,
    val appliedAdaptiveRatio: BigDecimal?,
    val appliedMultiplier: BigDecimal,
    val status: SizingStatus,
    val reason: String,
    val repeatAddReductionInfo: RepeatAddReductionInfo? = null,
    val rejectionType: SizingRejectionType? = null
)

data class RepeatAddReductionContext(
    val firstBuyAmount: BigDecimal,
    val existingBuyCount: Int
)

data class RepeatAddReductionInfo(
    val buyIndex: Int,
    val firstBuyAmount: BigDecimal,
    val originalAmount: BigDecimal,
    val adjustedAmount: BigDecimal,
    val strategy: String,
    val valueType: String,
    val percent: BigDecimal? = null,
    val fixedAmount: BigDecimal? = null
)

object CopyTradingSizingSupport {
    const val COPY_MODE_RATIO = "RATIO"
    const val COPY_MODE_FIXED = "FIXED"
    const val COPY_MODE_ADAPTIVE = "ADAPTIVE"

    const val MULTIPLIER_MODE_NONE = "NONE"
    const val MULTIPLIER_MODE_SINGLE = "SINGLE"
    const val MULTIPLIER_MODE_TIERED = "TIERED"

    const val REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM = "UNIFORM"
    const val REPEAT_ADD_REDUCTION_STRATEGY_PROGRESSIVE = "PROGRESSIVE"

    const val REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT = "PERCENT"
    const val REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED = "FIXED"

    fun validateConfig(config: CopyTradingSizingConfig): List<String> {
        val errors = mutableListOf<String>()

        if (config.copyMode !in setOf(COPY_MODE_RATIO, COPY_MODE_FIXED, COPY_MODE_ADAPTIVE)) {
            errors += "copyMode 必须是 RATIO、FIXED 或 ADAPTIVE"
        }

        if (config.copyRatio <= BigDecimal.ZERO) {
            errors += "copyRatio 必须大于 0"
        }

        if (config.copyMode == COPY_MODE_FIXED) {
            if (config.fixedAmount == null || config.fixedAmount < BigDecimal.ONE) {
                errors += "FIXED 模式下 fixedAmount 必须 >= 1"
            }
        }

        if (config.copyMode == COPY_MODE_ADAPTIVE) {
            val minRatio = config.adaptiveMinRatio
            val maxRatio = config.adaptiveMaxRatio
            val threshold = config.adaptiveThreshold
            if (minRatio == null || minRatio <= BigDecimal.ZERO) {
                errors += "ADAPTIVE 模式下 adaptiveMinRatio 必须大于 0"
            }
            if (maxRatio == null || maxRatio <= BigDecimal.ZERO) {
                errors += "ADAPTIVE 模式下 adaptiveMaxRatio 必须大于 0"
            }
            if (threshold == null || threshold <= BigDecimal.ZERO) {
                errors += "ADAPTIVE 模式下 adaptiveThreshold 必须大于 0"
            }
            if (minRatio != null && maxRatio != null && minRatio > maxRatio) {
                errors += "adaptiveMinRatio 不能大于 adaptiveMaxRatio"
            }
        }

        if (config.multiplierMode !in setOf(MULTIPLIER_MODE_NONE, MULTIPLIER_MODE_SINGLE, MULTIPLIER_MODE_TIERED)) {
            errors += "multiplierMode 必须是 NONE、SINGLE 或 TIERED"
        }

        if (config.multiplierMode == MULTIPLIER_MODE_SINGLE) {
            if (config.tradeMultiplier == null || config.tradeMultiplier < BigDecimal.ZERO) {
                errors += "SINGLE multiplier 模式下 tradeMultiplier 必须 >= 0"
            }
        }

        if (config.multiplierMode == MULTIPLIER_MODE_TIERED) {
            if (config.tieredMultipliers.isEmpty()) {
                errors += "TIERED multiplier 模式下 tieredMultipliers 不能为空"
            } else {
                errors += validateTieredMultipliers(config.tieredMultipliers)
            }
        }

        if (config.minOrderSize <= BigDecimal.ZERO) {
            errors += "minOrderSize 必须大于 0"
        }

        if (config.maxOrderSize <= BigDecimal.ZERO) {
            errors += "maxOrderSize 必须大于 0"
        }

        if (config.minOrderSize > config.maxOrderSize) {
            errors += "minOrderSize 不能大于 maxOrderSize"
        }

        if (config.maxPositionCount != null && config.maxPositionCount <= 0) {
            errors += "maxPositionCount 必须大于 0"
        }

        if (config.maxDailyVolume != null && config.maxDailyVolume <= BigDecimal.ZERO) {
            errors += "maxDailyVolume 必须大于 0"
        }

        if (config.repeatAddReductionEnabled) {
            if (config.repeatAddReductionStrategy !in setOf(
                    REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                    REPEAT_ADD_REDUCTION_STRATEGY_PROGRESSIVE
                )
            ) {
                errors += "repeatAddReductionStrategy 必须是 UNIFORM 或 PROGRESSIVE"
            }

            if (config.repeatAddReductionValueType !in setOf(
                    REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                    REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED
                )
            ) {
                errors += "repeatAddReductionValueType 必须是 PERCENT 或 FIXED"
            }

            if (config.repeatAddReductionValueType == REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT) {
                val percent = config.repeatAddReductionPercent
                if (percent == null || percent <= BigDecimal.ZERO || percent >= BigDecimal("100")) {
                    errors += "repeatAddReductionPercent 必须在 (0, 100) 范围内"
                }
            }

            if (config.repeatAddReductionValueType == REPEAT_ADD_REDUCTION_VALUE_TYPE_FIXED) {
                val fixedAmount = config.repeatAddReductionFixedAmount
                if (fixedAmount == null || fixedAmount <= BigDecimal.ZERO) {
                    errors += "repeatAddReductionFixedAmount 必须大于 0"
                }
            }
        }

        return errors
    }

    fun validateTieredMultipliers(tiers: List<SizingMultiplierTier>): List<String> {
        if (tiers.isEmpty()) {
            return emptyList()
        }

        val errors = mutableListOf<String>()
        val sorted = tiers.sortedBy { it.min }
        sorted.forEachIndexed { index, tier ->
            if (tier.min < BigDecimal.ZERO) {
                errors += "第 ${index + 1} 档 multiplier 的 min 不能小于 0"
            }
            if (tier.multiplier < BigDecimal.ZERO) {
                errors += "第 ${index + 1} 档 multiplier 必须 >= 0"
            }
            if (tier.max != null && tier.max <= tier.min) {
                errors += "第 ${index + 1} 档 multiplier 的 max 必须大于 min"
            }
            if (tier.max == null && index != sorted.lastIndex) {
                errors += "无上界的 tier 必须放在最后一档"
            }
        }

        for (index in 0 until sorted.lastIndex) {
            val current = sorted[index]
            val next = sorted[index + 1]
            if (current.max != null && current.max > next.min) {
                errors += "tieredMultipliers 区间不能重叠"
                break
            }
        }

        return errors
    }

    fun parseTieredMultipliers(json: String?): List<SizingMultiplierTier> {
        val list = json.fromJson<List<MultiplierTierDto>>() ?: emptyList()
        return list.mapNotNull { dto ->
            val min = dto.min.toBigDecimalOrNull()
            val max = dto.max?.toBigDecimalOrNull()
            val multiplier = dto.multiplier.toBigDecimalOrNull()
            if (min == null || multiplier == null) {
                null
            } else {
                SizingMultiplierTier(min = min, max = max, multiplier = multiplier)
            }
        }.sortedBy { it.min }
    }

    fun serializeTieredMultipliers(tiers: List<MultiplierTierDto>?): String? {
        if (tiers.isNullOrEmpty()) {
            return null
        }
        return tiers
            .sortedBy { it.min.toBigDecimalOrNull() ?: BigDecimal.ZERO }
            .toJson()
    }

    fun toTierDtoList(json: String?): List<MultiplierTierDto>? {
        return json.fromJson<List<MultiplierTierDto>>()?.sortedBy { it.min.toBigDecimalOrNull() ?: BigDecimal.ZERO }
    }
}
