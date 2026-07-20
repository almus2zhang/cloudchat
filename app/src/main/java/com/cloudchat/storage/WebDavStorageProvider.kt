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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream

class WebDavStorageProvider(
    private val config: ServerConfig,
    private val currentUser: String,
    private val useSafeClient: Boolean = false
) : StorageProvider {

    private val client: OkHttpClient by lazy {
        Log.d("WebDavStorage", "Initializing client for primary URL: ${config.webDavUrl} (Safe: $useSafeClient)")
        if (useSafeClient) {
            NetworkUtils.getSafeOkHttpClient().build()
        } else {
            NetworkUtils.getUnsafeOkHttpClient().build()
        }
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

        // 主地址：使用 WebDAV PROPFIND 列出文件/目录（非 GET）
        val primary = testSingleUrl(config.webDavUrl)
        if (primary.isSuccess) {
            report.appendLine("主地址: ${primary.getOrNull()}")
        } else {
            allOk = false
            report.appendLine("主地址: 连接失败 - ${primary.exceptionOrNull()?.message ?: "未知错误"}")
        }

        // 备用地址
        val fallbackUrl = config.webDavFallbackUrl
        if (!fallbackUrl.isNullOrBlank()) {
            val fb = testSingleUrl(fallbackUrl)
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

    private fun testSingleUrl(baseUrl: String): Result<String> {
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(baseUrl)
                    .header("Depth", "1")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .method(
                        "PROPFIND",
                        "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop/></D:propfind>"
                            .toRequestBody("application/xml; charset=utf-8".toMediaType())
                    )
                    .header("Authorization", auth)
                    .build()
                val response = client.newCall(request).execute()
                val code = response.code
                response.close()
                // 只要服务器返回了 HTTP 响应，即说明地址可达（连接成功）。
                // 403/401 等仅表示权限/认证问题，并不代表服务器不可达。
                return Result.success("HTTP $code")
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
        // Copy inputStream to a temp file to support retrying (since stream can only be read once)
        val tempFile = File.createTempFile("webdav_upload", ".tmp")
        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val fileLength = tempFile.length()

            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
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
        val tempFile = File.createTempFile("webdav_upload_range", ".tmp")
        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val fileLength = tempFile.length()

            runWithRetry { currentBaseUrl ->
                val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
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
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .head()
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val lastModified = response.header("Last-Modified")
                        if (lastModified != null) {
                            val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                            sdf.parse(lastModified)?.time ?: -1L
                        } else -1L
                    } else -1L
                }
            } catch (e: Exception) {
                Log.e("WebDavStorage", "Failed to get last modified", e)
                -1L
            }
        }
    }

    override suspend fun deleteFile(fileName: String): Unit = withContext(Dispatchers.IO) {
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
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
        runWithRetry { currentBaseUrl ->
            // Try to create the .trash directory
            val recycleBinUrl = "${currentBaseUrl}.trash"
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

            val baseName = fileName.substringAfterLast('/')
            val sourceUrl = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
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
                deleteFile(fileName)
            }
        }
    }

    override suspend fun uploadText(text: String, fileName: String): String = withContext(Dispatchers.IO) {
        runWithRetry { currentBaseUrl ->
            val url = "$currentBaseUrl${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
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
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "$baseUrl$encodedName"
    }
}
