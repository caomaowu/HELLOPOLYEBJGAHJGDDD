package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal

@Entity
@Table(
    name = "position_activity_log",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_position_activity_trade", columnNames = ["account_id", "trade_id"]),
        UniqueConstraint(name = "uk_position_activity_order_source", columnNames = ["account_id", "order_id", "source"])
    ],
    indexes = [
        Index(name = "idx_position_activity_position_time", columnList = "account_id, market_id, outcome_index, side, event_time"),
        Index(name = "idx_position_activity_event_time", columnList = "event_time"),
        Index(name = "idx_position_activity_source", columnList = "source")
    ]
)
data class PositionActivityLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "side", nullable = false, length = 100)
    val side: String,

    @Column(name = "event_type", nullable = false, length = 20)
    val eventType: String,

    @Column(name = "trade_side", nullable = false, length = 10)
    val tradeSide: String,

    @Column(name = "event_time", nullable = false)
    val eventTime: Long,

    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,

    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal,

    @Column(name = "actual_amount", nullable = false, precision = 20, scale = 8)
    val actualAmount: BigDecimal,

    @Column(name = "fee", nullable = false, precision = 20, scale = 8)
    val fee: BigDecimal = BigDecimal.ZERO,

    @Column(name = "remaining_quantity", nullable = false, precision = 20, scale = 8)
    val remainingQuantity: BigDecimal = BigDecimal.ZERO,

    @Column(name = "source", nullable = false, length = 32)
    val source: String,

    @Column(name = "trade_id", length = 100)
    val tradeId: String? = null,

    @Column(name = "order_id", length = 100)
    val orderId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
