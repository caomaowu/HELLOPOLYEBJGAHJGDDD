package com.wrbug.polymarketbot.dto

/**
 * Leader 发现基础筛选请求
 */
data class LeaderDiscoveryBaseRequest(
    val leaderIds: List<Long>? = null,
    val seedAddresses: List<String>? = null,
    val days: Int? = 7,
    val maxSeedMarkets: Int? = 20,
    val marketTradeLimit: Int? = 120,
    val traderLimit: Int? = 30,
    val excludeExistingLeaders: Boolean? = true
)

/**
 * Trader 扫描请求
 */
data class LeaderTraderScanRequest(
    val leaderIds: List<Long>? = null,
    val seedAddresses: List<String>? = null,
    val days: Int? = 7,
    val maxSeedMarkets: Int? = 20,
    val marketTradeLimit: Int? = 120,
    val traderLimit: Int? = 30,
    val excludeExistingLeaders: Boolean? = true
)

/**
 * 候选 Leader 推荐请求
 */
data class LeaderCandidateRecommendRequest(
    val leaderIds: List<Long>? = null,
    val seedAddresses: List<String>? = null,
    val candidateAddresses: List<String>? = null,
    val days: Int? = 7,
    val maxSeedMarkets: Int? = 20,
    val marketTradeLimit: Int? = 120,
    val traderLimit: Int? = 20,
    val excludeExistingLeaders: Boolean? = true,
    val minTrades: Int? = 8,
    val maxOpenPositions: Int? = 8,
    val maxMarketConcentrationRate: Double? = 0.45,
    val maxEstimatedDrawdownRate: Double? = 0.18,
    val maxRiskScore: Int? = 45,
    val lowRiskOnly: Boolean? = false
)

/**
 * 按市场反查活跃 Trader 请求
 */
data class LeaderMarketTraderLookupRequest(
    val marketIds: List<String>,
    val days: Int? = 7,
    val limitPerMarket: Int? = 20,
    val minTradesPerTrader: Int? = 1,
    val excludeExistingLeaders: Boolean? = false,
    val preferPool: Boolean? = true
)

/**
 * Leader 发现用的市场摘要
 */
data class LeaderDiscoveryMarketDto(
    val marketId: String,
    val title: String?,
    val slug: String?,
    val category: String?,
    val tradeCount: Int,
    val totalVolume: String,
    val lastSeenAt: Long?
)

/**
 * Trader 扫描结果
 */
data class LeaderDiscoveredTraderDto(
    val address: String,
    val displayName: String?,
    val profileImage: String? = null,
    val existingLeaderId: Long? = null,
    val existingLeaderName: String? = null,
    val recentTradeCount: Int,
    val recentBuyCount: Int,
    val recentSellCount: Int,
    val recentVolume: String,
    val distinctMarkets: Int,
    val sourceLeaderIds: List<Long>,
    val sampleMarkets: List<LeaderDiscoveryMarketDto>,
    val firstSeenAt: Long?,
    val lastSeenAt: Long?
)

/**
 * Trader 扫描响应
 */
data class LeaderTraderScanResponse(
    val seedAddresses: List<String>,
    val seedMarketCount: Int,
    val estimated: Boolean = true,
    val list: List<LeaderDiscoveredTraderDto>
)

/**
 * 候选 Leader 推荐结果
 */
data class LeaderCandidateRecommendationDto(
    val address: String,
    val displayName: String?,
    val profileImage: String? = null,
    val existingLeaderId: Long? = null,
    val existingLeaderName: String? = null,
    val recentTradeCount: Int,
    val distinctMarkets: Int,
    val activeDays: Int,
    val recentVolume: String,
    val currentPositionCount: Int,
    val currentPositionValue: String,
    val estimatedTotalBought: String,
    val estimatedRealizedPnl: String,
    val estimatedUnrealizedPnl: String,
    val estimatedTotalPnl: String,
    val estimatedRoiRate: String,
    val estimatedDrawdownRate: String,
    val marketConcentrationRate: String,
    val riskScore: Int,
    val recommendationScore: Int,
    val lowRisk: Boolean,
    val tags: List<String>,
    val reasons: List<String>,
    val sampleMarkets: List<LeaderDiscoveryMarketDto>,
    val lastSeenAt: Long?
)

