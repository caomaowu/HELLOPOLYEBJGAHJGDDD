package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.entity.ProxyConfig
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.PasswordAuthentication

/**
 * 代理配置提供者（单例）
 * 用于在工具函数中获取代理配置
 */
object ProxyConfigProvider {
    @Volatile
    private var proxyConfig: ProxyConfig? = null
    
    /**
     * 设置代理配置（由 ProxyConfigService 调用）
     */
    fun setProxyConfig(config: ProxyConfig?) {
        proxyConfig = config
    }
    
    /**
     * 获取代理配置
     */
    fun getProxyConfig(): ProxyConfig? = proxyConfig

    fun normalizeType(type: String?): String {
        return when (type?.trim()?.uppercase()) {
            "HTTP", "HTTPS", "SOCKS5" -> type.trim().uppercase()
            else -> "HTTP"
        }
    }
    
    /**
     * 获取 Proxy 对象（用于 OkHttp）
     */
    fun getProxy(): Proxy? {
        val config = proxyConfig ?: return null
        if (!config.enabled) {
            return null
        }
        if (config.host == null || config.port == null) {
            return null
        }
        val proxyType = when (normalizeType(config.type)) {
            "SOCKS5" -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP  // HTTP / HTTPS 都使用 HTTP CONNECT 代理
        }
        return Proxy(proxyType, InetSocketAddress(config.host, config.port))
    }
    
    /**
     * 获取代理用户名
     */
    fun getProxyUsername(): String? = proxyConfig?.username
    
    /**
     * 获取代理密码
     */
    fun getProxyPassword(): String? = proxyConfig?.password

    /**
     * 根据当前代理配置刷新 JVM 级别的代理认证器。
     * SOCKS5 用户名密码依赖这里；HTTP/HTTPS 代理也可以共用此认证。
     */
    fun refreshAuthenticator() {
        val config = proxyConfig
        if (config == null || !config.enabled || config.username.isNullOrBlank() || config.password.isNullOrBlank()) {
            Authenticator.setDefault(null)
            return
        }

        val expectedHost = config.host
        val expectedPort = config.port
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                // SOCKS5 在不同 JVM/底层实现里的 requestorType 并不总是 PROXY，
                // 这里改为只校验目标 host/port，避免明明凭证正确却拿不到认证。
                if (!expectedHost.isNullOrBlank() && requestingHost != null && !requestingHost.equals(expectedHost, ignoreCase = true)) {
                    return null
                }
                if (expectedPort != null && requestingPort > 0 && requestingPort != expectedPort) {
                    return null
                }
                return PasswordAuthentication(config.username, config.password.toCharArray())
            }
        })
    }
}

