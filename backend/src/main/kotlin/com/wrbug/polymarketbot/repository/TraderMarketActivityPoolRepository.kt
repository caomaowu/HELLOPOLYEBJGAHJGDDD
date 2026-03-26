package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.TraderMarketActivityPool
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TraderMarketActivityPoolRepository : JpaRepository<TraderMarketActivityPool, Long> {
    fun findByMarketIdIn(marketIds: Collection<String>): List<TraderMarketActivityPool>
    fun findByMarketIdAndTraderAddress(marketId: String, traderAddress: String): TraderMarketActivityPool?

    @Modifying
    @Query("DELETE FROM TraderMarketActivityPool e WHERE e.lastSeenAt < :cutoffTime")
    fun deleteByLastSeenAtBefore(@Param("cutoffTime") cutoffTime: Long): Int
}
