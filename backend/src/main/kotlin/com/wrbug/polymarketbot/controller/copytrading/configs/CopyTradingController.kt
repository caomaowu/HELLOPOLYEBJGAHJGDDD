package com.wrbug.polymarketbot.controller.copytrading.configs

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.service.copytrading.configs.FilteredOrderService
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 跟单配置管理控制器（钱包-模板关联）
 */
@RestController
@RequestMapping("/api/copy-trading/configs")
class CopyTradingController(
    private val copyTradingService: CopyTradingService,
    private val filteredOrderService: FilteredOrderService,
    private val executionEventService: CopyTradingExecutionEventService,
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingController::class.java)
    
    /**
     * 创建跟单配置
     * 支持两种方式：
     * 1. 提供 templateId：从模板填充配置，可以覆盖部分字段
     * 2. 不提供 templateId：手动输入所有配置参数
     */
    @PostMapping("/create")
    fun createCopyTrading(@RequestBody request: CopyTradingCreateRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            // templateId 现在是可选的，如果提供则必须 > 0
            if (request.templateId != null && request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID, messageSource = messageSource))
            }
            
            val result = copyTradingService.createCopyTrading(request)
            result.fold(
                onSuccess = { copyTrading ->
                    ResponseEntity.ok(ApiResponse.success(copyTrading))
                },
                onFailure = { e ->
                    logger.error("创建跟单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_CREATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建跟单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_CREATE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 查询跟单列表
     */
    @PostMapping("/list")
    fun getCopyTradingList(@RequestBody request: CopyTradingListRequest): ResponseEntity<ApiResponse<CopyTradingListResponse>> {
        return try {
            val result = copyTradingService.getCopyTradingList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询跟单列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询跟单列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 更新跟单配置
     */
    @PostMapping("/update")
    fun updateCopyTrading(@RequestBody request: CopyTradingUpdateRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            val result = copyTradingService.updateCopyTrading(request)
            result.fold(
                onSuccess = { copyTrading ->
                    ResponseEntity.ok(ApiResponse.success(copyTrading))
                },
                onFailure = { e ->
                    logger.error("更新跟单配置失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新跟单配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 更新跟单状态（兼容旧接口）
     */
    @PostMapping("/update-status")
    fun updateCopyTradingStatus(@RequestBody request: CopyTradingUpdateStatusRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            val result = copyTradingService.updateCopyTradingStatus(request)
            result.fold(
                onSuccess = { copyTrading ->
                    ResponseEntity.ok(ApiResponse.success(copyTrading))
                },
                onFailure = { e ->
                    logger.error("更新跟单状态失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新跟单状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 删除跟单
     */
    @PostMapping("/delete")
    fun deleteCopyTrading(@RequestBody request: CopyTradingDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            val result = copyTradingService.deleteCopyTrading(request.copyTradingId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除跟单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除跟单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_DELETE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 查询钱包绑定的模板
     */
    @PostMapping("/account-templates")
    fun getAccountTemplates(@RequestBody request: AccountTemplatesRequest): ResponseEntity<ApiResponse<AccountTemplatesResponse>> {
        return try {
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            
            val result = copyTradingService.getAccountTemplates(request.accountId)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询钱包绑定的模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_TEMPLATES_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询钱包绑定的模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_TEMPLATES_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 查询被过滤订单列表
     */
    @PostMapping("/filtered-orders")
    fun getFilteredOrders(@RequestBody request: FilteredOrderListRequest): ResponseEntity<ApiResponse<FilteredOrderListResponse>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            val response = filteredOrderService.getFilteredOrders(request)
            ResponseEntity.ok(ApiResponse.success(response))
        } catch (e: Exception) {
            logger.error("查询被过滤订单列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 查询执行事件列表
     */
    @PostMapping("/execution-events")
    fun getExecutionEvents(@RequestBody request: CopyTradingExecutionEventListRequest): ResponseEntity<ApiResponse<CopyTradingExecutionEventListResponse>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            val normalizedLatencyMetric = request.latencyMetric?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedLatencyMetric != null && normalizedLatencyMetric !in CopyTradingExecutionEventService.SUPPORTED_LATENCY_METRICS) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "不支持的耗时指标: $normalizedLatencyMetric", messageSource))
            }
            if (request.minLatencyMs != null && request.minLatencyMs < 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "最小耗时阈值不能小于 0", messageSource))
            }
            if (request.minLatencyMs != null && normalizedLatencyMetric == null) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "使用最小耗时阈值时必须指定耗时指标", messageSource))
            }
            if (request.startTime != null && request.endTime != null && request.startTime > request.endTime) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "开始时间不能大于结束时间", messageSource))
            }

            val response = executionEventService.getEvents(
                request.copy(
                    latencyMetric = normalizedLatencyMetric
                )
            )
            ResponseEntity.ok(ApiResponse.success(response))
        } catch (e: Exception) {
            logger.error("查询执行事件列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 查询小额聚合缓冲快照
     */
    @PostMapping("/aggregation-snapshot")
    fun getAggregationSnapshot(@RequestBody request: CopyTradingAggregationSnapshotRequest): ResponseEntity<ApiResponse<Any>> {
        return try {
            if (request.copyTradingId != null && request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            ResponseEntity.ok(
                ApiResponse.success(
                    copyOrderTrackingService.getSmallOrderAggregationDiagnostics(request.copyTradingId)
                )
            )
        } catch (e: Exception) {
            logger.error("查询小额聚合缓冲快照异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

