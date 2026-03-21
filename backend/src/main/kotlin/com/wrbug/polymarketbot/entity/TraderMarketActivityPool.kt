package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "trader_market_activity_pool",
    indexes = [
        Index(name = "idx_trader_market_activity_market", columnList = "market_id"),
        Index(name = "idx_trader_market_activity_trader", columnList = "trader_address"),
        Index(name = "idx_trader_market_activity_last_seen", columnList = "last_seen_at")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_trader_market_activity_market_trader", columnNames = ["market_id", "trader_address"])
    ]
)
data class TraderMarketActivityPool(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "trader_address", nullable = false, length = 42)
    val traderAddress: String,

    @Column(name = "display_name", length = 255)
    var displayName: String? = null,

    @Column(name = "trade_count", nullable = false)
    var tradeCount: Int = 0,

    @Column(name = "buy_count", nullable = false)
    var buyCount: Int = 0,

    @Column(name = "sell_count", nullable = false)
    var sellCount: Int = 0,

    @Column(name = "total_volume", nullable = false, precision = 36, scale = 8)
    var totalVolume: BigDecimal = BigDecimal.ZERO,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Long = System.currentTimeMillis(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Long = System.currentTimeMillis(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
