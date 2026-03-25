package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTradingRepeatAddState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CopyTradingRepeatAddStateRepository : JpaRepository<CopyTradingRepeatAddState, Long> {
    fun findByCopyTradingIdAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ): CopyTradingRepeatAddState?

    fun deleteByCopyTradingIdAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    )

    fun deleteByCopyTradingId(copyTradingId: Long)
}
