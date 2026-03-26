package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.PositionActivitySyncState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PositionActivitySyncStateRepository : JpaRepository<PositionActivitySyncState, Long> {
    fun findByAccountId(accountId: Long): PositionActivitySyncState?
}

