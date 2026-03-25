package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "copy_trading_repeat_add_state",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_repeat_add_state",
            columnNames = ["copy_trading_id", "market_id", "outcome_index"]
        )
    ],
    indexes = [
        Index(name = "idx_repeat_add_state_copy_trading", columnList = "copy_trading_id"),
        Index(name = "idx_repeat_add_state_market", columnList = "market_id, outcome_index")
    ]
)
data class CopyTradingRepeatAddState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int,

    @Column(name = "first_buy_amount", nullable = false, precision = 20, scale = 8)
    val firstBuyAmount: BigDecimal,

    @Column(name = "buy_count", nullable = false)
    val buyCount: Int,

    @Column(name = "last_buy_amount", nullable = false, precision = 20, scale = 8)
    val lastBuyAmount: BigDecimal,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
