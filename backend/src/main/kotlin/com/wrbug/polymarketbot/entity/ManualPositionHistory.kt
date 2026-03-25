package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * 手动平仓历史记录
 * 用于补齐上游接口不再返回的已平仓仓位信息
 */
@Entity
@Table(name = "manual_position_history")
data class ManualPositionHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "market_slug", length = 500)
    val marketSlug: String? = null,

    @Column(name = "event_slug", length = 500)
    val eventSlug: String? = null,

    @Column(name = "market_icon", length = 1000)
    val marketIcon: String? = null,

    @Column(name = "side", nullable = false, length = 100)
    val side: String,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "closed_quantity", nullable = false, precision = 20, scale = 8)
    val closedQuantity: BigDecimal,

    @Column(name = "avg_price", nullable = false, precision = 20, scale = 8)
    val avgPrice: BigDecimal,

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    val closePrice: BigDecimal,

    @Column(name = "initial_value", nullable = false, precision = 20, scale = 8)
    val initialValue: BigDecimal,

    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal,

    @Column(name = "percent_realized_pnl", precision = 20, scale = 8)
    val percentRealizedPnl: BigDecimal? = null,

    @Column(name = "close_order_id", nullable = false, length = 100)
    val closeOrderId: String,

    @Column(name = "close_order_status", length = 50)
    val closeOrderStatus: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
