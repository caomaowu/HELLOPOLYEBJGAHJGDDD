package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.TraderActivityEventHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TraderActivityEventHistoryRepository : JpaRepository<TraderActivityEventHistory, Long>,
    JpaSpecificationExecutor<TraderActivityEventHistory> {
    fun findByEventKeyIn(eventKeys: Collection<String>): List<TraderActivityEventHistory>

    @Modifying
    @Query("DELETE FROM TraderActivityEventHistory e WHERE e.createdAt < :cutoffTime")
    fun deleteByCreatedAtBefore(@Param("cutoffTime") cutoffTime: Long): Int
}
