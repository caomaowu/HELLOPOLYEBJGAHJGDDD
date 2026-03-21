package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "trader_candidate_pool",
    indexes = [
        Index(name = "idx_trader_candidate_pool_last_seen", columnList = "last_seen_at"),
        Index(name = "idx_trader_candidate_pool_low_risk", columnList = "low_risk"),
        Index(name = "idx_trader_candidate_pool_recommendation", columnList = "recommendation_score"),
        Index(name = "idx_trader_candidate_pool_favorite", columnList = "favorite"),
        Index(name = "idx_trader_candidate_pool_blacklisted", columnList = "blacklisted")
    ]
)
data class TraderCandidatePool(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "address", nullable = false, unique = true, length = 42)
    val address: String,

    @Column(name = "display_name", length = 255)
    var displayName: String? = null,

    @Column(name = "profile_image", length = 500)
    var profileImage: String? = null,

    @Column(name = "source", nullable = false, length = 50)
    var source: String = "activity-ws",

    @Column(name = "recent_trade_count", nullable = false)
    var recentTradeCount: Int = 0,

    @Column(name = "recent_buy_count", nullable = false)
    var recentBuyCount: Int = 0,

    @Column(name = "recent_sell_count", nullable = false)
    var recentSellCount: Int = 0,

    @Column(name = "recent_volume", nullable = false, precision = 36, scale = 8)
    var recentVolume: BigDecimal = BigDecimal.ZERO,

    @Column(name = "distinct_markets", nullable = false)
    var distinctMarkets: Int = 0,

    @Column(name = "tracked_market_ids_json", columnDefinition = "TEXT")
    var trackedMarketIdsJson: String? = null,

    @Column(name = "last_market_id", length = 100)
    var lastMarketId: String? = null,

    @Column(name = "last_market_title", length = 500)
    var lastMarketTitle: String? = null,

    @Column(name = "last_market_slug", length = 200)
    var lastMarketSlug: String? = null,

    @Column(name = "favorite", nullable = false)
    var favorite: Boolean = false,

    @Column(name = "blacklisted", nullable = false)
    var blacklisted: Boolean = false,

    @Column(name = "manual_note", columnDefinition = "TEXT")
    var manualNote: String? = null,

    @Column(name = "manual_tags_json", columnDefinition = "TEXT")
    var manualTagsJson: String? = null,

    @Column(name = "recommendation_score")
    var recommendationScore: Int? = null,

    @Column(name = "risk_score")
    var riskScore: Int? = null,

    @Column(name = "low_risk", nullable = false)
    var lowRisk: Boolean = false,

    @Column(name = "estimated_roi_rate", precision = 18, scale = 6)
    var estimatedRoiRate: BigDecimal? = null,

    @Column(name = "estimated_drawdown_rate", precision = 18, scale = 6)
    var estimatedDrawdownRate: BigDecimal? = null,

    @Column(name = "market_concentration_rate", precision = 18, scale = 6)
    var marketConcentrationRate: BigDecimal? = null,

    @Column(name = "active_days")
    var activeDays: Int? = null,

    @Column(name = "current_position_count")
    var currentPositionCount: Int? = null,

    @Column(name = "estimated_total_pnl", precision = 36, scale = 8)
    var estimatedTotalPnl: BigDecimal? = null,

    @Column(name = "last_evaluated_at")
    var lastEvaluatedAt: Long? = null,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Long = System.currentTimeMillis(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Long = System.currentTimeMillis(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
