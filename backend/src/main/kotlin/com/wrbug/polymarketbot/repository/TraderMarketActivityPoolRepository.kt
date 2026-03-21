package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.TraderMarketActivityPool
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TraderMarketActivityPoolRepository : JpaRepository<TraderMarketActivityPool, Long> {
    fun findByMarketIdIn(marketIds: Collection<String>): List<TraderMarketActivityPool>
    fun findByMarketIdAndTraderAddress(marketId: String, traderAddress: String): TraderMarketActivityPool?
}
