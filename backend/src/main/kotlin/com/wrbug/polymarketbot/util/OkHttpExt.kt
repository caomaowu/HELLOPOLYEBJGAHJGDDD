package com.wrbug.polymarketbot.util

import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

data class ClientTimeoutConfig(
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val callTimeoutSeconds: Long
)

enum class HttpClientProfile(val timeoutConfig: ClientTimeoutConfig) {
    DEFAULT(
        ClientTimeoutConfig(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 30,
            writeTimeoutSeconds = 30,
            callTimeoutSeconds = 0
        )
    ),
    ORDER_SUBMIT(
        ClientTimeoutConfig(
            connectTimeoutSeconds = 5,
            readTimeoutSeconds = 8,
            writeTimeoutSeconds = 8,
            callTimeoutSeconds = 10
        )
    )
}

/**
 * 获取代理配置（用于 WebSocket 和 HTTP 请求）
 * 从数据库读取代理配置
 * @return Proxy 对象，如果未启用代理则返回 null
 */
fun getProxyConfig(): Proxy? {
    return ProxyConfigProvider.getProxy()
}

/**
 * 创建OkHttpClient客户端
 * 自动应用代理配置（从数据库读取）
 * @return OkHttpClient.Builder
 */
fun createClient(profile: HttpClientProfile = HttpClientProfile.DEFAULT): OkHttpClient.Builder {
    val timeoutConfig = profile.timeoutConfig
    val builder = OkHttpClient.Builder()
        .dns(Ipv4PreferredDns())
        .connectTimeout(timeoutConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutConfig.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutConfig.writeTimeoutSeconds, TimeUnit.SECONDS)

    if (timeoutConfig.callTimeoutSeconds > 0) {
        builder.callTimeout(timeoutConfig.callTimeoutSeconds, TimeUnit.SECONDS)
    }

    // 从数据库读取代理配置
    val dbProxy = ProxyConfigProvider.getProxy()
    if (dbProxy != null) {
        builder.proxy(dbProxy)
        builder.createSSLSocketFactory()

        // HTTP/HTTPS 代理使用 Proxy-Authorization；SOCKS5 认证由 JVM Authenticator 处理
        val username = ProxyConfigProvider.getProxyUsername()
        val password = ProxyConfigProvider.getProxyPassword()
        if (dbProxy.type() == java.net.Proxy.Type.HTTP && username != null && password != null) {
            builder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
    }

    return builder
}

/**
 * 为OkHttpClient创建信任所有证书的SSL工厂
 * @return OkHttpClient.Builder
 */
fun OkHttpClient.Builder.createSSLSocketFactory(): OkHttpClient.Builder {
    return apply {
        try {
            val sc: SSLContext = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(TrustAllManager()), SecureRandom())
            sslSocketFactory(sc.socketFactory, TrustAllManager())
        } catch (t: Error) {

        }
    }
}

/**
 * 信任所有证书的TrustManager
 */
class TrustAllManager : X509TrustManager {
    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
    }

    override fun getAcceptedIssuers() = arrayOfNulls<X509Certificate>(0)
}

/**
 * 信任所有主机名的HostnameVerifier
 */
class TrustAllHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        return true
    }
}

/**
 * 优先使用 IPv4，避免部分代理或本地网络在 Polymarket 的 IPv6 链路上长时间超时。
 */
class Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4Addresses.isNotEmpty()) {
            ipv4Addresses + addresses.filterNot { it is Inet4Address }
        } else {
            addresses
        }
    }
}
