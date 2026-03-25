package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.entity.CopyTradingRepeatAddState
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepeatAddStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class RepeatAddStateSnapshot(
    val firstBuyAmount: BigDecimal,
    val buyCount: Int,
    val lastBuyAmount: BigDecimal
)

@Service
class CopyTradingRepeatAddStateService(
    private val repeatAddStateRepository: CopyTradingRepeatAddStateRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository
) {

    @Transactional
    fun getActiveState(copyTradingId: Long, marketId: String, outcomeIndex: Int?): RepeatAddStateSnapshot? {
        val normalizedOutcomeIndex = normalizeOutcomeIndex(outcomeIndex)
        val state = repeatAddStateRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            copyTradingId,
            marketId,
            normalizedOutcomeIndex
        ) ?: return null

        val hasActivePosition = copyOrderTrackingRepository.existsActivePosition(copyTradingId, marketId, outcomeIndex)
        if (!hasActivePosition) {
            repeatAddStateRepository.delete(state)
            return null
        }

        return RepeatAddStateSnapshot(
            firstBuyAmount = state.firstBuyAmount,
            buyCount = state.buyCount,
            lastBuyAmount = state.lastBuyAmount
        )
    }

    @Transactional
    fun recordSuccessfulBuy(copyTradingId: Long, marketId: String, outcomeIndex: Int?, buyAmount: BigDecimal) {
        val normalizedOutcomeIndex = normalizeOutcomeIndex(outcomeIndex)
        val now = System.currentTimeMillis()
        val existing = repeatAddStateRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            copyTradingId,
            marketId,
            normalizedOutcomeIndex
        )

        val next = if (existing == null) {
            CopyTradingRepeatAddState(
                copyTradingId = copyTradingId,
                marketId = marketId,
                outcomeIndex = normalizedOutcomeIndex,
                firstBuyAmount = buyAmount,
                buyCount = 1,
                lastBuyAmount = buyAmount,
                createdAt = now,
                updatedAt = now
            )
        } else {
            existing.copy(
                buyCount = existing.buyCount + 1,
                lastBuyAmount = buyAmount,
                updatedAt = now
            )
        }

        repeatAddStateRepository.save(next)
    }

    @Transactional
    fun clearIfNoActivePosition(copyTradingId: Long, marketId: String, outcomeIndex: Int?) {
        val hasActivePosition = copyOrderTrackingRepository.existsActivePosition(copyTradingId, marketId, outcomeIndex)
        if (!hasActivePosition) {
            repeatAddStateRepository.deleteByCopyTradingIdAndMarketIdAndOutcomeIndex(
                copyTradingId,
                marketId,
                normalizeOutcomeIndex(outcomeIndex)
            )
        }
    }

    @Transactional
    fun clearAll(copyTradingId: Long) {
        repeatAddStateRepository.deleteByCopyTradingId(copyTradingId)
    }

    private fun normalizeOutcomeIndex(outcomeIndex: Int?): Int = outcomeIndex ?: -1
}
