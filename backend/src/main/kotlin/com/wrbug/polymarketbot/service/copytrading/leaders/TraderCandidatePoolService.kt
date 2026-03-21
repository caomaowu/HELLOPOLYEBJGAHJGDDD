package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.dto.ActivityTrader
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolBatchLabelUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolBatchLabelUpdateResponse
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolLabelUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolItemDto
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolListRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolListResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryByAddressRequest
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryBackfillItemDto
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryByMarketRequest
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryItemDto
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryItemDto
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryRequest
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryResponse
import com.wrbug.polymarketbot.dto.LeaderMarketTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupItemDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupResponse
import com.wrbug.polymarketbot.entity.TraderActivityEventHistory
import com.wrbug.polymarketbot.entity.TraderCandidatePool
import com.wrbug.polymarketbot.entity.TraderCandidateScoreHistory
import com.wrbug.polymarketbot.entity.TraderMarketActivityPool
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.TraderActivityEventHistoryRepository
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
import java.security.MessageDigest
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
    private val traderActivityEventHistoryRepository: TraderActivityEventHistoryRepository,
    private val leaderRepository: LeaderRepository,
    private val marketService: MarketService
) {

    data class CandidateLabelSnapshot(
        val address: String,
        val displayName: String?,
        val profileImage: String?,
        val favorite: Boolean,
        val blacklisted: Boolean,
        val manualNote: String?,
        val manualTags: List<String>
    )

    private val logger = LoggerFactory.getLogger(TraderCandidatePoolService::class.java)

    private val pendingCandidates = ConcurrentHashMap<String, PendingCandidate>()
    private val pendingMarketActivities = ConcurrentHashMap<String, PendingMarketActivity>()
    private val pendingActivityEvents = ConcurrentHashMap<String, PendingActivityEvent>()

    fun isRealtimeDiscoveryEnabled(): Boolean = true

    fun recordActivityTrade(payload: ActivityTradePayload) {
        val pendingEvent = buildPendingActivityEvent(payload, source = "activity-ws", receivedAt = System.currentTimeMillis()) ?: return
        val added = pendingActivityEvents.putIfAbsent(pendingEvent.eventKey, pendingEvent) == null
        if (!added) {
            return
        }
        accumulatePendingCandidate(pendingEvent)
        accumulatePendingMarketActivity(pendingEvent)
    }

    @Transactional
    fun backfillActivityHistory(
        address: String,
        activities: List<UserActivityResponse>,
        source: String = "data-api-backfill"
    ): LeaderActivityHistoryBackfillItemDto {
        val normalizedAddress = normalizeAddress(address)
        val normalizedEvents = activities.asSequence()
            .mapNotNull { activity -> buildPendingActivityEvent(normalizedAddress, activity, source) }
            .distinctBy { it.eventKey }
            .toList()
        if (normalizedEvents.isEmpty()) {
            return LeaderActivityHistoryBackfillItemDto(
                address = normalizedAddress,
                fetchedTrades = 0,
                insertedEvents = 0,
                skippedEvents = 0
            )
        }

        val existingEventKeys = traderActivityEventHistoryRepository.findByEventKeyIn(normalizedEvents.map { it.eventKey })
            .map { it.eventKey }
            .toSet()
        val newEvents = normalizedEvents.filterNot { it.eventKey in existingEventKeys }
        val now = System.currentTimeMillis()

        upsertCandidateBatch(buildCandidateBatchFromEvents(newEvents), now)
        upsertMarketBatch(buildMarketBatchFromEvents(newEvents), now)
        insertEventBatch(newEvents, now)

        return LeaderActivityHistoryBackfillItemDto(
            address = normalizedAddress,
            fetchedTrades = normalizedEvents.size,
            insertedEvents = newEvents.size,
            skippedEvents = normalizedEvents.size - newEvents.size,
            firstEventTime = normalizedEvents.minOfOrNull { it.eventTimestamp },
            lastEventTime = normalizedEvents.maxOfOrNull { it.eventTimestamp }
        )
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 15000)
    @Transactional
    fun flushPendingSnapshots() {
        val candidateBatch = drainPendingCandidates()
        val marketBatch = drainPendingMarketActivities()
        val eventBatch = drainPendingActivityEvents()
        if (candidateBatch.isEmpty() && marketBatch.isEmpty() && eventBatch.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        upsertCandidateBatch(candidateBatch, now)
        upsertMarketBatch(marketBatch, now)
        insertEventBatch(eventBatch, now)

        logger.debug(
            "候选池批量 flush 完成: candidates={}, marketItems={}, events={}",
            candidateBatch.size,
            marketBatch.size,
            eventBatch.size
        )
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
        applyLabelUpdate(
            entity = entity,
            favorite = request.favorite,
            blacklisted = request.blacklisted,
            manualNote = request.manualNote,
            manualTags = request.manualTags,
            updatedAt = now
        )
        val saved = traderCandidatePoolRepository.save(entity)
        val leaderMap = leaderRepository.findAll().associateBy { it.leaderAddress.lowercase() }
        val marketMap = marketService.getMarkets(saved.lastMarketId?.let(::listOf).orEmpty())
        return toPoolItemDto(saved, leaderMap, marketMap)
    }

    @Transactional
    fun updateCandidateLabelsBatch(request: LeaderCandidatePoolBatchLabelUpdateRequest): LeaderCandidatePoolBatchLabelUpdateResponse {
        val normalizedAddresses = request.addresses
            .map(::normalizeAddress)
            .filter { it.startsWith("0x") && it.length == 42 }
            .distinct()
        require(normalizedAddresses.isNotEmpty()) { "请至少提供一个有效地址" }
        val now = System.currentTimeMillis()
        val existing = traderCandidatePoolRepository.findByAddressIn(normalizedAddresses).associateBy { it.address }
        val updatedEntities = normalizedAddresses.map { address ->
            val entity = existing[address] ?: TraderCandidatePool(
                address = address,
                firstSeenAt = now,
                lastSeenAt = now,
                createdAt = now,
                updatedAt = now
            )
            applyLabelUpdate(
                entity = entity,
                favorite = request.favorite,
                blacklisted = request.blacklisted,
                manualNote = request.manualNote,
                manualTags = request.manualTags,
                updatedAt = now
            )
            entity
        }
        val saved = traderCandidatePoolRepository.saveAll(updatedEntities)
        val leaderMap = leaderRepository.findAll().associateBy { it.leaderAddress.lowercase() }
        val marketMap = marketService.getMarkets(saved.mapNotNull { it.lastMarketId }.distinct())
        return LeaderCandidatePoolBatchLabelUpdateResponse(
            updatedCount = saved.size,
            list = saved.map { toPoolItemDto(it, leaderMap, marketMap) }
        )
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

    fun getActivityHistoryByAddress(request: LeaderActivityHistoryByAddressRequest): LeaderActivityHistoryResponse {
        val address = normalizeAddress(request.address)
        require(address.startsWith("0x") && address.length == 42) { "地址格式不正确" }
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceIn(1, 200)
        val includeRaw = request.includeRaw == true
        val pageable = PageRequest.of(
            page - 1,
            limit,
            Sort.by(
                Sort.Order.desc("eventTimestamp"),
                Sort.Order.desc("id")
            )
        )
        val historyPage = traderActivityEventHistoryRepository.findAll(
            Specification.where<TraderActivityEventHistory> { root, _, cb ->
                cb.equal(root.get<String>("traderAddress"), address)
            }
                .and(buildTimestampSpecification(request.startTime, request.endTime)),
            pageable
        )
        return buildActivityHistoryResponse(historyPage.content, historyPage.totalElements, page, limit, includeRaw)
    }

    fun getActivityHistoryByMarket(request: LeaderActivityHistoryByMarketRequest): LeaderActivityHistoryResponse {
        val marketId = request.marketId.trim()
        require(marketId.isNotBlank()) { "marketId 不能为空" }
        val traderAddress = request.traderAddress
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeAddress)
        if (traderAddress != null) {
            require(traderAddress.startsWith("0x") && traderAddress.length == 42) { "traderAddress 格式不正确" }
        }
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceIn(1, 200)
        val includeRaw = request.includeRaw == true
        val pageable = PageRequest.of(
            page - 1,
            limit,
            Sort.by(
                Sort.Order.desc("eventTimestamp"),
                Sort.Order.desc("id")
            )
        )
        val historyPage = traderActivityEventHistoryRepository.findAll(
            Specification.where<TraderActivityEventHistory> { root, _, cb ->
                cb.equal(root.get<String>("marketId"), marketId)
            }
                .and(
                    Specification<TraderActivityEventHistory> { root, _, cb ->
                        if (traderAddress == null) cb.conjunction() else cb.equal(root.get<String>("traderAddress"), traderAddress)
                    }
                )
                .and(buildTimestampSpecification(request.startTime, request.endTime)),
            pageable
        )
        return buildActivityHistoryResponse(historyPage.content, historyPage.totalElements, page, limit, includeRaw)
    }

    fun getMarketLookupFromPool(request: LeaderMarketTraderLookupRequest): LeaderMarketTraderLookupResponse? {
        if (request.preferPool != true) return null
        val marketItems = traderMarketActivityPoolRepository.findByMarketIdIn(request.marketIds)
        if (marketItems.isEmpty()) {
            return null
        }
        val cutoffTime = System.currentTimeMillis() - (request.days ?: 7) * 24L * 60 * 60 * 1000
        val grouped = marketItems.groupBy { it.marketId }
        if (!request.marketIds.all { grouped.containsKey(it) }) {
            return null
        }
        val blacklistedAddresses = if (request.excludeBlacklistedTraders != false) {
            findBlacklistedAddresses(marketItems.map { it.traderAddress })
        } else {
            emptySet()
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
                            item.lastSeenAt >= cutoffTime &&
                            item.traderAddress !in blacklistedAddresses &&
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

    private fun upsertCandidateBatch(candidateBatch: List<PendingCandidate>, now: Long) {
        if (candidateBatch.isEmpty()) return
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

    private fun upsertMarketBatch(marketBatch: List<PendingMarketActivity>, now: Long) {
        if (marketBatch.isEmpty()) return
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

    private fun insertEventBatch(eventBatch: List<PendingActivityEvent>, now: Long) {
        if (eventBatch.isEmpty()) return
        val existingEventKeys = traderActivityEventHistoryRepository.findByEventKeyIn(eventBatch.map { it.eventKey })
            .map { it.eventKey }
            .toSet()
        val entities = eventBatch.asSequence()
            .filter { it.eventKey !in existingEventKeys }
            .map { pending ->
                TraderActivityEventHistory(
                    eventKey = pending.eventKey,
                    source = pending.source,
                    traderAddress = pending.traderAddress,
                    displayName = pending.displayName,
                    marketId = pending.marketId,
                    marketSlug = pending.marketSlug,
                    asset = pending.asset,
                    transactionHash = pending.transactionHash,
                    side = pending.side,
                    outcome = pending.outcome,
                    outcomeIndex = pending.outcomeIndex,
                    price = pending.price,
                    size = pending.size,
                    volume = pending.volume,
                    eventTimestamp = pending.eventTimestamp,
                    receivedAt = pending.receivedAt,
                    normalizedJson = pending.normalizedJson,
                    rawPayloadJson = pending.rawPayloadJson,
                    createdAt = now
                )
            }.toList()
        if (entities.isNotEmpty()) {
            traderActivityEventHistoryRepository.saveAll(entities)
        }
    }

    private fun buildCandidateBatchFromEvents(events: List<PendingActivityEvent>): List<PendingCandidate> {
        val candidateMap = linkedMapOf<String, PendingCandidate>()
        events.forEach { event ->
            val candidate = candidateMap.getOrPut(event.traderAddress) { PendingCandidate(address = event.traderAddress) }
            candidate.tradeCount += 1
            if (event.side == "BUY") candidate.buyCount += 1
            if (event.side == "SELL") candidate.sellCount += 1
            candidate.volume = candidate.volume.add(event.volume ?: BigDecimal.ZERO)
            candidate.displayName = event.displayName ?: candidate.displayName
            candidate.lastMarketId = event.marketId
            candidate.lastMarketSlug = event.marketSlug ?: candidate.lastMarketSlug
            candidate.marketIds += event.marketId
            candidate.firstSeenAt = minOf(candidate.firstSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            candidate.lastSeenAt = maxOf(candidate.lastSeenAt ?: event.eventTimestamp, event.eventTimestamp)
        }
        return candidateMap.values.toList()
    }

    private fun buildMarketBatchFromEvents(events: List<PendingActivityEvent>): List<PendingMarketActivity> {
        val marketMap = linkedMapOf<String, PendingMarketActivity>()
        events.forEach { event ->
            val key = "${event.marketId}|${event.traderAddress}"
            val item = marketMap.getOrPut(key) {
                PendingMarketActivity(marketId = event.marketId, traderAddress = event.traderAddress)
            }
            item.tradeCount += 1
            if (event.side == "BUY") item.buyCount += 1
            if (event.side == "SELL") item.sellCount += 1
            item.totalVolume = item.totalVolume.add(event.volume ?: BigDecimal.ZERO)
            item.displayName = event.displayName ?: item.displayName
            item.firstSeenAt = minOf(item.firstSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            item.lastSeenAt = maxOf(item.lastSeenAt ?: event.eventTimestamp, event.eventTimestamp)
        }
        return marketMap.values.toList()
    }

    private fun accumulatePendingCandidate(event: PendingActivityEvent) {
        pendingCandidates.compute(event.traderAddress) { _, existing ->
            val candidate = existing ?: PendingCandidate(address = event.traderAddress)
            candidate.tradeCount += 1
            if (event.side == "BUY") candidate.buyCount += 1
            if (event.side == "SELL") candidate.sellCount += 1
            candidate.volume = candidate.volume.add(event.volume ?: BigDecimal.ZERO)
            candidate.displayName = event.displayName ?: candidate.displayName
            candidate.lastMarketId = event.marketId
            candidate.lastMarketSlug = event.marketSlug ?: candidate.lastMarketSlug
            candidate.marketIds += event.marketId
            candidate.firstSeenAt = minOf(candidate.firstSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            candidate.lastSeenAt = maxOf(candidate.lastSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            candidate
        }
    }

    private fun accumulatePendingMarketActivity(event: PendingActivityEvent) {
        val key = "${event.marketId}|${event.traderAddress}"
        pendingMarketActivities.compute(key) { _, existing ->
            val item = existing ?: PendingMarketActivity(marketId = event.marketId, traderAddress = event.traderAddress)
            item.tradeCount += 1
            if (event.side == "BUY") item.buyCount += 1
            if (event.side == "SELL") item.sellCount += 1
            item.totalVolume = item.totalVolume.add(event.volume ?: BigDecimal.ZERO)
            item.displayName = event.displayName ?: item.displayName
            item.firstSeenAt = minOf(item.firstSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            item.lastSeenAt = maxOf(item.lastSeenAt ?: event.eventTimestamp, event.eventTimestamp)
            item
        }
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

    private fun drainPendingActivityEvents(): List<PendingActivityEvent> {
        val drained = mutableListOf<PendingActivityEvent>()
        pendingActivityEvents.entries.toList().forEach { (key, value) ->
            if (pendingActivityEvents.remove(key, value)) {
                drained += value.copy()
            }
        }
        return drained
    }

    private fun buildEventKey(
        traderAddress: String,
        marketId: String,
        payload: ActivityTradePayload,
        eventTimestamp: Long
    ): String {
        val txHash = payload.transactionHash?.trim()?.lowercase().orEmpty()
        if (txHash.isNotBlank()) {
            return "tx:$txHash"
        }
        val seed = listOf(
            traderAddress,
            marketId,
            payload.asset,
            payload.side.uppercase(),
            eventTimestamp.toString(),
            payload.price?.toString().orEmpty(),
            payload.size?.toString().orEmpty(),
            payload.outcome.orEmpty(),
            payload.outcomeIndex?.toString().orEmpty()
        ).joinToString("|")
        return "fp:${sha256(seed)}"
    }

    private fun buildPendingActivityEvent(
        payload: ActivityTradePayload,
        source: String,
        receivedAt: Long
    ): PendingActivityEvent? {
        val traderAddress = extractTraderAddress(payload) ?: return null
        val marketId = payload.conditionId.takeIf { it.isNotBlank() } ?: return null
        val timestamp = normalizeTimestamp(payload.timestamp)
        val side = payload.side.uppercase()
        val displayName = payload.trader?.name?.takeIf { it.isNotBlank() } ?: payload.name?.takeIf { it.isNotBlank() }
        val price = payload.price.toSafeBigDecimal()
        val size = payload.size.toSafeBigDecimal()
        val volume = computeVolume(payload.price, payload.size)
        val eventKey = buildEventKey(
            traderAddress = traderAddress,
            marketId = marketId,
            payload = payload,
            eventTimestamp = timestamp
        )
        return PendingActivityEvent(
            eventKey = eventKey,
            source = source,
            traderAddress = traderAddress,
            displayName = displayName,
            marketId = marketId,
            marketSlug = payload.slug,
            asset = payload.asset.takeIf { it.isNotBlank() },
            transactionHash = payload.transactionHash?.takeIf { it.isNotBlank() },
            side = side.takeIf { it == "BUY" || it == "SELL" },
            outcome = payload.outcome,
            outcomeIndex = payload.outcomeIndex,
            price = price,
            size = size,
            volume = volume,
            eventTimestamp = timestamp,
            receivedAt = receivedAt,
            normalizedJson = buildNormalizedEventJson(
                traderAddress = traderAddress,
                marketId = marketId,
                payload = payload,
                source = source,
                side = side,
                eventTimestamp = timestamp,
                price = price,
                size = size,
                volume = volume
            ),
            rawPayloadJson = payload.toJson()
        )
    }

    private fun buildPendingActivityEvent(
        traderAddress: String,
        activity: UserActivityResponse,
        source: String
    ): PendingActivityEvent? {
        if (!activity.type.equals("TRADE", ignoreCase = true)) return null
        val marketId = activity.conditionId.takeIf { it.isNotBlank() } ?: return null
        val payload = ActivityTradePayload(
            asset = activity.asset ?: "",
            conditionId = marketId,
            eventSlug = activity.eventSlug,
            slug = activity.slug,
            outcome = activity.outcome,
            outcomeIndex = activity.outcomeIndex,
            side = activity.side ?: "",
            price = activity.price,
            size = activity.size,
            timestamp = activity.timestamp,
            transactionHash = activity.transactionHash,
            trader = ActivityTrader(
                name = activity.name ?: activity.pseudonym,
                address = traderAddress
            ),
            proxyWallet = traderAddress,
            name = activity.name ?: activity.pseudonym
        )
        return buildPendingActivityEvent(
            payload = payload,
            source = source,
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun buildNormalizedEventJson(
        traderAddress: String,
        marketId: String,
        payload: ActivityTradePayload,
        source: String,
        side: String,
        eventTimestamp: Long,
        price: BigDecimal,
        size: BigDecimal,
        volume: BigDecimal
    ): String {
        return linkedMapOf(
            "source" to source,
            "traderAddress" to traderAddress,
            "displayName" to (payload.trader?.name ?: payload.name),
            "marketId" to marketId,
            "marketSlug" to payload.slug,
            "asset" to payload.asset.takeIf { it.isNotBlank() },
            "side" to side,
            "outcome" to payload.outcome,
            "outcomeIndex" to payload.outcomeIndex,
            "price" to price.stripTrailingZeros().toPlainString(),
            "size" to size.stripTrailingZeros().toPlainString(),
            "volume" to volume.stripTrailingZeros().toPlainString(),
            "transactionHash" to payload.transactionHash,
            "eventTimestamp" to eventTimestamp
        ).toJson()
    }

    private fun buildActivityHistoryResponse(
        items: List<TraderActivityEventHistory>,
        total: Long,
        page: Int,
        limit: Int,
        includeRaw: Boolean
    ): LeaderActivityHistoryResponse {
        val labelMap = getCandidateLabelSnapshots(items.map { it.traderAddress })
        val marketMap = marketService.getMarkets(items.map { it.marketId }.distinct())
        return LeaderActivityHistoryResponse(
            list = items.map { item ->
                val labels = labelMap[item.traderAddress]
                val market = marketMap[item.marketId]
                LeaderActivityHistoryItemDto(
                    eventKey = item.eventKey,
                    source = item.source,
                    traderAddress = item.traderAddress,
                    displayName = labels?.displayName ?: item.displayName,
                    marketId = item.marketId,
                    marketTitle = market?.title,
                    marketSlug = item.marketSlug ?: market?.slug,
                    asset = item.asset,
                    transactionHash = item.transactionHash,
                    side = item.side,
                    outcome = item.outcome,
                    outcomeIndex = item.outcomeIndex,
                    price = item.price?.let(::formatHistoryDecimal),
                    size = item.size?.let(::formatHistoryDecimal),
                    volume = item.volume?.let(::formatHistoryDecimal),
                    eventTimestamp = item.eventTimestamp,
                    receivedAt = item.receivedAt,
                    favorite = labels?.favorite ?: false,
                    blacklisted = labels?.blacklisted ?: false,
                    manualNote = labels?.manualNote,
                    manualTags = labels?.manualTags ?: emptyList(),
                    normalizedJson = item.normalizedJson,
                    rawPayloadJson = if (includeRaw) item.rawPayloadJson else null
                )
            },
            total = total,
            page = page,
            limit = limit
        )
    }

    private fun buildTimestampSpecification(
        startTime: Long?,
        endTime: Long?
    ): Specification<TraderActivityEventHistory> {
        return Specification { root, _, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (startTime != null) {
                predicates += cb.greaterThanOrEqualTo(root.get("eventTimestamp"), startTime)
            }
            if (endTime != null) {
                predicates += cb.lessThanOrEqualTo(root.get("eventTimestamp"), endTime)
            }
            cb.and(*predicates.toTypedArray())
        }
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

    fun findBlacklistedAddresses(addresses: Collection<String>): Set<String> {
        if (addresses.isEmpty()) return emptySet()
        return traderCandidatePoolRepository.findByAddressIn(addresses.map(::normalizeAddress).distinct())
            .asSequence()
            .filter { it.blacklisted }
            .map { it.address }
            .toSet()
    }

    fun getCandidateLabelSnapshots(addresses: Collection<String>): Map<String, CandidateLabelSnapshot> {
        if (addresses.isEmpty()) return emptyMap()
        return traderCandidatePoolRepository.findByAddressIn(addresses.map(::normalizeAddress).distinct())
            .associate { item ->
                item.address to CandidateLabelSnapshot(
                    address = item.address,
                    displayName = item.displayName,
                    profileImage = item.profileImage,
                    favorite = item.favorite,
                    blacklisted = item.blacklisted,
                    manualNote = item.manualNote,
                    manualTags = item.manualTagsJson.parseStringArray()
                )
            }
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

    private fun applyLabelUpdate(
        entity: TraderCandidatePool,
        favorite: Boolean?,
        blacklisted: Boolean?,
        manualNote: String?,
        manualTags: List<String>?,
        updatedAt: Long
    ) {
        favorite?.let { entity.favorite = it }
        blacklisted?.let { entity.blacklisted = it }
        if (manualNote != null) {
            entity.manualNote = manualNote.trim().ifBlank { null }
        }
        if (manualTags != null) {
            entity.manualTagsJson = manualTags
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .distinct()
                .toJson()
        }
        entity.updatedAt = updatedAt
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

    private fun formatHistoryDecimal(value: BigDecimal): String {
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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

    private data class PendingActivityEvent(
        val eventKey: String,
        val source: String,
        val traderAddress: String,
        val displayName: String?,
        val marketId: String,
        val marketSlug: String?,
        val asset: String?,
        val transactionHash: String?,
        val side: String?,
        val outcome: String?,
        val outcomeIndex: Int?,
        val price: BigDecimal?,
        val size: BigDecimal?,
        val volume: BigDecimal?,
        val eventTimestamp: Long,
        val receivedAt: Long,
        val normalizedJson: String?,
        val rawPayloadJson: String?
    )
}
