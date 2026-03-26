package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.ManualPositionHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ManualPositionHistoryRepository : JpaRepository<ManualPositionHistory, Long> {
    fun findAllByOrderByCreatedAtDesc(): List<ManualPositionHistory>

    fun findByAccountIdOrderByCreatedAtAsc(accountId: Long): List<ManualPositionHistory>
}
