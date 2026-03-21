package com.wrbug.polymarketbot.service.copytrading.leaders

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.MarketTradeResponse
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendRequest
import com.wrbug.polymarketbot.dto.LeaderCandidateRecommendResponse
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolBatchLabelUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolBatchLabelUpdateResponse
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolLabelUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderCandidatePoolItemDto
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryByAddressRequest
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryBackfillRequest
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryBackfillResponse
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryByMarketRequest
import com.wrbug.polymarketbot.dto.LeaderActivityHistoryResponse
import com.wrbug.polymarketbot.dto.LeaderDiscoveredTraderDto
import com.wrbug.polymarketbot.dto.LeaderDiscoveryMarketDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupItemDto
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupRequest
import com.wrbug.polymarketbot.dto.LeaderMarketTraderLookupResponse
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryRequest
import com.wrbug.polymarketbot.dto.LeaderCandidateScoreHistoryResponse
import com.wrbug.polymarketbot.dto.LeaderTraderScanRequest
import com.wrbug.polymarketbot.dto.LeaderTraderScanResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/**
 * Leader 发现与评估辅助服务
 */
@Service
class LeaderDiscoveryService(
    private val leaderRepository: LeaderRepository,
    private val marketService: MarketService,
    private val retrofitFactory: RetrofitFactory,
    private val evaluator: LeaderDiscoveryEvaluator,
    private val traderCandidatePoolService: TraderCandidatePoolService
) {

    private val logger = LoggerFactory.getLogger(LeaderDiscoveryService::class.java)

    private val activityCache: Cache<String, List<UserActivityResponse>> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(200)
        .build()

    private val positionCache: Cache<String, List<PositionResponse>> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(200)
        .build()

    private val marketTradeCache: Cache<String, List<MarketTradeResponse>> = Caffeine.newBuilder()
        .expireAfterWrite(3, TimeUnit.MINUTES)
        .maximumSize(300)
        .build()

    fun scanTraders(request: LeaderTraderScanRequest): Result<LeaderTraderScanResponse> = runBlocking {
        runCatching {
            val normalizedRequest = normalizeScanRequest(request)
            val seedLeaders = resolveSeedLeaders(normalizedRequest.leaderIds)
            val seedAddresses = resolveSeedAddresses(seedLeaders, normalizedRequest.seedAddresses)
            val seedMarkets = loadSeedMarkets(seedAddresses, normalizedRequest.days ?: 7, normalizedRequest.maxSeedMarkets ?: 20)
            val traders = discoverTraders(
                marketIds = seedMarkets.map { it.marketId },
                limitPerMarket = normalizedRequest.marketTradeLimit ?: 120,
                traderLimit = normalizedRequest.traderLimit ?: 30,
                excludeAddresses = seedAddresses,
                excludeExistingLeaders = normalizedRequest.excludeExistingLeaders ?: true
            )

            LeaderTraderScanResponse(
                seedAddresses = seedAddresses,
                seedMarketCount = seedMarkets.size,
                list = applyScanFilters(traders, normalizedRequest)
            )
        }
    }

    fun recommendCandidates(request: LeaderCandidateRecommendRequest): Result<LeaderCandidateRecommendResponse> = runBlocking {
        runCatching {
            val normalizedRequest = normalizeRecommendRequest(request)
            val seedLeaders = resolveSeedLeaders(normalizedRequest.leaderIds)
            val seedAddresses = if (!normalizedRequest.candidateAddresses.isNullOrEmpty()) {
                resolveOptionalSeedAddresses(seedLeaders, normalizedRequest.seedAddresses)
            } else {
                resolveSeedAddresses(seedLeaders, normalizedRequest.seedAddresses)
            }

            val discoveredCandidates = if (!normalizedRequest.candidateAddresses.isNullOrEmpty()) {
                normalizedRequest.candidateAddresses.map { normalizeAddress(it) }.distinct()
            } else {
                val seedMarkets = loadSeedMarkets(seedAddresses, normalizedRequest.days ?: 7, normalizedRequest.maxSeedMarkets ?: 20)
                discoverTraders(
                    marketIds = seedMarkets.map { it.marketId },
                    limitPerMarket = normalizedRequest.marketTradeLimit ?: 120,
                    traderLimit = normalizedRequest.traderLimit ?: 20,
                    excludeAddresses = seedAddresses,
                    excludeExistingLeaders = normalizedRequest.excludeExistingLeaders ?: true
                ).map { it.address }
            }.let { addresses ->
                if (normalizedRequest.excludeBlacklistedTraders != false) {
                    val blacklisted = traderCandidatePoolService.findBlacklistedAddresses(addresses)
                    addresses.filterNot { it in blacklisted }
                } else {
                    addresses
                }
            }

            val leaderMap = leaderRepository.findAll().associateBy { normalizeAddress(it.leaderAddress) }
            val recommendations = coroutineScope {
                discoveredCandidates.map { address ->
                    async {
                        val activities = fetchUserActivities(address, normalizedRequest.days ?: 7, 160)
                        val positions = fetchUserPositions(address, 80)
                        val marketBreakdown = buildMarketBreakdown(activities).take(3)
                        evaluator.evaluateCandidate(
                            address = address,
                            displayName = resolveDisplayName(activities, leaderMap[address]),
                            profileImage = activities.firstNotNullOfOrNull { it.profileImageOptimized ?: it.profileImage },
                            existingLeader = leaderMap[address],
                            activities = activities,
                            positions = positions,
                            sampleMarkets = marketBreakdown,
                            request = normalizedRequest
                        )
                    }
                }.awaitAll()
            }.sortedWith(
                compareByDescending<com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto> { it.lowRisk }
                    .thenByDescending { it.recommendationScore }
                    .thenByDescending { it.recentTradeCount }
            )
            traderCandidatePoolService.updateEvaluationSnapshots(recommendations)

            LeaderCandidateRecommendResponse(
                seedAddresses = seedAddresses,
                list = if (normalizedRequest.lowRiskOnly == true) {
                    applyRecommendationFilters(recommendations.filter { it.lowRisk }, normalizedRequest)
                } else {
                    applyRecommendationFilters(recommendations, normalizedRequest)
                }
            )
        }
    }

    fun lookupMarketTraders(request: LeaderMarketTraderLookupRequest): Result<LeaderMarketTraderLookupResponse> = runBlocking {
        runCatching {
            val normalizedRequest = normalizeMarketLookupRequest(request)
            traderCandidatePoolService.getMarketLookupFromPool(normalizedRequest)?.let {
                return@runCatching applyMarketLookupFilters(it, normalizedRequest)
            }
            val marketMap = marketService.getMarkets(normalizedRequest.marketIds)
            val existingLeaderMap = leaderRepository.findAll().associateBy { normalizeAddress(it.leaderAddress) }
            val cutoffTime = System.currentTimeMillis() - (normalizedRequest.days ?: 7) * 24L * 60 * 60 * 1000

            val items = coroutineScope {
                normalizedRequest.marketIds.map { marketId ->
                    async {
                        val marketTrades = fetchMarketTrades(marketId, normalizedRequest.limitPerMarket ?: 20)
                        val grouped = linkedMapOf<String, MutableList<MarketTradeResponse>>()
                        marketTrades.forEach { trade ->
                            val address = extractTraderAddress(trade) ?: return@forEach
                            if ((normalizedRequest.excludeExistingLeaders ?: false) && existingLeaderMap.containsKey(address)) {
                                return@forEach
                            }
                            grouped.getOrPut(address) { mutableListOf() }.add(trade)
                        }
                        val blacklistedAddresses = if (normalizedRequest.excludeBlacklistedTraders != false) {
                            traderCandidatePoolService.findBlacklistedAddresses(grouped.keys)
                        } else {
                            emptySet()
                        }

                        val traders = grouped.entries
                            .filter { (address, _) -> address !in blacklistedAddresses }
                            .map { (address, trades) ->
                                val leader = existingLeaderMap[address]
                                LeaderMarketTraderDto(
                                    address = address,
                                    displayName = leader?.leaderName,
                                    existingLeaderId = leader?.id,
                                    existingLeaderName = leader?.leaderName,
                                    tradeCount = trades.size,
                                    buyCount = trades.count { it.side.equals("BUY", ignoreCase = true) },
                                    sellCount = trades.count { it.side.equals("SELL", ignoreCase = true) },
                                    totalVolume = formatDecimal(trades.fold(BigDecimal.ZERO) { acc, trade ->
                                        acc + (trade.usdcSize ?: (trade.price ?: 0.0) * (trade.size ?: 0.0)).toSafeBigDecimal()
                                    }),
                                    firstSeenAt = trades.mapNotNull { it.timestamp }.minOrNull()?.times(1000),
                                    lastSeenAt = trades.mapNotNull { it.timestamp }.maxOrNull()?.times(1000)
                                )
                            }
                            .filter { it.tradeCount >= (normalizedRequest.minTradesPerTrader ?: 1) }
                            .filter { (it.lastSeenAt ?: 0L) >= cutoffTime }
                            .sortedWith(compareByDescending<LeaderMarketTraderDto> { it.tradeCount }.thenByDescending { it.totalVolume.toSafeBigDecimal() })
                            .take(normalizedRequest.limitPerMarket ?: 20)

                        LeaderMarketTraderLookupItemDto(
                            marketId = marketId,
                            marketTitle = marketMap[marketId]?.title ?: marketTrades.firstOrNull()?.title,
                            marketSlug = marketMap[marketId]?.slug ?: marketTrades.firstOrNull()?.slug,
                            traderCount = traders.size,
                            list = traders
                        )
                    }
                }.awaitAll()
            }

            applyMarketLookupFilters(LeaderMarketTraderLookupResponse(source = "data-api", list = items), normalizedRequest)
        }
    }

    fun getCandidatePool(request: com.wrbug.polymarketbot.dto.LeaderCandidatePoolListRequest): Result<com.wrbug.polymarketbot.dto.LeaderCandidatePoolListResponse> {
        return runCatching {
            traderCandidatePoolService.getCandidatePool(request)
        }
    }

    fun updateCandidatePoolLabels(request: LeaderCandidatePoolLabelUpdateRequest): Result<LeaderCandidatePoolItemDto> {
        return runCatching {
            traderCandidatePoolService.updateCandidateLabels(request)
        }
    }

    fun updateCandidatePoolLabelsBatch(request: LeaderCandidatePoolBatchLabelUpdateRequest): Result<LeaderCandidatePoolBatchLabelUpdateResponse> {
        return runCatching {
            traderCandidatePoolService.updateCandidateLabelsBatch(request)
        }
    }

    fun getCandidateScoreHistory(request: LeaderCandidateScoreHistoryRequest): Result<LeaderCandidateScoreHistoryResponse> {
        return runCatching {
            traderCandidatePoolService.getCandidateScoreHistory(request)
        }
    }

    fun getActivityHistoryByAddress(request: LeaderActivityHistoryByAddressRequest): Result<LeaderActivityHistoryResponse> {
        return runCatching {
            traderCandidatePoolService.getActivityHistoryByAddress(request)
        }
    }

    fun getActivityHistoryByMarket(request: LeaderActivityHistoryByMarketRequest): Result<LeaderActivityHistoryResponse> {
        return runCatching {
            traderCandidatePoolService.getActivityHistoryByMarket(request)
        }
    }

    fun backfillActivityHistory(request: LeaderActivityHistoryBackfillRequest): Result<LeaderActivityHistoryBackfillResponse> = runBlocking {
        runCatching {
            val leaderIds = request.leaderIds.orEmpty().distinct()
            val seedLeaders = if (leaderIds.isEmpty()) emptyList() else resolveSeedLeaders(leaderIds)
            val addresses = resolveOptionalSeedAddresses(seedLeaders, request.addresses)
            require(addresses.isNotEmpty()) { "请至少提供一个 Leader 或地址" }

            val days = (request.days ?: 7).coerceIn(1, 90)
            val maxRecordsPerAddress = (request.maxRecordsPerAddress ?: 500).coerceIn(1, 2000)
            val startSeconds = (System.currentTimeMillis() - days * 24L * 60 * 60 * 1000) / 1000

            val items = addresses.map { address ->
                val activities = fetchUserActivitiesInternal(
                    address = address,
                    startSeconds = startSeconds,
                    endSeconds = null,
                    maxRecords = maxRecordsPerAddress,
                    useCache = false
                )
                traderCandidatePoolService.backfillActivityHistory(
                    address = address,
                    activities = activities,
                    source = "data-api-backfill"
                )
            }

            LeaderActivityHistoryBackfillResponse(
                days = days,
                maxRecordsPerAddress = maxRecordsPerAddress,
                totalAddresses = items.size,
                totalFetchedTrades = items.sumOf { it.fetchedTrades },
                totalInsertedEvents = items.sumOf { it.insertedEvents },
                totalSkippedEvents = items.sumOf { it.skippedEvents },
                list = items
            )
        }
    }

    private suspend fun discoverTraders(
        marketIds: List<String>,
        limitPerMarket: Int,
        traderLimit: Int,
        excludeAddresses: List<String>,
        excludeExistingLeaders: Boolean
    ): List<LeaderDiscoveredTraderDto> {
        val existingLeaderMap = leaderRepository.findAll().associateBy { normalizeAddress(it.leaderAddress) }
        val excluded = excludeAddresses.map { normalizeAddress(it) }.toSet()
        val traderMap = linkedMapOf<String, MutableTraderAggregate>()

        for (marketId in marketIds.distinct()) {
            val marketTrades = fetchMarketTrades(marketId, limitPerMarket)
            marketTrades.forEach { trade ->
                val address = extractTraderAddress(trade) ?: return@forEach
                if (address in excluded) return@forEach
                if (excludeExistingLeaders && existingLeaderMap.containsKey(address)) return@forEach

                val aggregate = traderMap.getOrPut(address) { MutableTraderAggregate(address = address) }
                aggregate.tradeCount += 1
                if (trade.side.equals("BUY", ignoreCase = true)) aggregate.buyCount += 1
                if (trade.side.equals("SELL", ignoreCase = true)) aggregate.sellCount += 1
                aggregate.volume += (trade.usdcSize ?: (trade.price ?: 0.0) * (trade.size ?: 0.0)).toSafeBigDecimal()
                val marketStats = aggregate.markets.getOrPut(marketId) { MutableMarketStats(marketId = marketId) }
                marketStats.tradeCount += 1
                marketStats.totalVolume += (trade.usdcSize ?: (trade.price ?: 0.0) * (trade.size ?: 0.0)).toSafeBigDecimal()
                marketStats.title = trade.title ?: marketStats.title
                marketStats.slug = trade.slug ?: marketStats.slug
                val tradeTimestamp = trade.timestamp?.times(1000)
                aggregate.firstSeenAt = minTimestamp(aggregate.firstSeenAt, tradeTimestamp)
                aggregate.lastSeenAt = maxTimestamp(aggregate.lastSeenAt, tradeTimestamp)
                marketStats.lastSeenAt = maxTimestamp(marketStats.lastSeenAt, tradeTimestamp)
            }
        }

        return traderMap.values
            .sortedWith(compareByDescending<MutableTraderAggregate> { it.tradeCount }.thenByDescending { it.volume })
            .take(traderLimit)
            .map { aggregate ->
                val leader = existingLeaderMap[aggregate.address]
                LeaderDiscoveredTraderDto(
                    address = aggregate.address,
                    displayName = leader?.leaderName,
                    existingLeaderId = leader?.id,
                    existingLeaderName = leader?.leaderName,
                    recentTradeCount = aggregate.tradeCount,
                    recentBuyCount = aggregate.buyCount,
                    recentSellCount = aggregate.sellCount,
                    recentVolume = formatDecimal(aggregate.volume),
                    distinctMarkets = aggregate.markets.size,
                    sourceLeaderIds = emptyList(),
                    sampleMarkets = aggregate.markets.values
                        .sortedWith(compareByDescending<MutableMarketStats> { it.tradeCount }.thenByDescending { it.totalVolume })
                        .take(3)
                        .map { stats ->
                            buildMarketDto(stats)
                        },
                    firstSeenAt = aggregate.firstSeenAt,
                    lastSeenAt = aggregate.lastSeenAt
                )
            }
    }

    private suspend fun loadSeedMarkets(seedAddresses: List<String>, days: Int, maxSeedMarkets: Int): List<LeaderDiscoveryMarketDto> {
        val marketStats = linkedMapOf<String, MutableMarketStats>()
        for (address in seedAddresses) {
            fetchUserActivities(address, days, 120).forEach { activity ->
                if (!activity.type.equals("TRADE", ignoreCase = true)) return@forEach
                val marketId = activity.conditionId.takeIf { it.isNotBlank() } ?: return@forEach
                val stats = marketStats.getOrPut(marketId) { MutableMarketStats(marketId = marketId) }
                stats.tradeCount += 1
                stats.totalVolume += (activity.usdcSize ?: 0.0).toSafeBigDecimal()
                stats.title = activity.title ?: stats.title
                stats.slug = activity.slug ?: stats.slug
                val activityTimestamp = activity.timestamp * 1000
                stats.lastSeenAt = maxTimestamp(stats.lastSeenAt, activityTimestamp)
            }
        }
        val marketMeta = marketService.getMarkets(marketStats.keys.toList())
        marketStats.values.forEach { stats ->
            val market = marketMeta[stats.marketId]
            if (market != null) {
                stats.title = market.title
                stats.slug = market.slug
                stats.category = market.category
            }
        }
        return marketStats.values
            .sortedWith(compareByDescending<MutableMarketStats> { it.tradeCount }.thenByDescending { it.lastSeenAt ?: 0L })
            .take(maxSeedMarkets)
            .map { buildMarketDto(it) }
    }

    private fun buildMarketBreakdown(activities: List<UserActivityResponse>): List<LeaderDiscoveryMarketDto> {
        val statsMap = linkedMapOf<String, MutableMarketStats>()
        activities.forEach { activity ->
            if (!activity.type.equals("TRADE", ignoreCase = true)) return@forEach
            val marketId = activity.conditionId.takeIf { it.isNotBlank() } ?: return@forEach
            val stats = statsMap.getOrPut(marketId) { MutableMarketStats(marketId = marketId) }
            stats.tradeCount += 1
            stats.totalVolume += (activity.usdcSize ?: 0.0).toSafeBigDecimal()
            stats.title = activity.title ?: stats.title
            stats.slug = activity.slug ?: stats.slug
            stats.lastSeenAt = maxTimestamp(stats.lastSeenAt, activity.timestamp * 1000)
        }
        val marketMeta = marketService.getMarkets(statsMap.keys.toList())
        return statsMap.values
            .onEach { stats ->
                val market = marketMeta[stats.marketId]
                if (market != null) {
                    stats.title = market.title
                    stats.slug = market.slug
                    stats.category = market.category
                }
            }
            .sortedWith(compareByDescending<MutableMarketStats> { it.tradeCount }.thenByDescending { it.totalVolume })
            .map { buildMarketDto(it) }
    }

    private suspend fun fetchUserActivities(address: String, days: Int, maxRecords: Int): List<UserActivityResponse> {
        val start = (System.currentTimeMillis() - days * 24L * 60 * 60 * 1000) / 1000
        return fetchUserActivitiesInternal(
            address = address,
            startSeconds = start,
            endSeconds = null,
            maxRecords = maxRecords,
            useCache = true
        )
    }

    private suspend fun fetchUserActivitiesInternal(
        address: String,
        startSeconds: Long,
        endSeconds: Long?,
        maxRecords: Int,
        useCache: Boolean
    ): List<UserActivityResponse> {
        val normalizedAddress = normalizeAddress(address)
        val cacheKey = "$normalizedAddress:$startSeconds:${endSeconds ?: "open"}:$maxRecords"
        if (useCache) {
            activityCache.getIfPresent(cacheKey)?.let { return it }
        }

        val dataApi = retrofitFactory.createDataApi()
        val results = mutableListOf<UserActivityResponse>()
        var offset = 0
        val pageSize = minOf(100, maxRecords)

        while (results.size < maxRecords) {
            val response = dataApi.getUserActivity(
                user = normalizedAddress,
                limit = pageSize,
                offset = offset,
                type = listOf("TRADE"),
                start = startSeconds,
                end = endSeconds,
                sortBy = "TIMESTAMP",
                sortDirection = "DESC"
            )
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("获取 trader activity 失败: address={}, code={}, message={}", normalizedAddress, response.code(), response.message())
                break
            }
            val body = response.body().orEmpty()
            if (body.isEmpty()) break
            results += body
            if (body.size < pageSize) break
            offset += body.size
        }
        val deduplicated = results.distinctBy {
            "${it.transactionHash ?: ""}_${it.timestamp}_${it.conditionId}_${it.side ?: ""}_${it.usdcSize ?: 0.0}"
        }
        if (useCache) {
            activityCache.put(cacheKey, deduplicated)
        }
        return deduplicated
    }

    private suspend fun fetchUserPositions(address: String, limit: Int): List<PositionResponse> {
        val normalizedAddress = normalizeAddress(address)
        val cacheKey = "$normalizedAddress:$limit"
        positionCache.getIfPresent(cacheKey)?.let { return it }
        val response = retrofitFactory.createDataApi().getPositions(
            user = normalizedAddress,
            sizeThreshold = 0.0,
            limit = limit,
            offset = 0
        )
        val positions = if (response.isSuccessful && response.body() != null) {
            response.body().orEmpty()
        } else {
            logger.warn("获取 trader positions 失败: address={}, code={}, message={}", normalizedAddress, response.code(), response.message())
            emptyList()
        }
        positionCache.put(cacheKey, positions)
        return positions
    }

    private suspend fun fetchMarketTrades(marketId: String, limit: Int): List<MarketTradeResponse> {
        val cacheKey = "$marketId:$limit"
        marketTradeCache.getIfPresent(cacheKey)?.let { return it }
        val response = retrofitFactory.createDataApi().getMarketTrades(
            market = marketId,
            limit = limit,
            offset = 0,
            sortBy = "TIMESTAMP",
            sortDirection = "DESC"
        )
        val trades = if (response.isSuccessful && response.body() != null) {
            response.body().orEmpty()
        } else {
            logger.warn("获取 market trades 失败: marketId={}, code={}, message={}", marketId, response.code(), response.message())
            emptyList()
        }
        marketTradeCache.put(cacheKey, trades)
        return trades
    }

    private fun resolveSeedLeaders(leaderIds: List<Long>?): List<Leader> {
        return if (leaderIds.isNullOrEmpty()) {
            leaderRepository.findAllByOrderByCreatedAtAsc()
        } else {
            leaderRepository.findAllById(leaderIds).toList()
        }
    }

    private fun resolveSeedAddresses(seedLeaders: List<Leader>, seedAddresses: List<String>?): List<String> {
        val distinct = resolveOptionalSeedAddresses(seedLeaders, seedAddresses)
        require(distinct.isNotEmpty()) { "请至少提供一个种子 Leader 或地址" }
        return distinct
    }

    private fun resolveOptionalSeedAddresses(seedLeaders: List<Leader>, seedAddresses: List<String>?): List<String> {
        val combined = mutableListOf<String>()
        combined += seedLeaders.map { normalizeAddress(it.leaderAddress) }
        combined += seedAddresses.orEmpty().mapNotNull { it.takeIf(String::isNotBlank) }.map { normalizeAddress(it) }
        return combined.distinct()
    }

    private fun normalizeScanRequest(request: LeaderTraderScanRequest): LeaderTraderScanRequest {
        return request.copy(
            days = (request.days ?: 7).coerceIn(1, 30),
            maxSeedMarkets = (request.maxSeedMarkets ?: 20).coerceIn(1, 50),
            marketTradeLimit = (request.marketTradeLimit ?: 120).coerceIn(20, 200),
            traderLimit = (request.traderLimit ?: 30).coerceIn(1, 100),
            includeTags = normalizeTags(request.includeTags),
            excludeTags = normalizeTags(request.excludeTags),
            excludeBlacklistedTraders = request.excludeBlacklistedTraders ?: true
        )
    }

    private fun normalizeRecommendRequest(request: LeaderCandidateRecommendRequest): LeaderCandidateRecommendRequest {
        return request.copy(
            days = (request.days ?: 7).coerceIn(1, 30),
            maxSeedMarkets = (request.maxSeedMarkets ?: 20).coerceIn(1, 50),
            marketTradeLimit = (request.marketTradeLimit ?: 120).coerceIn(20, 200),
            traderLimit = (request.traderLimit ?: 20).coerceIn(1, 100),
            minTrades = (request.minTrades ?: 8).coerceIn(1, 200),
            maxOpenPositions = (request.maxOpenPositions ?: 8).coerceIn(1, 50),
            maxMarketConcentrationRate = (request.maxMarketConcentrationRate ?: 0.45).coerceIn(0.05, 1.0),
            maxEstimatedDrawdownRate = (request.maxEstimatedDrawdownRate ?: 0.18).coerceIn(0.01, 1.0),
            maxRiskScore = (request.maxRiskScore ?: 45).coerceIn(0, 100),
            includeTags = normalizeTags(request.includeTags),
            excludeTags = normalizeTags(request.excludeTags),
            excludeBlacklistedTraders = request.excludeBlacklistedTraders ?: true
        )
    }

    private fun normalizeMarketLookupRequest(request: LeaderMarketTraderLookupRequest): LeaderMarketTraderLookupRequest {
        val marketIds = request.marketIds.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct()
        require(marketIds.isNotEmpty()) { "请至少提供一个市场 ID" }
        return request.copy(
            marketIds = marketIds,
            days = (request.days ?: 7).coerceIn(1, 30),
            limitPerMarket = (request.limitPerMarket ?: 20).coerceIn(1, 100),
            minTradesPerTrader = (request.minTradesPerTrader ?: 1).coerceIn(1, 50),
            includeTags = normalizeTags(request.includeTags),
            excludeTags = normalizeTags(request.excludeTags),
            excludeBlacklistedTraders = request.excludeBlacklistedTraders ?: true
        )
    }

    private fun applyScanFilters(
        traders: List<LeaderDiscoveredTraderDto>,
        request: LeaderTraderScanRequest
    ): List<LeaderDiscoveredTraderDto> {
        val labelMap = traderCandidatePoolService.getCandidateLabelSnapshots(traders.map { it.address })
        return traders
            .map { item ->
                val snapshot = labelMap[item.address]
                item.copy(
                    displayName = snapshot?.displayName ?: item.displayName,
                    profileImage = snapshot?.profileImage ?: item.profileImage,
                    favorite = snapshot?.favorite ?: false,
                    blacklisted = snapshot?.blacklisted ?: false,
                    manualNote = snapshot?.manualNote,
                    manualTags = snapshot?.manualTags ?: emptyList()
                )
            }
            .filter { matchesDiscoveryFilters(it.favorite, it.blacklisted, it.manualTags, request.favoriteOnly, request.excludeBlacklistedTraders, request.includeTags, request.excludeTags) }
    }

    private fun applyRecommendationFilters(
        recommendations: List<com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto>,
        request: LeaderCandidateRecommendRequest
    ): List<com.wrbug.polymarketbot.dto.LeaderCandidateRecommendationDto> {
        val labelMap = traderCandidatePoolService.getCandidateLabelSnapshots(recommendations.map { it.address })
        return recommendations
            .map { item ->
                val snapshot = labelMap[item.address]
                item.copy(
                    displayName = snapshot?.displayName ?: item.displayName,
                    profileImage = snapshot?.profileImage ?: item.profileImage,
                    favorite = snapshot?.favorite ?: false,
                    blacklisted = snapshot?.blacklisted ?: false,
                    manualNote = snapshot?.manualNote,
                    manualTags = snapshot?.manualTags ?: emptyList()
                )
            }
            .filter { matchesDiscoveryFilters(it.favorite, it.blacklisted, it.manualTags, request.favoriteOnly, request.excludeBlacklistedTraders, request.includeTags, request.excludeTags) }
    }

    private fun applyMarketLookupFilters(
        response: LeaderMarketTraderLookupResponse,
        request: LeaderMarketTraderLookupRequest
    ): LeaderMarketTraderLookupResponse {
        val addresses = response.list.flatMap { it.list }.map { it.address }
        val labelMap = traderCandidatePoolService.getCandidateLabelSnapshots(addresses)
        return response.copy(
            list = response.list.map { item ->
                val traders = item.list
                    .map { trader ->
                        val snapshot = labelMap[trader.address]
                        trader.copy(
                            displayName = snapshot?.displayName ?: trader.displayName,
                            favorite = snapshot?.favorite ?: false,
                            blacklisted = snapshot?.blacklisted ?: false,
                            manualNote = snapshot?.manualNote,
                            manualTags = snapshot?.manualTags ?: emptyList()
                        )
                    }
                    .filter { matchesDiscoveryFilters(it.favorite, it.blacklisted, it.manualTags, request.favoriteOnly, request.excludeBlacklistedTraders, request.includeTags, request.excludeTags) }
                item.copy(
                    traderCount = traders.size,
                    list = traders
                )
            }
        )
    }

    private fun matchesDiscoveryFilters(
        favorite: Boolean,
        blacklisted: Boolean,
        manualTags: List<String>,
        favoriteOnly: Boolean?,
        excludeBlacklistedTraders: Boolean?,
        includeTags: List<String>?,
        excludeTags: List<String>?
    ): Boolean {
        if (excludeBlacklistedTraders != false && blacklisted) return false
        if (favoriteOnly == true && !favorite) return false
        val tagSet = manualTags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val includeTagSet = includeTags.orEmpty().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val excludeTagSet = excludeTags.orEmpty().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (includeTagSet.isNotEmpty() && includeTagSet.none { it in tagSet }) return false
        if (excludeTagSet.isNotEmpty() && excludeTagSet.any { it in tagSet }) return false
        return true
    }

    private fun normalizeTags(tags: List<String>?): List<String>? {
        val normalized = tags.orEmpty()
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
        return normalized.ifEmpty { null }
    }

    private fun resolveDisplayName(activities: List<UserActivityResponse>, leader: Leader?): String? {
        return leader?.leaderName
            ?: activities.firstNotNullOfOrNull { it.pseudonym?.takeIf(String::isNotBlank) }
            ?: activities.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
    }

    private fun extractTraderAddress(trade: MarketTradeResponse): String? {
        val raw = trade.proxyWallet
            ?: trade.user
            ?: trade.owner
            ?: trade.makerAddress
        return raw?.takeIf { it.startsWith("0x") && it.length == 42 }?.let { normalizeAddress(it) }
    }

    private fun normalizeAddress(address: String): String = address.trim().lowercase()

    private fun buildMarketDto(stats: MutableMarketStats): LeaderDiscoveryMarketDto {
        return LeaderDiscoveryMarketDto(
            marketId = stats.marketId,
            title = stats.title,
            slug = stats.slug,
            category = stats.category,
            tradeCount = stats.tradeCount,
            totalVolume = formatDecimal(stats.totalVolume),
            lastSeenAt = stats.lastSeenAt
        )
    }

    private fun minTimestamp(current: Long?, candidate: Long?): Long? {
        if (candidate == null) return current
        if (current == null) return candidate
        return minOf(current, candidate)
    }

    private fun maxTimestamp(current: Long?, candidate: Long?): Long? {
        if (candidate == null) return current
        if (current == null) return candidate
        return maxOf(current, candidate)
    }

    private fun formatDecimal(value: BigDecimal): String {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private data class MutableTraderAggregate(
        val address: String,
        var tradeCount: Int = 0,
        var buyCount: Int = 0,
        var sellCount: Int = 0,
        var volume: BigDecimal = BigDecimal.ZERO,
        var firstSeenAt: Long? = null,
        var lastSeenAt: Long? = null,
        val markets: MutableMap<String, MutableMarketStats> = linkedMapOf()
    )

    private data class MutableMarketStats(
        val marketId: String,
        var title: String? = null,
        var slug: String? = null,
        var category: String? = null,
        var tradeCount: Int = 0,
        var totalVolume: BigDecimal = BigDecimal.ZERO,
        var lastSeenAt: Long? = null
    )
}
