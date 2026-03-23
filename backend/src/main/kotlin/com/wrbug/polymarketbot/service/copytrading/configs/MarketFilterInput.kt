package com.wrbug.polymarketbot.service.copytrading.configs

/**
 * 跟单过滤使用的市场元数据输入。
 * 统一承载关键字、分类、周期、系列、截止时间所需字段。
 */
data class MarketFilterInput(
    val title: String? = null,
    val category: String? = null,
    val endDate: Long? = null,
    val seriesSlugPrefix: String? = null,
    val intervalSeconds: Int? = null
)
