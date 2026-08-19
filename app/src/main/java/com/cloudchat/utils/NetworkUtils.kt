package com.cloudchat.utils

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LanCheckResult(
    val isServerLan: Boolean,
    val isSameLan: Boolean,
    val deviceIps: List<String>,
    val serverHost: String,
    val debugMessage: String
)

object NetworkUtils {
    @Volatile
    var currentAuth: String? = null

    /**
     * Safe client that honors system certificates and Network Security Config.
     * Prevents packet capture by only trusting system-provided CAs.
     */
    fun getSafeOkHttpClient(): OkHttpClient.Builder {
        val authInterceptor = createAuthInterceptor()
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
    }

    /**
     * Unsafe client that bypasses SSL verification.
     * Use only for self-built versions with non-standard certs.
     */
    fun getUnsafeOkHttpClient(): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val authInterceptor = createAuthInterceptor()

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(authInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to create unsafe client", e)
            return getSafeOkHttpClient()
        }
    }

    private fun createAuthInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        Log.d("NetworkUtils", "Request: ${request.method} $url")
        
        val authenticatedRequest = if (currentAuth != null && request.header("Authorization") == null) {
            request.newBuilder()
                .header("Authorization", currentAuth!!)
                .build()
        } else {
            request
        }
        
        try {
            val response = chain.proceed(authenticatedRequest)
            Log.d("NetworkUtils", "Response: ${response.code} for $url")
            response
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Request failed for $url", e)
            throw e
        }
    }

    /**
     * 判断 IP 或主机名是否处于 RFC 1918 私有局域网网段 (10.x.x.x, 172.16-31.x.x, 192.168.x.x, 127.x.x.x) 或 .local/.lan/localhost
     */
    fun isPrivateOrLanIp(host: String): Boolean {
        val cleanHost = host.trim().lowercase()
        if (cleanHost.isEmpty()) return false
        if (cleanHost == "localhost" || cleanHost.endsWith(".local") || cleanHost.endsWith(".lan")) {
            return true
        }
        val parts = cleanHost.split(".")
        if (parts.size != 4) return false
        return try {
            val octets = parts.map { it.toInt() }
            val p0 = octets[0]
            val p1 = octets[1]
            when {
                p0 == 127 -> true
                p0 == 10 -> true
                p0 == 172 && (p1 in 16..31) -> true
                p0 == 192 && p1 == 168 -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取本机所有活跃的非环回 IPv4 地址
     */
    fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.contains("p2p") || name.contains("dummy")) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddr = addr.hostAddress
                        if (!hostAddr.isNullOrBlank() && !hostAddr.startsWith("127.")) {
                            result.add(hostAddr)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Error getting local IP: ${e.message}")
        }
        return result
    }

    /**
     * 判断两个 IPv4 地址是否属于相同的局域网子网 (根据 class C /24 或 class A/B 网段)
     */
    fun isSameSubnet(ip1: String, ip2: String): Boolean {
        val p1 = ip1.trim().split(".")
        val p2 = ip2.trim().split(".")
        if (p1.size != 4 || p2.size != 4) return false
        return try {
            val o1 = p1.map { it.toInt() }
            val o2 = p2.map { it.toInt() }
            if (o1[0] == 192 && o1[1] == 168 && o2[0] == 192 && o2[1] == 168) {
                o1[2] == o2[2]
            } else if (o1[0] == 10 && o2[0] == 10) {
                o1[1] == o2[1] && o1[2] == o2[2]
            } else if (o1[0] == 172 && o2[0] == 172 && (o1[1] in 16..31) && (o2[1] in 16..31)) {
                o1[1] == o2[1] && o1[2] == o2[2]
            } else {
                o1[0] == o2[0] && o1[1] == o2[1] && o1[2] == o2[2]
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 URL 提取主机名/IP
     */
    fun extractHost(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return try {
            val uri = URI(url)
            uri.host ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 核心检测逻辑
     */
    fun checkLanStatus(serverUrl: String, fallbackUrl: String): LanCheckResult {
        val host = extractHost(serverUrl)
        if (host.isBlank()) {
            return LanCheckResult(
                isServerLan = false,
                isSameLan = false,
                deviceIps = emptyList(),
                serverHost = host,
                debugMessage = "[LAN Debug] 主服务 URL 为空"
            )
        }

        val isServerLan = isPrivateOrLanIp(host)
        if (!isServerLan) {
            return LanCheckResult(
                isServerLan = false,
                isSameLan = false,
                deviceIps = emptyList(),
                serverHost = host,
                debugMessage = "[LAN Debug] 配置的主服务 ($host) 为公网域名/地址"
            )
        }

        val localIps = getLocalIpv4Addresses()
        if (localIps.isEmpty()) {
            return LanCheckResult(
                isServerLan = true,
                isSameLan = false,
                deviceIps = emptyList(),
                serverHost = host,
                debugMessage = "[LAN Debug] 主服务 ($host) 为局域网 IP，但未找到本机有效 IPv4 -> 瞬间切换至备用地址: ${fallbackUrl.ifBlank { "未配置" }}"
            )
        }

        var matchedIp: String? = null
        for (ip in localIps) {
            if (isSameSubnet(ip, host) || host == "localhost" || host == "127.0.0.1" || ip == host) {
                matchedIp = ip
                break
            }
        }

        return if (matchedIp != null) {
            LanCheckResult(
                isServerLan = true,
                isSameLan = true,
                deviceIps = localIps,
                serverHost = host,
                debugMessage = "[LAN Debug MATCHED] 本机 IP ($matchedIp) 与配置的服务地址 ($host) 处于同一局域网 -> 使用局域网地址 ($serverUrl)"
            )
        } else {
            val target = if (fallbackUrl.isNotBlank()) fallbackUrl else "原地址(未配备用)"
            LanCheckResult(
                isServerLan = true,
                isSameLan = false,
                deviceIps = localIps,
                serverHost = host,
                debugMessage = "[LAN Debug SWITCH] 本机 IP (${localIps.joinToString()}) 与局域网服务地址 ($host) 不在同一网段 -> 瞬间切换至备用地址: $target"
            )
        }
    }
}
