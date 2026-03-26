package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "position_activity_sync_state")
data class PositionActivitySyncState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false, unique = true)
    val accountId: Long,

    @Column(name = "initialized_at", nullable = false)
    val initializedAt: Long,

    @Column(name = "last_synced_trade_time", nullable = false)
    val lastSyncedTradeTime: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

