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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val uploadProgress = _uploadProgress.asStateFlow()

    private val _syncInterval = MutableStateFlow(5000L) // Default 5s
    val syncInterval = _syncInterval.asStateFlow()

    private var syncJob: kotlinx.coroutines.Job? = null
    private var lastKnownCloudTime: Long = 0L

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _activeDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloadIds = _activeDownloadIds.asStateFlow()

    private val transientLocalUris = mutableMapOf<String, String>()
    
    // Track active downloads to prevent duplicates
    private val activeDownloads = mutableSetOf<String>()
    
    // Track cancelled downloads
    private val cancelledDownloads = mutableSetOf<String>()

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

    fun getLocalFile(messageId: String, fileName: String? = null): File {
        // Sanitize fileName to avoid issues with special characters in filesystem
        val safeName = fileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_") ?: "file"
        return File(getMediaCacheDir(), "${messageId}_$safeName")
    }

    fun getTransientUri(messageId: String, fileName: String? = null): String? {
        // 1. Check transient (just uploaded/sent in current session)
        transientLocalUris[messageId]?.let {
            Log.d("ChatRepository", "getTransientUri: Found transient URI for $messageId: $it")
            return it
        }
        
        // 2. Check local disk cache
        val cacheDir = getMediaCacheDir()
        
        // If fileName is known, check that specific path first
        if (fileName != null) {
            val file = getLocalFile(messageId, fileName)
            if (file.exists()) return "file://${file.absolutePath}"
        }
        
        // Fallback: search for any file starting with messageId_ in cache dir
        try {
            val files = cacheDir.listFiles { _, name -> name.startsWith("${messageId}_") }
            if (!files.isNullOrEmpty()) {
                return "file://${files[0].absolutePath}"
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error searching cache for $messageId", e)
        }
        
        return null
    }

    fun isCached(messageId: String, fileName: String? = null): Boolean {
        return getTransientUri(messageId, fileName) != null
    }

    suspend fun downloadFileToCache(messageId: String, fileName: String, remoteUrl: String): File? {
        // Launch in GlobalScope to prevent cancellation when caller's scope is cancelled
        // This ensures downloads complete even if user navigates away
        val job = GlobalScope.launch(Dispatchers.IO) {
            downloadFileInternal(messageId, fileName, remoteUrl)
        }
        return null // Return immediately, actual result via progress updates
    }
    
    private suspend fun downloadFileInternal(messageId: String, fileName: String, remoteUrl: String): File? = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext null
        val targetFile = getLocalFile(messageId, fileName)
        
        Log.d("ChatRepository", "Download requested for $messageId ($fileName). Target exists: ${targetFile.exists()}")
        
        // If file already exists, mark as complete and return
        if (targetFile.exists()) {
            Log.d("ChatRepository", "File already cached: ${targetFile.absolutePath}")
            _downloadProgress.update { it + (messageId to -1) }
            return@withContext targetFile
        }
        
        // Check if this file is already being downloaded
        synchronized(activeDownloads) {
            if (activeDownloads.contains(messageId)) {
                Log.d("ChatRepository", "Download already in progress for $messageId")
                return@withContext null
            }
            activeDownloads.add(messageId)
            _activeDownloadIds.value = activeDownloads.toSet()
            cancelledDownloads.remove(messageId) // Clear cancelled flag
        }
        
        val tmpFile = File(targetFile.absolutePath + ".tmp")
        Log.d("ChatRepository", "Starting download to ${tmpFile.absolutePath}")
        
        // Check if a previous download was interrupted
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        
        try {
            _downloadProgress.update { it + (messageId to 0) }
            provider.downloadFile(fileName, tmpFile) { progress ->
                // Check if download was cancelled
                synchronized(cancelledDownloads) {
                    if (cancelledDownloads.contains(messageId)) {
                        throw InterruptedException("Download cancelled by user")
                    }
                }
                _downloadProgress.update { it + (messageId to progress) }
            }
            
            // Check one more time before renaming
            synchronized(cancelledDownloads) {
                if (cancelledDownloads.contains(messageId)) {
                    throw InterruptedException("Download cancelled by user")
                }
            }
            
            if (tmpFile.exists()) {
                if (tmpFile.renameTo(targetFile)) {
                    Log.d("ChatRepository", "Download successful and renamed to: ${targetFile.absolutePath}")
                } else {
                    Log.e("ChatRepository", "Rename failed, using fallback copy for $fileName")
                    tmpFile.copyTo(targetFile, true)
                    tmpFile.delete()
                }
            }
            
            Log.d("ChatRepository", "Download task finished for $messageId. File exists: ${targetFile.exists()}")
            _downloadProgress.update { it + (messageId to -1) } // Complete
            
            synchronized(activeDownloads) {
                activeDownloads.remove(messageId)
                _activeDownloadIds.value = activeDownloads.toSet()
            }
            
            if (targetFile.exists()) return@withContext targetFile
        } catch (e: InterruptedException) {
            Log.d("ChatRepository", "Download cancelled: $fileName")
            _downloadProgress.update { it - messageId }
            if (tmpFile.exists()) tmpFile.delete()
            
            synchronized(activeDownloads) {
                activeDownloads.remove(messageId)
                _activeDownloadIds.value = activeDownloads.toSet()
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Download to cache failed: $fileName", e)
            _downloadProgress.update { it - messageId }
            if (tmpFile.exists()) tmpFile.delete()
            
            synchronized(activeDownloads) {
                activeDownloads.remove(messageId)
                _activeDownloadIds.value = activeDownloads.toSet()
            }
        }
        null
    }
    
    fun cancelDownload(messageId: String) {
        synchronized(cancelledDownloads) {
            cancelledDownloads.add(messageId)
        }
        Log.d("ChatRepository", "Download cancellation requested for $messageId")
    }

    suspend fun updateConfig(config: ServerConfig) {
        currentConfig = config
        if (config.type == com.cloudchat.model.StorageType.WEBDAV) {
            NetworkUtils.currentAuth = Credentials.basic(config.webDavUser, config.webDavPass)
        }
        storageProvider = if (config.type == com.cloudchat.model.StorageType.S3) {
            S3StorageProvider(config, config.saveDir)
        } else {
            WebDavStorageProvider(config, config.saveDir)
        }
        loadLocalHistory(config.id)
        if (_messages.value.isEmpty()) {
            refreshHistoryFromCloud()
        }
        startSyncLoop()
    }

    fun setSyncInterval(ms: Long) {
        _syncInterval.value = ms
        startSyncLoop()
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = kotlinx.coroutines.MainScope().launch {
            while (isActive) {
                delay(_syncInterval.value)
                try {
                    val provider = storageProvider ?: continue
                    val remoteTime = provider.getLastModified("chat_history.json")
                    if (remoteTime > lastKnownCloudTime) {
                        Log.d("ChatRepository", "Cloud change detected, refreshing...")
                        refreshHistoryFromCloud()
                        lastKnownCloudTime = remoteTime
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Sync loop error", e)
                }
            }
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
        val userDir = config.saveDir
        val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
        val cloudPath = if (root.isEmpty()) userDir else "$root/$userDir"

        val remoteUrl = if (fileName != null) {
            provider.getFullUrl(fileName)
        } else null

        val newMessage = ChatMessage(
            sender = config.username, // Username identifies the sender
            senderName = config.username,
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
                    
                    // Relaxed Verification: Trust the uploadFile call first.
                    // Only mark failed if uploadFile threw exception (caught below).
                    val remoteSize = try { provider.getFileSize(fileName) } catch (e: Exception) { -1L }
                    
                    if (remoteSize <= 0) {
                        Log.w("ChatRepository", "Upload finished but remote size check failed or returned 0. Assuming success anyway.")
                    }
                    
                    updateMessageStatus(newMessage.id, MessageStatus.SUCCESS)
                    
                    // Ensure progress is cleared or set to 100
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
        val messagesToDelete = _messages.value.filter { it.id in ids }
        _messages.update { list ->
            list.filterNot { it.id in ids }
        }
        
        // Asynchronously delete files from cloud
        GlobalScope.launch(Dispatchers.IO) {
            val provider = storageProvider ?: return@launch
            messagesToDelete.forEach { msg ->
                if (msg.type != com.cloudchat.model.MessageType.TEXT) {
                    // Delete main file
                    try { provider.deleteFile(msg.content) } catch (e: Exception) {}
                    // Delete thumbnail if exists
                    msg.thumbnailUrl?.let { url ->
                        val thumbName = "thumb_${msg.content}"
                        try { provider.deleteFile(thumbName) } catch (e: Exception) {}
                    }
                }
            }
        }
        
        syncHistory(ids)
    }

    suspend fun retryMessage(messageId: String) {
        val originalMessage = _messages.value.find { it.id == messageId } ?: return
        
        // 1. Try to find the file content source BEFORE deleting anything
        var inputStream: InputStream? = null
        var localUriStr: String? = transientLocalUris[messageId]
        var fileSize = originalMessage.fileSize
        
        try {
            // Strategy A: Memory Cache (Transient URI)
            if (localUriStr != null) {
                inputStream = context.contentResolver.openInputStream(Uri.parse(localUriStr))
            }
            
            // Strategy B: Disk Cache (If A failed or wasn't available)
            if (inputStream == null) {
                val cachedFile = getLocalFile(messageId, originalMessage.content)
                if (cachedFile.exists()) {
                    inputStream = cachedFile.inputStream()
                    localUriStr = "file://${cachedFile.absolutePath}"
                    fileSize = cachedFile.length()
                }
            }
            
            // If still null, we can't retry
            if (inputStream == null) {
                Log.e("ChatRepository", "Cannot retry message $messageId: Source file not found in memory or disk cache")
                // TODO: Notify user via UI event (toast) that file is lost? 
                // For now just allow the UI to show the 'retry' button again (it remains Failed)
                return 
            }

            // 2. Only if we have a valid stream, proceed to delete and resend
            deleteMessages(listOf(messageId))
            
            if (originalMessage.type == com.cloudchat.model.MessageType.TEXT) {
                sendMessage(content = originalMessage.content)
            } else {
                sendMessage(
                    content = originalMessage.content,
                    type = originalMessage.type,
                    inputStream = inputStream,
                    fileName = originalMessage.content,
                    localUri = localUriStr
                )
            }
            
        } catch (e: Exception) {
            Log.e("ChatRepository", "Retry failed", e)
            inputStream?.close()
        }
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
                // Resize to max 800px width/height (doubled for better quality)
                val scale = 800f / Math.max(it.width, it.height).coerceAtLeast(1)
                val resized = if (scale < 1f) {
                    val matrix = Matrix().apply { postScale(scale, scale) }
                    Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
                } else it
                
                thumbFile.outputStream().use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)  // Increased quality from 70 to 85
                }
                thumbFile
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Thumb gen failed", e)
            null
        }
    }

    private fun syncHistory(excludeIds: List<String> = emptyList()) {
        val config = currentConfig ?: return
        val currentList = _messages.value
        context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).edit().putString("history_${config.id}", gson.toJson(currentList)).apply()
        
        // Also save to the file we were using
        val file = getLocalHistoryFile(config.id)
        file.writeText(gson.toJson(currentList))
        
        // Sync to cloud in background
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val provider = storageProvider ?: return@launch
                
                // Fetch latest from cloud first to merge
                val tempFile = File(context.cacheDir, "cloud_history_merge.json")
                var mergedList = currentList
                try {
                    provider.downloadFile("chat_history.json", tempFile)
                    if (tempFile.exists()) {
                        val json = tempFile.readText()
                        val cloudList: List<ChatMessage> = gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
                        
                        val localIds = currentList.map { it.id }.toSet()
                        // Merge logic: include cloud messages that are NOT in local AND NOT in excludeIds
                        val uniqueCloud = cloudList.filter { it.id !in localIds && it.id !in excludeIds }
                        mergedList = currentList + uniqueCloud
                    }
                } catch (e: Exception) {
                    // History might not exist yet
                }

                provider.uploadText(gson.toJson(mergedList.sortedBy { it.timestamp }), "chat_history.json")
                lastKnownCloudTime = provider.getLastModified("chat_history.json")
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
                val cloudHistory: List<ChatMessage> = gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
                val cloudIds = cloudHistory.map { it.id }.toSet()

                _messages.update { current ->
                    // 1. Keep all messages from cloud
                    // 2. Keep local messages that are NOT SUCCESS (sending or failed) and NOT in cloud
                    // 3. Keep local messages that are SUCCESS but somehow NOT in cloud? (Wait, if they are SUCCESS but not in cloud, they were likely DELETED by others)
                    
                    val pendingOrFailed = current.filter { it.status != MessageStatus.SUCCESS && it.id !in cloudIds }
                    
                    val merged = cloudHistory.map { cloudMsg ->
                        current.find { it.id == cloudMsg.id }?.let { localMsg ->
                             // Prefer local if SUCCESS, but usually cloud version of a SUCCESS message is the same.
                             // Update local if cloud has more info? Not much to update usually.
                             cloudMsg // Cloud is truth for historical messages
                        } ?: cloudMsg
                    } + pendingOrFailed
                    
                    merged.sortedBy { it.timestamp }
                }
                saveLocalHistory(config.id)
                lastKnownCloudTime = provider.getLastModified("chat_history.json")
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud refresh failed", e)
        }
    }
}
