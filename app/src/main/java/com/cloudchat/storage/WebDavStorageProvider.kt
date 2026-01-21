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
    private val currentUser: String
) : StorageProvider {

    private val client: OkHttpClient by lazy {
        NetworkUtils.getUnsafeOkHttpClient().build()
    }

    private val auth = Credentials.basic(config.webDavUser, config.webDavPass)

    private val baseUrl: String
        get() {
            var url = config.webDavUrl.trim()
            if (!url.startsWith("http")) url = "http://$url"
            val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
            val userDir = config.username
            val path = if (root.isEmpty()) userDir else "$root/$userDir"
            return "${url.removeSuffix("/")}/$path/"
        }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", auth)
                .method("PROPFIND", "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop/></D:propfind>".toRequestBody("text/xml".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    mkCol(baseUrl)
                    Result.success(Unit)
                } else if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mkCol(url: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .method("MKCOL", null)
            .build()
        client.newCall(request).execute().close()
    }

    override suspend fun uploadFile(
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val url = getFullUrl(fileName)
        val requestBody = if (onProgress != null && contentLength > 0) {
            ProgressRequestBody(contentType.toMediaType(), inputStream, contentLength, onProgress)
        } else {
            inputStream.readBytes().toRequestBody(contentType.toMediaType())
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .put(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")
        }
        return@withContext url
    }

    override suspend fun getFileSize(fileName: String): Long = withContext(Dispatchers.IO) {
        val url = getFullUrl(fileName)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .head()
            .build()
        
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return@withContext response.header("Content-Length")?.toLong() ?: -1L
            }
        }
        return@withContext -1L
    }

    override suspend fun uploadText(text: String, fileName: String): String = withContext(Dispatchers.IO) {
        val url = getFullUrl(fileName)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .put(text.toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")
        }
        return@withContext url
    }

    override suspend fun listMessages(): List<ChatMessage> = emptyList()

    override suspend fun downloadFile(fileName: String, destination: File, onProgress: ((Int) -> Unit)?) {
        withContext(Dispatchers.IO) {
            val url = getFullUrl(fileName)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", auth)
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

    override fun getFullUrl(fileName: String): String {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "$baseUrl$encodedName"
    }
}
