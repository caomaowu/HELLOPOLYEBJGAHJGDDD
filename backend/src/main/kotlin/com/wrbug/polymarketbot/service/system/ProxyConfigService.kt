package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.repository.ProxyConfigRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingWebSocketService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import com.wrbug.polymarketbot.util.TrustAllHostnameVerifier
import com.wrbug.polymarketbot.util.createSSLSocketFactory
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 代理配置服务
 */
@Service
class ProxyConfigService(
    private val proxyConfigRepository: ProxyConfigRepository
) : ApplicationContextAware {
    companion object {
        private val SUPPORTED_PROXY_TYPES = setOf("HTTP", "HTTPS", "SOCKS5")
    }

    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    private val logger = LoggerFactory.getLogger(ProxyConfigService::class.java)
    
    /**
     * 获取当前代理配置
     * 优先返回启用中的配置；如果没有启用中的配置，则返回最近编辑的一条代理配置
     */
    fun getProxyConfig(): ProxyConfigDto? {
        val config = findPreferredProxyConfig()
        refreshRuntimeProxy(findEnabledProxyConfig())
        return config?.let { toDto(it) }
    }
    
    /**
     * 初始化代理配置（应用启动时调用）
     */
    fun initProxyConfig() {
        val config = findEnabledProxyConfig()
        refreshRuntimeProxy(config)
        if (config != null) {
            logger.info("初始化代理配置：type=${config.type}, host=${config.host}, port=${config.port}, enabled=${config.enabled}")
        } else {
            logger.info("未找到启用的代理配置")
        }
    }
    
    /**
     * 获取所有代理配置（用于管理）
     */
    fun getAllProxyConfigs(): List<ProxyConfigDto> {
        return proxyConfigRepository.findAll()
            .filter { isSupportedType(it.type) }
            .map { toDto(it) }
    }
    
    /**
     * 创建或更新代理配置
     */
    @Transactional
    fun saveProxyConfig(request: ProxyConfigSaveRequest): Result<ProxyConfigDto> {
        return try {
            val normalizedType = normalizeAndValidateType(request.type)
            val host = request.host.trim()
            if (host.isBlank()) {
                return Result.failure(IllegalArgumentException("代理主机不能为空"))
            }
            if (request.port <= 0 || request.port > 65535) {
                return Result.failure(IllegalArgumentException("代理端口必须在 1-65535 之间"))
            }

            val now = System.currentTimeMillis()
            val allProxyConfigs = proxyConfigRepository.findAll()
                .filter { isSupportedType(it.type) }
            val existing = allProxyConfigs.firstOrNull { ProxyConfigProvider.normalizeType(it.type) == normalizedType }

            val password = when {
                request.password != null && request.password.isNotBlank() -> request.password
                else -> existing?.password
            }

            val config = if (existing != null) {
                existing.copy(
                    type = normalizedType,
                    enabled = request.enabled,
                    host = host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password,
                    updatedAt = now
                )
            } else {
                ProxyConfig(
                    type = normalizedType,
                    enabled = request.enabled,
                    host = host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password
                )
            }

            val disabledConfigs = allProxyConfigs
                .filter { it.id != config.id && it.enabled }
                .map { it.copy(enabled = false, updatedAt = now) }
            if (disabledConfigs.isNotEmpty()) {
                proxyConfigRepository.saveAll(disabledConfigs)
            }

            val saved = proxyConfigRepository.save(config)
            logger.info("保存代理配置成功：type=${saved.type}, host=${saved.host}, port=${saved.port}, enabled=${saved.enabled}")

            refreshRuntimeProxy(if (saved.enabled) saved else null)
            triggerWebSocketReconnect()

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("保存代理配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 创建或更新 HTTP 代理配置（兼容旧接口）
     */
    @Transactional
    fun saveHttpProxyConfig(request: HttpProxyConfigRequest): Result<ProxyConfigDto> {
        return saveProxyConfig(
            ProxyConfigSaveRequest(
                type = "HTTP",
                enabled = request.enabled,
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password
            )
        )
    }
    
    /**
     * 检查代理是否可用
     * 使用配置的代理请求 Polymarket 健康检查接口
     */
    fun checkProxy(): ProxyCheckResponse {
        return try {
            val config = findEnabledProxyConfig()
                ?: return ProxyCheckResponse.create(
                    success = false,
                    message = "未配置代理或代理未启用"
                )

            if (config.host == null || config.port == null) {
                return ProxyCheckResponse.create(
                    success = false,
                    message = "代理配置不完整：缺少主机或端口"
                )
            }

            refreshRuntimeProxy(config)

            val proxyType = when (ProxyConfigProvider.normalizeType(config.type)) {
                "SOCKS5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            val proxy = Proxy(proxyType, InetSocketAddress(config.host, config.port))

            val clientBuilder = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)

            clientBuilder.createSSLSocketFactory()
            clientBuilder.hostnameVerifier(TrustAllHostnameVerifier())

            if (proxyType == Proxy.Type.HTTP && config.username != null && config.password != null) {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = okhttp3.Credentials.basic(config.username, config.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }

            val client = clientBuilder.build()
            val startTime = System.currentTimeMillis()
            val httpProbePassed = runCatching {
                val httpRequest = Request.Builder()
                    .url("http://example.com/")
                    .get()
                    .build()
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} ${response.message}")
                    }
                }
                true
            }.getOrDefault(false)

            val request = Request.Builder()
                .url("https://data-api.polymarket.com/")
                .get()
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                val responseTime = System.currentTimeMillis() - startTime
                val message = buildHttpsProxyFailureMessage(
                    type = ProxyConfigProvider.normalizeType(config.type),
                    httpProbePassed = httpProbePassed,
                    exception = e
                )
                return ProxyCheckResponse.create(
                    success = false,
                    message = message,
                    responseTime = responseTime
                )
            }

            response.use {
                val responseTime = System.currentTimeMillis() - startTime
                val responseBody = it.body?.string()

                if (it.isSuccessful && responseBody != null) {
                    if (responseBody.contains("\"data\"") && responseBody.contains("OK")) {
                        logger.info("代理检查成功：type=${config.type}, host=${config.host}, port=${config.port}, responseTime=${responseTime}ms")
                        return ProxyCheckResponse.create(
                            success = true,
                            message = "${ProxyConfigProvider.normalizeType(config.type)} 代理连接成功",
                            responseTime = responseTime
                        )
                    }
                    return ProxyCheckResponse.create(
                        success = false,
                        message = "${ProxyConfigProvider.normalizeType(config.type)} 代理连接成功，但响应格式不正确：$responseBody",
                        responseTime = responseTime
                    )
                }

                ProxyCheckResponse.create(
                    success = false,
                    message = "${ProxyConfigProvider.normalizeType(config.type)} 代理连接失败：HTTP ${it.code} ${it.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.error("代理检查异常", e)
            ProxyCheckResponse.create(
                success = false,
                message = "代理检查失败：${e.message}"
            )
        }
    }
    
    /**
     * 删除代理配置
     */
    @Transactional
    fun deleteProxyConfig(id: Long): Result<Unit> {
        return try {
            val config = proxyConfigRepository.findById(id)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("代理配置不存在"))
            
            val wasEnabled = config.enabled
            proxyConfigRepository.delete(config)
            logger.info("删除代理配置成功：id=$id, type=${config.type}")

            if (wasEnabled) {
                refreshRuntimeProxy(findEnabledProxyConfig())
                triggerWebSocketReconnect()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除代理配置失败：id=$id", e)
            Result.failure(e)
        }
    }
    
    /**
     * 触发所有 WebSocket 重连（使用新代理配置）
     */
    private fun triggerWebSocketReconnect() {
        try {
            val context = applicationContext ?: return
            
            // 重连订单推送服务的 WebSocket 连接
            try {
                val orderPushService = context.getBean(OrderPushService::class.java)
                kotlinx.coroutines.runBlocking {
                    try {
                        orderPushService.reconnectAllAccounts()
                        logger.info("已触发订单推送服务 WebSocket 重连")
                    } catch (e: Exception) {
                        logger.error("触发订单推送服务重连失败", e)
                    }
                }
            } catch (e: BeansException) {
                logger.debug("订单推送服务未找到，跳过重连", e)
            }
            
            // 重连跟单 WebSocket 服务的连接
            try {
                val copyTradingWebSocketService = context.getBean(CopyTradingWebSocketService::class.java)
                try {
                    copyTradingWebSocketService.reconnectAll()
                    logger.info("已触发跟单 WebSocket 服务重连")
                } catch (e: Exception) {
                    logger.error("触发跟单 WebSocket 服务重连失败", e)
                }
            } catch (e: BeansException) {
                logger.debug("跟单 WebSocket 服务未找到，跳过重连", e)
            }
        } catch (e: Exception) {
            logger.error("触发 WebSocket 重连失败", e)
        }
    }
    
    /**
     * 转换为 DTO（不包含密码）
     */
    private fun toDto(config: ProxyConfig): ProxyConfigDto {
        return ProxyConfigDto(
            id = config.id,
            type = normalizeSupportedType(config.type) ?: "HTTP",
            enabled = config.enabled,
            host = config.host,
            port = config.port,
            username = config.username,
            subscriptionUrl = config.subscriptionUrl,
            lastSubscriptionUpdate = config.lastSubscriptionUpdate,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }

    private fun findEnabledProxyConfig(): ProxyConfig? {
        return proxyConfigRepository.findAll()
            .asSequence()
            .filter { it.enabled && isSupportedType(it.type) }
            .sortedByDescending { it.updatedAt }
            .firstOrNull()
    }

    private fun findPreferredProxyConfig(): ProxyConfig? {
        return proxyConfigRepository.findAll()
            .asSequence()
            .filter { isSupportedType(it.type) }
            .sortedWith(
                compareByDescending<ProxyConfig> { it.enabled }
                    .thenByDescending { it.updatedAt }
                    .thenByDescending { it.id ?: 0L }
            )
            .firstOrNull()
    }

    private fun refreshRuntimeProxy(config: ProxyConfig?) {
        ProxyConfigProvider.setProxyConfig(config)
        ProxyConfigProvider.refreshAuthenticator()
    }

    private fun normalizeAndValidateType(type: String?): String {
        val normalizedType = normalizeSupportedType(type)
        if (normalizedType == null) {
            throw IllegalArgumentException("不支持的代理协议类型：$type")
        }
        return normalizedType
    }

    private fun buildHttpsProxyFailureMessage(type: String, httpProbePassed: Boolean, exception: Exception): String {
        val rawMessage = exception.message ?: exception.javaClass.simpleName
        val lowerMessage = rawMessage.lowercase()
        return when {
            httpProbePassed && ("connection reset" in lowerMessage || "远程主机强迫关闭" in rawMessage) -> {
                "$type 代理可访问普通 HTTP 站点，但建立到 data-api.polymarket.com:443 的 HTTPS 隧道时连接被重置。通常表示该代理节点不支持 HTTPS CONNECT，或屏蔽了 Polymarket 这类目标站点。"
            }
            httpProbePassed -> {
                "$type 代理可访问普通 HTTP 站点，但访问 Polymarket HTTPS 接口失败：$rawMessage"
            }
            else -> {
                "$type 代理检查失败：$rawMessage"
            }
        }
    }

    private fun isSupportedType(type: String?): Boolean {
        return normalizeSupportedType(type) != null
    }

    private fun normalizeSupportedType(type: String?): String? {
        return when (type?.trim()?.uppercase()) {
            "HTTP" -> "HTTP"
            "HTTPS" -> "HTTPS"
            "SOCKS5" -> "SOCKS5"
            else -> null
        }
    }
}

