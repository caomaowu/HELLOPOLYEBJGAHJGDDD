package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.DateUtils
import org.slf4j.LoggerFactory
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 跟单过滤条件检查服务
 */
@Service
class CopyTradingFilterService(
    private val clobService: PolymarketClobService,
    private val jsonUtils: JsonUtils
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingFilterService::class.java)
    
    /**
     * 检查过滤条件
     * @param copyTrading 跟单配置
     * @param tokenId token ID（用于获取订单簿）
     * @param tradePrice Leader 交易价格，用于价格区间检查
     * @param marketTitle 市场标题，用于关键字过滤
     * @param marketEndDate 市场截止时间，用于市场截止时间检查
     * @return 过滤结果
     */
    suspend fun checkFilters(
        copyTrading: CopyTrading,
        tokenId: String,
        tradePrice: BigDecimal? = null,  // Leader 交易价格，用于价格区间检查
        marketTitle: String? = null,  // 市场标题，用于关键字过滤
        marketEndDate: Long? = null  // 市场截止时间，用于市场截止时间检查
    ): FilterResult {
        // 1. 关键字过滤检查（如果配置了关键字过滤）
        if (copyTrading.keywordFilterMode != null && copyTrading.keywordFilterMode != "DISABLED") {
            val keywordCheck = checkKeywordFilter(copyTrading, marketTitle)
            if (!keywordCheck.isPassed) {
                return keywordCheck
            }
        }
        
        // 1.5. 市场截止时间检查（如果配置了市场截止时间限制）
        if (copyTrading.maxMarketEndDate != null) {
            val marketEndDateCheck = checkMarketEndDate(copyTrading, marketEndDate)
            if (!marketEndDateCheck.isPassed) {
                return marketEndDateCheck
            }
        }
        
        // 2. 价格区间检查（如果配置了价格区间）
        if (tradePrice != null) {
            val priceRangeCheck = checkPriceRange(copyTrading, tradePrice)
            if (!priceRangeCheck.isPassed) {
                return FilterResult.priceRangeFailed(priceRangeCheck.reason)
            }
        }
        
        // 3. 检查是否需要获取订单簿
        // 只有在配置了需要订单簿的过滤条件时才获取订单簿
        val needOrderbook = copyTrading.maxSpread != null || copyTrading.minOrderDepth != null

        // 3.5. 如果不需要订单簿，则直接通过
        if (!needOrderbook) {
            return FilterResult.passed()
        }
        
        // 4. 获取订单簿（仅在需要时，只请求一次）
        val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
        if (!orderbookResult.isSuccess) {
            val error = orderbookResult.exceptionOrNull()
            return FilterResult.orderbookError("获取订单簿失败: ${error?.message ?: "未知错误"}")
        }
        
        val orderbook = orderbookResult.getOrNull()
            ?: return FilterResult.orderbookEmpty()
        
        // 5. 买一卖一价差过滤（如果配置了）
        if (copyTrading.maxSpread != null) {
            val spreadCheck = checkSpread(copyTrading, orderbook)
            if (!spreadCheck.isPassed) {
                return FilterResult.spreadFailed(spreadCheck.reason, orderbook)
            }
        }
        
        // 6. 订单深度过滤（如果配置了，检查所有方向）
        if (copyTrading.minOrderDepth != null) {
            val depthCheck = checkOrderDepth(copyTrading, orderbook)
            if (!depthCheck.isPassed) {
                return FilterResult.orderDepthFailed(depthCheck.reason, orderbook)
            }
        }
        
        return FilterResult.passed(orderbook)
    }
    
    /**
     * 检查关键字过滤
     * @param copyTrading 跟单配置
     * @param marketTitle 市场标题
     * @return 过滤结果
     */
    private fun checkKeywordFilter(
        copyTrading: CopyTrading,
        marketTitle: String?
    ): FilterResult {
        // 如果未启用关键字过滤，直接通过
        if (copyTrading.keywordFilterMode == null || copyTrading.keywordFilterMode == "DISABLED") {
            return FilterResult.passed()
        }
        
        // 如果没有市场标题，无法进行关键字过滤，为了安全起见，不通过
        if (marketTitle.isNullOrBlank()) {
            return FilterResult.keywordFilterFailed("市场标题为空，无法进行关键字过滤")
        }
        
        // 解析关键字列表
        val keywords = jsonUtils.parseStringArray(copyTrading.keywords)
        if (keywords.isEmpty()) {
            // 如果关键字列表为空，白名单模式不通过，黑名单模式通过
            return if (copyTrading.keywordFilterMode == "WHITELIST") {
                FilterResult.keywordFilterFailed("白名单模式但关键字列表为空")
            } else {
                FilterResult.passed()
            }
        }
        
        // 将市场标题转换为小写，用于不区分大小写的匹配
        val titleLower = marketTitle.lowercase()
        
        // 检查市场标题是否包含任意关键字
        val containsKeyword = keywords.any { keyword ->
            titleLower.contains(keyword.lowercase())
        }
        
        // 根据过滤模式决定是否通过
        return when (copyTrading.keywordFilterMode) {
            "WHITELIST" -> {
                if (containsKeyword) {
                    FilterResult.passed()
                } else {
                    FilterResult.keywordFilterFailed("白名单模式：市场标题不包含任何关键字。市场标题：$marketTitle，关键字列表：${keywords.joinToString(", ")}")
                }
            }
            "BLACKLIST" -> {
                if (containsKeyword) {
                    FilterResult.keywordFilterFailed("黑名单模式：市场标题包含关键字。市场标题：$marketTitle，匹配的关键字：${keywords.filter { titleLower.contains(it.lowercase()) }.joinToString(", ")}")
                } else {
                    FilterResult.passed()
                }
            }
            else -> FilterResult.passed()
        }
    }
    
    /**
     * 检查价格区间
     * @param copyTrading 跟单配置
     * @param tradePrice Leader 交易价格
     * @return 过滤结果
     */
    private fun checkPriceRange(
        copyTrading: CopyTrading,
        tradePrice: BigDecimal
    ): FilterResult {
        // 如果未配置价格区间，直接通过
        if (copyTrading.minPrice == null && copyTrading.maxPrice == null) {
            return FilterResult.passed()
        }
        
        // 检查最低价格
        if (copyTrading.minPrice != null && tradePrice.lt(copyTrading.minPrice)) {
            val priceStr = tradePrice.stripTrailingZeros().toPlainString()
            val minPriceStr = copyTrading.minPrice.stripTrailingZeros().toPlainString()
            return FilterResult.priceRangeFailed("价格低于最低限制: $priceStr < $minPriceStr")
        }
        
        // 检查最高价格
        if (copyTrading.maxPrice != null && tradePrice.gt(copyTrading.maxPrice)) {
            val priceStr = tradePrice.stripTrailingZeros().toPlainString()
            val maxPriceStr = copyTrading.maxPrice.stripTrailingZeros().toPlainString()
            return FilterResult.priceRangeFailed("价格高于最高限制: $priceStr > $maxPriceStr")
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查买一卖一价差
     * bestBid: 买盘中的最高价格（最大值）
     * bestAsk: 卖盘中的最低价格（最小值）
     */
    private fun checkSpread(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        // 如果未启用价差过滤，直接通过
        if (copyTrading.maxSpread == null) {
            return FilterResult.passed()
        }
        
        // 获取买盘中的最高价格（bestBid = bids 中的最大值）
        val bestBid = orderbook.bids
            .mapNotNull { it.price.toSafeBigDecimal() }
            .maxOrNull()
        
        // 获取卖盘中的最低价格（bestAsk = asks 中的最小值）
        val bestAsk = orderbook.asks
            .mapNotNull { it.price.toSafeBigDecimal() }
            .minOrNull()
        
        if (bestBid == null || bestAsk == null) {
            return FilterResult.spreadFailed("订单簿缺少买一或卖一价格", orderbook)
        }
        
        // 计算价差（绝对价格）
        val spread = bestAsk.subtract(bestBid)
        
        if (spread.gt(copyTrading.maxSpread)) {
            val spreadStr = spread.stripTrailingZeros().toPlainString()
            val maxSpreadStr = copyTrading.maxSpread.stripTrailingZeros().toPlainString()
            return FilterResult.spreadFailed("价差过大: $spreadStr > $maxSpreadStr", orderbook)
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查订单深度（检查所有方向：买盘和卖盘的总深度）
     */
    private fun checkOrderDepth(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        // 如果未启用订单深度过滤，直接通过
        if (copyTrading.minOrderDepth == null) {
            return FilterResult.passed()
        }
        
        // 计算买盘（bids）总深度
        var bidsDepth = BigDecimal.ZERO
        for (order in orderbook.bids) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            val orderAmount = price.multi(size)
            bidsDepth = bidsDepth.add(orderAmount)
        }
        
        // 计算卖盘（asks）总深度
        var asksDepth = BigDecimal.ZERO
        for (order in orderbook.asks) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            val orderAmount = price.multi(size)
            asksDepth = asksDepth.add(orderAmount)
        }
        
        // 计算总深度（买盘 + 卖盘）
        val totalDepth = bidsDepth.add(asksDepth)
        
        if (totalDepth.lt(copyTrading.minOrderDepth)) {
            val totalDepthStr = totalDepth.stripTrailingZeros().toPlainString()
            val minDepthStr = copyTrading.minOrderDepth.stripTrailingZeros().toPlainString()
            return FilterResult.orderDepthFailed("订单深度不足: $totalDepthStr < $minDepthStr", orderbook)
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查市场截止时间
     * @param copyTrading 跟单配置
     * @param marketEndDate 市场截止时间（毫秒时间戳）
     * @return 过滤结果
     */
    private fun checkMarketEndDate(
        copyTrading: CopyTrading,
        marketEndDate: Long?
    ): FilterResult {
        // 如果未配置市场截止时间限制，直接通过
        if (copyTrading.maxMarketEndDate == null) {
            return FilterResult.passed()
        }
        
        // 如果没有市场截止时间，无法检查，为了安全起见，不通过
        if (marketEndDate == null) {
            return FilterResult.marketEndDateFailed("市场缺少截止时间信息，无法进行市场截止时间检查")
        }
        
        // 检查：市场截止时间 - 当前时间 <= 最大限制时间
        val currentTime = System.currentTimeMillis()
        val remainingTime = marketEndDate - currentTime
        
        if (remainingTime > copyTrading.maxMarketEndDate) {
            val remainingTimeFormatted = DateUtils.formatDuration(remainingTime)
            val maxLimitFormatted = DateUtils.formatDuration(copyTrading.maxMarketEndDate)
            return FilterResult.marketEndDateFailed(
                "市场截止时间超出限制: 剩余时间=${remainingTimeFormatted} > 最大限制=${maxLimitFormatted}"
            )
        }
        
        return FilterResult.passed()
    }
}

