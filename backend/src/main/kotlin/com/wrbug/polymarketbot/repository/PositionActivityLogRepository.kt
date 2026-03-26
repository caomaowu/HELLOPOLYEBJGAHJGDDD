package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.PositionActivityLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PositionActivityLogRepository : JpaRepository<PositionActivityLog, Long> {
    fun existsByAccountIdAndTradeId(accountId: Long, tradeId: String): Boolean

    fun existsByAccountIdAndOrderIdAndSource(accountId: Long, orderId: String, source: String): Boolean

    fun findByAccountIdAndMarketIdAndOutcomeIndexAndSideOrderByEventTimeAscIdAsc(
        accountId: Long,
        marketId: String,
        outcomeIndex: Int?,
        side: String
    ): List<PositionActivityLog>

    fun findByAccountIdAndMarketIdAndOutcomeIndexIsNullAndSideOrderByEventTimeAscIdAsc(
        accountId: Long,
        marketId: String,
        side: String
    ): List<PositionActivityLog>

    fun findByAccountIdAndMarketIdAndOutcomeIndexAndSide(
        accountId: Long,
        marketId: String,
        outcomeIndex: Int?,
        side: String,
        pageable: Pageable
    ): Page<PositionActivityLog>

    fun findByAccountIdAndMarketIdAndOutcomeIndexIsNullAndSide(
        accountId: Long,
        marketId: String,
        side: String,
        pageable: Pageable
    ): Page<PositionActivityLog>
}

