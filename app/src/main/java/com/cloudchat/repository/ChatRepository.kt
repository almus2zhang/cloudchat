package com.cloudchat.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.cloudchat.model.ChatMessage
import com.cloudchat.model.MessageStatus
import com.cloudchat.model.ServerConfig
import com.cloudchat.storage.S3StorageProvider
import com.cloudchat.storage.StorageProvider
import com.cloudchat.storage.WebDavStorageProvider
import com.cloudchat.utils.NetworkUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

class ChatRepository(private val context: Context) {
    private val gson = Gson()
    private var storageProvider: StorageProvider? = null
    private var currentConfig: ServerConfig? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _uploadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val uploadProgress: StateFlow<Map<String, Int>> = _uploadProgress

    private val transientLocalUris = mutableMapOf<String, String>()

    private fun getLocalHistoryFile(accountId: String): File {
        val dir = File(context.filesDir, "history")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "chat_${accountId}.json")
    }

    fun getTransientUri(messageId: String): String? = transientLocalUris[messageId]

    suspend fun updateConfig(config: ServerConfig) {
        currentConfig = config
        if (config.type == com.cloudchat.model.StorageType.WEBDAV) {
            NetworkUtils.currentAuth = Credentials.basic(config.webDavUser, config.webDavPass)
        }
        storageProvider = if (config.type == com.cloudchat.model.StorageType.S3) {
            S3StorageProvider(config, config.username)
        } else {
            WebDavStorageProvider(config, config.username)
        }
        loadLocalHistory(config.id)
        if (_messages.value.isEmpty()) {
            refreshHistoryFromCloud()
        }
    }

    private suspend fun loadLocalHistory(accountId: String) = withContext(Dispatchers.IO) {
        val file = getLocalHistoryFile(accountId)
        if (file.exists()) {
            try {
                val json = file.readText()
                val history: List<ChatMessage> = gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
                _messages.value = history
            } catch (e: Exception) {
                Log.e("ChatRepository", "Load failed", e)
            }
        }
    }

    private suspend fun saveLocalHistory(accountId: String) = withContext(Dispatchers.IO) {
        val file = getLocalHistoryFile(accountId)
        val json = gson.toJson(_messages.value)
        file.writeText(json)
    }

    suspend fun sendMessage(
        content: String, 
        type: com.cloudchat.model.MessageType = com.cloudchat.model.MessageType.TEXT, 
        inputStream: InputStream? = null, 
        fileName: String? = null,
        localUri: String? = null
    ) {
        val provider = storageProvider ?: return
        val config = currentConfig ?: return
        
        var fileSize = 0L
        var videoDuration = 0L

        if (localUri != null) {
            val uri = Uri.parse(localUri)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { 
                fileSize = it.length
            }
            if (type == com.cloudchat.model.MessageType.VIDEO) {
                videoDuration = getVideoDuration(uri)
            }
        }

        val encodedFileName = fileName?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val userDir = config.username
        val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
        val cloudPath = if (root.isEmpty()) userDir else "$root/$userDir"

        val remoteUrl = if (encodedFileName != null && config.type == com.cloudchat.model.StorageType.WEBDAV) {
            var url = config.webDavUrl.trim()
            if (!url.startsWith("http")) url = "http://$url"
            "${url.removeSuffix("/")}/$cloudPath/$encodedFileName"
        } else encodedFileName

        val newMessage = ChatMessage(
            sender = config.username,
            content = fileName ?: content,
            type = type,
            isOutgoing = true,
            remoteUrl = remoteUrl,
            fileSize = fileSize,
            videoDuration = videoDuration,
            status = if (inputStream != null) MessageStatus.SENDING else MessageStatus.SUCCESS
        )

        localUri?.let { transientLocalUris[newMessage.id] = it }
        _messages.value = _messages.value + newMessage
        saveLocalHistory(config.id)

        if (inputStream != null) {
            _uploadProgress.value = _uploadProgress.value + (newMessage.id to 0)
        }

        withContext(Dispatchers.IO) {
            try {
                if (inputStream != null && fileName != null) {
                    val contentType = when(type) {
                        com.cloudchat.model.MessageType.IMAGE -> "image/jpeg"
                        com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    
                    // Upload
                    provider.uploadFile(inputStream, fileName, contentType, fileSize) { progress ->
                        _uploadProgress.value = _uploadProgress.value + (newMessage.id to progress)
                    }
                    
                    // Verification: Check if file exists and size matches (roughly)
                    val remoteSize = provider.getFileSize(fileName)
                    if (remoteSize > 0) {
                        updateMessageStatus(newMessage.id, MessageStatus.SUCCESS)
                    } else {
                        updateMessageStatus(newMessage.id, MessageStatus.FAILED)
                    }
                    
                    _uploadProgress.value = _uploadProgress.value + (newMessage.id to -1)
                } else {
                    provider.uploadText(content, "msg_${System.currentTimeMillis()}.txt")
                }
                provider.uploadText(gson.toJson(_messages.value), "chat_history.json")
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud sync failed", e)
                updateMessageStatus(newMessage.id, MessageStatus.FAILED)
                _uploadProgress.value = _uploadProgress.value - newMessage.id
            }
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.value = _messages.value.map { 
            if (it.id == messageId) it.copy(status = status) else it 
        }
        currentConfig?.let { config ->
            // Trigger local save
            val file = getLocalHistoryFile(config.id)
            file.writeText(gson.toJson(_messages.value))
        }
    }

    private fun getVideoDuration(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            (time?.toLong() ?: 0L) / 1000
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun refreshHistoryFromCloud() = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext
        val config = currentConfig ?: return@withContext
        try {
            val tempFile = File(context.cacheDir, "cloud_history.json")
            provider.downloadFile("chat_history.json", tempFile)
            if (tempFile.exists()) {
                val json = tempFile.readText()
                val history: List<ChatMessage> = gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
                _messages.value = history
                saveLocalHistory(config.id)
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud refresh failed", e)
        }
    }
}
