package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 回测执行审计事件
 * 用于记录回测执行过程中的关键决策与状态变更。
 */
@Entity
@Table(name = "backtest_audit_event")
data class BacktestAuditEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "backtest_task_id", nullable = false)
    val backtestTaskId: Long,

    @Column(name = "event_time")
    val eventTime: Long? = null,

    @Column(name = "stage", nullable = false, length = 50)
    val stage: String,

    @Column(name = "event_type", nullable = false, length = 80)
    val eventType: String,

    @Column(name = "decision", nullable = false, length = 20)
    val decision: String = "INFO",

    @Column(name = "leader_trade_id", length = 120)
    val leaderTradeId: String? = null,

    @Column(name = "market_id", length = 100)
    val marketId: String? = null,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "side", length = 20)
    val side: String? = null,

    @Column(name = "reason_code", length = 80)
    val reasonCode: String? = null,

    @Column(name = "reason_message", columnDefinition = "TEXT")
    val reasonMessage: String? = null,

    @Column(name = "detail_json", columnDefinition = "TEXT")
    val detailJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
