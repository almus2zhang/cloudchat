package com.cloudchat.storage

import com.cloudchat.model.ChatMessage
import java.io.File
import java.io.InputStream

interface StorageProvider {
    suspend fun testConnection(): Result<Unit>
    suspend fun uploadFile(
        inputStream: InputStream, 
        fileName: String, 
        contentType: String,
        contentLength: Long = -1,
        onProgress: ((Int) -> Unit)? = null
    ): String
    suspend fun uploadText(text: String, fileName: String): String
    suspend fun listMessages(): List<ChatMessage>
    suspend fun downloadFile(fileName: String, destination: File)
    suspend fun getFileSize(fileName: String): Long // For verification
}
