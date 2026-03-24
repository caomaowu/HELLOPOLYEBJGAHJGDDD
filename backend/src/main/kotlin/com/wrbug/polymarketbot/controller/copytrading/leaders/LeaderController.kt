package com.wrbug.polymarketbot.controller.copytrading.leaders

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.leaders.LeaderDiscoveryService
import com.wrbug.polymarketbot.service.copytrading.leaders.LeaderService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Leader 管理控制器
 */
@RestController
@RequestMapping("/api/copy-trading/leaders")
class LeaderController(
    private val leaderService: LeaderService,
    private val leaderDiscoveryService: LeaderDiscoveryService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(LeaderController::class.java)
    
    /**
     * 添加被跟单者
     */
    @PostMapping("/add")
    fun addLeader(@RequestBody request: LeaderAddRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ADDRESS_EMPTY, messageSource = messageSource))
            }
            
            val result = leaderService.addLeader(request)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("添加 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_ADD_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("添加 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_ADD_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 更新被跟单者
     */
    @PostMapping("/update")
    fun updateLeader(@RequestBody request: LeaderUpdateRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            
            val result = leaderService.updateLeader(request)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("更新 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_UPDATE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 删除被跟单者
     */
    @PostMapping("/delete")
    fun deleteLeader(@RequestBody request: LeaderDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            
            val result = leaderService.deleteLeader(request.leaderId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DELETE_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 查询被跟单者列表
     */
    @PostMapping("/list")
    fun getLeaderList(@RequestBody request: LeaderListRequest): ResponseEntity<ApiResponse<LeaderListResponse>> {
        return try {
            val result = leaderService.getLeaderList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 查询被跟单者详情
     */
    @PostMapping("/detail")
    fun getLeaderDetail(@RequestBody request: LeaderDetailRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }

            val result = leaderService.getLeaderDetail(request.leaderId)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DETAIL_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询被跟单者余额
     */
    @PostMapping("/balance")
    fun getLeaderBalance(@RequestBody request: LeaderBalanceRequest): ResponseEntity<ApiResponse<LeaderBalanceResponse>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }

            val result = leaderService.getLeaderBalance(request.leaderId)
            result.fold(
                onSuccess = { balance ->
                    ResponseEntity.ok(ApiResponse.success(balance))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 余额失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 余额异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 扫描近期活跃 Trader
     */
    @PostMapping("/discovery/scan")
    fun scanTraders(@RequestBody request: LeaderTraderScanRequest): ResponseEntity<ApiResponse<LeaderTraderScanResponse>> {
        return try {
            val result = leaderDiscoveryService.scanTraders(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("扫描 Trader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("扫描 Trader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 扫描开放市场并发现活跃 Trader（一期）
     */
    @PostMapping("/discovery/scan-markets")
    fun scanMarkets(@RequestBody request: LeaderMarketScanRequest): ResponseEntity<ApiResponse<LeaderMarketScanResponse>> {
        return try {
            val result = leaderDiscoveryService.scanMarkets(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("全市场扫描 Trader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("全市场扫描 Trader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 推荐候选 Leader
     */
    @PostMapping("/discovery/recommend")
    fun recommendCandidates(@RequestBody request: LeaderCandidateRecommendRequest): ResponseEntity<ApiResponse<LeaderCandidateRecommendResponse>> {
        return try {
            val result = leaderDiscoveryService.recommendCandidates(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("推荐候选 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("推荐候选 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 单地址 Trader 分析
     */
    @PostMapping("/discovery/analyze")
    fun analyzeTrader(@RequestBody request: LeaderTraderAnalysisRequest): ResponseEntity<ApiResponse<LeaderTraderAnalysisResponse>> {
        return try {
            val result = leaderDiscoveryService.analyzeTrader(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("分析 Trader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("分析 Trader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 按市场反查活跃 Trader
     */
    @PostMapping("/discovery/market-traders")
    fun lookupMarketTraders(@RequestBody request: LeaderMarketTraderLookupRequest): ResponseEntity<ApiResponse<LeaderMarketTraderLookupResponse>> {
        return try {
            val result = leaderDiscoveryService.lookupMarketTraders(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("按市场反查活跃 Trader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("按市场反查活跃 Trader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 查询实时候选池
     */
    @PostMapping("/discovery/pool")
    fun getCandidatePool(@RequestBody request: LeaderCandidatePoolListRequest): ResponseEntity<ApiResponse<LeaderCandidatePoolListResponse>> {
        return try {
            val result = leaderDiscoveryService.getCandidatePool(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询候选池失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询候选池异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 更新候选池人工标注
     */
    @PostMapping("/discovery/pool/update-labels")
    fun updateCandidatePoolLabels(@RequestBody request: LeaderCandidatePoolLabelUpdateRequest): ResponseEntity<ApiResponse<LeaderCandidatePoolItemDto>> {
        return try {
            val result = leaderDiscoveryService.updateCandidatePoolLabels(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("更新候选池人工标注失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新候选池人工标注异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 批量更新候选池人工标注
     */
    @PostMapping("/discovery/pool/update-labels-batch")
    fun updateCandidatePoolLabelsBatch(@RequestBody request: LeaderCandidatePoolBatchLabelUpdateRequest): ResponseEntity<ApiResponse<LeaderCandidatePoolBatchLabelUpdateResponse>> {
        return try {
            val result = leaderDiscoveryService.updateCandidatePoolLabelsBatch(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("批量更新候选池人工标注失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("批量更新候选池人工标注异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 查询候选评分历史
     */
    @PostMapping("/discovery/pool/history")
    fun getCandidateScoreHistory(@RequestBody request: LeaderCandidateScoreHistoryRequest): ResponseEntity<ApiResponse<LeaderCandidateScoreHistoryResponse>> {
        return try {
            val result = leaderDiscoveryService.getCandidateScoreHistory(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询候选评分历史失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询候选评分历史异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 按地址查询 discovery activity 历史事件
     */
    @PostMapping("/discovery/history/address")
    fun getActivityHistoryByAddress(@RequestBody request: LeaderActivityHistoryByAddressRequest): ResponseEntity<ApiResponse<LeaderActivityHistoryResponse>> {
        return try {
            val result = leaderDiscoveryService.getActivityHistoryByAddress(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("按地址查询 activity 历史事件失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("按地址查询 activity 历史事件异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 按市场查询 discovery activity 历史事件
     */
    @PostMapping("/discovery/history/market")
    fun getActivityHistoryByMarket(@RequestBody request: LeaderActivityHistoryByMarketRequest): ResponseEntity<ApiResponse<LeaderActivityHistoryResponse>> {
        return try {
            val result = leaderDiscoveryService.getActivityHistoryByMarket(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("按市场查询 activity 历史事件失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("按市场查询 activity 历史事件异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 手动回填 discovery activity 历史事件
     */
    @PostMapping("/discovery/history/backfill")
    fun backfillActivityHistory(@RequestBody request: LeaderActivityHistoryBackfillRequest): ResponseEntity<ApiResponse<LeaderActivityHistoryBackfillResponse>> {
        return try {
            val result = leaderDiscoveryService.backfillActivityHistory(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("回填 activity 历史事件失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("回填 activity 历史事件异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

/**
 * Leader 详情请求
 */
data class LeaderDetailRequest(
    val leaderId: Long
)





