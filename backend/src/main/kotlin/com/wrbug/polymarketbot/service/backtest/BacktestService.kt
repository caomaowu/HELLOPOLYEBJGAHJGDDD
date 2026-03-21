package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.BacktestAuditEvent
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.BacktestAuditEventRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingConfig
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingSupport
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 回测任务服务
 */
@Service
class BacktestService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val backtestAuditEventRepository: BacktestAuditEventRepository,
    private val leaderRepository: LeaderRepository
) {
    private val logger = LoggerFactory.getLogger(BacktestService::class.java)

    /**
     * 创建回测任务
     */
    @Transactional
    fun createBacktestTask(request: BacktestCreateRequest): Result<BacktestTaskDto> {
        return try {
            // 1. 验证 Leader 是否存在
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            // 2. 验证回测天数
            if (request.backtestDays < 1 || request.backtestDays > 15) {
                return Result.failure(IllegalArgumentException("回测天数必须在 1-15 之间"))
            }

            // 3. 验证恢复页码（如果提供）
            if (request.pageForResume != null && request.pageForResume < 1) {
                return Result.failure(IllegalArgumentException("恢复页码必须大于 0"))
            }

            // 4. 验证初始金额
            val initialBalance = request.initialBalance.toSafeBigDecimal()
            if (initialBalance <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("初始金额必须大于 0"))
            }

            // 4. 创建回测任务
            val task = BacktestTask(
                taskName = request.taskName.trim(),
                leaderId = request.leaderId,
                initialBalance = initialBalance,
                backtestDays = request.backtestDays,
                startTime = System.currentTimeMillis() - (request.backtestDays * 24 * 3600 * 1000),
                status = "PENDING",

                // 跟单配置（不包含 max_position_count）
                copyMode = request.copyMode ?: "RATIO",
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
                supportSell = request.supportSell ?: true,
                keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                keywords = if (request.keywords != null && request.keywords.isNotEmpty()) {
                    request.keywords.toJson()
                } else {
                    null
                },
                maxPositionValue = request.maxPositionValue?.toSafeBigDecimal(),
                minPrice = request.minPrice?.toSafeBigDecimal(),
                maxPrice = request.maxPrice?.toSafeBigDecimal()
            )

            validateTask(task)?.let { return Result.failure(IllegalArgumentException(it)) }

            backtestTaskRepository.save(task)

            // 5. 转换为 DTO 返回
            Result.success(task.toDto(leader))
        } catch (e: Exception) {
            logger.error("创建回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测任务列表
     */
    fun getBacktestTaskList(request: BacktestListRequest): Result<BacktestListResponse> {
        return try {
            // 获取所有符合条件的任务
            val allTasks = when {
                request.leaderId != null && request.status != null -> {
                    backtestTaskRepository.findByLeaderIdAndStatus(request.leaderId, request.status)
                }
                request.leaderId != null -> {
                    backtestTaskRepository.findByLeaderId(request.leaderId)
                        .filter { request.status == null || it.status == request.status }
                }
                request.status != null -> {
                    backtestTaskRepository.findByStatus(request.status)
                }
                else -> {
                    backtestTaskRepository.findAll()
                }
            }

            // 排序
            val sortedTasks = when (request.sortBy) {
                "profitAmount" -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.profitAmount }
                    } else {
                        allTasks.sortedByDescending { it.profitAmount }
                    }
                }
                "profitRate" -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.profitRate }
                    } else {
                        allTasks.sortedByDescending { it.profitRate }
                    }
                }
                else -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.createdAt }
                    } else {
                        allTasks.sortedByDescending { it.createdAt }
                    }
                }
            }

            // 分页
            val total = sortedTasks.size
            val pagedTasks = sortedTasks
                .drop((request.page - 1) * request.size)
                .take(request.size)

            val list = pagedTasks.map { task ->
                val leader = leaderRepository.findById(task.leaderId).orElse(null)
                task.toDto(leader)
            }

            Result.success(
                BacktestListResponse(
                    list = list,
                    total = total.toLong(),
                    page = request.page,
                    size = request.size
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测任务列表失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测任务详情
     */
    fun getBacktestTaskDetail(request: BacktestDetailRequest): Result<BacktestDetailResponse> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            val leader = leaderRepository.findById(task.leaderId).orElse(null)

            val taskDto = task.toDto(leader)

            Result.success(
                BacktestDetailResponse(
                    task = taskDto,
                    config = task.toConfigDto(),
                    statistics = task.toStatisticsDto()
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测任务详情失败", e)
            Result.failure(e)
        }
    }

    /**
     * 比较多个已完成回测任务
     */
    fun compareBacktestTasks(request: BacktestCompareRequest): Result<BacktestCompareResponse> {
        return try {
            val taskIds = request.taskIds.distinct()
            require(taskIds.size >= 2) { "请至少选择两个回测任务进行比较" }
            require(taskIds.size <= 10) { "单次最多比较 10 个回测任务" }

            val tasks = backtestTaskRepository.findAllById(taskIds).toList()
            require(tasks.size == taskIds.size) { "存在回测任务不存在" }
            require(tasks.all { it.status == "COMPLETED" }) { "仅支持比较已完成的回测任务" }

            val leaderMap = leaderRepository.findAllById(tasks.map { it.leaderId }.distinct()).associateBy { it.id }
            val items = taskIds.mapNotNull { taskId ->
                val task = tasks.firstOrNull { it.id == taskId } ?: return@mapNotNull null
                val leader = leaderMap[task.leaderId]
                BacktestCompareItemDto(
                    task = task.toDto(leader),
                    config = task.toConfigDto(),
                    statistics = task.toStatisticsDto(),
                    highlights = buildHighlights(task)
                )
            }

            Result.success(
                BacktestCompareResponse(
                    list = items,
                    configDifferences = buildConfigDifferences(items),
                    summary = buildCompareSummary(items)
                )
            )
        } catch (e: Exception) {
            logger.error("比较回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 生成回测审计摘要
     * 复用 compare 主干，并补充事件级审计摘要。
     */
    fun getBacktestAudit(request: BacktestAuditRequest): Result<BacktestAuditResponse> {
        return compareBacktestTasks(BacktestCompareRequest(taskIds = request.taskIds)).map { compare ->
            val taskIds = compare.list.map { it.task.id }
            val targetTaskId = resolveTargetTaskId(
                candidateTaskIds = taskIds,
                preferredTaskId = request.targetTaskId
            )
            val summary = targetTaskId?.let { buildAuditSummary(it) }
            val recentEvents = if (request.includeEventTrail != false && targetTaskId != null) {
                val eventLimit = (request.eventPageSize ?: 50).coerceIn(1, 200)
                backtestAuditEventRepository.findTop200ByBacktestTaskIdOrderByIdDesc(targetTaskId)
                    .take(eventLimit)
                    .asReversed()
                    .map(::toAuditEventDto)
            } else {
                emptyList()
            }
            BacktestAuditResponse(
                compare = compare,
                generatedAt = System.currentTimeMillis(),
                summary = summary,
                recentEvents = recentEvents
            )
        }
    }

    /**
     * 查询回测事件级审计链
     */
    fun getBacktestAuditEvents(request: BacktestAuditEventListRequest): Result<BacktestAuditEventListResponse> {
        return try {
            require(request.taskId > 0) { "taskId 必须大于 0" }
            val task = backtestTaskRepository.findById(request.taskId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            val page = request.page.coerceAtLeast(1)
            val size = request.size.coerceIn(1, 500)
            val stage = request.stage?.trim()?.takeIf(String::isNotBlank)
            val decision = request.decision?.trim()?.uppercase()?.takeIf(String::isNotBlank)
            val eventType = request.eventType?.trim()?.takeIf(String::isNotBlank)
            val pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Order.asc("id"))
            )
            val eventPage = backtestAuditEventRepository.findByTaskIdWithFilters(
                taskId = task.id!!,
                stage = stage,
                decision = decision,
                eventType = eventType,
                pageable = pageable
            )
            Result.success(
                BacktestAuditEventListResponse(
                    list = eventPage.content.map(::toAuditEventDto),
                    total = eventPage.totalElements,
                    page = page,
                    size = size,
                    summary = buildAuditSummary(task.id)
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测审计事件失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测交易记录
     */
    fun getBacktestTrades(request: BacktestTradeListRequest): Result<BacktestTradeListResponse> {
        return try {
            val pageRequest = PageRequest.of(
                request.page - 1,
                request.size,
                Sort.by(Sort.Order.asc("tradeTime"))
            )

            val tradesPage = backtestTradeRepository.findByBacktestTaskId(
                request.taskId,
                pageRequest
            )

            val list = tradesPage.content.map { trade ->
                BacktestTradeDto(
                    id = trade.id!!,
                    tradeTime = trade.tradeTime,
                    marketId = trade.marketId,
                    marketTitle = trade.marketTitle,
                    side = trade.side,
                    outcome = trade.outcome,
                    outcomeIndex = trade.outcomeIndex,
                    quantity = trade.quantity.toPlainString(),
                    price = trade.price.toPlainString(),
                    amount = trade.amount.toPlainString(),
                    fee = trade.fee.toPlainString(),
                    profitLoss = trade.profitLoss?.toPlainString(),
                    balanceAfter = trade.balanceAfter.toPlainString(),
                    leaderTradeId = trade.leaderTradeId
                )
            }

            Result.success(
                BacktestTradeListResponse(
                    list = list,
                    total = tradesPage.totalElements,
                    page = request.page,
                    size = request.size
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测交易记录失败", e)
            Result.failure(e)
        }
    }

    /**
     * 删除回测任务
     */
    @Transactional
    fun deleteBacktestTask(request: BacktestDeleteRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status == "RUNNING") {
                return Result.failure(IllegalStateException("回测任务正在运行，无法删除"))
            }

            backtestTaskRepository.deleteById(request.id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 停止回测任务
     */
    @Transactional
    fun stopBacktestTask(request: BacktestStopRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status != "RUNNING") {
                return Result.failure(IllegalArgumentException("回测任务未在运行中"))
            }

            task.status = "STOPPED"
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("停止回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 重试回测任务
     * 从断点继续执行，保留已处理的交易记录
     */
    @Transactional
    fun retryBacktestTask(request: BacktestRetryRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status == "RUNNING") {
                return Result.failure(IllegalArgumentException("回测任务正在运行中，无需重试"))
            }

            // 重置任务状态为 PENDING，进度保持不变
            task.status = "PENDING"
            task.errorMessage = null
            task.updatedAt = System.currentTimeMillis()

            // 不清理已处理的交易记录，保留恢复点
            backtestTaskRepository.save(task)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("重试回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 按当前配置重新测试：基于已完成的回测任务创建一份相同配置的新任务（名称可修改）
     */
    @Transactional
    fun rerunBacktestTask(request: BacktestRerunRequest): Result<BacktestTaskDto> {
        return try {
            val source = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (source.status != "COMPLETED") {
                return Result.failure(IllegalStateException("仅支持对已完成的回测任务重新测试"))
            }

            val newTaskName = request.taskName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${source.taskName} (副本)"

            val newTask = BacktestTask(
                taskName = newTaskName,
                leaderId = source.leaderId,
                initialBalance = source.initialBalance,
                backtestDays = source.backtestDays,
                startTime = source.startTime,
                status = "PENDING",
                copyMode = source.copyMode,
                copyRatio = source.copyRatio,
                fixedAmount = source.fixedAmount,
                adaptiveMinRatio = source.adaptiveMinRatio,
                adaptiveMaxRatio = source.adaptiveMaxRatio,
                adaptiveThreshold = source.adaptiveThreshold,
                multiplierMode = source.multiplierMode,
                tradeMultiplier = source.tradeMultiplier,
                tieredMultipliers = source.tieredMultipliers,
                maxOrderSize = source.maxOrderSize,
                minOrderSize = source.minOrderSize,
                maxDailyLoss = source.maxDailyLoss,
                maxDailyOrders = source.maxDailyOrders,
                maxDailyVolume = source.maxDailyVolume,
                supportSell = source.supportSell,
                keywordFilterMode = source.keywordFilterMode,
                keywords = source.keywords,
                maxPositionValue = source.maxPositionValue,
                minPrice = source.minPrice,
                maxPrice = source.maxPrice
            )

            backtestTaskRepository.save(newTask)
            val leader = leaderRepository.findById(newTask.leaderId).orElse(null)
            Result.success(newTask.toDto(leader))
        } catch (e: Exception) {
            logger.error("按配置重新测试失败", e)
            Result.failure(e)
        }
    }

    private fun resolveTargetTaskId(candidateTaskIds: List<Long>, preferredTaskId: Long?): Long? {
        if (candidateTaskIds.isEmpty()) return null
        return if (preferredTaskId != null && candidateTaskIds.contains(preferredTaskId)) {
            preferredTaskId
        } else {
            candidateTaskIds.first()
        }
    }

    private fun buildAuditSummary(taskId: Long): BacktestAuditSummaryDto {
        val total = backtestAuditEventRepository.countByBacktestTaskId(taskId)
        val pass = backtestAuditEventRepository.countByBacktestTaskIdAndDecision(taskId, "PASS")
        val skip = backtestAuditEventRepository.countByBacktestTaskIdAndDecision(taskId, "SKIP")
        val error = backtestAuditEventRepository.countByBacktestTaskIdAndDecision(taskId, "ERROR")
        val stop = backtestAuditEventRepository.countByBacktestTaskIdAndDecision(taskId, "STOP")
        val stageCounts = backtestAuditEventRepository.countByTaskIdGroupByStage(taskId)
            .associate { row ->
                val stage = row[0]?.toString().orEmpty()
                val count = when (val value = row[1]) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Number -> value.toLong()
                    else -> 0L
                }
                stage to count
            }
        val latestEventAt = backtestAuditEventRepository.findTop200ByBacktestTaskIdOrderByIdDesc(taskId)
            .firstOrNull()
            ?.createdAt
        return BacktestAuditSummaryDto(
            taskId = taskId,
            totalEvents = total,
            passEvents = pass,
            skipEvents = skip,
            errorEvents = error,
            stopEvents = stop,
            stageCounts = stageCounts,
            latestEventAt = latestEventAt
        )
    }

    private fun toAuditEventDto(event: BacktestAuditEvent): BacktestAuditEventDto {
        return BacktestAuditEventDto(
            id = event.id ?: 0L,
            taskId = event.backtestTaskId,
            eventTime = event.eventTime,
            stage = event.stage,
            eventType = event.eventType,
            decision = event.decision,
            leaderTradeId = event.leaderTradeId,
            marketId = event.marketId,
            marketTitle = event.marketTitle,
            side = event.side,
            reasonCode = event.reasonCode,
            reasonMessage = event.reasonMessage,
            detailJson = event.detailJson,
            createdAt = event.createdAt
        )
    }

    private fun validateTask(task: BacktestTask): String? {
        val config = CopyTradingSizingConfig(
            copyMode = task.copyMode,
            copyRatio = task.copyRatio,
            fixedAmount = task.fixedAmount,
            adaptiveMinRatio = task.adaptiveMinRatio,
            adaptiveMaxRatio = task.adaptiveMaxRatio,
            adaptiveThreshold = task.adaptiveThreshold,
            multiplierMode = task.multiplierMode,
            tradeMultiplier = task.tradeMultiplier,
            tieredMultipliers = CopyTradingSizingSupport.parseTieredMultipliers(task.tieredMultipliers),
            maxOrderSize = task.maxOrderSize,
            minOrderSize = task.minOrderSize,
            maxPositionValue = task.maxPositionValue,
            maxDailyVolume = task.maxDailyVolume
        )
        return CopyTradingSizingSupport.validateConfig(config).firstOrNull()
    }

    private fun buildHighlights(task: BacktestTask): List<String> {
        val highlights = mutableListOf<String>()
        task.profitRate?.let {
            highlights += "收益率 ${it.stripTrailingZeros().toPlainString()}%"
        }
        task.maxDrawdown?.let {
            highlights += "最大回撤 ${it.stripTrailingZeros().toPlainString()}"
        }
        if (task.supportSell) {
            highlights += "启用卖出跟随"
        }
        if (task.maxDailyVolume != null) {
            highlights += "限制单日成交额 ${task.maxDailyVolume.stripTrailingZeros().toPlainString()}"
        }
        if (task.keywordFilterMode != "DISABLED") {
            highlights += "关键字过滤 ${task.keywordFilterMode}"
        }
        return highlights
    }

    private fun buildConfigDifferences(items: List<BacktestCompareItemDto>): List<BacktestConfigDifferenceDto> {
        val fields = listOf(
            "copyMode" to ("跟单模式" to { item: BacktestCompareItemDto -> item.config.copyMode }),
            "copyRatio" to ("跟单比例" to { item: BacktestCompareItemDto -> item.config.copyRatio }),
            "fixedAmount" to ("固定金额" to { item: BacktestCompareItemDto -> item.config.fixedAmount }),
            "adaptiveMinRatio" to ("自适应最小比例" to { item: BacktestCompareItemDto -> item.config.adaptiveMinRatio }),
            "adaptiveMaxRatio" to ("自适应最大比例" to { item: BacktestCompareItemDto -> item.config.adaptiveMaxRatio }),
            "adaptiveThreshold" to ("自适应阈值" to { item: BacktestCompareItemDto -> item.config.adaptiveThreshold }),
            "multiplierMode" to ("Multiplier 模式" to { item: BacktestCompareItemDto -> item.config.multiplierMode }),
            "tradeMultiplier" to ("交易乘数" to { item: BacktestCompareItemDto -> item.config.tradeMultiplier }),
            "maxOrderSize" to ("最大单笔" to { item: BacktestCompareItemDto -> item.config.maxOrderSize }),
            "minOrderSize" to ("最小单笔" to { item: BacktestCompareItemDto -> item.config.minOrderSize }),
            "maxDailyLoss" to ("最大日亏损" to { item: BacktestCompareItemDto -> item.config.maxDailyLoss }),
            "maxDailyOrders" to ("最大日订单数" to { item: BacktestCompareItemDto -> item.config.maxDailyOrders.toString() }),
            "maxDailyVolume" to ("最大日成交额" to { item: BacktestCompareItemDto -> item.config.maxDailyVolume }),
            "supportSell" to ("跟随卖出" to { item: BacktestCompareItemDto -> item.config.supportSell.toString() }),
            "keywordFilterMode" to ("关键字过滤模式" to { item: BacktestCompareItemDto -> item.config.keywordFilterMode }),
            "keywords" to ("关键字" to { item: BacktestCompareItemDto -> item.config.keywords?.joinToString(",") }),
            "maxPositionValue" to ("最大仓位金额" to { item: BacktestCompareItemDto -> item.config.maxPositionValue }),
            "minPrice" to ("最低价格" to { item: BacktestCompareItemDto -> item.config.minPrice }),
            "maxPrice" to ("最高价格" to { item: BacktestCompareItemDto -> item.config.maxPrice })
        )

        return fields.mapNotNull { (field, labelAndExtractor) ->
            val (label, extractor) = labelAndExtractor
            val values = items.associate { it.task.id to extractor(it) }
            if (values.values.filterNotNull().distinct().size <= 1 && values.values.all { it == values.values.firstOrNull() }) {
                null
            } else {
                BacktestConfigDifferenceDto(field = field, label = label, values = values)
            }
        }
    }

    private fun buildCompareSummary(items: List<BacktestCompareItemDto>): BacktestCompareSummaryDto {
        val bestProfit = items.maxByOrNull { it.task.profitAmount.toSafeBigDecimal() }
        val bestProfitRate = items.maxByOrNull { it.task.profitRate.toSafeBigDecimal() }
        val bestWinRate = items.maxByOrNull { it.statistics.winRate.toSafeBigDecimal() }
        val lowestDrawdown = items.minByOrNull { it.statistics.maxDrawdown.toSafeBigDecimal() }
        val notes = mutableListOf<String>()

        bestProfit?.let {
            notes += "收益额最高: ${it.task.taskName} (${it.task.profitAmount ?: "0"})"
        }
        bestProfitRate?.let {
            notes += "收益率最高: ${it.task.taskName} (${it.task.profitRate ?: "0"}%)"
        }
        lowestDrawdown?.let {
            notes += "回撤最低: ${it.task.taskName} (${it.statistics.maxDrawdown})"
        }
        val anchorTaskId = bestProfitRate?.task?.id ?: bestProfit?.task?.id ?: lowestDrawdown?.task?.id
        val whyChain = buildWhyChain(items, anchorTaskId)
        val topReason = whyChain?.topReasons?.firstOrNull()
        if (topReason != null) {
            notes += "主要差异因子: ${topReason.title}（${topReason.detail}）"
        }

        return BacktestCompareSummaryDto(
            bestProfitTaskId = bestProfit?.task?.id,
            bestProfitRateTaskId = bestProfitRate?.task?.id,
            bestWinRateTaskId = bestWinRate?.task?.id,
            lowestDrawdownTaskId = lowestDrawdown?.task?.id,
            notes = notes,
            whyChain = whyChain
        )
    }

    private fun buildWhyChain(
        items: List<BacktestCompareItemDto>,
        anchorTaskId: Long?
    ): BacktestCompareWhyChainDto? {
        if (items.size < 2) return null

        val topReasons = mutableListOf<BacktestCompareReasonItemDto>()
        val profitRates = items.map { it.task.profitRate.toSafeBigDecimal() }
        val drawdowns = items.map { it.statistics.maxDrawdown.toSafeBigDecimal() }
        val winRates = items.map { it.statistics.winRate.toSafeBigDecimal() }
        val tradeCounts = items.map { BigDecimal(it.statistics.totalTrades) }

        val profitRateSpread = (profitRates.maxOrNull() ?: BigDecimal.ZERO) - (profitRates.minOrNull() ?: BigDecimal.ZERO)
        if (profitRateSpread > BigDecimal.ZERO) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "profit_rate_spread",
                title = "收益率差异",
                detail = "任务间收益率差值 ${formatDecimal(profitRateSpread)}%",
                type = "NEUTRAL",
                score = profitRateSpread.toDouble()
            )
        }

        val drawdownSpread = (drawdowns.maxOrNull() ?: BigDecimal.ZERO) - (drawdowns.minOrNull() ?: BigDecimal.ZERO)
        if (drawdownSpread > BigDecimal.ZERO) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "drawdown_spread",
                title = "回撤控制差异",
                detail = "任务间最大回撤差值 ${formatDecimal(drawdownSpread)}",
                type = "NEUTRAL",
                score = drawdownSpread.toDouble()
            )
        }

        val winRateSpread = (winRates.maxOrNull() ?: BigDecimal.ZERO) - (winRates.minOrNull() ?: BigDecimal.ZERO)
        if (winRateSpread > BigDecimal.ZERO) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "win_rate_spread",
                title = "胜率差异",
                detail = "任务间胜率差值 ${formatDecimal(winRateSpread)}%",
                type = "NEUTRAL",
                score = winRateSpread.toDouble()
            )
        }

        val tradeCountSpread = (tradeCounts.maxOrNull() ?: BigDecimal.ZERO) - (tradeCounts.minOrNull() ?: BigDecimal.ZERO)
        if (tradeCountSpread > BigDecimal.ZERO) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "trade_count_spread",
                title = "交易活跃度差异",
                detail = "任务间交易笔数差值 ${tradeCountSpread.toInt()}",
                type = "NEUTRAL",
                score = tradeCountSpread.toDouble()
            )
        }

        if (items.map { it.config.supportSell }.distinct().size > 1) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "support_sell_mode",
                title = "卖出跟随开关差异",
                detail = "部分任务启用了卖出跟随，影响回撤与平仓节奏",
                type = "NEUTRAL",
                score = 5.0
            )
        }

        if (items.map { it.config.copyMode }.distinct().size > 1) {
            topReasons += BacktestCompareReasonItemDto(
                factor = "copy_mode_diff",
                title = "跟单模式差异",
                detail = "任务使用了不同跟单模式（RATIO/FIXED/ADAPTIVE）",
                type = "NEUTRAL",
                score = 5.0
            )
        }

        val perTaskReasons = items.associate { item ->
            item.task.id to buildPerTaskReasons(item, items)
        }

        return BacktestCompareWhyChainDto(
            anchorTaskId = anchorTaskId,
            topReasons = topReasons.sortedByDescending { it.score }.take(5),
            perTaskReasons = perTaskReasons
        )
    }

    private fun buildPerTaskReasons(
        current: BacktestCompareItemDto,
        all: List<BacktestCompareItemDto>
    ): List<BacktestCompareReasonItemDto> {
        val reasons = mutableListOf<BacktestCompareReasonItemDto>()
        val currentProfitRate = current.task.profitRate.toSafeBigDecimal()
        val currentWinRate = current.statistics.winRate.toSafeBigDecimal()
        val currentDrawdown = current.statistics.maxDrawdown.toSafeBigDecimal()
        val currentTrades = current.statistics.totalTrades

        val maxProfitRate = all.maxOfOrNull { it.task.profitRate.toSafeBigDecimal() } ?: BigDecimal.ZERO
        val maxWinRate = all.maxOfOrNull { it.statistics.winRate.toSafeBigDecimal() } ?: BigDecimal.ZERO
        val minDrawdown = all.minOfOrNull { it.statistics.maxDrawdown.toSafeBigDecimal() } ?: BigDecimal.ZERO
        val maxTrades = all.maxOfOrNull { it.statistics.totalTrades } ?: 0

        if (currentProfitRate == maxProfitRate && maxProfitRate > BigDecimal.ZERO) {
            reasons += BacktestCompareReasonItemDto(
                factor = "profit_rate",
                title = "收益率领先",
                detail = "该任务收益率位于本次对比第一",
                type = "POSITIVE",
                score = 9.0
            )
        }

        if (currentWinRate == maxWinRate && maxWinRate > BigDecimal.ZERO) {
            reasons += BacktestCompareReasonItemDto(
                factor = "win_rate",
                title = "胜率领先",
                detail = "该任务胜率位于本次对比第一",
                type = "POSITIVE",
                score = 8.0
            )
        }

        if (currentDrawdown == minDrawdown) {
            reasons += BacktestCompareReasonItemDto(
                factor = "drawdown",
                title = "回撤控制更优",
                detail = "该任务最大回撤位于本次对比最低",
                type = "POSITIVE",
                score = 8.0
            )
        }

        if (maxTrades > 0 && currentTrades < (maxTrades / 3)) {
            reasons += BacktestCompareReasonItemDto(
                factor = "trade_count",
                title = "样本量偏少",
                detail = "该任务交易笔数明显少于其他任务，稳定性结论需谨慎",
                type = "NEGATIVE",
                score = 7.0
            )
        }

        if (!current.config.supportSell && all.any { it.config.supportSell }) {
            reasons += BacktestCompareReasonItemDto(
                factor = "support_sell",
                title = "未启用卖出跟随",
                detail = "可能导致仓位退出不及时，放大回撤尾部风险",
                type = "NEGATIVE",
                score = 6.0
            )
        }

        val maxDailyLossUpper = all.maxOfOrNull { it.config.maxDailyLoss.toSafeBigDecimal() } ?: BigDecimal.ZERO
        if (current.config.maxDailyLoss.toSafeBigDecimal() < maxDailyLossUpper) {
            reasons += BacktestCompareReasonItemDto(
                factor = "risk_limit",
                title = "日亏损限制更严格",
                detail = "该任务设置了更紧的 maxDailyLoss，风控更保守",
                type = "POSITIVE",
                score = 5.0
            )
        }

        if (reasons.isEmpty()) {
            reasons += BacktestCompareReasonItemDto(
                factor = "baseline",
                title = "结果接近组内中位",
                detail = "关键指标未出现明显领先或落后，属于中性结果",
                type = "NEUTRAL",
                score = 1.0
            )
        }

        return reasons.sortedByDescending { it.score }.take(4)
    }

    private fun formatDecimal(value: BigDecimal): String {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/**
 * 扩展函数：BacktestTask 转 DTO
 */
private fun BacktestTask.toDto(leader: Leader?): BacktestTaskDto {
    return BacktestTaskDto(
        id = this.id!!,
        taskName = this.taskName,
        leaderId = this.leaderId,
        leaderName = leader?.leaderName,
        leaderAddress = leader?.leaderAddress,
        initialBalance = this.initialBalance.toPlainString(),
        finalBalance = this.finalBalance?.toPlainString(),
        profitAmount = this.profitAmount?.toPlainString(),
        profitRate = this.profitRate?.toPlainString(),
        backtestDays = this.backtestDays,
        startTime = this.startTime,
        endTime = this.endTime,
        status = this.status,
        progress = this.progress,
        totalTrades = this.totalTrades,
        createdAt = this.createdAt,
        executionStartedAt = this.executionStartedAt,
        executionFinishedAt = this.executionFinishedAt,
        dataSource = this.dataSource,
        errorMessage = this.errorMessage,
        updatedAt = this.updatedAt,
        lastProcessedTradeTime = this.lastProcessedTradeTime,
        lastProcessedTradeIndex = this.lastProcessedTradeIndex,
        processedTradeCount = this.processedTradeCount
    )
}

private fun BacktestTask.toConfigDto(): BacktestConfigDto {
    return BacktestConfigDto(
        copyMode = this.copyMode,
        copyRatio = this.copyRatio.toPlainString(),
        fixedAmount = this.fixedAmount?.toPlainString(),
        adaptiveMinRatio = this.adaptiveMinRatio?.toPlainString(),
        adaptiveMaxRatio = this.adaptiveMaxRatio?.toPlainString(),
        adaptiveThreshold = this.adaptiveThreshold?.toPlainString(),
        multiplierMode = this.multiplierMode,
        tradeMultiplier = this.tradeMultiplier?.toPlainString(),
        tieredMultipliers = CopyTradingSizingSupport.toTierDtoList(this.tieredMultipliers),
        maxOrderSize = this.maxOrderSize.toPlainString(),
        minOrderSize = this.minOrderSize.toPlainString(),
        maxDailyLoss = this.maxDailyLoss.toPlainString(),
        maxDailyOrders = this.maxDailyOrders,
        maxDailyVolume = this.maxDailyVolume?.toPlainString(),
        supportSell = this.supportSell,
        keywordFilterMode = this.keywordFilterMode,
        keywords = if (this.keywords != null) {
            this.keywords.fromJson<List<String>>()
        } else {
            emptyList()
        },
        maxPositionValue = this.maxPositionValue?.toPlainString(),
        minPrice = this.minPrice?.toPlainString(),
        maxPrice = this.maxPrice?.toPlainString()
    )
}

private fun BacktestTask.toStatisticsDto(): BacktestStatisticsDto {
    return BacktestStatisticsDto(
        totalTrades = this.totalTrades,
        buyTrades = this.buyTrades,
        sellTrades = this.sellTrades,
        winTrades = this.winTrades,
        lossTrades = this.lossTrades,
        winRate = this.winRate?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00",
        maxProfit = this.maxProfit?.toPlainString() ?: "0.00",
        maxLoss = this.maxLoss?.toPlainString() ?: "0.00",
        maxDrawdown = this.maxDrawdown?.toPlainString() ?: "0.00",
        avgHoldingTime = this.avgHoldingTime
    )
}