/**
 * 候选 Leader 推荐响应
 */
data class LeaderCandidateRecommendResponse(
    val seedAddresses: List<String>,
    val estimated: Boolean = true,
    val list: List<LeaderCandidateRecommendationDto>
)

/**
 * 按市场反查的 Trader 结果
 */
data class LeaderMarketTraderDto(
    val address: String,
    val displayName: String?,
    val existingLeaderId: Long? = null,
    val existingLeaderName: String? = null,
    val tradeCount: Int,
    val buyCount: Int,
    val sellCount: Int,
    val totalVolume: String,
    val firstSeenAt: Long?,
    val lastSeenAt: Long?
)

/**
 * 单个市场的 Trader 反查结果
 */
data class LeaderMarketTraderLookupItemDto(
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val traderCount: Int,
    val list: List<LeaderMarketTraderDto>
)

/**
 * 按市场反查活跃 Trader 响应
 */
data class LeaderMarketTraderLookupResponse(
    val estimated: Boolean = true,
    val source: String = "data-api",
    val list: List<LeaderMarketTraderLookupItemDto>
)

/**
 * 候选池列表请求
 */
data class LeaderCandidatePoolListRequest(
    val page: Int? = 1,
    val limit: Int? = 20,
    val lowRiskOnly: Boolean? = false,
    val favoriteOnly: Boolean? = false,
    val includeBlacklisted: Boolean? = false
)

/**
 * 候选池快照条目
 */
data class LeaderCandidatePoolItemDto(
    val address: String,
    val displayName: String?,
    val profileImage: String? = null,
    val existingLeaderId: Long? = null,
    val existingLeaderName: String? = null,
    val recentTradeCount: Int,
    val recentBuyCount: Int,
    val recentSellCount: Int,
    val recentVolume: String,
    val distinctMarkets: Int,
    val lastMarketId: String? = null,
    val lastMarketTitle: String? = null,
    val lastMarketSlug: String? = null,
    val favorite: Boolean = false,
    val blacklisted: Boolean = false,
    val manualNote: String? = null,
    val manualTags: List<String> = emptyList(),
    val recommendationScore: Int? = null,
    val riskScore: Int? = null,
    val lowRisk: Boolean,
    val estimatedRoiRate: String? = null,
    val estimatedDrawdownRate: String? = null,
    val marketConcentrationRate: String? = null,
    val lastEvaluatedAt: Long? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

/**
 * 候选池列表响应
 */
data class LeaderCandidatePoolListResponse(
    val list: List<LeaderCandidatePoolItemDto>,
    val total: Long,
    val page: Int,
    val limit: Int
)

/**
 * 候选池人工标注更新请求
 */
data class LeaderCandidatePoolLabelUpdateRequest(
    val address: String,
    val favorite: Boolean? = null,
    val blacklisted: Boolean? = null,
    val manualNote: String? = null,
    val manualTags: List<String>? = null
)

/**
 * 候选评分历史查询请求
 */
data class LeaderCandidateScoreHistoryRequest(
    val address: String,
    val page: Int? = 1,
    val limit: Int? = 20
)

/**
 * 候选评分历史条目
 */
data class LeaderCandidateScoreHistoryItemDto(
    val address: String,
    val source: String,
    val recommendationScore: Int? = null,
    val riskScore: Int? = null,
    val lowRisk: Boolean,
    val estimatedRoiRate: String? = null,
    val estimatedDrawdownRate: String? = null,
    val marketConcentrationRate: String? = null,
    val activeDays: Int? = null,
    val currentPositionCount: Int? = null,
    val estimatedTotalPnl: String? = null,
    val recentTradeCount: Int,
    val distinctMarkets: Int,
    val lastSeenAt: Long? = null,
    val tags: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val createdAt: Long
)

/**
 * 候选评分历史响应
 */
data class LeaderCandidateScoreHistoryResponse(
    val list: List<LeaderCandidateScoreHistoryItemDto>,
    val total: Long,
    val page: Int,
    val limit: Int
)
