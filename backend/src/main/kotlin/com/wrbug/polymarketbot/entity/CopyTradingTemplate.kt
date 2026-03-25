package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 跟单模板实体
 */
@Entity
@Table(name = "copy_trading_templates")
data class CopyTradingTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "template_name", unique = true, nullable = false, length = 100)
    val templateName: String,  // 模板名称
    
    @Column(name = "copy_mode", nullable = false, length = 10)
    val copyMode: String = "RATIO",  // "RATIO" 或 "FIXED"
    
    @Column(name = "copy_ratio", nullable = false, precision = 20, scale = 8)
    val copyRatio: BigDecimal = BigDecimal.ONE,  // 仅在 copyMode="RATIO" 时生效
    
    @Column(name = "fixed_amount", precision = 20, scale = 8)
    val fixedAmount: BigDecimal? = null,  // 仅在 copyMode="FIXED" 时生效

    @Column(name = "adaptive_min_ratio", precision = 20, scale = 8)
    val adaptiveMinRatio: BigDecimal? = null,

    @Column(name = "adaptive_max_ratio", precision = 20, scale = 8)
    val adaptiveMaxRatio: BigDecimal? = null,

    @Column(name = "adaptive_threshold", precision = 20, scale = 8)
    val adaptiveThreshold: BigDecimal? = null,

    @Column(name = "multiplier_mode", nullable = false, length = 10)
    val multiplierMode: String = "NONE",

    @Column(name = "trade_multiplier", precision = 20, scale = 8)
    val tradeMultiplier: BigDecimal? = null,

    @Column(name = "tiered_multipliers", columnDefinition = "JSON")
    val tieredMultipliers: String? = null,

    @Column(name = "max_order_size", nullable = false, precision = 20, scale = 8)
    val maxOrderSize: BigDecimal = "1000".toSafeBigDecimal(),
    
    @Column(name = "min_order_size", nullable = false, precision = 20, scale = 8)
    val minOrderSize: BigDecimal = "1".toSafeBigDecimal(),
    
    @Column(name = "max_daily_loss", nullable = false, precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal = "10000".toSafeBigDecimal(),
    
    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 100,
    
    @Column(name = "price_tolerance", nullable = false, precision = 5, scale = 2)
    val priceTolerance: BigDecimal = "5".toSafeBigDecimal(),  // 百分比
    
    @Column(name = "delay_seconds", nullable = false)
    val delaySeconds: Int = 0,
    
    @Column(name = "poll_interval_seconds", nullable = false)
    val pollIntervalSeconds: Int = 5,  // 轮询间隔（仅在 WebSocket 不可用时使用）
    
    @Column(name = "use_websocket", nullable = false)
    val useWebSocket: Boolean = true,  // 是否优先使用 WebSocket 推送
    
    @Column(name = "websocket_reconnect_interval", nullable = false)
    val websocketReconnectInterval: Int = 5000,  // WebSocket 重连间隔（毫秒）
    
    @Column(name = "websocket_max_retries", nullable = false)
    val websocketMaxRetries: Int = 10,  // WebSocket 最大重试次数
    
    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,  // 是否支持跟单卖出
    
    // 过滤条件字段
    @Column(name = "min_order_depth", precision = 20, scale = 8)
    val minOrderDepth: BigDecimal? = null,  // 最小订单深度（USDC金额），NULL表示不启用
    
    @Column(name = "max_spread", precision = 20, scale = 8)
    val maxSpread: BigDecimal? = null,  // 最大价差（绝对价格），NULL表示不启用
    
    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = null,  // 最低价格（可选），NULL表示不限制最低价
    
    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = null,  // 最高价格（可选），NULL表示不限制最高价

    @Column(name = "market_category_mode", nullable = false, length = 20)
    val marketCategoryMode: String = "DISABLED",  // 市场分类过滤模式：DISABLED/WHITELIST/BLACKLIST

    @Column(name = "market_categories", columnDefinition = "JSON")
    val marketCategories: String? = null,  // 市场分类过滤列表（JSON数组）

    @Column(name = "market_interval_mode", nullable = false, length = 20)
    val marketIntervalMode: String = "DISABLED",  // 市场周期过滤模式：DISABLED/WHITELIST/BLACKLIST

    @Column(name = "market_intervals", columnDefinition = "JSON")
    val marketIntervals: String? = null,  // 市场周期过滤列表（JSON数组，单位秒）

    @Column(name = "market_series_mode", nullable = false, length = 20)
    val marketSeriesMode: String = "DISABLED",  // 市场系列过滤模式：DISABLED/WHITELIST/BLACKLIST

    @Column(name = "market_series", columnDefinition = "JSON")
    val marketSeries: String? = null,  // 市场系列过滤列表（JSON数组，如 btc-updown-15m）
    
    @Column(name = "push_filtered_orders", nullable = false)
    val pushFilteredOrders: Boolean = false,  // 推送已过滤订单（默认关闭）

    @Column(name = "max_daily_volume", precision = 20, scale = 8)
    val maxDailyVolume: BigDecimal? = null,

    @Column(name = "small_order_aggregation_enabled", nullable = false)
    val smallOrderAggregationEnabled: Boolean = false,

    @Column(name = "small_order_aggregation_window_seconds", nullable = false)
    val smallOrderAggregationWindowSeconds: Int = 300,

    @Column(name = "repeat_add_reduction_enabled", nullable = false)
    val repeatAddReductionEnabled: Boolean = false,

    @Column(name = "repeat_add_reduction_strategy", nullable = false, length = 20)
    val repeatAddReductionStrategy: String = "UNIFORM",

    @Column(name = "repeat_add_reduction_value_type", nullable = false, length = 20)
    val repeatAddReductionValueType: String = "PERCENT",

    @Column(name = "repeat_add_reduction_percent", precision = 10, scale = 4)
    val repeatAddReductionPercent: BigDecimal? = null,

    @Column(name = "repeat_add_reduction_fixed_amount", precision = 20, scale = 8)
    val repeatAddReductionFixedAmount: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

