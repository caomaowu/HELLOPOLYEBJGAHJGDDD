package com.wrbug.polymarketbot.service.copytrading.templates

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CopyTradingTemplate
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.util.IllegalBigDecimal
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.MarketFilterSupport
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingConfig
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingSupport
import com.wrbug.polymarketbot.service.copytrading.aggregation.SmallOrderAggregationSupport
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 跟单模板管理服务
 */
@Service
class CopyTradingTemplateService(
    private val templateRepository: CopyTradingTemplateRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val jsonUtils: JsonUtils,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingTemplateService::class.java)
    
    /**
     * 创建模板
     */
    @Transactional
    fun createTemplate(request: TemplateCreateRequest): Result<TemplateDto> {
        return try {
            // 1. 验证模板名称
            if (request.templateName.isBlank()) {
                return Result.failure(IllegalArgumentException("模板名称不能为空"))
            }
            
            // 2. 检查模板名称是否已存在
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            
            // 3. 验证 copyMode
            val copyMode = request.copyMode

            // 4. 创建模板
            val template = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: BigDecimal.ONE,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal(),
                adaptiveMinRatio = request.adaptiveMinRatio?.toSafeBigDecimal(),
                adaptiveMaxRatio = request.adaptiveMaxRatio?.toSafeBigDecimal(),
                adaptiveThreshold = request.adaptiveThreshold?.toSafeBigDecimal(),
                multiplierMode = request.multiplierMode ?: CopyTradingSizingSupport.MULTIPLIER_MODE_NONE,
                tradeMultiplier = request.tradeMultiplier?.toSafeBigDecimal(),
                tieredMultipliers = CopyTradingSizingSupport.serializeTieredMultipliers(request.tieredMultipliers),
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: "1000".toSafeBigDecimal(),
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: "1".toSafeBigDecimal(),
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: "10000".toSafeBigDecimal(),
                maxDailyOrders = request.maxDailyOrders ?: 100,
                maxDailyVolume = request.maxDailyVolume?.toSafeBigDecimal(),
                smallOrderAggregationEnabled = request.smallOrderAggregationEnabled ?: false,
                smallOrderAggregationWindowSeconds = request.smallOrderAggregationWindowSeconds
                    ?: SmallOrderAggregationSupport.DEFAULT_WINDOW_SECONDS,
                repeatAddReductionEnabled = request.repeatAddReductionEnabled ?: false,
                repeatAddReductionStrategy = request.repeatAddReductionStrategy
                    ?: CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_STRATEGY_UNIFORM,
                repeatAddReductionValueType = request.repeatAddReductionValueType
                    ?: CopyTradingSizingSupport.REPEAT_ADD_REDUCTION_VALUE_TYPE_PERCENT,
                repeatAddReductionPercent = request.repeatAddReductionPercent?.toSafeBigDecimal(),
                repeatAddReductionFixedAmount = request.repeatAddReductionFixedAmount?.toSafeBigDecimal(),
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: "5".toSafeBigDecimal(),
                delaySeconds = request.delaySeconds ?: 0,
                pollIntervalSeconds = request.pollIntervalSeconds ?: 5,
                useWebSocket = request.useWebSocket ?: true,
                websocketReconnectInterval = request.websocketReconnectInterval ?: 5000,
                websocketMaxRetries = request.websocketMaxRetries ?: 10,
                supportSell = request.supportSell ?: true,
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal(),
                maxSpread = request.maxSpread?.toSafeBigDecimal(),
                minPrice = request.minPrice?.toSafeBigDecimal(),
                maxPrice = request.maxPrice?.toSafeBigDecimal(),
                marketCategoryMode = MarketFilterSupport.normalizeFilterMode(request.marketCategoryMode),
                marketCategories = resolveMarketCategoriesJson(
                    mode = MarketFilterSupport.normalizeFilterMode(request.marketCategoryMode),
                    categories = request.marketCategories
                ),
                marketIntervalMode = MarketFilterSupport.normalizeFilterMode(request.marketIntervalMode),
                marketIntervals = resolveMarketIntervalsJson(
                    mode = MarketFilterSupport.normalizeFilterMode(request.marketIntervalMode),
                    intervals = request.marketIntervals
                ),
                marketSeriesMode = MarketFilterSupport.normalizeFilterMode(request.marketSeriesMode),
                marketSeries = resolveMarketSeriesJson(
                    mode = MarketFilterSupport.normalizeFilterMode(request.marketSeriesMode),
                    series = request.marketSeries
                ),
                coinFilterMode = MarketFilterSupport.normalizeFilterMode(request.coinFilterMode),
                coinSymbols = resolveCoinSymbolsJson(
                    mode = MarketFilterSupport.normalizeFilterMode(request.coinFilterMode),
                    symbols = request.coinSymbols
                ),
                pushFilteredOrders = request.pushFilteredOrders ?: false
            )

            validateTemplate(template)?.let { return Result.failure(IllegalArgumentException(it)) }
            
            val saved = templateRepository.save(template)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("创建模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新模板
     */
    @Transactional
    fun updateTemplate(request: TemplateUpdateRequest): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            // 如果提供了模板名称，验证名称唯一性
            if (request.templateName != null) {
                if (request.templateName.isBlank()) {
                    return Result.failure(IllegalArgumentException("模板名称不能为空"))
                }
                // 如果新名称与当前名称不同，检查是否已存在
                if (request.templateName != template.templateName) {
                    if (templateRepository.existsByTemplateName(request.templateName)) {
                        return Result.failure(IllegalArgumentException("模板名称已存在"))
                    }
                }
            }
            
            // 验证 copyMode
            val updated = template.copy(
                templateName = request.templateName ?: template.templateName,
                copyMode = request.copyMode ?: template.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: template.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: template.fixedAmount,
                adaptiveMinRatio = mergeOptionalDecimal(request.adaptiveMinRatio, template.adaptiveMinRatio),
                adaptiveMaxRatio = mergeOptionalDecimal(request.adaptiveMaxRatio, template.adaptiveMaxRatio),
                adaptiveThreshold = mergeOptionalDecimal(request.adaptiveThreshold, template.adaptiveThreshold),
                multiplierMode = request.multiplierMode ?: template.multiplierMode,
                tradeMultiplier = mergeOptionalDecimal(request.tradeMultiplier, template.tradeMultiplier),
                tieredMultipliers = if (request.tieredMultipliers != null) {
                    CopyTradingSizingSupport.serializeTieredMultipliers(request.tieredMultipliers)
                } else {
                    template.tieredMultipliers
                },
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: template.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: template.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: template.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: template.maxDailyOrders,
                maxDailyVolume = mergeOptionalDecimal(request.maxDailyVolume, template.maxDailyVolume),
                smallOrderAggregationEnabled = request.smallOrderAggregationEnabled ?: template.smallOrderAggregationEnabled,
                smallOrderAggregationWindowSeconds = request.smallOrderAggregationWindowSeconds
                    ?: template.smallOrderAggregationWindowSeconds,
                repeatAddReductionEnabled = request.repeatAddReductionEnabled ?: template.repeatAddReductionEnabled,
                repeatAddReductionStrategy = request.repeatAddReductionStrategy ?: template.repeatAddReductionStrategy,
                repeatAddReductionValueType = request.repeatAddReductionValueType ?: template.repeatAddReductionValueType,
                repeatAddReductionPercent = mergeOptionalDecimal(request.repeatAddReductionPercent, template.repeatAddReductionPercent),
                repeatAddReductionFixedAmount = mergeOptionalDecimal(
                    request.repeatAddReductionFixedAmount,
                    template.repeatAddReductionFixedAmount
                ),
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: template.priceTolerance,
                delaySeconds = request.delaySeconds ?: template.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: template.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: template.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: template.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: template.websocketMaxRetries,
                supportSell = request.supportSell ?: template.supportSell,
                minOrderDepth = mergeOptionalDecimal(request.minOrderDepth, template.minOrderDepth),
                maxSpread = mergeOptionalDecimal(request.maxSpread, template.maxSpread),
                minPrice = mergeOptionalDecimal(request.minPrice, template.minPrice),
                maxPrice = mergeOptionalDecimal(request.maxPrice, template.maxPrice),
                marketCategoryMode = request.marketCategoryMode?.let { MarketFilterSupport.normalizeFilterMode(it) }
                    ?: template.marketCategoryMode,
                marketCategories = when {
                    request.marketCategoryMode?.let { MarketFilterSupport.normalizeFilterMode(it) } == MarketFilterSupport.FILTER_MODE_DISABLED -> null
                    request.marketCategories != null -> convertMarketCategoriesToJson(request.marketCategories)
                    else -> template.marketCategories
                },
                marketIntervalMode = request.marketIntervalMode?.let { MarketFilterSupport.normalizeFilterMode(it) }
                    ?: template.marketIntervalMode,
                marketIntervals = when {
                    request.marketIntervalMode?.let { MarketFilterSupport.normalizeFilterMode(it) } == MarketFilterSupport.FILTER_MODE_DISABLED -> null
                    request.marketIntervals != null -> convertMarketIntervalsToJson(request.marketIntervals)
                    else -> template.marketIntervals
                },
                marketSeriesMode = request.marketSeriesMode?.let { MarketFilterSupport.normalizeFilterMode(it) }
                    ?: template.marketSeriesMode,
                marketSeries = when {
                    request.marketSeriesMode?.let { MarketFilterSupport.normalizeFilterMode(it) } == MarketFilterSupport.FILTER_MODE_DISABLED -> null
                    request.marketSeries != null -> convertMarketSeriesToJson(request.marketSeries)
                    else -> template.marketSeries
                },
                coinFilterMode = request.coinFilterMode?.let { MarketFilterSupport.normalizeFilterMode(it) }
                    ?: template.coinFilterMode,
                coinSymbols = when {
                    request.coinFilterMode?.let { MarketFilterSupport.normalizeFilterMode(it) } == MarketFilterSupport.FILTER_MODE_DISABLED -> null
                    request.coinSymbols != null -> convertCoinSymbolsToJson(request.coinSymbols)
                    else -> template.coinSymbols
                },
                pushFilteredOrders = request.pushFilteredOrders ?: template.pushFilteredOrders,
                updatedAt = System.currentTimeMillis()
            )

            validateTemplate(updated)?.let { return Result.failure(IllegalArgumentException(it)) }
            
            val saved = templateRepository.save(updated)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除模板
     */
    @Transactional
    fun deleteTemplate(templateId: Long): Result<Unit> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            // 模板不再绑定跟单配置，可以直接删除，无需检查使用情况
            templateRepository.delete(template)
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 复制模板
     */
    @Transactional
    fun copyTemplate(request: TemplateCopyRequest): Result<TemplateDto> {
        return try {
            val sourceTemplate = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("源模板不存在"))
            
            // 检查新模板名称是否已存在
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            
            // 创建新模板
            val newTemplate = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = request.copyMode ?: sourceTemplate.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: sourceTemplate.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: sourceTemplate.fixedAmount,
                adaptiveMinRatio = request.adaptiveMinRatio?.toSafeBigDecimal() ?: sourceTemplate.adaptiveMinRatio,
                adaptiveMaxRatio = request.adaptiveMaxRatio?.toSafeBigDecimal() ?: sourceTemplate.adaptiveMaxRatio,
                adaptiveThreshold = request.adaptiveThreshold?.toSafeBigDecimal() ?: sourceTemplate.adaptiveThreshold,
                multiplierMode = request.multiplierMode ?: sourceTemplate.multiplierMode,
                tradeMultiplier = request.tradeMultiplier?.toSafeBigDecimal() ?: sourceTemplate.tradeMultiplier,
                tieredMultipliers = request.tieredMultipliers?.let { CopyTradingSizingSupport.serializeTieredMultipliers(it) }
                    ?: sourceTemplate.tieredMultipliers,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: sourceTemplate.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: sourceTemplate.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: sourceTemplate.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: sourceTemplate.maxDailyOrders,
                maxDailyVolume = request.maxDailyVolume?.toSafeBigDecimal() ?: sourceTemplate.maxDailyVolume,
                smallOrderAggregationEnabled = request.smallOrderAggregationEnabled ?: sourceTemplate.smallOrderAggregationEnabled,
                smallOrderAggregationWindowSeconds = request.smallOrderAggregationWindowSeconds
                    ?: sourceTemplate.smallOrderAggregationWindowSeconds,
                repeatAddReductionEnabled = request.repeatAddReductionEnabled ?: sourceTemplate.repeatAddReductionEnabled,
                repeatAddReductionStrategy = request.repeatAddReductionStrategy ?: sourceTemplate.repeatAddReductionStrategy,
                repeatAddReductionValueType = request.repeatAddReductionValueType ?: sourceTemplate.repeatAddReductionValueType,
                repeatAddReductionPercent = request.repeatAddReductionPercent?.toSafeBigDecimal()
                    ?: sourceTemplate.repeatAddReductionPercent,
                repeatAddReductionFixedAmount = request.repeatAddReductionFixedAmount?.toSafeBigDecimal()
                    ?: sourceTemplate.repeatAddReductionFixedAmount,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: sourceTemplate.priceTolerance,
                delaySeconds = request.delaySeconds ?: sourceTemplate.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: sourceTemplate.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: sourceTemplate.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: sourceTemplate.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: sourceTemplate.websocketMaxRetries,
                supportSell = request.supportSell ?: sourceTemplate.supportSell,
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal() ?: sourceTemplate.minOrderDepth,
                maxSpread = request.maxSpread?.toSafeBigDecimal() ?: sourceTemplate.maxSpread,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: sourceTemplate.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: sourceTemplate.maxPrice,
                marketCategoryMode = MarketFilterSupport.normalizeFilterMode(
                    request.marketCategoryMode ?: sourceTemplate.marketCategoryMode
                ),
                marketCategories = resolveMarketCategoriesJson(
                    mode = MarketFilterSupport.normalizeFilterMode(
                        request.marketCategoryMode ?: sourceTemplate.marketCategoryMode
                    ),
                    categories = request.marketCategories ?: jsonUtils.parseStringArray(sourceTemplate.marketCategories)
                ),
                marketIntervalMode = MarketFilterSupport.normalizeFilterMode(
                    request.marketIntervalMode ?: sourceTemplate.marketIntervalMode
                ),
                marketIntervals = resolveMarketIntervalsJson(
                    mode = MarketFilterSupport.normalizeFilterMode(
                        request.marketIntervalMode ?: sourceTemplate.marketIntervalMode
                    ),
                    intervals = request.marketIntervals ?: jsonUtils.parseIntArray(sourceTemplate.marketIntervals)
                ),
                marketSeriesMode = MarketFilterSupport.normalizeFilterMode(
                    request.marketSeriesMode ?: sourceTemplate.marketSeriesMode
                ),
                marketSeries = resolveMarketSeriesJson(
                    mode = MarketFilterSupport.normalizeFilterMode(
                        request.marketSeriesMode ?: sourceTemplate.marketSeriesMode
                    ),
                    series = request.marketSeries ?: jsonUtils.parseStringArray(sourceTemplate.marketSeries)
                ),
                coinFilterMode = MarketFilterSupport.normalizeFilterMode(
                    request.coinFilterMode ?: sourceTemplate.coinFilterMode
                ),
                coinSymbols = resolveCoinSymbolsJson(
                    mode = MarketFilterSupport.normalizeFilterMode(
                        request.coinFilterMode ?: sourceTemplate.coinFilterMode
                    ),
                    symbols = request.coinSymbols ?: jsonUtils.parseStringArray(sourceTemplate.coinSymbols)
                ),
                pushFilteredOrders = request.pushFilteredOrders ?: sourceTemplate.pushFilteredOrders
            )

            validateTemplate(newTemplate)?.let { return Result.failure(IllegalArgumentException(it)) }
            
            val saved = templateRepository.save(newTemplate)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("复制模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询模板列表
     */
    fun getTemplateList(): Result<TemplateListResponse> {
        return try {
            val templates = templateRepository.findAllByOrderByCreatedAtDesc()
            val templateDtos = templates.map { template ->
                toDto(template)
            }
            
            Result.success(
                TemplateListResponse(
                    list = templateDtos,
                    total = templateDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询模板列表失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询模板详情
     */
    fun getTemplateDetail(templateId: Long): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            Result.success(toDto(template))
        } catch (e: Exception) {
            logger.error("查询模板详情失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     */
    private fun toDto(template: CopyTradingTemplate): TemplateDto {
        return TemplateDto(
            id = template.id!!,
            templateName = template.templateName,
            copyMode = template.copyMode,
            copyRatio = template.copyRatio.toPlainString(),
            fixedAmount = template.fixedAmount?.toPlainString(),
            adaptiveMinRatio = template.adaptiveMinRatio?.toPlainString(),
            adaptiveMaxRatio = template.adaptiveMaxRatio?.toPlainString(),
            adaptiveThreshold = template.adaptiveThreshold?.toPlainString(),
            multiplierMode = template.multiplierMode,
            tradeMultiplier = template.tradeMultiplier?.toPlainString(),
            tieredMultipliers = CopyTradingSizingSupport.toTierDtoList(template.tieredMultipliers),
            maxOrderSize = template.maxOrderSize.toPlainString(),
            minOrderSize = template.minOrderSize.toPlainString(),
            maxDailyLoss = template.maxDailyLoss.toPlainString(),
            maxDailyOrders = template.maxDailyOrders,
            maxDailyVolume = template.maxDailyVolume?.toPlainString(),
            smallOrderAggregationEnabled = template.smallOrderAggregationEnabled,
            smallOrderAggregationWindowSeconds = template.smallOrderAggregationWindowSeconds,
            repeatAddReductionEnabled = template.repeatAddReductionEnabled,
            repeatAddReductionStrategy = template.repeatAddReductionStrategy,
            repeatAddReductionValueType = template.repeatAddReductionValueType,
            repeatAddReductionPercent = template.repeatAddReductionPercent?.toPlainString(),
            repeatAddReductionFixedAmount = template.repeatAddReductionFixedAmount?.toPlainString(),
            priceTolerance = template.priceTolerance.toPlainString(),
            delaySeconds = template.delaySeconds,
            pollIntervalSeconds = template.pollIntervalSeconds,
            useWebSocket = template.useWebSocket,
            websocketReconnectInterval = template.websocketReconnectInterval,
            websocketMaxRetries = template.websocketMaxRetries,
            supportSell = template.supportSell,
            minOrderDepth = template.minOrderDepth?.toPlainString(),
            maxSpread = template.maxSpread?.toPlainString(),
            minPrice = template.minPrice?.toPlainString(),
            maxPrice = template.maxPrice?.toPlainString(),
            marketCategoryMode = template.marketCategoryMode,
            marketCategories = jsonUtils.parseStringArray(template.marketCategories).takeIf { it.isNotEmpty() },
            marketIntervalMode = template.marketIntervalMode,
            marketIntervals = jsonUtils.parseIntArray(template.marketIntervals).takeIf { it.isNotEmpty() },
            marketSeriesMode = template.marketSeriesMode,
            marketSeries = jsonUtils.parseStringArray(template.marketSeries).takeIf { it.isNotEmpty() },
            coinFilterMode = template.coinFilterMode,
            coinSymbols = jsonUtils.parseStringArray(template.coinSymbols).takeIf { it.isNotEmpty() },
            pushFilteredOrders = template.pushFilteredOrders,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt
        )
    }

    private fun convertMarketCategoriesToJson(categories: List<String>?): String? {
        val normalized = MarketFilterSupport.normalizeMarketCategories(categories)
        return if (normalized.isEmpty()) null else gson.toJson(normalized)
    }

    private fun resolveMarketCategoriesJson(mode: String, categories: List<String>?): String? {
        return if (mode == MarketFilterSupport.FILTER_MODE_DISABLED) null else convertMarketCategoriesToJson(categories)
    }

    private fun convertMarketIntervalsToJson(intervals: List<Int>?): String? {
        val normalized = MarketFilterSupport.normalizeMarketIntervals(intervals)
        return if (normalized.isEmpty()) null else gson.toJson(normalized)
    }

    private fun resolveMarketIntervalsJson(mode: String, intervals: List<Int>?): String? {
        return if (mode == MarketFilterSupport.FILTER_MODE_DISABLED) null else convertMarketIntervalsToJson(intervals)
    }

    private fun convertMarketSeriesToJson(series: List<String>?): String? {
        val normalized = MarketFilterSupport.normalizeMarketSeries(series)
        return if (normalized.isEmpty()) null else gson.toJson(normalized)
    }

    private fun resolveMarketSeriesJson(mode: String, series: List<String>?): String? {
        return if (mode == MarketFilterSupport.FILTER_MODE_DISABLED) null else convertMarketSeriesToJson(series)
    }

    private fun convertCoinSymbolsToJson(symbols: List<String>?): String? {
        val normalized = MarketFilterSupport.normalizeCoinSymbols(symbols)
        return if (normalized.isEmpty()) null else gson.toJson(normalized)
    }

    private fun resolveCoinSymbolsJson(mode: String, symbols: List<String>?): String? {
        return if (mode == MarketFilterSupport.FILTER_MODE_DISABLED) null else convertCoinSymbolsToJson(symbols)
    }

    private fun mergeOptionalDecimal(rawValue: String?, currentValue: BigDecimal?): BigDecimal? {
        if (rawValue == null) {
            return currentValue
        }
        if (rawValue.isBlank()) {
            return null
        }
        val converted = rawValue.toSafeBigDecimal()
        return if (converted == IllegalBigDecimal) currentValue else converted
    }

    private fun validateTemplate(template: CopyTradingTemplate): String? {
        val config = CopyTradingSizingConfig(
            copyMode = template.copyMode,
            copyRatio = template.copyRatio,
            fixedAmount = template.fixedAmount,
            adaptiveMinRatio = template.adaptiveMinRatio,
            adaptiveMaxRatio = template.adaptiveMaxRatio,
            adaptiveThreshold = template.adaptiveThreshold,
            multiplierMode = template.multiplierMode,
            tradeMultiplier = template.tradeMultiplier,
            tieredMultipliers = CopyTradingSizingSupport.parseTieredMultipliers(template.tieredMultipliers),
            maxOrderSize = template.maxOrderSize,
            minOrderSize = template.minOrderSize,
            maxPositionValue = null,
            maxPositionCount = null,
            maxDailyVolume = template.maxDailyVolume,
            repeatAddReductionEnabled = template.repeatAddReductionEnabled,
            repeatAddReductionStrategy = template.repeatAddReductionStrategy,
            repeatAddReductionValueType = template.repeatAddReductionValueType,
            repeatAddReductionPercent = template.repeatAddReductionPercent,
            repeatAddReductionFixedAmount = template.repeatAddReductionFixedAmount
        )
        CopyTradingSizingSupport.validateConfig(config).firstOrNull()?.let { return it }
        SmallOrderAggregationSupport.validateConfig(
            enabled = template.smallOrderAggregationEnabled,
            windowSeconds = template.smallOrderAggregationWindowSeconds
        ).firstOrNull()?.let { return it }

        val marketCategoryMode = MarketFilterSupport.normalizeFilterMode(template.marketCategoryMode)
        MarketFilterSupport.validateFilterMode(marketCategoryMode, "marketCategoryMode")?.let { return it }
        MarketFilterSupport.validateFilterValues(
            mode = marketCategoryMode,
            values = jsonUtils.parseStringArray(template.marketCategories),
            fieldLabel = "模板市场分类过滤"
        )?.let { return it }

        val marketIntervalMode = MarketFilterSupport.normalizeFilterMode(template.marketIntervalMode)
        MarketFilterSupport.validateFilterMode(marketIntervalMode, "marketIntervalMode")?.let { return it }
        MarketFilterSupport.validateFilterValues(
            mode = marketIntervalMode,
            values = jsonUtils.parseIntArray(template.marketIntervals),
            fieldLabel = "模板市场周期过滤"
        )?.let { return it }

        val marketSeriesMode = MarketFilterSupport.normalizeFilterMode(template.marketSeriesMode)
        MarketFilterSupport.validateFilterMode(marketSeriesMode, "marketSeriesMode")?.let { return it }
        MarketFilterSupport.validateFilterValues(
            mode = marketSeriesMode,
            values = jsonUtils.parseStringArray(template.marketSeries),
            fieldLabel = "模板市场系列过滤"
        )?.let { return it }

        val coinFilterMode = MarketFilterSupport.normalizeFilterMode(template.coinFilterMode)
        MarketFilterSupport.validateFilterMode(coinFilterMode, "coinFilterMode")?.let { return it }
        MarketFilterSupport.validateFilterValues(
            mode = coinFilterMode,
            values = jsonUtils.parseStringArray(template.coinSymbols),
            fieldLabel = "模板币种过滤"
        )?.let { return it }

        return null
    }
}
