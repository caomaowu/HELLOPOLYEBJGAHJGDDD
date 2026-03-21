package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "trader_activity_event_history",
    indexes = [
        Index(name = "idx_trader_activity_event_history_trader_time", columnList = "trader_address,event_timestamp"),
        Index(name = "idx_trader_activity_event_history_market_time", columnList = "market_id,event_timestamp"),
        Index(name = "idx_trader_activity_event_history_tx_hash", columnList = "transaction_hash"),
        Index(name = "idx_trader_activity_event_history_created", columnList = "created_at")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_trader_activity_event_history_event_key", columnNames = ["event_key"])
    ]
)
data class TraderActivityEventHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "event_key", nullable = false, length = 128, unique = true)
    val eventKey: String,

    @Column(name = "source", nullable = false, length = 50)
    var source: String = "activity-ws",

    @Column(name = "trader_address", nullable = false, length = 42)
    val traderAddress: String,

    @Column(name = "display_name", length = 255)
    var displayName: String? = null,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "market_slug", length = 200)
    var marketSlug: String? = null,

    @Column(name = "asset", length = 100)
    var asset: String? = null,

    @Column(name = "transaction_hash", length = 100)
    var transactionHash: String? = null,

    @Column(name = "side", length = 16)
    var side: String? = null,

    @Column(name = "outcome", length = 50)
    var outcome: String? = null,

    @Column(name = "outcome_index")
    var outcomeIndex: Int? = null,

    @Column(name = "price", precision = 36, scale = 18)
    var price: BigDecimal? = null,

    @Column(name = "size", precision = 36, scale = 18)
    var size: BigDecimal? = null,

    @Column(name = "volume", precision = 36, scale = 8)
    var volume: BigDecimal? = null,

    @Column(name = "event_timestamp", nullable = false)
    var eventTimestamp: Long = System.currentTimeMillis(),

    @Column(name = "received_at", nullable = false)
    var receivedAt: Long = System.currentTimeMillis(),

    @Column(name = "normalized_json", columnDefinition = "TEXT")
    var normalizedJson: String? = null,

    @Column(name = "raw_payload_json", columnDefinition = "LONGTEXT")
    var rawPayloadJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
