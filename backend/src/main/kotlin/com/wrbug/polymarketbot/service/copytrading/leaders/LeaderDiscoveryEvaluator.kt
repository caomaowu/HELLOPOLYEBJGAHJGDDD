package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendRequest
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto
import com.wrbug.polymarketbot.dto.LeaderDiscoveryMarketDto
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

/**
 * Leader 发现评估器
 * 所有指标均为估算值，只用于候选筛选与解释。
 */
@Component
class LeaderDiscoveryEvaluator {

    fun evaluateCandidate(
        address: String,
        displayName: String?,
        profileImage: String?,
        existingLeader: Leader?,
        activities: List<UserActivityResponse>,
        positions: List<PositionResponse>,
        sampleMarkets: List<LeaderDiscoveryMarketDto>,
        request: LeaderCandidateRecommendRequest
    ): LeaderCandidateRecommendationDto {
        val trades = activities.filter { it.type.equals("TRADE", ignoreCase = true) }
        val totalTradeCount = trades.size
        val totalVolume = trades.fold(BigDecimal.ZERO) { acc, activity -> acc + (activity.usdcSize ?: 0.0).toSafeBigDecimal() }
        val distinctMarkets = trades.mapNotNull { it.conditionId.takeIf { id -> id.isNotBlank() } }.distinct().size
        val activeDays = trades.map { Instant.ofEpochSecond(it.timestamp).atZone(ZoneOffset.UTC).toLocalDate() }.distinct().size

        val currentPositionValue = positions.fold(BigDecimal.ZERO) { acc, position -> acc + (position.currentValue ?: 0.0).toSafeBigDecimal() }
        val estimatedTotalBought = positions.fold(BigDecimal.ZERO) { acc, position ->
            acc + when {
                position.totalBought != null && position.totalBought > 0.0 -> position.totalBought.toSafeBigDecimal()
                position.initialValue != null && position.initialValue > 0.0 -> position.initialValue.toSafeBigDecimal()
                else -> BigDecimal.ZERO
            }
        }
        val estimatedRealizedPnl = positions.fold(BigDecimal.ZERO) { acc, position -> acc + (position.realizedPnl ?: 0.0).toSafeBigDecimal() }
        val estimatedUnrealizedPnl = positions.fold(BigDecimal.ZERO) { acc, position -> acc + (position.cashPnl ?: 0.0).toSafeBigDecimal() }
        val estimatedTotalPnl = estimatedRealizedPnl + estimatedUnrealizedPnl

        val roiBase = maxDecimal(estimatedTotalBought, currentPositionValue, totalVolume, BigDecimal.ONE)
        val estimatedRoiRate = safeRate(estimatedTotalPnl, roiBase)

        val negativeOpenLoss = positions.fold(BigDecimal.ZERO) { acc, position ->
            val pnl = (position.cashPnl ?: 0.0).toSafeBigDecimal()
            if (pnl < BigDecimal.ZERO) acc + pnl.abs() else acc
        }
        val estimatedDrawdownRate = safeRate(negativeOpenLoss, maxDecimal(estimatedTotalBought, currentPositionValue, BigDecimal.ONE))

        val largestPositionValue = positions.maxOfOrNull { (it.currentValue ?: 0.0).toSafeBigDecimal() } ?: BigDecimal.ZERO
        val marketConcentrationRate = if (currentPositionValue > BigDecimal.ZERO) {
            safeRate(largestPositionValue, currentPositionValue)
        } else {
            BigDecimal.ZERO
        }

        val buyCount = trades.count { it.side.equals("BUY", ignoreCase = true) }
        val sellCount = trades.count { it.side.equals("SELL", ignoreCase = true) }
        val currentPositionCount = positions.count { (it.size ?: 0.0) > 0.0 }

        val riskScore = computeRiskScore(
            totalTradeCount = totalTradeCount,
            activeDays = activeDays,
            currentPositionCount = currentPositionCount,
            estimatedDrawdownRate = estimatedDrawdownRate,
            marketConcentrationRate = marketConcentrationRate,
            buyCount = buyCount,
            sellCount = sellCount
        )
        val recommendationScore = computeRecommendationScore(
            totalTradeCount = totalTradeCount,
            activeDays = activeDays,
            distinctMarkets = distinctMarkets,
            estimatedRoiRate = estimatedRoiRate,
            marketConcentrationRate = marketConcentrationRate,
            riskScore = riskScore
        )

        val tags = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        if (existingLeader != null) {
            tags += "existingLeader"
            reasons += "已在 Leader 列表中，可直接复用现有配置"
        }
        if (estimatedRoiRate > BigDecimal("0.05")) {
            tags += "positiveRoi"
            reasons += "估算 ROI 为 ${formatRate(estimatedRoiRate)}"
        } else if (estimatedRoiRate < BigDecimal.ZERO) {
            tags += "negativeRoi"
            reasons += "估算 ROI 为 ${formatRate(estimatedRoiRate)}，需要谨慎"
        }
        if (marketConcentrationRate <= BigDecimal("0.35")) {
            tags += "diversified"
            reasons += "当前市场集中度约 ${formatRate(marketConcentrationRate)}"
        } else {
            tags += "concentrated"
            reasons += "当前市场集中度约 ${formatRate(marketConcentrationRate)}"
        }
        if (currentPositionCount <= (request.maxOpenPositions ?: 8)) {
            tags += "manageableExposure"
            reasons += "当前开放仓位 $currentPositionCount 个"
        } else {
            tags += "heavyExposure"
            reasons += "当前开放仓位 $currentPositionCount 个，持仓较多"
        }
        if (totalTradeCount >= (request.minTrades ?: 8)) {
            tags += "activeTrader"
            reasons += "最近 ${request.days ?: 7} 天成交 $totalTradeCount 笔"
        } else {
            tags += "limitedHistory"
            reasons += "最近 ${request.days ?: 7} 天仅成交 $totalTradeCount 笔"
        }
        if (estimatedDrawdownRate > BigDecimal.ZERO) {
            reasons += "估算开放回撤约 ${formatRate(estimatedDrawdownRate)}"
        }

        val lowRisk = isLowRisk(
            totalTradeCount = totalTradeCount,
            currentPositionCount = currentPositionCount,
            marketConcentrationRate = marketConcentrationRate,
            estimatedDrawdownRate = estimatedDrawdownRate,
            riskScore = riskScore,
            request = request
        )
        if (lowRisk) {
            tags += "lowRisk"
        }

        return LeaderCandidateRecommendationDto(
            address = address,
            displayName = displayName,
            profileImage = profileImage,
            existingLeaderId = existingLeader?.id,
            existingLeaderName = existingLeader?.leaderName,
            recentTradeCount = totalTradeCount,
            distinctMarkets = distinctMarkets,
            activeDays = activeDays,
            recentVolume = formatDecimal(totalVolume),
            currentPositionCount = currentPositionCount,
            currentPositionValue = formatDecimal(currentPositionValue),
            estimatedTotalBought = formatDecimal(estimatedTotalBought),
            estimatedRealizedPnl = formatDecimal(estimatedRealizedPnl),
            estimatedUnrealizedPnl = formatDecimal(estimatedUnrealizedPnl),
            estimatedTotalPnl = formatDecimal(estimatedTotalPnl),
            estimatedRoiRate = formatRate(estimatedRoiRate),
            estimatedDrawdownRate = formatRate(estimatedDrawdownRate),
            marketConcentrationRate = formatRate(marketConcentrationRate),
            riskScore = riskScore,
            recommendationScore = recommendationScore,
            lowRisk = lowRisk,
            tags = tags.distinct(),
            reasons = reasons.distinct().take(6),
            sampleMarkets = sampleMarkets,
            lastSeenAt = trades.maxOfOrNull { it.timestamp }?.times(1000)
        )
    }

