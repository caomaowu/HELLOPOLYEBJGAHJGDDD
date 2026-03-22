package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ApiHealthCheckDto
import com.wrbug.polymarketbot.dto.ApiHealthCheckResponse
import com.wrbug.polymarketbot.dto.StartupHealthAccountDto
import com.wrbug.polymarketbot.dto.StartupHealthSummaryDto
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.copytrading.monitor.PolymarketActivityWsService
import com.wrbug.polymarketbot.service.copytrading.monitor.UnifiedOnChainWsService
import com.wrbug.polymarketbot.service.accounts.AccountExecutionDiagnosticsService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * API 健康检查服务
 */
@Service
class ApiHealthCheckService(
    private val rpcNodeService: RpcNodeService,
    private val accountExecutionDiagnosticsService: AccountExecutionDiagnosticsService
) : ApplicationContextAware {

    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    /**
     * 获取订单推送服务（通过 ApplicationContext 避免循环依赖）
     */
    private fun getOrderPushService(): OrderPushService? {
        return try {
            applicationContext?.getBean(OrderPushService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    /**
     * 获取 RelayClientService（通过 ApplicationContext 避免循环依赖）
     */
    private fun getRelayClientService(): RelayClientService? {
        return try {
            applicationContext?.getBean(RelayClientService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    /**
     * 获取 PolymarketActivityWsService（通过 ApplicationContext 避免循环依赖）
     */
    private fun getPolymarketActivityWsService(): PolymarketActivityWsService? {
        return try {
            applicationContext?.getBean(PolymarketActivityWsService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    /**
     * 获取 UnifiedOnChainWsService（通过 ApplicationContext 避免循环依赖）
     */
    private fun getUnifiedOnChainWsService(): UnifiedOnChainWsService? {
        return try {
            applicationContext?.getBean(UnifiedOnChainWsService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    /**
     * 获取 BinanceKlineService（通过 ApplicationContext 避免循环依赖）
     */
    private fun getBinanceKlineService(): BinanceKlineService? {
        return try {
            applicationContext?.getBean(BinanceKlineService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private val logger = LoggerFactory.getLogger(ApiHealthCheckService::class.java)

    /**
     * 检查所有 API 的健康状态
     */
    suspend fun checkAllApis(): ApiHealthCheckResponse {
        val apis = coroutineScope {
            val jobs = mutableListOf<Deferred<ApiHealthCheckDto>>(
                async { checkClobApi() },
                async { checkDataApi() },
                async { checkGammaApi() },
                async { checkPolygonRpc() },
                async { checkBinanceApi() },
                async { checkBinanceWebSocket() },
                async { checkPolymarketRtdsWebSocket() },
                async { checkPolymarketActivityWebSocket() },
                async { checkUnifiedOnChainWebSocket() },
                async { checkBuilderRelayerApi() },
                async { checkGitHubApi() },
                async { checkCopyTradingExecutionReadiness() }
            )
            jobs.awaitAll()
        }
            .map(::attachSuggestion)

        val accountDiagnostics = accountExecutionDiagnosticsService.diagnoseEnabledCopyTradingAccounts(forceRefresh = false)
        return ApiHealthCheckResponse(
            apis = apis,
            summary = buildStartupSummary(apis, accountDiagnostics),
            unhealthyAccounts = buildUnhealthyAccounts(accountDiagnostics)
        )
    }

    /**
     * 检查 Polymarket CLOB API
     */
    private suspend fun checkClobApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "${PolymarketConstants.CLOB_BASE_URL}/"
        checkApi("Polymarket CLOB API", url)
    }

    /**
     * 检查 Polymarket Data API
     */
    private suspend fun checkDataApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "${PolymarketConstants.DATA_API_BASE_URL}/"
        checkApi("Polymarket Data API", url)
    }

    /**
     * 检查 Polymarket Gamma API
     * 使用 /markets 接口检查 API 可用性（调用实际的业务接口）
     */
    private suspend fun checkGammaApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        return@withContext try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            // 使用 /markets 接口检查（不传参数，返回空列表或少量市场数据）
            val url = "${PolymarketConstants.GAMMA_BASE_URL}/markets"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                // 检查响应体是否为有效的 JSON 数组（即使为空数组也可以）
                val responseBody = response.body?.string()
                if (responseBody != null && (responseBody.trim().startsWith("[") || responseBody.trim()
                        .startsWith("{"))
                ) {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
            } else {
                ApiHealthCheckDto(
                    name = "Polymarket Gamma API",
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket Gamma API 失败", e)
            ApiHealthCheckDto(
                name = "Polymarket Gamma API",
                url = "${PolymarketConstants.GAMMA_BASE_URL}/markets",
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    /**
     * 检查 Polygon RPC
     * 使用动态获取的可用节点（RpcNodeService 总是返回一个有效的 URL，包括默认节点）
     */
    private suspend fun checkPolygonRpc(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        // 使用 RpcNodeService 获取可用节点（总是返回有效值，包括默认节点）
        val rpcUrl = rpcNodeService.getHttpUrl()
        checkJsonRpcApi("Polygon RPC", rpcUrl)
    }

    /**
     * 检查币安 API（用于 K 线等）
     * 使用 /api/v3/ping 端点
     */
    private suspend fun checkBinanceApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "https://api.binance.com/api/v3/ping"
        checkApi("币安 API", url)
    }

    /**
     * 检查币安 K 线 WebSocket 连接状态（5m / 15m）
     */
    private suspend fun checkBinanceWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        val binanceWsUrl = "wss://stream.binance.com:9443"
        try {
            val binanceKlineService = getBinanceKlineService()
            if (binanceKlineService == null) {
                return@withContext ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "服务未初始化"
                )
            }
            val statuses = binanceKlineService.getConnectionStatuses()
            val total = statuses.size
            val connected = statuses.values.count { it }
            if (connected == total && total > 0) {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "success",
                    message = "连接正常 (按策略订阅)"
                )
            } else if (total == 0) {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "success",
                    message = "无加密价差策略，未订阅"
                )
            } else if (connected > 0) {
                val which = statuses.filter { it.value }.keys.joinToString("、")
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "部分连接正常 ($which)"
                )
            } else {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "连接断开"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查币安 WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "币安 WebSocket",
                url = binanceWsUrl,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    /**
     * 检查 Polymarket RTDS WebSocket 连接状态
     * 用于订单推送服务
     */
    private suspend fun checkPolymarketRtdsWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val orderPushService = getOrderPushService()
            val statuses = orderPushService?.getConnectionStatuses() ?: emptyMap()
            val total = statuses.size
            val connected = statuses.values.count { it }

            if (total == 0) {
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "skipped",
                    message = "未配置账户连接"
                )
            } else if (connected > 0) {
                val message = if (connected == total) {
                    "所有账户连接正常 ($connected/$total)"
                } else {
                    "部分账户连接正常 ($connected/$total)"
                }
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "success",
                    message = message
                )
            } else {
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "error",
                    message = "所有账户连接断开 (0/$total)"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket RTDS WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "Polymarket RTDS WebSocket",
                url = PolymarketConstants.RTDS_WS_URL,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    /**
     * 检查 Polymarket Activity WebSocket 连接状态
     * 用于 Activity 全局交易流监听
     */
    private suspend fun checkPolymarketActivityWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val activityWsService = getPolymarketActivityWsService()
            val snapshot = activityWsService?.getHealthSnapshot()
                ?: return@withContext ApiHealthCheckDto(
                    name = "Polymarket Activity WebSocket",
                    url = PolymarketConstants.ACTIVITY_WS_URL,
                    status = "error",
                    message = "服务未初始化"
                )

            when {
                snapshot.connected && snapshot.idleMillis != null && snapshot.idleMillis >= snapshot.silenceTimeoutMs -> {
                    ApiHealthCheckDto(
                        name = "Polymarket Activity WebSocket",
                        url = PolymarketConstants.ACTIVITY_WS_URL,
                        status = "warning",
                        message = "连接存在，但已静默 ${snapshot.idleMillis}ms，等待自愈重连",
                        suggestion = "检查 Activity WS 自愈日志与代理网络连通性，确认重连后能重新收到全局 activity 消息。"
                    )
                }
                snapshot.connected -> {
                    ApiHealthCheckDto(
                        name = "Polymarket Activity WebSocket",
                        url = PolymarketConstants.ACTIVITY_WS_URL,
                        status = "success",
                        message = "连接正常，监听 ${snapshot.monitoredCount} 个 Leader"
                    )
                }
                snapshot.monitoredCount == 0 -> {
                    ApiHealthCheckDto(
                        name = "Polymarket Activity WebSocket",
                        url = PolymarketConstants.ACTIVITY_WS_URL,
                        status = "skipped",
                        message = "当前无启用 Leader，未启动监听"
                    )
                }
                else -> {
                    ApiHealthCheckDto(
                        name = "Polymarket Activity WebSocket",
                        url = PolymarketConstants.ACTIVITY_WS_URL,
                        status = "error",
                        message = "连接断开"
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket Activity WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "Polymarket Activity WebSocket",
                url = PolymarketConstants.ACTIVITY_WS_URL,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    /**
     * 检查统一链上 WebSocket 连接状态
     * 用于监听链上事件
     */
    private suspend fun checkUnifiedOnChainWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val unifiedOnChainWsService = getUnifiedOnChainWsService()

            if (unifiedOnChainWsService == null) {
                return@withContext ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "error",
                    message = "服务未初始化"
                )
            }

            // 检查连接状态
            val statuses = unifiedOnChainWsService.getConnectionStatuses()
            val total = statuses.size
            val connected = statuses.values.count { it }

            if (total == 0) {
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "skipped",
                    message = "未配置地址监听"
                )
            } else if (connected > 0) {
                val message = if (connected == total) {
                    "所有地址连接正常 ($connected/$total)"
                } else {
                    "部分地址连接正常 ($connected/$total)"
                }
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "success",
                    message = message
                )
            } else {
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "error",
                    message = "所有地址连接断开 (0/$total)"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查链上 WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "链上 WebSocket",
                url = rpcNodeService.getWsUrl(),
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    /**
     * 检查普通 HTTP API
     */
    private suspend fun checkApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "success",
                    message = "连接成功",
                    responseTime = responseTime
                )
            } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    /**
     * 检查 JSON-RPC API（如 Polygon RPC）
     */
    private suspend fun checkJsonRpcApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            // 发送一个简单的 JSON-RPC 请求（获取链 ID）
            val jsonRpcRequest = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_chainId",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                ?: "application/json".toMediaType()

            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(mediaType, jsonRpcRequest))
                .header("Content-Type", "application/json")
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null && responseBody.contains("\"result\"")) {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
            } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 JSON-RPC API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
    /**
     * 检查 Builder Relayer API
     */
    private suspend fun checkBuilderRelayerApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val relayClientService = getRelayClientService()
        
        if (relayClientService == null) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "error",
                message = "服务未初始化"
            )
        }
        
        if (!relayClientService.isBuilderApiKeyConfigured()) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "skipped",
                message = "Builder API Key 未配置"
            )
        }
        
        return@withContext try {
            val result = relayClientService.checkBuilderRelayerApiHealth()
            result.fold(
                onSuccess = { responseTime ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = PolymarketConstants.BUILDER_RELAYER_URL,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                },
                onFailure = { e ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = PolymarketConstants.BUILDER_RELAYER_URL,
                        status = "error",
                        message = e.message ?: "连接失败"
                    )
                }
            )
        } catch (e: Exception) {
            logger.warn("检查 Builder Relayer API 失败", e)
            ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
    /**
     * 检查 GitHub API
     */
    private suspend fun checkGitHubApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/"
        // 直接使用 GitHub API 根端点检查可用性
        checkApi("GitHub API", url)
    }

    /**
     * 检查启用中的跟单配置是否通过执行前诊断
     */
    private suspend fun checkCopyTradingExecutionReadiness(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        return@withContext try {
            val summary = accountExecutionDiagnosticsService.summarizeEnabledCopyTradingReadiness()
            ApiHealthCheckDto(
                name = "跟单执行前诊断",
                url = "/api/accounts/check-setup-status",
                status = summary.status,
                message = summary.message,
                suggestion = if (summary.status == "error") {
                    "先处理异常账户的执行前诊断失败项，再启用或重启对应跟单配置。"
                } else {
                    null
                }
            )
        } catch (e: Exception) {
            logger.warn("检查跟单执行前诊断失败", e)
            ApiHealthCheckDto(
                name = "跟单执行前诊断",
                url = "/api/accounts/check-setup-status",
                status = "error",
                message = e.message ?: "检查失败"
            )
        }
    }

    private fun buildStartupSummary(
        apis: List<ApiHealthCheckDto>,
        accountDiagnostics: List<AccountExecutionDiagnosticsService.EnabledAccountDiagnostics>
    ): StartupHealthSummaryDto {
        val successCount = apis.count { it.status == "success" }
        val warningCount = apis.count { it.status == "warning" }
        val errorCount = apis.count { it.status == "error" }
        val enabledCopyTradingCount = accountDiagnostics.sumOf { it.enabledCopyTradingCount }
        val unhealthyAccounts = accountDiagnostics.filter { !it.setupStatus.executionReady }
        val unhealthyCopyTradingCount = unhealthyAccounts.sumOf { it.enabledCopyTradingCount }
        val status = when {
            errorCount > 0 || unhealthyAccounts.isNotEmpty() -> "error"
            warningCount > 0 -> "warning"
            else -> "success"
        }
        val message = when (status) {
            "error" -> "启动前检查发现阻断项，请先处理异常服务或账户后再启动跟单"
            "warning" -> "启动前检查存在告警项，建议先处理后再启动跟单"
            else -> "启动前检查通过，可以启动当前跟单链路"
        }
        val actionItems = buildActionItems(apis, unhealthyAccounts)

        return StartupHealthSummaryDto(
            status = status,
            message = message,
            checkedAt = System.currentTimeMillis(),
            totalChecks = apis.size,
            successCount = successCount,
            warningCount = warningCount,
            errorCount = errorCount,
            enabledCopyTradingCount = enabledCopyTradingCount,
            unhealthyCopyTradingCount = unhealthyCopyTradingCount,
            unhealthyAccountCount = unhealthyAccounts.size,
            actionItems = actionItems
        )
    }

    private fun buildUnhealthyAccounts(
        accountDiagnostics: List<AccountExecutionDiagnosticsService.EnabledAccountDiagnostics>
    ): List<StartupHealthAccountDto> {
        return accountDiagnostics
            .filter { !it.setupStatus.executionReady }
            .map { item ->
                val failedChecks = item.setupStatus.checks.filter { check ->
                    check.status == "error" || check.status == "warning"
                }
                StartupHealthAccountDto(
                    accountId = item.accountId,
                    accountName = item.accountName,
                    enabledCopyTradingCount = item.enabledCopyTradingCount,
                    executionReady = item.setupStatus.executionReady,
                    errorCount = failedChecks.count { it.status == "error" },
                    warningCount = failedChecks.count { it.status == "warning" },
                    failedChecks = failedChecks,
                    checkedAt = item.setupStatus.checkedAt
                )
            }
    }

    private fun buildActionItems(
        apis: List<ApiHealthCheckDto>,
        unhealthyAccounts: List<AccountExecutionDiagnosticsService.EnabledAccountDiagnostics>
    ): List<String> {
        val apiItems = apis
            .filter { it.status == "error" || it.status == "warning" }
            .mapNotNull { it.suggestion }

        val accountItems = unhealthyAccounts.flatMap { item ->
            item.setupStatus.checks
                .filter { it.status == "error" || it.status == "warning" }
                .mapNotNull { it.suggestion }
        }

        return (apiItems + accountItems)
            .distinct()
            .take(8)
    }

    private fun attachSuggestion(check: ApiHealthCheckDto): ApiHealthCheckDto {
        if (!check.suggestion.isNullOrBlank()) {
            return check
        }
        val suggestion = when (check.name) {
            "Polymarket CLOB API" -> "确认代理配置、网络出口和 Polymarket CLOB 服务可达，再重新执行健康检查。"
            "Polymarket Data API" -> "检查 Data API 连通性；若长期失败，需要评估 discovery 与兜底数据源是否受影响。"
            "Polymarket Gamma API" -> "检查市场数据接口可达性，必要时切换网络或代理。"
            "Polygon RPC" -> "检查当前 RPC 节点配置和优先级，必要时切换到健康节点。"
            "币安 API" -> "检查访问地区限制、代理配置和外网连通性。"
            "币安 WebSocket" -> "确认已启用的加密策略所需订阅正常，必要时检查代理和 WebSocket 出口。"
            "Polymarket RTDS WebSocket" -> "检查账户 API 凭证和 RTDS 连接状态，确保用户频道能自动重连并重新订阅。"
            "Polymarket Activity WebSocket" -> "检查 Activity WS 自愈日志、代理连通性和全局 activity 流是否恢复。"
            "链上 WebSocket" -> "检查 RPC WebSocket 节点是否可用，并确认已启用账户的链上监听已成功订阅。"
            "Builder Relayer API" -> "补齐 Builder API Key/Secret/Passphrase，或检查 Builder Relayer 可达性。"
            "GitHub API" -> "检查外网访问和代理设置，避免更新检查与公告同步失败。"
            "跟单执行前诊断" -> "先修复异常账户的执行前诊断失败项，再启动跟单。"
            else -> null
        }
        return check.copy(suggestion = suggestion)
    }
}

