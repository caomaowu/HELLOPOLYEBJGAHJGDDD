package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolLabelUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolItemDto
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolListRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolListResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryItemDto
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryRequest
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryResponse
import com.wrbug.polymarketbot.dto.LeaderMarketTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupItemDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupResponse
import com.wrbug.polymarketbot.entity.TraderCandidatePool
import com.wrbug.polymarketbot.entity.TraderCandidateScoreHistory
import com.wrbug.polymarketbot.entity.TraderMarketActivityPool
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.TraderCandidatePoolRepository
import com.wrbug.polymarketbot.repository.TraderCandidateScoreHistoryRepository
import com.wrbug.polymarketbot.repository.TraderMarketActivityPoolRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.parseStringArray
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Trader 候选池服务
 * 负责把全局 activity 流增量沉淀为可查询的候选池与市场活跃快照。
 */
@Service
class TraderCandidatePoolService(
    private val traderCandidatePoolRepository: TraderCandidatePoolRepository,
    private val traderCandidateScoreHistoryRepository: TraderCandidateScoreHistoryRepository,
    private val traderMarketActivityPoolRepository: TraderMarketActivityPoolRepository,
    private val leaderRepository: LeaderRepository,
    private val marketService: MarketService
) {

    private val logger = LoggerFactory.getLogger(TraderCandidatePoolService::class.java)

    private val pendingCandidates = ConcurrentHashMap<String, PendingCandidate>()
    private val pendingMarketActivities = ConcurrentHashMap<String, PendingMarketActivity>()

    fun isRealtimeDiscoveryEnabled(): Boolean = true

    fun recordActivityTrade(payload: ActivityTradePayload) {
        val traderAddress = extractTraderAddress(payload) ?: return
        val marketId = payload.conditionId.takeIf { it.isNotBlank() } ?: return
        val timestamp = normalizeTimestamp(payload.timestamp)
        val side = payload.side.uppercase()
        val displayName = payload.trader?.name?.takeIf { it.isNotBlank() } ?: payload.name?.takeIf { it.isNotBlank() }
        val volume = computeVolume(payload.price, payload.size)

        pendingCandidates.compute(traderAddress) { _, existing ->
            val candidate = existing ?: PendingCandidate(address = traderAddress)
            candidate.tradeCount += 1
            if (side == "BUY") candidate.buyCount += 1
            if (side == "SELL") candidate.sellCount += 1
            candidate.volume = candidate.volume.add(volume)
            candidate.displayName = displayName ?: candidate.displayName
            candidate.lastMarketId = marketId
            candidate.lastMarketSlug = payload.slug ?: candidate.lastMarketSlug
            candidate.marketIds += marketId
            candidate.firstSeenAt = minOf(candidate.firstSeenAt ?: timestamp, timestamp)
            candidate.lastSeenAt = maxOf(candidate.lastSeenAt ?: timestamp, timestamp)
            candidate
        }

        val key = "$marketId|$traderAddress"
        pendingMarketActivities.compute(key) { _, existing ->
            val item = existing ?: PendingMarketActivity(marketId = marketId, traderAddress = traderAddress)
            item.tradeCount += 1
            if (side == "BUY") item.buyCount += 1
            if (side == "SELL") item.sellCount += 1
            item.totalVolume = item.totalVolume.add(volume)
            item.displayName = displayName ?: item.displayName
            item.firstSeenAt = minOf(item.firstSeenAt ?: timestamp, timestamp)
            item.lastSeenAt = maxOf(item.lastSeenAt ?: timestamp, timestamp)
            item
        }
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 15000)
    @Transactional
    fun flushPendingSnapshots() {
        val candidateBatch = drainPendingCandidates()
        val marketBatch = drainPendingMarketActivities()
        if (candidateBatch.isEmpty() && marketBatch.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        if (candidateBatch.isNotEmpty()) {
            val existingCandidates = traderCandidatePoolRepository.findByAddressIn(candidateBatch.map { it.address }).associateBy { it.address }
            val marketIds = candidateBatch.mapNotNull { it.lastMarketId }.distinct()
            val marketMap = marketService.getMarkets(marketIds)
            val mergedCandidates = candidateBatch.map { pending ->
                val existing = existingCandidates[pending.address]
                val entity = existing ?: TraderCandidatePool(
                    address = pending.address,
                    firstSeenAt = pending.firstSeenAt ?: now,
                    lastSeenAt = pending.lastSeenAt ?: now,
                    createdAt = now,
                    updatedAt = now
                )
                entity.displayName = pending.displayName ?: entity.displayName
                entity.source = "activity-ws"
                entity.recentTradeCount += pending.tradeCount
                entity.recentBuyCount += pending.buyCount
                entity.recentSellCount += pending.sellCount
                entity.recentVolume = entity.recentVolume.add(pending.volume)
                entity.firstSeenAt = minOf(entity.firstSeenAt, pending.firstSeenAt ?: entity.firstSeenAt)
                entity.lastSeenAt = maxOf(entity.lastSeenAt, pending.lastSeenAt ?: entity.lastSeenAt)
                entity.lastMarketId = pending.lastMarketId ?: entity.lastMarketId
                entity.lastMarketSlug = pending.lastMarketSlug ?: entity.lastMarketSlug
                val lastMarket = pending.lastMarketId?.let { marketMap[it] }
                entity.lastMarketTitle = lastMarket?.title ?: entity.lastMarketTitle
                val trackedMarketIds = mergeTrackedMarkets(entity.trackedMarketIdsJson.parseStringArray(), pending.marketIds.toList())
                entity.trackedMarketIdsJson = trackedMarketIds.toJson()
                entity.distinctMarkets = trackedMarketIds.size
                entity.updatedAt = now
                entity
            }
            traderCandidatePoolRepository.saveAll(mergedCandidates)
        }

        if (marketBatch.isNotEmpty()) {
            val existingMarketItems = traderMarketActivityPoolRepository.findByMarketIdIn(marketBatch.map { it.marketId }.distinct())
                .associateBy { "${it.marketId}|${it.traderAddress}" }
            val mergedMarketItems = marketBatch.map { pending ->
                val key = "${pending.marketId}|${pending.traderAddress}"
                val existing = existingMarketItems[key]
                val entity = existing ?: TraderMarketActivityPool(
                    marketId = pending.marketId,
                    traderAddress = pending.traderAddress,
                    firstSeenAt = pending.firstSeenAt ?: now,
                    lastSeenAt = pending.lastSeenAt ?: now,
                    createdAt = now,
                    updatedAt = now
                )
                entity.displayName = pending.displayName ?: entity.displayName
                entity.tradeCount += pending.tradeCount
                entity.buyCount += pending.buyCount
                entity.sellCount += pending.sellCount
                entity.totalVolume = entity.totalVolume.add(pending.totalVolume)
                entity.firstSeenAt = minOf(entity.firstSeenAt, pending.firstSeenAt ?: entity.firstSeenAt)
                entity.lastSeenAt = maxOf(entity.lastSeenAt, pending.lastSeenAt ?: entity.lastSeenAt)
                entity.updatedAt = now
                entity
            }
            traderMarketActivityPoolRepository.saveAll(mergedMarketItems)
        }

        logger.debug("候选池批量 flush 完成: candidates={}, marketItems={}", candidateBatch.size, marketBatch.size)
    }

    fun updateEvaluationSnapshots(recommendations: List<LeaderCandidateRecommendationDto>) {
        if (recommendations.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = traderCandidatePoolRepository.findByAddressIn(recommendations.map { it.address }).associateBy { it.address }
        val savedEntities = traderCandidatePoolRepository.saveAll(recommendations.map { recommendation ->
            val entity = existing[recommendation.address] ?: TraderCandidatePool(
                address = recommendation.address,
                firstSeenAt = recommendation.lastSeenAt ?: now,
                lastSeenAt = recommendation.lastSeenAt ?: now,
                createdAt = now,
                updatedAt = now
            )
            entity.displayName = recommendation.displayName ?: entity.displayName
            entity.profileImage = recommendation.profileImage ?: entity.profileImage
            entity.recommendationScore = recommendation.recommendationScore
            entity.riskScore = recommendation.riskScore
            entity.lowRisk = recommendation.lowRisk
            entity.estimatedRoiRate = percentageToRatio(recommendation.estimatedRoiRate)
            entity.estimatedDrawdownRate = percentageToRatio(recommendation.estimatedDrawdownRate)
            entity.marketConcentrationRate = percentageToRatio(recommendation.marketConcentrationRate)
            entity.activeDays = recommendation.activeDays
            entity.currentPositionCount = recommendation.currentPositionCount
            entity.estimatedTotalPnl = recommendation.estimatedTotalPnl.toSafeBigDecimal()
            entity.lastEvaluatedAt = now
            entity.lastSeenAt = maxOf(entity.lastSeenAt, recommendation.lastSeenAt ?: entity.lastSeenAt)
            entity.updatedAt = now
            entity
        })
        val recommendationMap = recommendations.associateBy { normalizeAddress(it.address) }
        val histories = savedEntities.mapNotNull { entity ->
            val recommendation = recommendationMap[entity.address] ?: return@mapNotNull null
            TraderCandidateScoreHistory(
                candidateId = entity.id,
                address = entity.address,
                source = "recommendation",
                recommendationScore = entity.recommendationScore,
                riskScore = entity.riskScore,
                lowRisk = entity.lowRisk,
                estimatedRoiRate = entity.estimatedRoiRate,
                estimatedDrawdownRate = entity.estimatedDrawdownRate,
                marketConcentrationRate = entity.marketConcentrationRate,
                activeDays = entity.activeDays,
                currentPositionCount = entity.currentPositionCount,
                estimatedTotalPnl = entity.estimatedTotalPnl,
                recentTradeCount = recommendation.recentTradeCount,
                distinctMarkets = recommendation.distinctMarkets,
                lastSeenAt = recommendation.lastSeenAt,
                tagsJson = recommendation.tags.toJson(),
                reasonsJson = recommendation.reasons.toJson(),
                createdAt = now
            )
        }
        if (histories.isNotEmpty()) {
            traderCandidateScoreHistoryRepository.saveAll(histories)
        }
    }

    fun getCandidatePool(request: LeaderCandidatePoolListRequest): LeaderCandidatePoolListResponse {
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceIn(1, 100)
        val sort = if (request.lowRiskOnly == true) {
            Sort.by(
                Sort.Order.desc("recommendationScore"),
                Sort.Order.desc("lastSeenAt")
            )
        } else {
            Sort.by(Sort.Order.desc("lastSeenAt"))
        }
        val pageable = PageRequest.of(page - 1, limit, sort)
        val poolPage = traderCandidatePoolRepository.findAll(buildPoolSpecification(request), pageable)
        val leaderMap = leaderRepository.findAll().associateBy { it.leaderAddress.lowercase() }
        val marketMap = marketService.getMarkets(poolPage.content.mapNotNull { it.lastMarketId }.distinct())
        return LeaderCandidatePoolListResponse(
            list = poolPage.content.map { item -> toPoolItemDto(item, leaderMap, marketMap) },
            total = poolPage.totalElements,
            page = page,
            limit = limit
        )
    }

    @Transactional
    fun updateCandidateLabels(request: LeaderCandidatePoolLabelUpdateRequest): LeaderCandidatePoolItemDto {
        val address = normalizeAddress(request.address)
        require(address.startsWith("0x") && address.length == 42) { "地址格式不正确" }
        val now = System.currentTimeMillis()
        val entity = traderCandidatePoolRepository.findByAddress(address) ?: TraderCandidatePool(
            address = address,
            firstSeenAt = now,
            lastSeenAt = now,
            createdAt = now,
            updatedAt = now
        )
        request.favorite?.let { entity.favorite = it }
        request.blacklisted?.let { entity.blacklisted = it }
        if (request.manualNote != null) {
            entity.manualNote = request.manualNote.trim().ifBlank { null }
        }
        if (request.manualTags != null) {
            entity.manualTagsJson = request.manualTags
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .distinct()
                .toJson()
        }
        entity.updatedAt = now
        val saved = traderCandidatePoolRepository.save(entity)
        val leaderMap = leaderRepository.findAll().associateBy { it.leaderAddress.lowercase() }
        val marketMap = marketService.getMarkets(saved.lastMarketId?.let(::listOf).orEmpty())
        return toPoolItemDto(saved, leaderMap, marketMap)
    }

    fun getCandidateScoreHistory(request: LeaderCandidateScoreHistoryRequest): LeaderCandidateScoreHistoryResponse {
        val address = normalizeAddress(request.address)
        require(address.startsWith("0x") && address.length == 42) { "地址格式不正确" }
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceIn(1, 100)
        val historyPage = traderCandidateScoreHistoryRepository.findByAddressOrderByCreatedAtDesc(
            address,
            PageRequest.of(page - 1, limit)
        )
        return LeaderCandidateScoreHistoryResponse(
            list = historyPage.content.map { item ->
                LeaderCandidateScoreHistoryItemDto(
                    address = item.address,
                    source = item.source,
                    recommendationScore = item.recommendationScore,
                    riskScore = item.riskScore,
                    lowRisk = item.lowRisk,
                    estimatedRoiRate = item.estimatedRoiRate?.let(::formatRate),
                    estimatedDrawdownRate = item.estimatedDrawdownRate?.let(::formatRate),
                    marketConcentrationRate = item.marketConcentrationRate?.let(::formatRate),
                    activeDays = item.activeDays,
                    currentPositionCount = item.currentPositionCount,
                    estimatedTotalPnl = item.estimatedTotalPnl?.let(::formatDecimal),
                    recentTradeCount = item.recentTradeCount,
                    distinctMarkets = item.distinctMarkets,
                    lastSeenAt = item.lastSeenAt,
                    tags = item.tagsJson.parseStringArray(),
                    reasons = item.reasonsJson.parseStringArray(),
                    createdAt = item.createdAt
                )
            },
            total = historyPage.totalElements,
            page = page,
            limit = limit
        )
    }

    fun getMarketLookupFromPool(request: LeaderMarketTraderLookupRequest): LeaderMarketTraderLookupResponse? {
        if (request.preferPool != true) return null
        val marketItems = traderMarketActivityPoolRepository.findByMarketIdIn(request.marketIds)
        if (marketItems.isEmpty()) {
            return null
        }
        val grouped = marketItems.groupBy { it.marketId }
        if (!request.marketIds.all { grouped.containsKey(it) }) {
            return null
        }
        val leaderMap = leaderRepository.findAll().associateBy { it.leaderAddress.lowercase() }
        val marketMap = marketService.getMarkets(request.marketIds)
        return LeaderMarketTraderLookupResponse(
            source = "candidate-pool",
            list = request.marketIds.map { marketId ->
                val traders = grouped[marketId].orEmpty()
                    .asSequence()
                    .filter { item ->
                        item.tradeCount >= (request.minTradesPerTrader ?: 1) &&
                            (!(request.excludeExistingLeaders ?: false) || !leaderMap.containsKey(item.traderAddress))
                    }
                    .sortedWith(compareByDescending<TraderMarketActivityPool> { it.tradeCount }.thenByDescending { it.totalVolume })
                    .take(request.limitPerMarket ?: 20)
                    .map { item ->
                        val leader = leaderMap[item.traderAddress]
                        LeaderMarketTraderDto(
                            address = item.traderAddress,
                            displayName = item.displayName ?: leader?.leaderName,
                            existingLeaderId = leader?.id,
                            existingLeaderName = leader?.leaderName,
                            tradeCount = item.tradeCount,
                            buyCount = item.buyCount,
                            sellCount = item.sellCount,
                            totalVolume = formatDecimal(item.totalVolume),
                            firstSeenAt = item.firstSeenAt,
                            lastSeenAt = item.lastSeenAt
                        )
                    }.toList()
                LeaderMarketTraderLookupItemDto(
                    marketId = marketId,
                    marketTitle = marketMap[marketId]?.title,
                    marketSlug = marketMap[marketId]?.slug,
                    traderCount = traders.size,
                    list = traders
                )
            }
        )
    }

    private fun drainPendingCandidates(): List<PendingCandidate> {
        val drained = mutableListOf<PendingCandidate>()
        pendingCandidates.entries.toList().forEach { (key, value) ->
            if (pendingCandidates.remove(key, value)) {
                drained += value.copy(marketIds = LinkedHashSet(value.marketIds))
            }
        }
        return drained
    }

    private fun drainPendingMarketActivities(): List<PendingMarketActivity> {
        val drained = mutableListOf<PendingMarketActivity>()
        pendingMarketActivities.entries.toList().forEach { (key, value) ->
            if (pendingMarketActivities.remove(key, value)) {
                drained += value.copy()
            }
        }
        return drained
    }

    private fun extractTraderAddress(payload: ActivityTradePayload): String? {
        val raw = payload.trader?.address ?: payload.proxyWallet
        return raw?.let(::normalizeAddress)?.takeIf { it.startsWith("0x") && it.length == 42 }
    }

    private fun normalizeTimestamp(raw: Any?): Long {
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: System.currentTimeMillis()
            else -> System.currentTimeMillis()
        }
        return if (value < 1_000_000_000_000L) value * 1000 else value
    }

    private fun computeVolume(price: Any?, size: Any?): BigDecimal {
        return price.toSafeBigDecimal().multiply(size.toSafeBigDecimal()).setScale(8, RoundingMode.HALF_UP)
    }

    private fun mergeTrackedMarkets(existing: List<String>, pending: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        existing.forEach { merged += it }
        pending.forEach { merged += it }
        val list = merged.toList()
        return if (list.size <= 50) list else list.takeLast(50)
    }

    private fun buildPoolSpecification(request: LeaderCandidatePoolListRequest): Specification<TraderCandidatePool> {
        return Specification.where<TraderCandidatePool>(null)
            .and { root, _, cb ->
                if (request.lowRiskOnly == true) cb.isTrue(root.get("lowRisk")) else cb.conjunction()
            }
            .and { root, _, cb ->
                if (request.favoriteOnly == true) cb.isTrue(root.get("favorite")) else cb.conjunction()
            }
            .and { root, _, cb ->
                if (request.includeBlacklisted == true) cb.conjunction() else cb.isFalse(root.get("blacklisted"))
            }
    }

    private fun toPoolItemDto(
        item: TraderCandidatePool,
        leaderMap: Map<String, com.wrbug.polymarketbot.entity.Leader>,
        marketMap: Map<String, com.wrbug.polymarketbot.entity.Market>
    ): LeaderCandidatePoolItemDto {
        val leader = leaderMap[item.address]
        val market = item.lastMarketId?.let { marketMap[it] }
        return LeaderCandidatePoolItemDto(
            address = item.address,
            displayName = item.displayName,
            profileImage = item.profileImage,
            existingLeaderId = leader?.id,
            existingLeaderName = leader?.leaderName,
            recentTradeCount = item.recentTradeCount,
            recentBuyCount = item.recentBuyCount,
            recentSellCount = item.recentSellCount,
            recentVolume = formatDecimal(item.recentVolume),
            distinctMarkets = item.distinctMarkets,
            lastMarketId = item.lastMarketId,
            lastMarketTitle = item.lastMarketTitle ?: market?.title,
            lastMarketSlug = item.lastMarketSlug ?: market?.slug,
            favorite = item.favorite,
            blacklisted = item.blacklisted,
            manualNote = item.manualNote,
            manualTags = item.manualTagsJson.parseStringArray(),
            recommendationScore = item.recommendationScore,
            riskScore = item.riskScore,
            lowRisk = item.lowRisk,
            estimatedRoiRate = item.estimatedRoiRate?.let(::formatRate),
            estimatedDrawdownRate = item.estimatedDrawdownRate?.let(::formatRate),
            marketConcentrationRate = item.marketConcentrationRate?.let(::formatRate),
            lastEvaluatedAt = item.lastEvaluatedAt,
            firstSeenAt = item.firstSeenAt,
            lastSeenAt = item.lastSeenAt
        )
    }

    private fun percentageToRatio(value: String?): BigDecimal? {
        if (value.isNullOrBlank()) return null
        return value.toSafeBigDecimal().divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
    }

    private fun formatDecimal(value: BigDecimal): String {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun formatRate(value: BigDecimal): String {
        return value.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun normalizeAddress(address: String): String = address.trim().lowercase()

    private data class PendingCandidate(
        val address: String,
        var displayName: String? = null,
        var tradeCount: Int = 0,
        var buyCount: Int = 0,
        var sellCount: Int = 0,
        var volume: BigDecimal = BigDecimal.ZERO,
        var firstSeenAt: Long? = null,
        var lastSeenAt: Long? = null,
        var lastMarketId: String? = null,
        var lastMarketSlug: String? = null,
        var marketIds: LinkedHashSet<String> = LinkedHashSet()
    )

    private data class PendingMarketActivity(
        val marketId: String,
        val traderAddress: String,
        var displayName: String? = null,
        var tradeCount: Int = 0,
        var buyCount: Int = 0,
        var sellCount: Int = 0,
        var totalVolume: BigDecimal = BigDecimal.ZERO,
        var firstSeenAt: Long? = null,
        var lastSeenAt: Long? = null
    )
}