    private fun computeRiskScore(
        totalTradeCount: Int,
        activeDays: Int,
        currentPositionCount: Int,
        estimatedDrawdownRate: BigDecimal,
        marketConcentrationRate: BigDecimal,
        buyCount: Int,
        sellCount: Int
    ): Int {
        var score = BigDecimal.ZERO
        score += marketConcentrationRate.multiply(BigDecimal("45"))
        score += estimatedDrawdownRate.multiply(BigDecimal("35"))
        score += BigDecimal(minOf(12, currentPositionCount))
        if (totalTradeCount < 8) score += BigDecimal("8")
        if (activeDays < 3) score += BigDecimal("5")
        if (buyCount > 0 && sellCount == 0) score += BigDecimal("6")
        return score.setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 100)
    }

    private fun computeRecommendationScore(
        totalTradeCount: Int,
        activeDays: Int,
        distinctMarkets: Int,
        estimatedRoiRate: BigDecimal,
        marketConcentrationRate: BigDecimal,
        riskScore: Int
    ): Int {
        var score = BigDecimal("35")
        score += BigDecimal(minOf(20, totalTradeCount))
        score += BigDecimal(minOf(10, activeDays * 2))
        score += BigDecimal(minOf(15, distinctMarkets * 2))
        score += estimatedRoiRate.multiply(BigDecimal("120"))
        score += (BigDecimal.ONE - marketConcentrationRate).max(BigDecimal.ZERO).multiply(BigDecimal("10"))
        score -= BigDecimal(riskScore).multiply(BigDecimal("0.45"))
        return score.setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 100)
    }

    private fun isLowRisk(
        totalTradeCount: Int,
        currentPositionCount: Int,
        marketConcentrationRate: BigDecimal,
        estimatedDrawdownRate: BigDecimal,
        riskScore: Int,
        request: LeaderCandidateRecommendRequest
    ): Boolean {
        return totalTradeCount >= (request.minTrades ?: 8) &&
            currentPositionCount <= (request.maxOpenPositions ?: 8) &&
            marketConcentrationRate <= BigDecimal.valueOf(request.maxMarketConcentrationRate ?: 0.45) &&
            estimatedDrawdownRate <= BigDecimal.valueOf(request.maxEstimatedDrawdownRate ?: 0.18) &&
            riskScore <= (request.maxRiskScore ?: 45)
    }

    private fun safeRate(numerator: BigDecimal, denominator: BigDecimal): BigDecimal {
        if (denominator <= BigDecimal.ZERO) return BigDecimal.ZERO
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP)
    }

    private fun maxDecimal(vararg values: BigDecimal): BigDecimal {
        return values.maxByOrNull { it } ?: BigDecimal.ZERO
    }

    private fun formatDecimal(value: BigDecimal): String {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun formatRate(value: BigDecimal): String {
        return value.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
