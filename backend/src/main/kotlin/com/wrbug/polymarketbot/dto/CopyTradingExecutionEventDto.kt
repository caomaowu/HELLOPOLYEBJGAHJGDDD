package com.wrbug.polymarketbot.dto

/**
 * 执行事件列表请求
 */
data class CopyTradingExecutionEventListRequest(
    val copyTradingId: Long,
    val eventType: String? = null,
    val stage: String? = null,
    val source: String? = null,
    val status: String? = null,
    val page: Int? = 1,
    val limit: Int? = 20,
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * 执行事件单条记录
 */
data class CopyTradingExecutionEventDto(
    val id: Long,
    val copyTradingId: Long,
    val accountId: Long,
    val accountName: String?,
    val leaderId: Long,
    val leaderName: String?,
    val leaderTradeId: String?,
    val marketId: String?,
    val marketTitle: String?,
    val side: String?,
    val outcomeIndex: Int?,
    val outcome: String?,
    val source: String?,
    val stage: String,
    val eventType: String,
    val status: String,
    val leaderPrice: String?,
    val leaderQuantity: String?,
    val leaderOrderAmount: String?,
    val calculatedQuantity: String?,
    val orderPrice: String?,
    val orderQuantity: String?,
    val orderId: String?,
    val aggregationKey: String?,
    val aggregationTradeCount: Int?,
    val message: String,
    val detailJson: String?,
    val createdAt: Long
)

/**
 * 执行事件列表响应
 */
data class CopyTradingExecutionEventListResponse(
    val list: List<CopyTradingExecutionEventDto>,
    val total: Long,
    val page: Int,
    val limit: Int
)
