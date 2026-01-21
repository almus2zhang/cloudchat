package com.cloudchat.storage

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.cloudchat.model.ChatMessage
import com.cloudchat.model.MessageType
import com.cloudchat.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class S3StorageProvider(
    private val config: ServerConfig,
    private val currentUser: String
) : StorageProvider {

    private val s3Client: AmazonS3Client by lazy {
        val credentials = BasicAWSCredentials(config.accessKey, config.secretKey)
        val client = AmazonS3Client(credentials)
        if (config.endpoint.isNotEmpty()) {
            var endpoint = config.endpoint.trim()
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "https://$endpoint"
            }
            client.setEndpoint(endpoint)
        }
        client
    }

    private val userPrefix: String
        get() {
            val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
            val userDir = config.username
            return if (root.isEmpty()) "$userDir/" else "$root/$userDir/"
        }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            s3Client.listBuckets()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadFile(
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val key = "$userPrefix$fileName"
        val metadata = ObjectMetadata().apply {
            this.contentType = contentType
            if (contentLength > 0) {
                this.contentLength = contentLength
            }
        }
        s3Client.putObject(config.bucket, key, inputStream, metadata)
        onProgress?.invoke(100)
        return@withContext key
    }

    override suspend fun getFileSize(fileName: String): Long = withContext(Dispatchers.IO) {
        try {
            val metadata = s3Client.getObjectMetadata(config.bucket, "$userPrefix$fileName")
            return@withContext metadata.contentLength
        } catch (e: Exception) {
            return@withContext -1L
        }
    }

    override suspend fun uploadText(text: String, fileName: String): String = withContext(Dispatchers.IO) {
        val key = "$userPrefix$fileName"
        val bytes = text.toByteArray()
        val metadata = ObjectMetadata().apply {
            contentLength = bytes.size.toLong()
            contentType = "text/plain"
        }
        s3Client.putObject(config.bucket, key, ByteArrayInputStream(bytes), metadata)
        return@withContext key
    }

    override suspend fun listMessages(): List<ChatMessage> = emptyList()

    override suspend fun downloadFile(fileName: String, destination: File, onProgress: ((Int) -> Unit)?) {
        withContext(Dispatchers.IO) {
            val key = "$userPrefix$fileName"
            val s3Object = s3Client.getObject(config.bucket, key)
            val totalLength = s3Object.objectMetadata.contentLength
            
            s3Object.objectContent.use { input ->
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

    override fun getFullUrl(fileName: String): String {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val key = "$userPrefix$encodedName"
        var endpoint = config.endpoint.trim()
        if (endpoint.isEmpty()) {
            // Default AWS S3 format
            return "https://${config.bucket}.s3.amazonaws.com/$key"
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://$endpoint"
        }
        return "${endpoint.removeSuffix("/")}/${config.bucket}/$key"
    }
}
