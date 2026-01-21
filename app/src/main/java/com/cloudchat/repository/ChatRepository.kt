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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress

    private val transientLocalUris = mutableMapOf<String, String>()

    private fun getLocalHistoryFile(accountId: String): File {
        val dir = File(context.filesDir, "history")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "chat_${accountId}.json")
    }

    private fun getMediaCacheDir(): File {
        val dir = File(context.filesDir, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLocalFile(messageId: String, fileName: String): File {
        return File(getMediaCacheDir(), "${messageId}_$fileName")
    }

    fun getTransientUri(messageId: String, fileName: String? = null): String? {
        // 1. Check transient (just uploaded)
        transientLocalUris[messageId]?.let { return it }
        
        // 2. Check local disk cache
        if (fileName != null) {
            val file = getLocalFile(messageId, fileName)
            if (file.exists()) return Uri.fromFile(file).toString()
        }
        
        return null
    }

    suspend fun downloadFileToCache(messageId: String, fileName: String, remoteUrl: String): File? = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext null
        val targetFile = getLocalFile(messageId, fileName)
        if (targetFile.exists()) return@withContext targetFile
        
        val tmpFile = File(targetFile.absolutePath + ".tmp")
        try {
            _downloadProgress.update { it + (messageId to 0) }
            provider.downloadFile(fileName, tmpFile) { progress ->
                _downloadProgress.update { it + (messageId to progress) }
            }
            if (tmpFile.exists()) {
                tmpFile.renameTo(targetFile)
            }
            _downloadProgress.update { it + (messageId to -1) } // Complete
            if (targetFile.exists()) return@withContext targetFile
        } catch (e: Exception) {
            Log.e("ChatRepository", "Download to cache failed: $fileName", e)
            _downloadProgress.update { it - messageId }
            if (tmpFile.exists()) tmpFile.delete()
        }
        null
    }

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
            fileSize = getFileSizeFromUri(uri)
            if (type == com.cloudchat.model.MessageType.VIDEO) {
                videoDuration = getVideoDuration(uri)
            }
        }

        val encodedFileName = fileName?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val userDir = config.username
        val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
        val cloudPath = if (root.isEmpty()) userDir else "$root/$userDir"

        val remoteUrl = if (fileName != null) {
            provider.getFullUrl(fileName)
        } else null

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

        localUri?.let { uriStr ->
            transientLocalUris[newMessage.id] = uriStr
            // Copy to local cache for offline access and sharing
            if (fileName != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val uri = Uri.parse(uriStr)
                        val targetFile = getLocalFile(newMessage.id, fileName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatRepository", "Failed to copy to cache: $fileName", e)
                    }
                }
            }
        }
        _messages.update { it + newMessage }
        saveLocalHistory(config.id)

        if (inputStream != null) {
            _uploadProgress.update { it + (newMessage.id to 0) }
        }

        withContext(Dispatchers.IO) {
            try {
                if (inputStream != null && fileName != null) {
                    val contentType = when(type) {
                        com.cloudchat.model.MessageType.IMAGE -> "image/jpeg"
                        com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    
                    // Upload Thumbnail first if possible
                    val thumbFile = localUri?.let { generateThumbnail(Uri.parse(it), type) }
                    var currentThumbnailUrl: String? = null
                    if (thumbFile != null && thumbFile.exists()) {
                        val thumbName = "thumb_${fileName}"
                        try {
                            provider.uploadFile(thumbFile.inputStream(), thumbName, "image/jpeg", thumbFile.length()) { _ -> }
                            currentThumbnailUrl = provider.getFullUrl(thumbName)
                            _messages.update { list ->
                                list.map { if (it.id == newMessage.id) it.copy(thumbnailUrl = currentThumbnailUrl) else it }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatRepository", "Thumb upload failed", e)
                        }
                    }

                    // Upload main file
                    provider.uploadFile(inputStream, fileName, contentType, fileSize) { progress ->
                        _uploadProgress.update { it + (newMessage.id to progress) }
                    }
                    
                    // Verification
                    val remoteSize = provider.getFileSize(fileName)
                    if (remoteSize > 0) {
                        updateMessageStatus(newMessage.id, MessageStatus.SUCCESS)
                    } else {
                        updateMessageStatus(newMessage.id, MessageStatus.FAILED)
                    }
                    
                    _uploadProgress.update { it + (newMessage.id to -1) }
                } else {
                    provider.uploadText(content, "msg_${System.currentTimeMillis()}.txt")
                }
                provider.uploadText(gson.toJson(_messages.value), "chat_history.json")
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud sync failed", e)
                updateMessageStatus(newMessage.id, MessageStatus.FAILED)
                _uploadProgress.update { it - newMessage.id }
            }
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.update { list ->
            list.map { if (it.id == messageId) it.copy(status = status) else it }
        }
        syncHistory()
    }

    suspend fun deleteMessages(ids: List<String>) {
        _messages.update { list ->
            list.filterNot { it.id in ids }
        }
        syncHistory()
    }

    private fun generateThumbnail(uri: Uri, type: com.cloudchat.model.MessageType): File? {
        val thumbFile = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
        return try {
            val bitmap = if (type == com.cloudchat.model.MessageType.VIDEO) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            } else if (type == com.cloudchat.model.MessageType.IMAGE) {
                context.contentResolver.openInputStream(uri)?.use { 
                    BitmapFactory.decodeStream(it)
                }
            } else null

            bitmap?.let { 
                // Resize to max 400px width/height
                val scale = 400f / Math.max(it.width, it.height).coerceAtLeast(1)
                val resized = if (scale < 1f) {
                    val matrix = Matrix().apply { postScale(scale, scale) }
                    Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
                } else it
                
                thumbFile.outputStream().use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                thumbFile
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Thumb gen failed", e)
            null
        }
    }

    private fun syncHistory() {
        val config = currentConfig ?: return
        val currentList = _messages.value
        context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).edit().putString("history_${config.id}", gson.toJson(currentList)).apply()
        
        // Also save to the file we were using
        val file = getLocalHistoryFile(config.id)
        file.writeText(gson.toJson(currentList))
        
        // Sync to cloud in background
        GlobalScope.launch(Dispatchers.IO) {
            try {
                storageProvider?.uploadText(gson.toJson(currentList), "chat_history.json")
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud sync on delete failed", e)
            }
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

    private fun getFileSizeFromUri(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex)
                } else 0L
            } ?: 0L
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
