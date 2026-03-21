package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "trader_candidate_score_history",
    indexes = [
        Index(name = "idx_trader_candidate_score_history_address_created", columnList = "address, created_at"),
        Index(name = "idx_trader_candidate_score_history_candidate_created", columnList = "candidate_id, created_at")
    ]
)
data class TraderCandidateScoreHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "candidate_id")
    val candidateId: Long? = null,

    @Column(name = "address", nullable = false, length = 42)
    val address: String,

    @Column(name = "source", nullable = false, length = 50)
    val source: String = "recommendation",

    @Column(name = "recommendation_score")
    val recommendationScore: Int? = null,

    @Column(name = "risk_score")
    val riskScore: Int? = null,

    @Column(name = "low_risk", nullable = false)
    val lowRisk: Boolean = false,

    @Column(name = "estimated_roi_rate", precision = 18, scale = 6)
    val estimatedRoiRate: BigDecimal? = null,

    @Column(name = "estimated_drawdown_rate", precision = 18, scale = 6)
    val estimatedDrawdownRate: BigDecimal? = null,

    @Column(name = "market_concentration_rate", precision = 18, scale = 6)
    val marketConcentrationRate: BigDecimal? = null,

    @Column(name = "active_days")
    val activeDays: Int? = null,

    @Column(name = "current_position_count")
    val currentPositionCount: Int? = null,

    @Column(name = "estimated_total_pnl", precision = 36, scale = 8)
    val estimatedTotalPnl: BigDecimal? = null,

    @Column(name = "recent_trade_count", nullable = false)
    val recentTradeCount: Int = 0,

    @Column(name = "distinct_markets", nullable = false)
    val distinctMarkets: Int = 0,

    @Column(name = "last_seen_at")
    val lastSeenAt: Long? = null,

    @Column(name = "tags_json", columnDefinition = "TEXT")
    val tagsJson: String? = null,

    @Column(name = "reasons_json", columnDefinition = "TEXT")
    val reasonsJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
