package com.cloudchat.storage

import android.util.Log
import com.cloudchat.model.ChatMessage
import com.cloudchat.model.ServerConfig
import com.cloudchat.utils.NetworkUtils
import com.cloudchat.utils.ProgressRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.InputStream

class WebDavStorageProvider(
    private val config: ServerConfig,
    private val currentUser: String,
    private val useSafeClient: Boolean = false
) : StorageProvider {

    private val client: OkHttpClient by lazy {
        // 是否使用「只信任系统 CA」的安全 client。
        // FULL 模式默认走安全 client；但若用户勾选「忽略证书」（自签名/私有 CA 服务器，如 Lucky），
        // 则改用不校验证书的 client，否则会像浏览器弹警告那样被拒绝连接。
        val effectiveSafe = useSafeClient && !config.webDavIgnoreCert
        Log.d("WebDavStorage", "Initializing client for primary URL: ${config.webDavUrl} (Safe: $effectiveSafe, ignoreCert: ${config.webDavIgnoreCert})")
        val builder = if (effectiveSafe) {
            NetworkUtils.getSafeOkHttpClient()
        } else {
            NetworkUtils.getUnsafeOkHttpClient()
        }
        builder
            .authenticator { _: Route?, response ->
                // 服务器返回 401 时自动用 Basic 凭据重试。这对以下情况至关重要：
                // 1) Lucky 等服务器在 STUN/反代下会把请求重定向到另一域名，OkHttp 跨域重定向会
                //    丢弃 Authorization 头，导致凭据丢失、认证失败；重定向后的请求收到 401，
                //    这里补回凭据即可成功。
                // 2) 挑战/响应式 Basic 认证（先 401 再带凭据）。
                // 限制重试次数，避免凭据错误时无限循环。
                if (responseCount(response) > 3) return@authenticator null
                val user = config.webDavUser ?: ""
                val pass = config.webDavPass ?: ""
                if (user.isBlank() && pass.isBlank()) return@authenticator null
                val credential = Credentials.basic(user, pass)
                response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
            .build()
    }

    // 测试连接专用：不跟随重定向，以便直接观察服务器返回的重定向状态码
    // （例如 Lucky/STUN 穿透在「访问域名未授权」时会返回 307 重定向到无效地址，
    //  若跟随重定向会导致连接 0.0.0.0 失败，从而掩盖真实原因）
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
    private val auth: String by lazy {
        val user = config.webDavUser ?: ""
        val pass = config.webDavPass ?: ""
        try {
            okhttp3.Credentials.basic(user, pass)
        } catch (e: Exception) {
            ""
        }
    }

    @Volatile
    private var useFallback = false

    @Volatile
    private var lastFailureTime = 0L

    private val fallbackCheckInterval = 30 * 1000L // 30 seconds

    private fun getBaseUrl(useFallbackUrl: Boolean): String {
        val rawUrl = if (useFallbackUrl && config.webDavFallbackUrl.isNotBlank()) {
            config.webDavFallbackUrl.trim()
        } else {
            config.webDavUrl.trim()
        }
        var url = rawUrl
        if (!url.startsWith("http")) url = "http://$url"
        val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
        val path = if (root.isEmpty()) currentUser else "$root/$currentUser"
        return "${url.removeSuffix("/")}/$path/"
    }

    /**
     * 校验并规范化要操作的文件名。
     * 仅允许非空、不以 / 开头、不含路径分隔符(/ \ )、不含 ? # % NUL 等危险字符、长度受限的名称。
     * 返回 null 表示不安全，调用方应拒绝该操作（避免 DELETE/MOVE/PUT 误伤整个目录或越权路径）。
     */
    private fun safeFileName(fileName: String): String? {
        val name = fileName.trim()
        if (name.isBlank() || name.length > 255) return null
        if (name == ".trash") return null
        if (name.startsWith("/") || name.startsWith("\\")) return null
        if (name.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '%' || it == '\u0000' }) return null
        return name
    }

    private val baseUrl: String
        get() {
            val now = System.currentTimeMillis()
            if (useFallback && now - lastFailureTime > fallbackCheckInterval) {
                useFallback = false
                Log.d("WebDavStorage", "Attempting to switch back to primary URL")
            }
            return getBaseUrl(useFallback)
        }

    private suspend fun <T> runWithRetry(block: suspend (String) -> T): T {
        val now = System.currentTimeMillis()
        if (useFallback && now - lastFailureTime > fallbackCheckInterval) {
            useFallback = false
            Log.d("WebDavStorage", "Switching back to primary to see if it recovered")
        }

        val urlToUse = getBaseUrl(useFallback)
        try {
            return block(urlToUse)
        } catch (e: java.io.IOException) {
            if (!useFallback && config.webDavFallbackUrl.isNotBlank()) {
                Log.w("WebDavStorage", "Primary URL request failed: ${e.message}. Trying fallback URL.", e)
                useFallback = true
                lastFailureTime = System.currentTimeMillis()
                val fallbackUrl = getBaseUrl(true)
                return block(fallbackUrl)
            } else {
                throw e
            }
        }
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        var allOk = true

        // 主地址：对实际数据根目录 (serverPath/username/) 做 PROPFIND 列出测试
        // （某些服务器禁止在裸根目录测试，所以改用数据根目录）
        val primary = testSingleUrl(getBaseUrl(false))
        if (primary.isSuccess) {
            report.appendLine("主地址: ${primary.getOrNull()}")
        } else {
            allOk = false
            report.appendLine("主地址: 连接失败 - ${primary.exceptionOrNull()?.message ?: "未知错误"}")
        }

        // 备用地址
        val fallbackUrl = config.webDavFallbackUrl
        if (!fallbackUrl.isNullOrBlank()) {
            val fb = testSingleUrl(getBaseUrl(true))
            if (fb.isSuccess) {
                report.appendLine("备用地址: ${fb.getOrNull()}")
            } else {
                allOk = false
                report.appendLine("备用地址: 连接失败 - ${fb.exceptionOrNull()?.message ?: "未知错误"}")
            }
        } else {
            report.appendLine("备用地址: 未配置")
        }

        val detail = report.toString().trimEnd()
        if (allOk) Result.success(detail) else Result.failure(Exception(detail))
    }

    override suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        suspend fun tryOnce(url: String): Boolean = try {
            // 只要能收到任意 HTTP 响应（含 401/403/404）即说明网络可达；
            // 仅当连接层异常（DNS/TLS/超时/拒绝）抛 IOException 时才算不可达。
            val resp = client.newCall(
                Request.Builder().url(url).head().addHeader("Authorization", auth).build()
            ).execute()
            resp.close()
            true
        } catch (e: Exception) {
            false
        }
        if (tryOnce(getBaseUrl(false))) return@withContext true
        if (!config.webDavFallbackUrl.isNullOrBlank() && tryOnce(getBaseUrl(true))) return@withContext true
        false
    }

    private fun testSingleUrl(testUrl: String): Result<String> {
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(testUrl)
                    .header("Depth", "1")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .method(
                        "PROPFIND",
                        "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>"
                            .toRequestBody("application/xml; charset=utf-8".toMediaType())
                    )
                    .header("Authorization", auth)
                    .build()
                val response = noRedirectClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string().orEmpty()
                response.close()

                return when {
                    // 正常列出目录
                    code == 207 || code in 200..299 -> {
                        val count = "<D:response".toRegex(RegexOption.IGNORE_CASE).findAll(body).count()
                        Result.success("HTTP $code (已列出 $count 项)")
                    }
                    // 服务器可达，但认证失败（用户名/密码错误）
                    code == 401 || code == 403 -> {
                        val authHeader = response.header("WWW-Authenticate").orEmpty()
                        val tip = if (authHeader.contains("Digest", ignoreCase = true)) {
                            "HTTP $code (服务器要求 Digest 摘要认证，当前客户端仅支持 Basic，需要升级客户端)"
                        } else {
                            "HTTP $code (服务器可达，但认证失败，请检查用户名/密码或认证方式)"
                        }
                        Result.success(tip)
                    }
                    // 3xx 重定向：常见于 Lucky 等 STUN 穿透服务，因域名未授权或路径问题
                    // 重定向到无效地址（如 http://0.0.0.0/），需在服务端放行域名
                    code in 300..399 -> {
                        val loc = response.header("Location").orEmpty()
                        val tip = if (loc.contains("0.0.0.0") || loc.isBlank()) {
                            "HTTP $code 重定向异常 (Location: $loc)。Lucky/STUN 穿透常因「访问域名未授权」返回此错误，请在 Lucky 后台把本域名加入允许访问列表，或检查 WebDAV 的外部访问域名设置。"
                        } else {
                            "HTTP $code 重定向到 $loc (请检查服务器路径/域名设置)"
                        }
                        Result.failure(Exception(tip))
                    }
                    // 数据目录不存在：尝试创建后再列出
                    code == 404 -> {
                        mkCol(testUrl)
                        val retry = noRedirectClient.newCall(request).execute()
                        val retryCode = retry.code
                        val retryBody = retry.body?.string().orEmpty()
                        retry.close()
                        if (retryCode == 207 || retryCode in 200..299) {
                            val count = "<D:response".toRegex(RegexOption.IGNORE_CASE).findAll(retryBody).count()
                            Result.success("HTTP $retryCode (目录已创建，已列出 $count 项)")
                        } else {
                            Result.failure(Exception("数据目录不存在且无法创建: HTTP $retryCode"))
                        }
                    }
                    else -> Result.failure(Exception("服务器返回 HTTP $code"))
                }
            } catch (e: Exception) {
                if (attempt == 1) return Result.failure(e)
            }
        }
        return Result.failure(Exception("未知错误"))
    }

    private fun mkCol(url: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .method("MKCOL", null)
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e("WebDavStorage", "Failed to MKCOL $url", e)
        }
    }

    override suspend fun uploadFile(
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val safe = safeFileName(fileName) ?: throw IllegalArgumentException("Unsafe fileName: '$fileName'")
        // Copy inputStream to a temp file to support retrying (since stream can only be read once)
        val tempFile = File.createTempFile("webdav_upload", ".tmp")
        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val fileLength = tempFile.length()

            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")}"
                tempFile.inputStream().use { stream ->
                    val requestBody = if (onProgress != null && fileLength > 0) {
                        ProgressRequestBody(contentType.toMediaType(), stream, fileLength, onProgress)
                    } else {
                        stream.readBytes().toRequestBody(contentType.toMediaType())
                    }

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", auth)
                        .put(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")
                    }
                }
                url
            }
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun uploadFileRange(
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        startByte: Long,
        endByte: Long,
        totalLength: Long,
        onProgress: ((Int) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val safe = safeFileName(fileName) ?: throw IllegalArgumentException("Unsafe fileName: '$fileName'")
        val tempFile = File.createTempFile("webdav_upload_range", ".tmp")
        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val fileLength = tempFile.length()

            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")}"
                tempFile.inputStream().use { stream ->
                    val requestBody = if (onProgress != null && fileLength > 0) {
                        ProgressRequestBody(contentType.toMediaType(), stream, fileLength, onProgress)
                    } else {
                        stream.readBytes().toRequestBody(contentType.toMediaType())
                    }

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", auth)
                        .addHeader("Content-Range", "bytes $startByte-$endByte/$totalLength")
                        .put(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Range upload failed: ${response.code}")
                    }
                }
                url
            }
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun getFileSize(fileName: String): Long = withContext(Dispatchers.IO) {
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .head()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLong() ?: 0L
                } else {
                    -1L
                }
            }
        }
    }

    override suspend fun getLastModified(fileName: String): Long = withContext(Dispatchers.IO) {
        try {
            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", auth)
                    .head()
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val lastModified = response.header("Last-Modified")
                        if (lastModified != null) {
                            val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                            return@runWithRetry sdf.parse(lastModified)?.time ?: -1L
                        }
                    }
                }
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    override suspend fun deleteFile(fileName: String): Unit = withContext(Dispatchers.IO) {
        val safe = safeFileName(fileName) ?: run {
            Log.e("WebDavStorage", "Refusing to delete unsafe fileName: '$fileName'")
            return@withContext
        }
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")}"
            // 绝对禁止删除目录：所有文件都位于同一目录下，DELETE 的目标 URL 必须以文件名结尾，
            // 绝不能以 '/' 结尾（指向数据根目录或任何子目录）。
            if (url.endsWith("/")) {
                Log.e("WebDavStorage", "Refusing to DELETE a directory (URL ends with '/') : $url")
                return@runWithRetry
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .delete()
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 404) {
                        Log.e("WebDavStorage", "Delete failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebDavStorage", "Failed to delete file", e)
            }
        }
    }

    override suspend fun recycleFile(fileName: String): Unit = withContext(Dispatchers.IO) {
        val safe = safeFileName(fileName) ?: run {
            Log.e("WebDavStorage", "Refusing to recycle unsafe fileName: '$fileName'")
            return@withContext
        }
        runWithRetry { currentBaseUrl ->
            // Try to create the .trash directory (must end with / for MKCOL)
            val recycleBinUrl = "${currentBaseUrl}.trash/"
            try {
                val mkcolRequest = Request.Builder()
                    .url(recycleBinUrl)
                    .addHeader("Authorization", auth)
                    .method("MKCOL", null)
                    .build()
                client.newCall(mkcolRequest).execute().use { }
            } catch (e: Exception) {
                // ignore
            }

            val baseName = safe.substringAfterLast('/')
            val sourceUrl = "$currentBaseUrl${java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")}"
            // 绝对禁止删除/移动目录：源 URL 必须以文件名结尾（不以 '/' 结尾），
            // 且绝不能等于整个数据根目录本身（否则会把整个保存目录移进回收站）。
            if (sourceUrl.endsWith("/") || sourceUrl == currentBaseUrl.removeSuffix("/")) {
                Log.e("WebDavStorage", "Refusing to recycle a directory: $sourceUrl")
                return@runWithRetry
            }
            val recycledFileName = "${System.currentTimeMillis()}_${baseName}"
            val destUrl = "${currentBaseUrl}.trash/" + java.net.URLEncoder.encode(recycledFileName, "UTF-8").replace("+", "%20")

            val moveRequest = Request.Builder()
                .url(sourceUrl)
                .addHeader("Authorization", auth)
                .addHeader("Destination", destUrl)
                .method("MOVE", null)
                .build()

            var success = false
            try {
                client.newCall(moveRequest).execute().use { response ->
                    if (response.isSuccessful || response.code == 404) {
                        success = true
                    } else {
                        Log.e("WebDavStorage", "MOVE failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebDavStorage", "MOVE failed with exception", e)
            }

            if (!success) {
                // Fallback to permanent delete if MOVE failed
                deleteFile(safe)
            }
        }
    }

    override suspend fun uploadText(text: String, fileName: String): String = withContext(Dispatchers.IO) {
        val safe = safeFileName(fileName) ?: throw IllegalArgumentException("Unsafe fileName: '$fileName'")
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .put(text.toRequestBody("text/plain".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")
            }
            url
        }
    }

    override suspend fun downloadText(fileName: String): String? = withContext(Dispatchers.IO) {
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else if (response.code == 404) {
                    null
                } else {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
            }
        }
    }

    override suspend fun listMessages(): List<ChatMessage> = emptyList()


    override suspend fun downloadFile(fileName: String, destination: File, onProgress: ((Int) -> Unit)?) {
        withContext(Dispatchers.IO) {
            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                    val body = response.body ?: throw Exception("Response body is null")
                    val totalLength = body.contentLength()
                    
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalLength > 0) {
                                    val progress = ((totalRead * 100) / totalLength).toInt()
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getFullUrl(fileName: String): String {
        val safe = safeFileName(fileName) ?: return "$baseUrl"
        val encodedName = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
        return "$baseUrl$encodedName"
    }
}
