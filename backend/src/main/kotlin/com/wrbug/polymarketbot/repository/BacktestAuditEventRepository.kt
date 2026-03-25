package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestAuditEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BacktestAuditEventRepository : JpaRepository<BacktestAuditEvent, Long> {

    @Query(
        """
        SELECT e FROM BacktestAuditEvent e
        WHERE e.backtestTaskId = :taskId
          AND (:stage IS NULL OR e.stage = :stage)
          AND (:decision IS NULL OR e.decision = :decision)
          AND (:eventType IS NULL OR e.eventType = :eventType)
        ORDER BY e.id
        """
    )
    fun findByTaskIdWithFilters(
        taskId: Long,
        stage: String?,
        decision: String?,
        eventType: String?,
        pageable: Pageable
    ): Page<BacktestAuditEvent>

    fun findTop200ByBacktestTaskIdOrderByIdDesc(backtestTaskId: Long): List<BacktestAuditEvent>

    fun countByBacktestTaskId(backtestTaskId: Long): Long

    fun countByBacktestTaskIdAndDecision(backtestTaskId: Long, decision: String): Long

    @Query("SELECT e.stage, COUNT(e.id) FROM BacktestAuditEvent e WHERE e.backtestTaskId = :taskId GROUP BY e.stage")
    fun countByTaskIdGroupByStage(taskId: Long): List<Array<Any>>

    @Modifying
    @Query("DELETE FROM BacktestAuditEvent e WHERE e.createdAt < :cutoffTime")
    fun deleteByCreatedAtBefore(@Param("cutoffTime") cutoffTime: Long): Int
}
