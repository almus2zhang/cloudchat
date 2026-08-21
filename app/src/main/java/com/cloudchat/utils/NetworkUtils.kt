package com.cloudchat.utils

import android.os.Environment
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
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

            val sniSocketFactory = SniSSLSocketFactory(sslContext.socketFactory)

            val tlsSpec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.COMPATIBLE_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build()

            val authInterceptor = createAuthInterceptor()

            return OkHttpClient.Builder()
                .connectionSpecs(listOf(tlsSpec, okhttp3.ConnectionSpec.CLEARTEXT))
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .sslSocketFactory(sniSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(authInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to create unsafe client", e)
            return getSafeOkHttpClient()
        }
    }

    private fun createAuthInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val shortName = url.split("?")[0].split("/").filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
        DebugLogger.log("HTTP", "${request.method} $shortName")
        
        val authenticatedRequest = if (currentAuth != null && request.header("Authorization") == null) {
            request.newBuilder()
                .header("Authorization", currentAuth!!)
                .build()
        } else {
            request
        }
        
        try {
            val response = chain.proceed(authenticatedRequest)
            DebugLogger.log("HTTP", "${request.method} $shortName -> Status ${response.code}")
            response
        } catch (e: Exception) {
            val causeMsg = e.cause?.message?.let { " ($it)" } ?: ""
            val errLine = "[FAIL] ${request.method} $shortName -> ${e.javaClass.simpleName}: ${e.message ?: "Connect Error"}$causeMsg"
            DebugLogger.log("HTTP_ERR", errLine)
            Log.e("NetworkUtils", errLine, e)
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

    data class DetailedInterfaceInfo(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val ipv4Addrs: List<String>
    )

    fun getAllNetworkInterfacesDetailed(): List<DetailedInterfaceInfo> {
        val list = mutableListOf<DetailedInterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = mutableListOf<String>()
                val enumAddrs = iface.inetAddresses
                while (enumAddrs.hasMoreElements()) {
                    val addr = enumAddrs.nextElement()
                    if (addr is Inet4Address) {
                        val hostAddr = addr.hostAddress
                        if (!hostAddr.isNullOrBlank()) {
                            addrs.add(hostAddr)
                        }
                    }
                }
                list.add(DetailedInterfaceInfo(iface.name, iface.isUp, iface.isLoopback, addrs))
            }
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Error getting detailed interfaces: ${e.message}")
        }
        return list
    }

    /**
     * 获取本机所有活跃的非环回 IPv4 地址
     */
    fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        val detailed = getAllNetworkInterfacesDetailed()
        for (iface in detailed) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (name.contains("p2p") || name.contains("dummy")) continue
            for (ip in iface.ipv4Addrs) {
                if (!ip.startsWith("127.")) {
                    result.add(ip)
                }
            }
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
     * 将调试日志写入 Download/cloudchat/lan_debug.txt 文件
     */
    fun writeDebugLogToFile(content: String) {
        try {
            val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val cloudChatDir = File(downloadsFolder, "cloudchat")
            if (!cloudChatDir.exists()) {
                cloudChatDir.mkdirs()
            }
            val debugFile = File(cloudChatDir, "lan_debug.txt")
            debugFile.writeText(content)

            val logFile = File(cloudChatDir, "debug.log")
            logFile.appendText("\n" + content)

            Log.d("NetworkUtils", "LAN debug log successfully written to ${debugFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to write debug log to download/cloudchat: ${e.message}", e)
        }
    }

    /**
     * 核心检测逻辑
     */
    fun checkLanStatus(serverUrl: String, fallbackUrl: String): LanCheckResult {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("CloudChat Android LAN Subnet Diagnosis Log")
        sb.appendLine("Time: $timeStr")
        sb.appendLine("==================================================")

        val host = extractHost(serverUrl)
        sb.appendLine("[1] Server Configuration:")
        sb.appendLine("    - Primary URL: $serverUrl")
        sb.appendLine("    - Extracted Host: $host")
        sb.appendLine("    - Fallback URL: ${fallbackUrl.ifBlank { "(None)" }}")

        if (host.isBlank()) {
            sb.appendLine("    - Status: Primary URL host is blank")
            val msg = "[LAN Debug] 主服务 URL 为空"
            writeDebugLogToFile(sb.toString())
            return LanCheckResult(false, false, emptyList(), host, msg)
        }

        val isServerLan = isPrivateOrLanIp(host)
        sb.appendLine("    - Is Private LAN Host: $isServerLan")

        if (!isServerLan) {
            sb.appendLine("    - Status: Primary host ($host) is public domain/IP. No LAN check needed.")
            val msg = "[LAN Debug] 配置的主服务 ($host) 为公网域名/地址"
            writeDebugLogToFile(sb.toString())
            return LanCheckResult(false, false, emptyList(), host, msg)
        }

        val allIfaces = getAllNetworkInterfacesDetailed()
        sb.appendLine("\n[2] Device Network Interfaces:")
        if (allIfaces.isEmpty()) {
            sb.appendLine("    - (No network interfaces returned by system)")
        } else {
            for (iface in allIfaces) {
                sb.appendLine("    - Interface '${iface.name}' (isUp=${iface.isUp}, isLoopback=${iface.isLoopback}):")
                if (iface.ipv4Addrs.isEmpty()) {
                    sb.appendLine("      * IPv4: (None)")
                } else {
                    for (ip in iface.ipv4Addrs) {
                        sb.appendLine("      * IPv4: $ip")
                    }
                }
            }
        }

        val localIps = getLocalIpv4Addresses()
        sb.appendLine("\n[3] Active Local IPv4 Addresses Filtered:")
        sb.appendLine("    - Filtered IPs: ${localIps.ifEmpty { listOf("(None)") }.joinToString()}")

        if (localIps.isEmpty()) {
            sb.appendLine("    - Result: No active non-loopback local IPv4 found -> Switching to fallback: ${fallbackUrl.ifBlank { "未配置" }}")
            val msg = "[LAN Debug] 主服务 ($host) 为局域网 IP，但未找到本机有效 IPv4 -> 瞬间切换至备用地址"
            writeDebugLogToFile(sb.toString())
            return LanCheckResult(true, false, emptyList(), host, msg)
        }

        sb.appendLine("\n[4] Subnet Comparison Steps:")
        var matchedIp: String? = null
        for (ip in localIps) {
            val match = isSameSubnet(ip, host) || host == "localhost" || host == "127.0.0.1" || ip == host
            sb.appendLine("    - Compare Local IP ($ip) vs Server Host ($host): $match")
            if (match) {
                matchedIp = ip
                break
            }
        }

        val result: LanCheckResult
        if (matchedIp != null) {
            sb.appendLine("\n[5] Final Decision:")
            sb.appendLine("    - Result: MATCHED SAME LAN (Matched IP: $matchedIp)")
            sb.appendLine("    - Effective URL Used: $serverUrl")
            result = LanCheckResult(
                isServerLan = true,
                isSameLan = true,
                deviceIps = localIps,
                serverHost = host,
                debugMessage = "[LAN Debug MATCHED] 本机 IP ($matchedIp) 与配置的服务地址 ($host) 处于同一局域网 -> 使用局域网地址 ($serverUrl)"
            )
        } else {
            val target = if (fallbackUrl.isNotBlank()) fallbackUrl else "原地址(未配备用)"
            sb.appendLine("\n[5] Final Decision:")
            sb.appendLine("    - Result: MISMATCHED (NOT ON SAME LAN)")
            sb.appendLine("    - Instantly Switching To Fallback URL: $target")
            result = LanCheckResult(
                isServerLan = true,
                isSameLan = false,
                deviceIps = localIps,
                serverHost = host,
                debugMessage = "[LAN Debug SWITCH] 本机 IP (${localIps.joinToString()}) 与局域网服务地址 ($host) 不在同一网段 -> 瞬间切换至备用地址: $target"
            )
        }
        sb.appendLine("==================================================")
        writeDebugLogToFile(sb.toString())
        return result
    }
}

class SniSSLSocketFactory(private val delegate: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): java.net.Socket {
        return delegate.createSocket()
    }

    override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket {
        val socket = delegate.createSocket(s, host, port, autoClose) as javax.net.ssl.SSLSocket
        setSni(socket, host)
        return socket
    }

    override fun createSocket(host: String, port: Int): java.net.Socket {
        val socket = delegate.createSocket(host, port) as javax.net.ssl.SSLSocket
        setSni(socket, host)
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort) as javax.net.ssl.SSLSocket
        setSni(socket, host)
        return socket
    }

    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket {
        return delegate.createSocket(host, port)
    }

    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket {
        return delegate.createSocket(host, port, localHost, localPort)
    }

    private fun setSni(socket: javax.net.ssl.SSLSocket, host: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val sslParameters = socket.sslParameters
                sslParameters.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                socket.sslParameters = sslParameters
            } else {
                val method = socket.javaClass.getMethod("setHostname", String::class.java)
                method.invoke(socket, host)
            }
        } catch (e: Exception) {
            Log.w("NetworkUtils", "Failed to set SNI hostname for $host: ${e.message}")
        }
    }
}
