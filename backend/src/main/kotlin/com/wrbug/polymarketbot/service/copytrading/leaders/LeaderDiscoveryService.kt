package com.wrbug.polymarketbot.service.copytrading.leaders

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.MarketTradeResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.LeaderMarketScanRequest
import com.wrbug.polymarketbot.dto.LeaderMarketScanResponse
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
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.parseStringArray
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    private val activeAddressCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(2000)
        .build()

    private companion object {
        const val DISCOVERY_MODE_ORDERBOOK = "ORDERBOOK"
        const val DISCOVERY_MODE_AGGRESSIVE = "AGGRESSIVE"
    }

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

    fun scanMarkets(request: LeaderMarketScanRequest): Result<LeaderMarketScanResponse> = runBlocking {
        runCatching {
            val startedAt = System.currentTimeMillis()
            val normalizedRequest = normalizeMarketScanRequest(request)
            val discoveryMode = normalizeDiscoveryMode(normalizedRequest.mode)
            val discoveryApiContext = DiscoveryApiContext(
                gammaApi = retrofitFactory.createIsolatedGammaApi(),
                clobApi = retrofitFactory.createIsolatedClobApiWithoutAuth(),
                dataApi = retrofitFactory.createIsolatedDataApi()
            )
            val markets = when (val marketFetch = fetchOpenMarkets(normalizedRequest.marketLimit ?: 100, discoveryApiContext.gammaApi)) {
                is OpenMarketFetchResult.Success -> marketFetch.markets
                is OpenMarketFetchResult.Failure -> throw IllegalStateException(marketFetch.message)
            }
            val tokenPerMarket = normalizedRequest.tokenPerMarketLimit ?: 2
            val ownerMap = scanOrderbookOwners(markets, tokenPerMarket, discoveryApiContext.clobApi)

            val sortedOwners = ownerMap.values
                .sortedWith(
                    compareByDescending<MutableOrderbookOwnerAggregate> { it.bidCount + it.askCount }
                        .thenByDescending { it.marketIds.size }
                )
                .take(normalizedRequest.maxCandidateAddresses ?: 500)

            val candidateAddresses = sortedOwners
                .take(normalizedRequest.validationSampleSize ?: sortedOwners.size)
                .map { it.address }
            val validatedAddresses = validateActiveAddressesInBatch(
                addresses = candidateAddresses,
                batchSize = normalizedRequest.validationBatchSize ?: 20,
                dataApi = discoveryApiContext.dataApi
            )
            val seedAddresses = if (discoveryMode == DISCOVERY_MODE_AGGRESSIVE && normalizedRequest.includeSeedAddresses != false) {
                resolveOptionalSeedAddresses(emptyList(), normalizedRequest.seedAddresses)
            } else {
                emptyList()
            }
            val aggressiveResult = if (discoveryMode == DISCOVERY_MODE_AGGRESSIVE) {
                discoverAggressiveCandidates(
                    request = normalizedRequest,
                    orderbookValidatedAddresses = validatedAddresses,
                    seedAddresses = seedAddresses,
                    scannedMarketIds = markets.mapNotNull { it.conditionId?.takeIf(String::isNotBlank) }.toSet(),
                    discoveryApiContext = discoveryApiContext
                )
            } else {
                AggressiveDiscoveryResult()
            }

            val existingLeaders = leaderRepository.findAll().associateBy { normalizeAddress(it.leaderAddress) }
            val sourceMap = buildDiscoverySourceMap(
                orderbookValidatedAddresses = validatedAddresses,
                ownerMap = ownerMap,
                aggressiveResult = aggressiveResult
            )
            val rankedAddresses = rankMarketScanAddresses(
                discoveryMode = discoveryMode,
                orderbookValidatedAddresses = validatedAddresses,
                aggressiveResult = aggressiveResult,
                sourceMap = sourceMap
            )
            val blacklisted = if (normalizedRequest.excludeBlacklistedTraders != false) {
                traderCandidatePoolService.findBlacklistedAddresses(rankedAddresses)
            } else {
                emptySet()
            }

            val finalAddresses = rankedAddresses
                .asSequence()
                .filterNot { normalizedRequest.excludeExistingLeaders != false && existingLeaders.containsKey(it) }
                .filterNot { it in blacklisted }
                .take(normalizedRequest.traderLimit ?: 50)
                .toList()

            val enriched = enrichDiscoveredTraders(
                addresses = finalAddresses,
                sourceMap = sourceMap,
                existingLeaders = existingLeaders,
                days = normalizedRequest.days ?: 7,
                activityLimit = normalizedRequest.activityLimit ?: 80,
                dataApi = discoveryApiContext.dataApi
            )

            val filtered = applyScanFilters(
                traders = enriched,
                request = LeaderTraderScanRequest(
                    favoriteOnly = normalizedRequest.favoriteOnly,
                    includeTags = normalizedRequest.includeTags,
                    excludeTags = normalizedRequest.excludeTags,
                    excludeBlacklistedTraders = normalizedRequest.excludeBlacklistedTraders
                )
            )

            if (normalizedRequest.persistToPool != false && filtered.isNotEmpty()) {
                val recommendationRequest = LeaderCandidateRecommendRequest(
                    days = normalizedRequest.days,
                    minTrades = 1,
                    maxOpenPositions = 30,
                    maxMarketConcentrationRate = 1.0,
                    maxEstimatedDrawdownRate = 1.0,
                    maxRiskScore = 100,
                    lowRiskOnly = false
                )
                val recommendations = coroutineScope {
                    filtered.map { trader ->
                        async {
                            val activities = fetchUserActivities(
                                trader.address,
                                normalizedRequest.days ?: 7,
                                normalizedRequest.activityLimit ?: 80,
                                discoveryApiContext.dataApi
                            )
                            val positions = fetchUserPositions(
                                trader.address,
                                normalizedRequest.positionLimit ?: 50,
                                discoveryApiContext.dataApi
                            )
                            evaluator.evaluateCandidate(
                                address = trader.address,
                                displayName = trader.displayName,
                                profileImage = trader.profileImage,
                                existingLeader = existingLeaders[trader.address],
                                activities = activities,
                                positions = positions,
                                sampleMarkets = trader.sampleMarkets,
                                request = recommendationRequest
                            )
                        }
                    }.awaitAll()
                }
                traderCandidatePoolService.updateEvaluationSnapshots(recommendations)
            }

            val tokenCount = markets.sumOf { extractMarketTokenIds(it).take(tokenPerMarket).size }
            LeaderMarketScanResponse(
                source = if (discoveryMode == DISCOVERY_MODE_AGGRESSIVE) "gamma+clob+data-api+market-trades" else "gamma+clob+data-api",
                discoveryMode = discoveryMode,
                marketCount = markets.size,
                tokenCount = tokenCount,
                rawAddressCount = ownerMap.size,
                validatedAddressCount = validatedAddresses.size,
                seedAddressCount = aggressiveResult.seedAddresses.size,
                expandedMarketCount = aggressiveResult.expandedMarketIds.size,
                expandedTraderCount = aggressiveResult.expandedTraders.size,
                finalCandidateCount = filtered.size,
                persistedToPool = normalizedRequest.persistToPool != false && filtered.isNotEmpty(),
                durationMs = System.currentTimeMillis() - startedAt,
                sources = buildMarketScanSources(discoveryMode, aggressiveResult),
                list = filtered
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
        excludeExistingLeaders: Boolean,
        dataApi: PolymarketDataApi? = null
    ): List<LeaderDiscoveredTraderDto> {
        val existingLeaderMap = leaderRepository.findAll().associateBy { normalizeAddress(it.leaderAddress) }
        val excluded = excludeAddresses.map { normalizeAddress(it) }.toSet()
        val traderMap = linkedMapOf<String, MutableTraderAggregate>()

        for (marketId in marketIds.distinct()) {
            val marketTrades = fetchMarketTrades(marketId, limitPerMarket, dataApi)
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

    private suspend fun discoverAggressiveCandidates(
        request: LeaderMarketScanRequest,
        orderbookValidatedAddresses: List<String>,
        seedAddresses: List<String>,
        scannedMarketIds: Set<String>,
        discoveryApiContext: DiscoveryApiContext
    ): AggressiveDiscoveryResult {
        val rounds = request.expansionRounds ?: 0
        val validatedSeedAddresses = if (seedAddresses.isEmpty()) {
            emptyList()
        } else {
            validateActiveAddressesInBatch(
                addresses = seedAddresses,
                batchSize = request.validationBatchSize ?: 20,
                dataApi = discoveryApiContext.dataApi
            )
        }
        if (rounds <= 0 && validatedSeedAddresses.isEmpty()) {
            return AggressiveDiscoveryResult(seedAddresses = validatedSeedAddresses)
        }

        val knownAddresses = linkedSetOf<String>()
        knownAddresses += orderbookValidatedAddresses
        knownAddresses += validatedSeedAddresses
        val expandedMarketIds = linkedSetOf<String>()
        val expandedTraders = linkedMapOf<String, LeaderDiscoveredTraderDto>()
        var tradersToExpand = knownAddresses.toList()

        repeat(rounds) {
            if (tradersToExpand.isEmpty()) return@repeat

            val candidateMarketIds = linkedSetOf<String>()
            tradersToExpand
                .take(request.expansionSeedTraderLimit ?: 30)
                .forEach { address ->
                    val activities = fetchUserActivities(
                        address = address,
                        days = request.days ?: 7,
                        maxRecords = request.activityLimit ?: 80,
                        dataApi = discoveryApiContext.dataApi
                    )
                    activities.forEach { activity ->
                        val marketId = activity.conditionId.takeIf(String::isNotBlank)
                        if (marketId != null && marketId !in scannedMarketIds && marketId !in expandedMarketIds) {
                            candidateMarketIds += marketId
                        }
                    }
                }

            val roundMarketIds = candidateMarketIds
                .take(request.expansionMarketLimit ?: 60)
            if (roundMarketIds.isEmpty()) {
                tradersToExpand = emptyList()
                return@repeat
            }

            expandedMarketIds += roundMarketIds
            val discovered = discoverTraders(
                marketIds = roundMarketIds,
                limitPerMarket = request.expansionTradeLimitPerMarket ?: 40,
                traderLimit = maxOf(
                    request.traderLimit ?: 50,
                    (request.expansionMarketLimit ?: 60) * 4
                ).coerceAtMost(request.maxCandidateAddresses ?: 500),
                excludeAddresses = knownAddresses.toList(),
                excludeExistingLeaders = request.excludeExistingLeaders ?: true,
                dataApi = discoveryApiContext.dataApi
            )
            val newAddresses = mutableListOf<String>()
            discovered.forEach { trader ->
                if (trader.address in knownAddresses) return@forEach
                expandedTraders.putIfAbsent(trader.address, trader)
                knownAddresses += trader.address
                newAddresses += trader.address
            }
            tradersToExpand = newAddresses
        }

        return AggressiveDiscoveryResult(
            seedAddresses = validatedSeedAddresses,
            expandedMarketIds = expandedMarketIds.toList(),
            expandedTraders = expandedTraders.values.toList()
        )
    }

    private fun buildDiscoverySourceMap(
        orderbookValidatedAddresses: List<String>,
        ownerMap: Map<String, MutableOrderbookOwnerAggregate>,
        aggressiveResult: AggressiveDiscoveryResult
    ): Map<String, MutableDiscoverySourceAggregate> {
        val result = linkedMapOf<String, MutableDiscoverySourceAggregate>()

        orderbookValidatedAddresses.forEach { address ->
            val source = result.getOrPut(address) { MutableDiscoverySourceAggregate(address = address) }
            source.sourceTypes += "orderbook"
            ownerMap[address]?.let { aggregate ->
                source.orderbookBidCount = aggregate.bidCount
                source.orderbookAskCount = aggregate.askCount
                source.sourceMarketIds += aggregate.marketIds
                source.sourceTokenIds += aggregate.tokenIds
            }
        }

        aggressiveResult.seedAddresses.forEach { address ->
            val source = result.getOrPut(address) { MutableDiscoverySourceAggregate(address = address) }
            source.sourceTypes += "seed"
        }

        aggressiveResult.expandedTraders.forEach { trader ->
            val source = result.getOrPut(trader.address) { MutableDiscoverySourceAggregate(address = trader.address) }
            source.sourceTypes += "market-expansion"
            source.sourceMarketIds += trader.sampleMarkets.map { it.marketId }
        }

        return result
    }

    private fun rankMarketScanAddresses(
        discoveryMode: String,
        orderbookValidatedAddresses: List<String>,
        aggressiveResult: AggressiveDiscoveryResult,
        sourceMap: Map<String, MutableDiscoverySourceAggregate>
    ): List<String> {
        if (discoveryMode != DISCOVERY_MODE_AGGRESSIVE) {
            return orderbookValidatedAddresses.distinct()
        }
        val addresses = linkedSetOf<String>()
        addresses += orderbookValidatedAddresses
        addresses += aggressiveResult.expandedTraders.map { it.address }
        addresses += aggressiveResult.seedAddresses
        return addresses.toList().sortedWith(
            compareByDescending<String> { sourceMap[it]?.buildDiscoveryConfidence() ?: 0 }
                .thenByDescending { sourceMap[it]?.sourceMarketIds?.size ?: 0 }
        )
    }

    private suspend fun enrichDiscoveredTraders(
        addresses: List<String>,
        sourceMap: Map<String, MutableDiscoverySourceAggregate>,
        existingLeaders: Map<String, Leader>,
        days: Int,
        activityLimit: Int,
        dataApi: PolymarketDataApi
    ): List<LeaderDiscoveredTraderDto> = coroutineScope {
        addresses.map { address ->
            async {
                val activities = fetchUserActivities(
                    address = address,
                    days = days,
                    maxRecords = activityLimit,
                    dataApi = dataApi
                )
                val source = sourceMap[address]
                val marketBreakdown = if (activities.isNotEmpty()) {
                    buildMarketBreakdown(activities).take(3)
                } else {
                    source?.sourceMarketIds
                        ?.take(3)
                        ?.map { marketId ->
                            LeaderDiscoveryMarketDto(
                                marketId = marketId,
                                title = null,
                                slug = null,
                                category = null,
                                tradeCount = 0,
                                totalVolume = "0",
                                lastSeenAt = null
                            )
                        }
                        ?: emptyList()
                }
                LeaderDiscoveredTraderDto(
                    address = address,
                    displayName = resolveDisplayName(activities, existingLeaders[address]),
                    profileImage = activities.firstNotNullOfOrNull { it.profileImageOptimized ?: it.profileImage },
                    existingLeaderId = existingLeaders[address]?.id,
                    existingLeaderName = existingLeaders[address]?.leaderName,
                    recentTradeCount = activities.size,
                    recentBuyCount = activities.count { it.side.equals("BUY", ignoreCase = true) },
                    recentSellCount = activities.count { it.side.equals("SELL", ignoreCase = true) },
                    recentVolume = formatDecimal(activities.fold(BigDecimal.ZERO) { acc, activity ->
                        acc + (activity.usdcSize ?: 0.0).toSafeBigDecimal()
                    }),
                    distinctMarkets = activities.mapNotNull { it.conditionId.takeIf(String::isNotBlank) }.distinct().size
                        .takeIf { it > 0 } ?: source?.sourceMarketIds?.size ?: 0,
                    sourceLeaderIds = emptyList(),
                    sampleMarkets = marketBreakdown,
                    firstSeenAt = activities.minOfOrNull { it.timestamp }?.times(1000),
                    lastSeenAt = activities.maxOfOrNull { it.timestamp }?.times(1000),
                    sourceType = source?.sourceTypes?.sorted()?.joinToString("+"),
                    sourceMarketIds = source?.sourceMarketIds?.toList()?.sorted() ?: emptyList(),
                    sourceTokenIds = source?.sourceTokenIds?.toList()?.sorted() ?: emptyList(),
                    orderbookBidCount = source?.orderbookBidCount ?: 0,
                    orderbookAskCount = source?.orderbookAskCount ?: 0,
                    discoveryConfidence = source?.buildDiscoveryConfidence(),
                    favorite = false,
                    blacklisted = false,
                    manualNote = source?.buildSourceSummary(),
                    manualTags = emptyList()
                )
            }
        }.awaitAll()
    }

    private fun buildMarketScanSources(
        discoveryMode: String,
        aggressiveResult: AggressiveDiscoveryResult
    ): List<String> {
        val sources = linkedSetOf("orderbook")
        if (discoveryMode == DISCOVERY_MODE_AGGRESSIVE) {
            if (aggressiveResult.seedAddresses.isNotEmpty()) {
                sources += "seed"
            }
            if (aggressiveResult.expandedMarketIds.isNotEmpty() || aggressiveResult.expandedTraders.isNotEmpty()) {
                sources += "market-expansion"
            }
        }
        return sources.toList()
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

    private suspend fun fetchUserActivities(
        address: String,
        days: Int,
        maxRecords: Int,
        dataApi: PolymarketDataApi? = null
    ): List<UserActivityResponse> {
        val start = (System.currentTimeMillis() - days * 24L * 60 * 60 * 1000) / 1000
        return fetchUserActivitiesInternal(
            address = address,
            startSeconds = start,
            endSeconds = null,
            maxRecords = maxRecords,
            useCache = true,
            dataApi = dataApi
        )
    }

    private suspend fun fetchUserActivitiesInternal(
        address: String,
        startSeconds: Long,
        endSeconds: Long?,
        maxRecords: Int,
        useCache: Boolean,
        dataApi: PolymarketDataApi? = null
    ): List<UserActivityResponse> {
        val normalizedAddress = normalizeAddress(address)
        val cacheKey = "$normalizedAddress:$startSeconds:${endSeconds ?: "open"}:$maxRecords"
        if (useCache) {
            activityCache.getIfPresent(cacheKey)?.let { return it }
        }

        val effectiveDataApi = dataApi ?: retrofitFactory.createDataApi()
        val results = mutableListOf<UserActivityResponse>()
        var offset = 0
        val pageSize = minOf(100, maxRecords)

        while (results.size < maxRecords) {
            val response = executeWithRetry(
                action = "获取 trader activity address=$normalizedAddress offset=$offset"
            ) {
                effectiveDataApi.getUserActivity(
                    user = normalizedAddress,
                    limit = pageSize,
                    offset = offset,
                    type = listOf("TRADE"),
                    start = startSeconds,
                    end = endSeconds,
                    sortBy = "TIMESTAMP",
                    sortDirection = "DESC"
                )
            } ?: break
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

    private suspend fun fetchUserPositions(
        address: String,
        limit: Int,
        dataApi: PolymarketDataApi? = null
    ): List<PositionResponse> {
        val normalizedAddress = normalizeAddress(address)
        val cacheKey = "$normalizedAddress:$limit"
        positionCache.getIfPresent(cacheKey)?.let { return it }
        val effectiveDataApi = dataApi ?: retrofitFactory.createDataApi()
        val response = executeWithRetry(
            action = "获取 trader positions address=$normalizedAddress"
        ) {
            effectiveDataApi.getPositions(
                user = normalizedAddress,
                sizeThreshold = 0.0,
                limit = limit,
                offset = 0
            )
        }
        val positions = if (response?.isSuccessful == true && response.body() != null) {
            response.body().orEmpty()
        } else {
            if (response != null) {
                logger.warn("获取 trader positions 失败: address={}, code={}, message={}", normalizedAddress, response.code(), response.message())
            }
            emptyList()
        }
        positionCache.put(cacheKey, positions)
        return positions
    }

    private suspend fun fetchMarketTrades(
        marketId: String,
        limit: Int,
        dataApi: PolymarketDataApi? = null
    ): List<MarketTradeResponse> {
        val cacheKey = "$marketId:$limit"
        marketTradeCache.getIfPresent(cacheKey)?.let { return it }
        val effectiveDataApi = dataApi ?: retrofitFactory.createDataApi()
        val response = executeWithRetry(
            action = "获取 market trades marketId=$marketId"
        ) {
            effectiveDataApi.getMarketTrades(
                market = marketId,
                limit = limit,
                offset = 0,
                sortBy = "TIMESTAMP",
                sortDirection = "DESC"
            )
        }
        val trades = if (response?.isSuccessful == true && response.body() != null) {
            response.body().orEmpty()
        } else {
            if (response != null) {
                logger.warn("获取 market trades 失败: marketId={}, code={}, message={}", marketId, response.code(), response.message())
            }
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

    private fun normalizeMarketScanRequest(request: LeaderMarketScanRequest): LeaderMarketScanRequest {
        return request.copy(
            mode = normalizeDiscoveryMode(request.mode),
            marketLimit = (request.marketLimit ?: 100).coerceIn(1, 300),
            tokenPerMarketLimit = (request.tokenPerMarketLimit ?: 2).coerceIn(1, 4),
            maxCandidateAddresses = (request.maxCandidateAddresses ?: 500).coerceIn(50, 5000),
            validationSampleSize = (request.validationSampleSize ?: 200).coerceIn(1, request.maxCandidateAddresses ?: 500),
            validationBatchSize = (request.validationBatchSize ?: 20).coerceIn(1, 100),
            days = (request.days ?: 7).coerceIn(1, 30),
            activityLimit = (request.activityLimit ?: 80).coerceIn(10, 200),
            positionLimit = (request.positionLimit ?: 50).coerceIn(1, 200),
            traderLimit = (request.traderLimit ?: 50).coerceIn(1, 500),
            seedAddresses = request.seedAddresses.orEmpty()
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .map(::normalizeAddress)
                .filter { it.startsWith("0x") && it.length == 42 }
                .distinct()
                .ifEmpty { null },
            includeSeedAddresses = request.includeSeedAddresses ?: true,
            expansionRounds = (request.expansionRounds ?: 1).coerceIn(0, 3),
            expansionSeedTraderLimit = (request.expansionSeedTraderLimit ?: 30).coerceIn(1, 100),
            expansionMarketLimit = (request.expansionMarketLimit ?: 60).coerceIn(1, 200),
            expansionTradeLimitPerMarket = (request.expansionTradeLimitPerMarket ?: 40).coerceIn(5, 120),
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

    private fun normalizeDiscoveryMode(mode: String?): String {
        return when (mode?.trim()?.uppercase()) {
            DISCOVERY_MODE_AGGRESSIVE -> DISCOVERY_MODE_AGGRESSIVE
            else -> DISCOVERY_MODE_ORDERBOOK
        }
    }

    private fun resolveDisplayName(activities: List<UserActivityResponse>, leader: Leader?): String? {
        return leader?.leaderName
            ?: activities.firstNotNullOfOrNull { it.pseudonym?.takeIf(String::isNotBlank) }
            ?: activities.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
    }

    private suspend fun fetchOpenMarkets(limit: Int, gammaApi: PolymarketGammaApi): OpenMarketFetchResult {
        var lastFailure = "Gamma API 未返回可用响应"
        repeat(2) { index ->
            try {
                val response = gammaApi.listMarkets(
                    conditionIds = null,
                    clobTokenIds = null,
                    includeTag = false,
                    limit = limit,
                    closed = false
                )
                if (!response.isSuccessful) {
                    lastFailure = "Gamma API HTTP ${response.code()} ${response.message()}"
                    logger.warn(
                        "获取开放市场失败: limit={}, attempt={}/{}, code={}, message={}, runtimeProxy={}",
                        limit,
                        index + 1,
                        2,
                        response.code(),
                        response.message(),
                        buildRuntimeProxyHint()
                    )
                } else {
                    val body = response.body()
                    if (body == null) {
                        lastFailure = "Gamma API 响应体为空"
                        logger.warn(
                            "获取开放市场失败: limit={}, attempt={}/{}, emptyBody=true, runtimeProxy={}",
                            limit,
                            index + 1,
                            2,
                            buildRuntimeProxyHint()
                        )
                    } else {
                        return OpenMarketFetchResult.Success(
                            body.filter { it.closed != true && it.archived != true && it.active != false }
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastFailure = e.message ?: e::class.java.simpleName
                logger.warn(
                    "获取开放市场异常: limit={}, attempt={}/{}, error={}, runtimeProxy={}",
                    limit,
                    index + 1,
                    2,
                    lastFailure,
                    buildRuntimeProxyHint()
                )
            }
            if (index < 1) {
                delay(200)
            }
        }
        return OpenMarketFetchResult.Failure(
            "获取开放市场失败：$lastFailure。当前运行代理：${buildRuntimeProxyHint()}。这通常不是“真的没有市场”，而是 Gamma/代理网络链路失败。"
        )
    }

    private suspend fun scanOrderbookOwners(
        markets: List<MarketResponse>,
        tokenPerMarketLimit: Int,
        clobApi: PolymarketClobApi
    ): MutableMap<String, MutableOrderbookOwnerAggregate> {
        val ownerMap = linkedMapOf<String, MutableOrderbookOwnerAggregate>()

        markets.forEach marketLoop@ { market ->
            val marketId = market.conditionId?.takeIf(String::isNotBlank) ?: return@marketLoop
            val marketTitle = market.question
            val marketSlug = market.slug
            val tokenIds = extractMarketTokenIds(market).take(tokenPerMarketLimit)
            tokenIds.forEach tokenLoop@ { tokenId ->
                val response = executeWithRetry(
                    action = "获取订单簿 tokenId=$tokenId"
                ) {
                    clobApi.getOrderbook(tokenId = tokenId, market = null)
                } ?: return@tokenLoop
                if (!response.isSuccessful || response.body() == null) {
                    logger.debug("获取订单簿失败: tokenId={}, code={}", tokenId, response.code())
                    return@tokenLoop
                }
                val orderbook = response.body() ?: return@tokenLoop
                orderbook.bids.forEach bidsLoop@ { entry ->
                    val address = entry.owner?.takeIf { it.startsWith("0x") && it.length == 42 }?.let(::normalizeAddress) ?: return@bidsLoop
                    val aggregate = ownerMap.getOrPut(address) { MutableOrderbookOwnerAggregate(address = address) }
                    aggregate.bidCount += 1
                    aggregate.marketIds += marketId
                    aggregate.tokenIds += tokenId
                    if (aggregate.marketTitle == null) aggregate.marketTitle = marketTitle
                    if (aggregate.marketSlug == null) aggregate.marketSlug = marketSlug
                }
                orderbook.asks.forEach asksLoop@ { entry ->
                    val address = entry.owner?.takeIf { it.startsWith("0x") && it.length == 42 }?.let(::normalizeAddress) ?: return@asksLoop
                    val aggregate = ownerMap.getOrPut(address) { MutableOrderbookOwnerAggregate(address = address) }
                    aggregate.askCount += 1
                    aggregate.marketIds += marketId
                    aggregate.tokenIds += tokenId
                    if (aggregate.marketTitle == null) aggregate.marketTitle = marketTitle
                    if (aggregate.marketSlug == null) aggregate.marketSlug = marketSlug
                }
            }
        }
        return ownerMap
    }

    private fun extractMarketTokenIds(market: MarketResponse): List<String> {
        val camel = market.clobTokenIds.parseStringArray()
        if (camel.isNotEmpty()) return camel
        return market.clob_token_ids.parseStringArray()
    }

    private suspend fun validateActiveAddressesInBatch(
        addresses: List<String>,
        batchSize: Int,
        dataApi: PolymarketDataApi
    ): List<String> {
        if (addresses.isEmpty()) return emptyList()
        val active = mutableListOf<String>()
        addresses.chunked(batchSize).forEach { batch ->
            val batchResults = coroutineScope {
                batch.map { address ->
                    async {
                        val cached = activeAddressCache.getIfPresent(address)
                        if (cached != null) {
                            return@async if (cached) address else null
                        }
                        val response = executeWithRetry(
                            action = "校验活跃地址 address=$address"
                        ) {
                            dataApi.getUserActivity(
                                user = address,
                                limit = 1,
                                offset = 0,
                                type = listOf("TRADE"),
                                sortBy = "TIMESTAMP",
                                sortDirection = "DESC"
                            )
                        }
                        val isActive = response?.isSuccessful == true && !response.body().isNullOrEmpty()
                        activeAddressCache.put(address, isActive)
                        if (isActive) address else null
                    }
                }.awaitAll()
            }
            active += batchResults.filterNotNull()
        }
        return active.distinct()
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

    private suspend fun <T> executeWithRetry(
        action: String,
        attempts: Int = 2,
        delayMs: Long = 200,
        block: suspend () -> T
    ): T? {
        repeat(attempts) { index ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn("{} 异常: attempt={}/{}, type={}, error={}", action, index + 1, attempts, e.javaClass.simpleName, e.message)
                if (index < attempts - 1) {
                    delay(delayMs)
                }
            }
        }
        return null
    }

    private fun buildRuntimeProxyHint(): String {
        val config = ProxyConfigProvider.getProxyConfig()
        if (config == null || !config.enabled) {
            return "未启用"
        }
        val host = config.host?.takeIf(String::isNotBlank) ?: "unknown-host"
        val port = config.port?.toString() ?: "unknown-port"
        val usernameHint = if (config.username.isNullOrBlank()) "anonymous" else "auth"
        return "${ProxyConfigProvider.normalizeType(config.type)} $host:$port ($usernameHint)"
    }

    private sealed interface OpenMarketFetchResult {
        data class Success(val markets: List<MarketResponse>) : OpenMarketFetchResult
        data class Failure(val message: String) : OpenMarketFetchResult
    }

    private data class DiscoveryApiContext(
        val gammaApi: PolymarketGammaApi,
        val clobApi: PolymarketClobApi,
        val dataApi: PolymarketDataApi
    )

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

    private data class MutableOrderbookOwnerAggregate(
        val address: String,
        var bidCount: Int = 0,
        var askCount: Int = 0,
        val marketIds: MutableSet<String> = linkedSetOf(),
        val tokenIds: MutableSet<String> = linkedSetOf(),
        var marketTitle: String? = null,
        var marketSlug: String? = null
    ) {
        fun buildSourceSummary(): String {
            val total = bidCount + askCount
            return "orderbook-source: total=$total,bids=$bidCount,asks=$askCount,markets=${marketIds.size},tokens=${tokenIds.size}"
        }
    }

    private data class AggressiveDiscoveryResult(
        val seedAddresses: List<String> = emptyList(),
        val expandedMarketIds: List<String> = emptyList(),
        val expandedTraders: List<LeaderDiscoveredTraderDto> = emptyList()
    )

    private data class MutableDiscoverySourceAggregate(
        val address: String,
        val sourceTypes: MutableSet<String> = linkedSetOf(),
        val sourceMarketIds: MutableSet<String> = linkedSetOf(),
        val sourceTokenIds: MutableSet<String> = linkedSetOf(),
        var orderbookBidCount: Int = 0,
        var orderbookAskCount: Int = 0
    ) {
        fun buildDiscoveryConfidence(): Int {
            var score = 0
            if ("orderbook" in sourceTypes) {
                score += 60 + minOf(30, orderbookBidCount + orderbookAskCount)
            }
            if ("market-expansion" in sourceTypes) {
                score += 30 + minOf(20, sourceMarketIds.size)
            }
            if ("seed" in sourceTypes) {
                score += 15
            }
            return score
        }

        fun buildSourceSummary(): String {
            val sourceText = sourceTypes.joinToString("+")
            return "discovery-source: type=$sourceText,markets=${sourceMarketIds.size},tokens=${sourceTokenIds.size},bids=$orderbookBidCount,asks=$orderbookAskCount"
        }
    }
}
