package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 跟单执行事件
 * 用于记录信号发现、过滤跳过、聚合等待/释放、执行提交与结果等过程事件
 */
@Entity
@Table(name = "copy_trading_execution_event")
data class CopyTradingExecutionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "leader_trade_id", length = 100)
    val leaderTradeId: String? = null,

    @Column(name = "market_id", length = 100)
    val marketId: String? = null,

    @Column(name = "side", length = 10)
    val side: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "outcome", length = 80)
    val outcome: String? = null,

    @Column(name = "source", length = 50)
    val source: String? = null,

    @Column(name = "stage", nullable = false, length = 40)
    val stage: String,

    @Column(name = "event_type", nullable = false, length = 80)
    val eventType: String,

    @Column(name = "status", nullable = false, length = 20)
    val status: String,

    @Column(name = "leader_price", precision = 20, scale = 8)
    val leaderPrice: BigDecimal? = null,

    @Column(name = "leader_quantity", precision = 20, scale = 8)
    val leaderQuantity: BigDecimal? = null,

    @Column(name = "leader_order_amount", precision = 20, scale = 8)
    val leaderOrderAmount: BigDecimal? = null,

    @Column(name = "calculated_quantity", precision = 20, scale = 8)
    val calculatedQuantity: BigDecimal? = null,

    @Column(name = "order_price", precision = 20, scale = 8)
    val orderPrice: BigDecimal? = null,

    @Column(name = "order_quantity", precision = 20, scale = 8)
    val orderQuantity: BigDecimal? = null,

    @Column(name = "order_id", length = 120)
    val orderId: String? = null,

    @Column(name = "aggregation_key", length = 200)
    val aggregationKey: String? = null,

    @Column(name = "aggregation_trade_count")
    val aggregationTradeCount: Int? = null,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String,

    @Column(name = "detail_json", columnDefinition = "TEXT")
    val detailJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
