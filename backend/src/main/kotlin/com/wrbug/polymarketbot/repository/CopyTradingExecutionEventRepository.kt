package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTradingExecutionEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CopyTradingExecutionEventRepository : JpaRepository<CopyTradingExecutionEvent, Long> {

    fun findTop100ByCopyTradingIdOrderByCreatedAtDesc(copyTradingId: Long): List<CopyTradingExecutionEvent>

    @Query(
        """
        SELECT e FROM CopyTradingExecutionEvent e
        WHERE e.copyTradingId = :copyTradingId
          AND (:eventType IS NULL OR e.eventType = :eventType)
          AND (:stage IS NULL OR e.stage = :stage)
          AND (:source IS NULL OR e.source = :source)
          AND (:status IS NULL OR e.status = :status)
          AND (:startTime IS NULL OR e.createdAt >= :startTime)
          AND (:endTime IS NULL OR e.createdAt <= :endTime)
        ORDER BY e.createdAt DESC
        """
    )
    fun search(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("eventType") eventType: String?,
        @Param("stage") stage: String?,
        @Param("source") source: String?,
        @Param("status") status: String?,
        @Param("startTime") startTime: Long?,
        @Param("endTime") endTime: Long?,
        pageable: Pageable
    ): Page<CopyTradingExecutionEvent>

    @Query(
        """
        SELECT e FROM CopyTradingExecutionEvent e
        WHERE e.copyTradingId = :copyTradingId
          AND (:eventType IS NULL OR e.eventType = :eventType)
          AND (:stage IS NULL OR e.stage = :stage)
          AND (:source IS NULL OR e.source = :source)
          AND (:status IS NULL OR e.status = :status)
          AND (:startTime IS NULL OR e.createdAt >= :startTime)
          AND (:endTime IS NULL OR e.createdAt <= :endTime)
        ORDER BY e.createdAt DESC
        """
    )
    fun searchAll(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("eventType") eventType: String?,
        @Param("stage") stage: String?,
        @Param("source") source: String?,
        @Param("status") status: String?,
        @Param("startTime") startTime: Long?,
        @Param("endTime") endTime: Long?
    ): List<CopyTradingExecutionEvent>
}
