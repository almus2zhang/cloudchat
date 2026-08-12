package com.cloudchat.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.cloudchat.model.ChatMessage
import com.cloudchat.model.MessageStatus
import com.cloudchat.model.ServerConfig
import java.util.UUID
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

class ChatRepository(private val context: Context) {
    companion object {
        const val TOTP_SECRET = "CLOUDSYNC2FA2222"
        const val TOTP_STEP = 30000L // 30 seconds

        fun getSafeAvatarFileName(username: String): String {
            val clean = username.trim().ifEmpty { "user" }
            val isPureAscii = clean.all { it.code in 32..126 }
            if (isPureAscii) {
                val key = clean.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                return "avatar_${key}.jpg"
            }
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
            val hex = bytes.joinToString("") { "%02x".format(it) }.take(12)
            return "avatar_u_${hex}.jpg"
        }
    }
    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(com.cloudchat.model.MessageType::class.java, com.google.gson.JsonDeserializer { jsonElement, _, _ ->
            try {
                if (jsonElement == null || jsonElement.isJsonNull) com.cloudchat.model.MessageType.TEXT
                else com.cloudchat.model.MessageType.valueOf(jsonElement.asString.uppercase())
            } catch (e: Exception) {
                com.cloudchat.model.MessageType.TEXT
            }
        })
        .create()
    private var storageProvider: StorageProvider? = null
    private var authStorageProvider: StorageProvider? = null
    private var currentConfig: ServerConfig? = null
    private val scope = kotlinx.coroutines.MainScope()

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

    private val _isSecurityAuthenticated = MutableStateFlow(false)
    val isSecurityAuthenticated = _isSecurityAuthenticated.asStateFlow()

    private val _securityError = MutableStateFlow<String?>(null)
    val securityError = _securityError.asStateFlow()

    private val _isServerConnected = MutableStateFlow(true)

    // Sync state observables for UI animated indicators
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()
    private val _mediaSyncProgress = MutableStateFlow(0f) // 0.0 ~ 1.0
    val mediaSyncProgress = _mediaSyncProgress.asStateFlow()

    /** 文件已成功上传后，待删除的源文件 URI（图库需经用户授权确认才可删）。 */
    val sourceReadyToDelete = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val isServerConnected = _isServerConnected.asStateFlow()
    
    // 服务器 history 异常事件：需要用户选择操作
    data class HistoryConflictEvent(
        val reason: String,  // "empty" | "not_found" | "corrupted"
        val message: String   // 用户可见的提示信息
    )
    val historyConflict = MutableSharedFlow<HistoryConflictEvent>(extraBufferCapacity = 4)
    
    // 本地覆盖上传的进度：-1=未开始, 0=完成, 1-100=百分比
    private val _uploadProgressPercent = MutableStateFlow(-1)
    val uploadProgressPercent: StateFlow<Int> = _uploadProgressPercent.asStateFlow()
    private val _uploadProgressText = MutableStateFlow("")
    val uploadProgressText: StateFlow<String> = _uploadProgressText.asStateFlow()

    private val deviceId: String by lazy {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    val authId: String by lazy {
        if (deviceId.length >= 6) deviceId.takeLast(6) else deviceId
    }

    private val transientLocalUris = mutableMapOf<String, String>()
    
    // Track active downloads to prevent duplicates
    private val activeDownloads = mutableSetOf<String>()
    
    // Track cancelled downloads
    private val cancelledDownloads = mutableSetOf<String>()

    // Track active upload jobs to support cancellation
    private val activeUploadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val uploadMutex = kotlinx.coroutines.sync.Mutex()

    fun cancelUpload(messageId: String) {
        activeUploadJobs[messageId]?.cancel()
        activeUploadJobs.remove(messageId)
        _uploadProgress.update { it - messageId }
        scope.launch {
            updateMessageStatus(messageId, com.cloudchat.model.MessageStatus.FAILED)
        }
    }

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

    suspend fun resolveAvatarPath(avatarName: String?): String? = withContext(Dispatchers.IO) {
        if (avatarName.isNullOrEmpty()) return@withContext null
        if (avatarName.startsWith("http://") || avatarName.startsWith("https://") || avatarName.startsWith("content://") || avatarName.startsWith("file://") || avatarName.startsWith("data:")) {
            return@withContext avatarName
        }

        val safeName = avatarName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val avatarDir = File(context.cacheDir, "avatars")
        if (!avatarDir.exists()) avatarDir.mkdirs()

        val localFile = File(avatarDir, safeName)
        if (localFile.exists() && localFile.length() > 0) {
            return@withContext "file://${localFile.absolutePath}"
        }

        try {
            val provider = storageProvider
            if (provider != null) {
                val tmpFile = File(avatarDir, "${safeName}.tmp")
                if (tmpFile.exists()) tmpFile.delete()
                provider.downloadFile(avatarName, tmpFile, null)
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    tmpFile.renameTo(localFile)
                    return@withContext "file://${localFile.absolutePath}"
                }
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to download avatar $avatarName", e)
        }
        null
    }

    suspend fun uploadCustomAvatar(config: ServerConfig, imageUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val avatarFileName = "avatar____${System.currentTimeMillis()}.jpg"

            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) return@withContext null

            val maxSide = 128
            val side = Math.min(originalBitmap.width, originalBitmap.height)
            val sx = (originalBitmap.width - side) / 2
            val sy = (originalBitmap.height - side) / 2
            val cropped = Bitmap.createBitmap(originalBitmap, sx, sy, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, maxSide, maxSide, true)

            val avatarDir = File(context.cacheDir, "avatars")
            if (!avatarDir.exists()) avatarDir.mkdirs()
            val localFile = File(avatarDir, avatarFileName)

            java.io.FileOutputStream(localFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val provider = if (config.type == com.cloudchat.model.StorageType.S3) {
                S3StorageProvider(config, config.saveDir)
            } else {
                WebDavStorageProvider(config, config.saveDir, false)
            }

            localFile.inputStream().use { input ->
                provider.uploadFile(input, avatarFileName, "image/jpeg", localFile.length(), null)
            }

            return@withContext avatarFileName
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to upload avatar", e)
            null
        }
    }

    suspend fun uploadAvatarFromBitmap(config: ServerConfig, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val avatarFileName = "avatar____${System.currentTimeMillis()}.jpg"
            val maxSide = 128
            val side = Math.min(bitmap.width, bitmap.height)
            val sx = (bitmap.width - side) / 2
            val sy = (bitmap.height - side) / 2
            val cropped = Bitmap.createBitmap(bitmap, sx, sy, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, maxSide, maxSide, true)

            val avatarDir = File(context.cacheDir, "avatars")
            if (!avatarDir.exists()) avatarDir.mkdirs()
            val localFile = File(avatarDir, avatarFileName)

            java.io.FileOutputStream(localFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val provider = if (config.type == com.cloudchat.model.StorageType.S3) {
                S3StorageProvider(config, config.saveDir)
            } else {
                WebDavStorageProvider(config, config.saveDir, false)
            }

            localFile.inputStream().use { input ->
                provider.uploadFile(input, avatarFileName, "image/jpeg", localFile.length(), null)
            }

            return@withContext avatarFileName
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to upload avatar from bitmap", e)
            null
        }
    }

    suspend fun updateProfileAvatar(newAvatarUrl: String) = withContext(Dispatchers.IO) {
        // Profile avatar changes only apply to new messages created going forward. Historical messages retain their original avatar.
    }

    suspend fun downloadFileToCache(messageId: String, fileName: String, remoteUrl: String): File? {
        val fullUrl = resolveUrl(remoteUrl) ?: remoteUrl
        return downloadFileInternal(messageId, fileName, fullUrl)
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

            val message = _messages.value.find { it.id == messageId }
            val isChunked = message?.isChunked ?: false
            val totalChunks = message?.totalChunks ?: 0

            if (isChunked && totalChunks > 0) {
                // Download chunks and merge
                tmpFile.outputStream().use { mergedOut ->
                    var partIdx = 0
                    while (partIdx < totalChunks) {
                        val partName = "${fileName}.part${partIdx}"
                        val partTmpFile = File(tmpFile.absolutePath + ".part${partIdx}")
                        if (partTmpFile.exists()) partTmpFile.delete()
                        
                        val currentPartIdx = partIdx
                        provider.downloadFile(partName, partTmpFile) { progress ->
                            synchronized(cancelledDownloads) {
                                if (cancelledDownloads.contains(messageId)) {
                                    throw InterruptedException("Download cancelled by user")
                                }
                            }
                            val overallProgress = ((currentPartIdx * 100) / totalChunks) + (progress / totalChunks)
                            _downloadProgress.update { it + (messageId to overallProgress) }
                        }
                        
                        // Append to merged file
                        partTmpFile.inputStream().use { partIn ->
                            partIn.copyTo(mergedOut)
                        }
                        partTmpFile.delete()
                        partIdx++
                    }
                }
            } else {
                provider.downloadFile(fileName, tmpFile) { progress ->
                    // Check if download was cancelled
                    synchronized(cancelledDownloads) {
                        if (cancelledDownloads.contains(messageId)) {
                            throw InterruptedException("Download cancelled by user")
                        }
                    }
                    _downloadProgress.update { it + (messageId to progress) }
                }
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
            for (p in 0 until 200) {
                val partTmpFile = File(tmpFile.absolutePath + ".part${p}")
                if (partTmpFile.exists()) partTmpFile.delete()
            }
            
            synchronized(activeDownloads) {
                activeDownloads.remove(messageId)
                _activeDownloadIds.value = activeDownloads.toSet()
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Download to cache failed: $fileName", e)
            _downloadProgress.update { it - messageId }
            if (tmpFile.exists()) tmpFile.delete()
            for (p in 0 until 200) {
                val partTmpFile = File(tmpFile.absolutePath + ".part${p}")
                if (partTmpFile.exists()) partTmpFile.delete()
            }
            
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

    suspend fun updateConfig(config: ServerConfig, appMode: com.cloudchat.model.AppMode) {
        val oldConfig = currentConfig
        currentConfig = config
        if (config.type == com.cloudchat.model.StorageType.WEBDAV) {
            val user = config.webDavUser ?: ""
            val pass = config.webDavPass ?: ""
            NetworkUtils.currentAuth = try { Credentials.basic(user, pass) } catch (e: Exception) { null }
        } else {
            NetworkUtils.currentAuth = null
        }
        
        val useSafeClient = appMode == com.cloudchat.model.AppMode.FULL

        Log.d("ChatRepository", "Initializing providers. Safe Mode: $useSafeClient (AppMode: $appMode)")
        authStorageProvider = if (config.type == com.cloudchat.model.StorageType.S3) {
            S3StorageProvider(config, config.saveDir)
        } else {
            WebDavStorageProvider(config, config.saveDir, useSafeClient)
        }

        // Data provider points to a subdirectory in FULL mode for user isolation
        val dataDir = if (appMode == com.cloudchat.model.AppMode.FULL) {
            val subPath = if (!config.fullModePath.isNullOrBlank()) config.fullModePath else authId
            "${config.saveDir.trimEnd('/')}/$subPath"
        } else {
            config.saveDir
        }
        
        Log.d("ChatRepository", "Data directory: $dataDir (Mode: $appMode)")

        storageProvider = if (config.type == com.cloudchat.model.StorageType.S3) {
            S3StorageProvider(config, dataDir)
        } else {
            WebDavStorageProvider(config, dataDir, useSafeClient)
        }

        // Ensure the data directory exists (creates subfolder on WebDAV if missing)
        scope.launch {
            storageProvider?.testConnection()
        }

        // 判断存储位置/账号是否发生变化。若变化（例如仅调整了 serverPath/saveDir，
        // 但 config.id 不变），旧的本地缓存历史已失效，必须按云端（新位置）重新加载，
        // 否则会错误地显示上一个位置的消息。
        val locationChanged = oldConfig == null || oldConfig.id != config.id ||
            oldConfig.type != config.type ||
            oldConfig.webDavUrl != config.webDavUrl ||
            oldConfig.serverPath != config.serverPath ||
            oldConfig.saveDir != config.saveDir ||
            oldConfig.username != config.username ||
            oldConfig.bucket != config.bucket ||
            oldConfig.fullModePath != config.fullModePath

        if (locationChanged) {
            // 切换配置：清空当前消息，从新配置的本地缓存加载，然后异步从云端刷新
            _messages.value = emptyList()
            loadLocalHistory(config.id)
            scope.launch { refreshHistoryFromCloud() }
        } else {
            // 位置未变（仅修改证书/分块等设置）：保留本地缓存，离线也能看
            loadLocalHistory(config.id)
            if (_messages.value.isEmpty()) {
                scope.launch { refreshHistoryFromCloud() }
            }
        }
        scope.launch { uploadLoginLog(config) }
        startSyncLoop()
    }

    private suspend fun uploadLoginLog(config: ServerConfig) = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext
        val logFileName = "login_logs.txt"
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$timestamp] User: ${config.username}, DeviceID: $deviceId\n"
        
        try {
            // Try to download existing log, append, and re-upload
            val tempFile = File(context.cacheDir, "login_logs_temp.txt")
            var currentLogs = ""
            try {
                provider.downloadFile(logFileName, tempFile)
                if (tempFile.exists()) {
                    currentLogs = tempFile.readText()
                }
            } catch (e: Exception) {
                // Log doesn't exist yet
            }
            provider.uploadText(currentLogs + logEntry, logFileName)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to upload login log", e)
        }
    }

    fun setSyncInterval(ms: Long) {
        _syncInterval.value = ms
        startSyncLoop()
    }

    private val isRefreshingFromCloud = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = kotlinx.coroutines.MainScope().launch {
            while (isActive) {
                delay(_syncInterval.value)
                try {
                    // SENDING 超时检测：超过 30 秒自动标记 FAILED
                    val now = System.currentTimeMillis()
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.status == MessageStatus.SENDING && (now - msg.timestamp) > 30_000) {
                                msg.copy(status = MessageStatus.FAILED)
                            } else msg
                        }
                    }
                    if (storageProvider != null && currentConfig != null) {
                        refreshHistoryFromCloud()
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Sync loop error", e)
                }
            }
        }
    }

    private suspend fun loadLocalHistory(accountId: String) = withContext(Dispatchers.IO) {
        val file = getLocalHistoryFile(accountId)
        var history: List<ChatMessage> = emptyList()
        if (file.exists()) {
            try {
                val json = file.readText()
                val rawHistory: List<ChatMessage>? = try { gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) } catch (e: Exception) { null }
                history = rawHistory?.mapNotNull { sanitizeMessage(it) } ?: emptyList()
            } catch (e: Exception) {
                Log.e("ChatRepository", "Load failed", e)
            }
        }
        // 文件为空时尝试从 SharedPrefs 恢复
        if (history.isEmpty()) {
            val prefsJson = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
                .getString("history_$accountId", null)
            if (!prefsJson.isNullOrBlank()) {
                try {
                    val raw: List<ChatMessage>? = gson.fromJson(prefsJson, object : TypeToken<List<ChatMessage>>() {}.type)
                    history = raw?.mapNotNull { sanitizeMessage(it) } ?: emptyList()
                } catch (e: Exception) {
                    Log.e("ChatRepository", "SharedPrefs load failed", e)
                }
            }
        }
        // 合并：文件中的历史 + 当前内存中不在文件里的 FAILED/SENDING 消息
        // 如果文件/SharedPrefs都为空，保留当前内存中的全部消息（不覆盖）
        val fileIds = history.map { it.id }.toSet()
        val pendingInMemory = _messages.value.filter { it.id !in fileIds && it.status != MessageStatus.SUCCESS }
        if (history.isNotEmpty()) {
            _messages.value = history + pendingInMemory
        } else {
            // 没有任何本地缓存数据：保留当前内存中的消息，不覆盖
            Log.w("ChatRepository", "loadLocalHistory: no cached data found, keeping current _messages")
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
        localUri: String? = null,
        locationAddress: String? = null,
        categories: List<String>? = null,
        groupId: String? = null,
        folderId: String? = null,
        deleteSourceFile: Boolean = false
    ) {
        val provider = storageProvider ?: return
        val config = currentConfig ?: return
        
        var fileSize = 0L
        var videoDuration = 0L

        if (localUri != null) {
            val uri = Uri.parse(localUri)
            fileSize = getFileSizeFromUri(uri)
            if (type == com.cloudchat.model.MessageType.VIDEO || type == com.cloudchat.model.MessageType.AUDIO) {
                videoDuration = getVideoDuration(uri)
            }
        }

        val encodedFileName = fileName?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val userDir = config.saveDir
        val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
        val cloudPath = if (root.isEmpty()) userDir else "$root/$userDir"

        val remoteUrl = fileName

        val useChunking = (inputStream != null && fileName != null && config.type == com.cloudchat.model.StorageType.WEBDAV && config.webDavChunkSize > 0L && fileSize > config.webDavChunkSize)
        val chunkSize = if (useChunking) config.webDavChunkSize else 0L
        val totalChunks = if (useChunking) ((fileSize + chunkSize - 1) / chunkSize).toInt() else 0

        val newMessage = ChatMessage(
            sender = config.username, // Username identifies the sender
            senderName = config.username,
            senderAvatar = config.avatarUrl,
            content = fileName ?: content,
            type = type,
            isOutgoing = true,
            remoteUrl = remoteUrl,
            fileSize = fileSize,
            videoDuration = videoDuration,
            status = MessageStatus.SENDING,
            locationAddress = locationAddress,
            isChunked = useChunking,
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            categories = categories,
            groupId = groupId,
            folderId = folderId
        )

        localUri?.let { uriStr ->
            transientLocalUris[newMessage.id] = uriStr
        }
        _messages.update { it + newMessage }
        // 立即同步写本地缓存，防止 scope 取消后消息丢失
        try {
            val json = gson.toJson(_messages.value)
            context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).edit()
                .putString("history_${config.id}", json).apply()
            getLocalHistoryFile(config.id).writeText(json)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to save local history on send", e)
        }

        if (inputStream != null) {
            _uploadProgress.update { it + (newMessage.id to 0) }
        }

        withContext(Dispatchers.IO) {
            uploadMutex.withLock {
                val currentJob = coroutineContext[kotlinx.coroutines.Job]
                if (currentJob != null) {
                    activeUploadJobs[newMessage.id] = currentJob
                }
            try {
                if (localUri != null && fileName != null) {
                    try {
                        val uri = Uri.parse(localUri)
                        val targetFile = getLocalFile(newMessage.id, fileName)
                        if (!targetFile.exists() || targetFile.length() == 0L) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatRepository", "Failed to copy file to local cache: $fileName", e)
                    }
                }

                if (fileName != null) {
                    val contentType = when(type) {
                        com.cloudchat.model.MessageType.IMAGE -> "image/jpeg"
                        com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                        else -> "application/octet-stream"
                    }

                    val targetFile = getLocalFile(newMessage.id, fileName)
                    val uploadStream = if (targetFile.exists() && targetFile.length() > 0L) {
                        targetFile.inputStream()
                    } else if (localUri != null) {
                        context.contentResolver.openInputStream(Uri.parse(localUri))
                    } else {
                        inputStream
                    }

                    if (uploadStream == null) {
                        throw IllegalStateException("No valid input stream available for file upload")
                    }

                    uploadStream.use { streamToUpload ->
                        // Upload Thumbnail first if possible
                        val thumbFile = localUri?.let { generateThumbnail(Uri.parse(it), type) }
                        if (thumbFile != null && thumbFile.exists()) {
                            val thumbName = "thumb_${fileName}"
                            try {
                                provider.uploadFile(thumbFile.inputStream(), thumbName, "image/jpeg", thumbFile.length()) { _ -> }
                                _messages.update { list ->
                                    list.map { if (it.id == newMessage.id) it.copy(thumbnailUrl = thumbName) else it }
                                }
                            } catch (e: Exception) {
                                Log.e("ChatRepository", "Thumb upload failed", e)
                            }
                        }

                        // Upload main file
                        var serverSupportsRangePut = false
                        if (useChunking) {
                            try {
                                val testFileName = "range_test_${System.currentTimeMillis()}.tmp"
                                val byte1 = byteArrayOf(65)
                                provider.uploadFileRange(
                                    byte1.inputStream(),
                                    testFileName,
                                    "application/octet-stream",
                                    0L,
                                    0L,
                                    2L
                                )
                                val byte2 = byteArrayOf(66)
                                provider.uploadFileRange(
                                    byte2.inputStream(),
                                    testFileName,
                                    "application/octet-stream",
                                    1L,
                                    1L,
                                    2L
                                )
                                val size = provider.getFileSize(testFileName)
                                if (size == 2L) {
                                    serverSupportsRangePut = true
                                }
                                provider.deleteFile(testFileName)
                            } catch (e: Exception) {
                                Log.w("ChatRepository", "Server does not support Range PUT: ${e.message}")
                            }
                        }

                        if (useChunking && serverSupportsRangePut) {
                            // Server supports Range PUT! Upload directly to the final file name using Content-Range.
                            _messages.update { list ->
                                list.map { if (it.id == newMessage.id) it.copy(isChunked = false, chunkSize = 0L, totalChunks = 0) else it }
                            }

                            val buffer = ByteArray(1024 * 64)
                            var partIdx = 0

                            while (partIdx < totalChunks) {
                                val chunkFile = File(context.cacheDir, "${newMessage.id}.part${partIdx}")
                                chunkFile.outputStream().use { out ->
                                    var bytesWritten = 0L
                                    while (bytesWritten < chunkSize) {
                                        val toRead = Math.min(buffer.size.toLong(), chunkSize - bytesWritten).toInt()
                                        val read = streamToUpload.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        out.write(buffer, 0, read)
                                        bytesWritten += read
                                    }
                                }

                                val partLength = chunkFile.length()
                                val startByte = partIdx * chunkSize
                                val endByte = startByte + partLength - 1
                                val currentPartIdx = partIdx
                                chunkFile.inputStream().use { partIn ->
                                    provider.uploadFileRange(
                                        partIn,
                                        fileName,
                                        contentType,
                                        startByte,
                                        endByte,
                                        fileSize,
                                        onProgress = { progress ->
                                            val overallProgress = ((currentPartIdx * 100) / totalChunks) + (progress / totalChunks)
                                            _uploadProgress.update { it + (newMessage.id to overallProgress) }
                                        }
                                    )
                                }
                                chunkFile.delete()
                                partIdx++
                            }
                        } else if (useChunking) {
                            // Fallback to client-side chunking
                            val buffer = ByteArray(1024 * 64)
                            var partIdx = 0

                            while (partIdx < totalChunks) {
                                val chunkFile = File(context.cacheDir, "${newMessage.id}.part${partIdx}")
                                chunkFile.outputStream().use { out ->
                                    var bytesWritten = 0L
                                    while (bytesWritten < chunkSize) {
                                        val toRead = Math.min(buffer.size.toLong(), chunkSize - bytesWritten).toInt()
                                        val read = streamToUpload.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        out.write(buffer, 0, read)
                                        bytesWritten += read
                                    }
                                }

                                val partName = "${fileName}.part${partIdx}"
                                val partLength = chunkFile.length()
                                val currentPartIdx = partIdx
                                chunkFile.inputStream().use { partIn ->
                                    provider.uploadFile(partIn, partName, contentType, partLength) { progress ->
                                        val overallProgress = ((currentPartIdx * 100) / totalChunks) + (progress / totalChunks)
                                        _uploadProgress.update { it + (newMessage.id to overallProgress) }
                                    }
                                }
                                chunkFile.delete()
                                partIdx++
                            }
                        } else {
                            val actualLength = if (targetFile.exists()) targetFile.length() else fileSize
                            provider.uploadFile(streamToUpload, fileName, contentType, actualLength) { progress ->
                                _uploadProgress.update { it + (newMessage.id to progress) }
                            }
                        }
                    }

                    updateMessageStatus(newMessage.id, MessageStatus.SUCCESS)
                    _uploadProgress.update { it - newMessage.id }

                    // 移动模式：上传已成功，先把应用内显示指向本地缓存副本，
                    // 再通知 UI 删除原始源文件（图库需用户授权，避免误删）
                    if (deleteSourceFile && localUri != null) {
                        val cache = getLocalFile(newMessage.id, newMessage.content)
                        if (cache.exists()) transientLocalUris[newMessage.id] = "file://${cache.absolutePath}"
                        else transientLocalUris.remove(newMessage.id)
                        sourceReadyToDelete.tryEmit(localUri)
                    }
                } else {
                    // 纯文本消息：上传到云端，成功才标记 SUCCESS
                    provider.uploadText(content, "msg_${System.currentTimeMillis()}.txt")
                    updateMessageStatus(newMessage.id, MessageStatus.SUCCESS)
                }
                syncHistory()
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud upload failed for message ${newMessage.id}", e)
                _uploadProgress.update { it - newMessage.id }
                updateMessageStatus(newMessage.id, MessageStatus.FAILED)
                config.id.let { saveLocalHistory(it) }
            } finally {
                activeUploadJobs.remove(newMessage.id)
            }
          }
        }
    }

    suspend fun sendCombinedMessage(
        content: String,
        firstMsg: ChatMessage,
        folderId: String? = null
    ) {
        val config = currentConfig ?: return
        val newMessage = ChatMessage(
            sender = firstMsg.sender,
            senderName = firstMsg.senderName ?: firstMsg.sender,
            senderAvatar = firstMsg.senderAvatar,
            content = content,
            type = com.cloudchat.model.MessageType.TEXT,
            isOutgoing = firstMsg.isOutgoing,
            status = MessageStatus.SUCCESS,
            folderId = folderId,
            lastModified = System.currentTimeMillis()
        )
        _messages.update { it + newMessage }
        saveLocalHistory(config.id)
        syncHistory()
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map { if (it.id == messageId) it.copy(status = status, lastModified = now) else it }
        }
        currentConfig?.id?.let { id ->
            scope.launch {
                saveLocalHistory(id)
            }
        }
        syncHistory()
    }

    suspend fun resendMessage(messageId: String) {
        val msg = _messages.value.find { it.id == messageId } ?: return
        val provider = storageProvider ?: return
        
        updateMessageStatus(messageId, MessageStatus.SENDING)

        withContext(Dispatchers.IO) {
            uploadMutex.withLock {
                val currentJob = coroutineContext[kotlinx.coroutines.Job]
                if (currentJob != null) {
                    activeUploadJobs[messageId] = currentJob
                }
                try {
                    val fileName = msg.content
                    if (msg.type == com.cloudchat.model.MessageType.TEXT) {
                        provider.uploadText(msg.content, "msg_${msg.id}.txt")
                        updateMessageStatus(messageId, MessageStatus.SUCCESS)
                    } else {
                        val targetFile = getLocalFile(msg.id, fileName)
                        val expectedSize = if (targetFile.exists() && targetFile.length() > 0L) targetFile.length() else msg.fileSize
                        val remoteSize = try { provider.getFileSize(fileName) } catch (e: Exception) { -1L }

                        if (expectedSize > 0L && remoteSize == expectedSize) {
                            Log.d("ChatRepository", "Resend: File $fileName already exists on server ($remoteSize bytes), marking SUCCESS")
                            updateMessageStatus(messageId, MessageStatus.SUCCESS)
                            _uploadProgress.update { it - messageId }
                        } else {
                            val uploadStream = if (targetFile.exists() && targetFile.length() > 0L) {
                                targetFile.inputStream()
                            } else {
                                val uriStr = transientLocalUris[messageId]
                                if (uriStr != null) context.contentResolver.openInputStream(Uri.parse(uriStr)) else null
                            }

                            if (uploadStream == null) {
                                throw IllegalStateException("No valid input stream available for resend")
                            }

                            uploadStream.use { stream ->
                                val contentType = when (msg.type) {
                                    com.cloudchat.model.MessageType.IMAGE -> "image/jpeg"
                                    com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                                    else -> "application/octet-stream"
                                }
                                val length = if (targetFile.exists()) targetFile.length() else msg.fileSize
                                provider.uploadFile(stream, fileName, contentType, length) { progress ->
                                    _uploadProgress.update { it + (messageId to progress) }
                                }
                            }
                            updateMessageStatus(messageId, MessageStatus.SUCCESS)
                            _uploadProgress.update { it - messageId }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Resend message failed for $messageId", e)
                    _uploadProgress.update { it - messageId }
                    updateMessageStatus(messageId, MessageStatus.FAILED)
                    currentConfig?.id?.let { saveLocalHistory(it) }
                } finally {
                    activeUploadJobs.remove(messageId)
                }
            }
        }
    }

    private fun sanitizeMessage(msg: ChatMessage?): ChatMessage? {
        if (msg == null) return null
        if (msg.id.isBlank() && msg.content.isBlank() && msg.remoteUrl.isNullOrBlank()) return null

        val safeId = if (msg.id.isBlank()) UUID.randomUUID().toString() else msg.id
        val safeContent = msg.content ?: ""
        val safeType = msg.type ?: com.cloudchat.model.MessageType.TEXT
        val safeTimestamp = if (msg.timestamp <= 0L) System.currentTimeMillis() else msg.timestamp
        val safeSender = msg.sender ?: "Unknown"
        val currentUsername = currentConfig?.username ?: ""
        val safeIsOutgoing = if (currentUsername.isNotBlank()) (safeSender == currentUsername) else msg.isOutgoing

        return msg.copy(
            id = safeId,
            content = safeContent,
            type = safeType,
            timestamp = safeTimestamp,
            sender = safeSender,
            senderName = msg.senderName ?: safeSender,
            isOutgoing = safeIsOutgoing,
            categories = msg.categories ?: emptyList()
        )
    }

    suspend fun deleteMessages(ids: List<String>) {
        val selectedMsgs = _messages.value.filter { it.id in ids }
        val targetContents = selectedMsgs.map { it.content }.filter { it.isNotBlank() }.toSet()
        val targetTimestamps = selectedMsgs.map { it.timestamp }.toSet()

        val messagesToDelete = _messages.value.filter { 
            it.id in ids || (targetContents.contains(it.content) && targetTimestamps.contains(it.timestamp))
        }
        
        _messages.update { list ->
            list.map { msg ->
                if (msg.id in ids || (targetContents.contains(msg.content) && targetTimestamps.contains(msg.timestamp))) {
                    msg.copy(isDeleted = true, lastModified = System.currentTimeMillis())
                } else {
                    msg
                }
            }
        }
        
        // Asynchronously recycle files from cloud
        GlobalScope.launch(Dispatchers.IO) {
            val provider = storageProvider ?: return@launch
            messagesToDelete.forEach { msg ->
                if (msg.type != com.cloudchat.model.MessageType.TEXT && msg.content.isNotBlank()) {
                    // Recycle main file
                    try { provider.recycleFile(msg.content) } catch (e: Exception) {}
                    // Recycle thumbnail if exists
                    msg.thumbnailUrl?.let { url ->
                        val thumbName = "thumb_${msg.content}"
                        try { provider.recycleFile(thumbName) } catch (e: Exception) {}
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

    /** 压缩图片为 WebP（最长边 1280px，质量 80），用于日记静态页面减小体积 */
    private fun compressImageToWebp(srcFile: File): File? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, opts)
            val origW = opts.outWidth
            val origH = opts.outHeight
            if (origW <= 0 || origH <= 0) return null

            val maxDim = 1280
            var sample = 1
            while (origW / sample > maxDim * 2 || origH / sample > maxDim * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(srcFile.absolutePath, decodeOpts) ?: return null

            // 若仍超过 1280，再等比缩放
            var result = bitmap
            val scale = maxDim.toFloat() / Math.max(result.width, result.height).coerceAtLeast(1)
            if (scale < 1f) {
                val matrix = Matrix().apply { postScale(scale, scale) }
                result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
                if (result != bitmap) bitmap.recycle()
            }

            val outFile = File(context.cacheDir, "diary_img_${System.currentTimeMillis()}.webp")
            outFile.outputStream().use { out ->
                result.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
            if (result != bitmap) result.recycle()
            outFile
        } catch (e: Exception) {
            Log.e("ChatRepository", "compressImageToWebp failed", e)
            null
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

    private fun getMonthShardName(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy_MM", java.util.Locale.getDefault())
        return "chat_history_${sdf.format(java.util.Date(timestamp))}.json"
    }

    private fun syncHistory(excludeIds: List<String> = emptyList()) {
        val config = currentConfig ?: return
        val currentList = _messages.value
        context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).edit().putString("history_${config.id}", gson.toJson(currentList)).apply()
        getLocalHistoryFile(config.id).writeText(gson.toJson(currentList))

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val provider = storageProvider ?: return@launch
                
                var indexJson = provider.downloadText("chat_index.json")
                val shardNames = mutableSetOf<String>()
                if (indexJson != null) {
                    try {
                        val list: List<String> = gson.fromJson(indexJson, object : TypeToken<List<String>>() {}.type)
                        shardNames.addAll(list)
                    } catch (e: Exception) {}
                }
                
                val shardsData = mutableMapOf<String, MutableList<ChatMessage>>()
                currentList.forEach { msg ->
                    val shardName = getMonthShardName(msg.timestamp)
                    shardNames.add(shardName)
                    if (shardsData[shardName] == null) shardsData[shardName] = mutableListOf()
                    shardsData[shardName]?.add(msg)
                }
                
                var indexChanged = false
                
                shardNames.forEach { shardName ->
                    val tempFile = File(context.cacheDir, "merge_$shardName")
                    var cloudList: List<ChatMessage> = emptyList()
                    try {
                        provider.downloadFile(shardName, tempFile)
                        if (tempFile.exists()) {
                            val json = tempFile.readText()
                            val rawList: List<ChatMessage>? = try { gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) } catch (e: Exception) { null }
                            cloudList = rawList?.mapNotNull { sanitizeMessage(it) } ?: emptyList()
                        }
                    } catch (e: Exception) {}
                    
                    val cloudMap = cloudList.associateBy { it.id }.toMutableMap()
                    var shardChanged = false
                    
                    val localShardMsgs = shardsData[shardName] ?: emptyList()
                    localShardMsgs.forEach { localMsg ->
                        if (localMsg.status == MessageStatus.SUCCESS) {
                            val cloudMsg = cloudMap[localMsg.id]
                            if (cloudMsg == null || localMsg.lastModified > cloudMsg.lastModified) {
                                cloudMap[localMsg.id] = localMsg
                                shardChanged = true
                            }
                        }
                    }
                    
                    if (shardChanged || cloudList.isEmpty()) {
                        val mergedList = cloudMap.values.sortedBy { it.timestamp }
                        provider.uploadText(gson.toJson(mergedList), shardName)
                        indexChanged = true
                    }
                }
                
                if (indexChanged || indexJson == null) {
                    provider.uploadText(gson.toJson(shardNames.toList()), "chat_index.json")
                }
                
                lastKnownCloudTime = provider.getLastModified("chat_index.json")
                _isServerConnected.value = true
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud sync failed", e)
                _isServerConnected.value = storageProvider?.isReachable() ?: false
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

    suspend fun linkServerFile(fileName: String) {
        val provider = storageProvider ?: return
        val config = currentConfig ?: return
        
        // Determine type based on extension
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val type = when (extension) {
            "jpg", "jpeg", "png", "webp", "gif" -> com.cloudchat.model.MessageType.IMAGE
            "mp4", "mkv", "mov", "avi" -> com.cloudchat.model.MessageType.VIDEO
            "mp3", "wav", "m4a", "flac" -> com.cloudchat.model.MessageType.AUDIO
            else -> com.cloudchat.model.MessageType.FILE
        }

        val remoteUrl = fileName
        
        // Attempt to get file size from server
        val fileSize = try { provider.getFileSize(fileName) } catch (e: Exception) { 0L }

        val newMessage = ChatMessage(
            sender = config.username,
            senderName = config.username,
            senderAvatar = config.avatarUrl,
            content = fileName,
            type = type,
            isOutgoing = true,
            remoteUrl = remoteUrl,
            fileSize = fileSize,
            status = MessageStatus.SUCCESS
        )

        _messages.update { it + newMessage }
        saveLocalHistory(config.id)
        
        withContext(Dispatchers.IO) {
            try {
                syncHistory()
            } catch (e: Exception) {
                Log.e("ChatRepository", "Link file sync failed", e)
            }
        }
    }

    suspend fun checkSecurityAuth() = withContext(Dispatchers.IO) {
        val provider = authStorageProvider ?: return@withContext
        val config = currentConfig ?: return@withContext
        
        val name1 = "auth_${authId}.verified"
        val name2 = "${authId}.verified"
        
        try {
            val content1 = provider.downloadText(name1)
            val content2 = provider.downloadText(name2)
            
            val isVerified = (content1?.trim()?.lowercase() == "yes") || 
                            (content2?.trim()?.lowercase() == "yes")
            
            if (isVerified) {
                Log.i("ChatRepository", "Security authentication SUCCESS (content verified)")
                _isSecurityAuthenticated.value = true
                _securityError.value = null
            } else {
                _isSecurityAuthenticated.value = false
                // _securityError.value = "未找到认证文件或文件内容错误 (需在根目录创建 [ID].verified 文件并写入 yes)"
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "未知错误"
            Log.e("ChatRepository", "Security check failed: $errorMsg", e)
            _isSecurityAuthenticated.value = false
            /*
            _securityError.value = when {
                errorMsg.contains("401") -> "服务器鉴权失败 (401): 请检查 WebDAV 用户名和密码"
                errorMsg.contains("SSL") || errorMsg.contains("cert") -> "SSL 证书检查失败 ($errorMsg): 请确保服务器证书链完整，或尝试开启 [SSL 兼容模式]"
                else -> "连接服务器失败: $errorMsg"
            }
            */
        }
    }

    suspend fun verify2FAServer(code: String): Boolean = withContext(Dispatchers.IO) {
        val provider = authStorageProvider ?: return@withContext false
        val config = currentConfig ?: return@withContext false

        // _securityError.value = "正在验证 2FA 动态码..."
        val isValid = verifyInternalTOTP(code)
        
        if (isValid) {
            val authFileName = "${authId}.verified"
            try {
                // Upload "yes" as required
                provider.uploadText("yes", authFileName)
                _isSecurityAuthenticated.value = true
                _securityError.value = null
                return@withContext true
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知上传错误"
                Log.e("ChatRepository", "Failed to upload auth file: $errorMsg", e)
                // _securityError.value = "上传认证文件失败: $errorMsg"
            }
        } else {
            // _securityError.value = "2FA 动态码不正确，请核对密钥或确保手机时间准确"
        }
        false
    }


    private fun verifyInternalTOTP(code: String): Boolean {
        // Solidified 2FA logic: 
        // We use a fixed secret and calculate a window code
        val secret = TOTP_SECRET
        val timeStep = TOTP_STEP
        val currentTime = System.currentTimeMillis()
        
        // Check current and previous window to account for clock skew
        val (isValidCurrent, expectedCurrent) = checkCode(code, secret, currentTime / timeStep)
        val (isValidPrevious, expectedPrevious) = checkCode(code, secret, (currentTime - timeStep) / timeStep)

        Log.d("ChatRepository", "2FA Check: input=$code, current_window_expected=$expectedCurrent, prev_window_expected=$expectedPrevious")

        return isValidCurrent || isValidPrevious
    }

    private fun checkCode(code: String, secret: String, window: Long): Pair<Boolean, String> {
        val expected = generateTOTP(secret, window)
        return Pair(code == expected, expected)
    }

    fun getCurrentTOTPCode(): String {
        return generateTOTP(TOTP_SECRET, System.currentTimeMillis() / TOTP_STEP)
    }

    private fun generateTOTP(secret: String, window: Long): String {
        try {
            // Simple Base32 decoding for the specific secret format
            val key = decodeBase32(secret)
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            
            val data = ByteBuffer.allocate(8).putLong(window).array()
            val hash = mac.doFinal(data)
            
            val offset = hash[hash.size - 1].toInt() and 0xf
            val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                         ((hash[offset + 1].toInt() and 0xff) shl 16) or
                         ((hash[offset + 2].toInt() and 0xff) shl 8) or
                         (hash[offset + 3].toInt() and 0xff)
            
            val otp = binary % 1000000
            return String.format("%06d", otp)
        } catch (e: Exception) {
            return "000000"
        }
    }

    private fun decodeBase32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var i = 0
        var index = 0
        var digit = 0
        var currByte = 0
        val base32 = s.uppercase()
        val bytes = ByteArray(base32.length * 5 / 8)
        
        for (char in base32) {
            val lookup = alphabet.indexOf(char)
            if (lookup == -1) continue
            digit = lookup
            if (index <= 3) {
                index = (index + 5) % 8
                if (index == 0) {
                    currByte = currByte or digit
                    bytes[i++] = currByte.toByte()
                    currByte = 0
                } else {
                    currByte = currByte or (digit shl (8 - index))
                }
            } else {
                index = (index + 5) % 8
                currByte = currByte or (digit shr index)
                bytes[i++] = currByte.toByte()
                currByte = (digit shl (8 - index)) and 0xFF
            }
        }
        return bytes
    }

    suspend fun refreshHistoryFromCloud() = withContext(Dispatchers.IO) {
        if (!isRefreshingFromCloud.compareAndSet(false, true)) return@withContext
        _isSyncing.value = true
        val provider = storageProvider ?: run { _isSyncing.value = false; isRefreshingFromCloud.set(false); return@withContext }
        val config = currentConfig ?: run { isRefreshingFromCloud.set(false); return@withContext }
        try {
            var indexJson = provider.downloadText("chat_index.json")
            if (indexJson == null) {
                // Migration: Check if chat_history.json exists
                val oldJson = provider.downloadText("chat_history.json")
                if (oldJson != null) {
                    val rawCloudHistory: List<ChatMessage> = try { gson.fromJson(oldJson, object : TypeToken<List<ChatMessage>>() {}.type) } catch(e:Exception){emptyList()}
                    val cloudHistory = rawCloudHistory.mapNotNull { sanitizeMessage(it) }
                    // Shard it
                    val shards = cloudHistory.groupBy { getMonthShardName(it.timestamp) }
                    shards.forEach { (shardName, msgs) ->
                        provider.uploadText(gson.toJson(msgs.sortedBy{it.timestamp}), shardName)
                    }
                    provider.uploadText(gson.toJson(shards.keys.toList()), "chat_index.json")
                    try { provider.recycleFile("chat_history.json") } catch (e: Exception) {}
                    indexJson = gson.toJson(shards.keys.toList())
                } else {
                    // 服务器没有任何 history 文件：异常状态（正常不会为空），通知用户选择
                    _isServerConnected.value = true
                    _isSyncing.value = false
                    isRefreshingFromCloud.set(false)
                    historyConflict.tryEmit(HistoryConflictEvent(
                        reason = "not_found",
                        message = "服务器上未找到聊天记录，可能是首次使用或记录被意外清空。\n\n" +
                                "• 用本地记录覆盖服务器：将本机聊天记录上传到服务器\n" +
                                "• 清空本地记录：清除本机所有记录，从零开始"
                    ))
                    return@withContext
                }
            }
            
            val shardNames: List<String> = try { gson.fromJson(indexJson, object : TypeToken<List<String>>() {}.type) } catch(e:Exception){emptyList()}
            val allCloudMsgs = mutableListOf<ChatMessage>()
            
            shardNames.forEach { shard ->
                val json = provider.downloadText(shard)
                if (json != null) {
                    val raw: List<ChatMessage>? = try { gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) } catch (e: Exception) { null }
                    if (raw != null) {
                        allCloudMsgs.addAll(raw.mapNotNull { sanitizeMessage(it) })
                    }
                }
            }
            
            val cloudIds = allCloudMsgs.map { it.id }.toSet()

            // 服务器 index 文件存在：云端是唯一真相
            // - shard 有数据：合并云端 + 本地 pending
            // - shard 全为空（服务器被清空）：保留本地 pending，清空已成功的
            _messages.update { current ->
                val pendingOrFailed = current.filter { it.status != MessageStatus.SUCCESS && it.id !in cloudIds }
                if (allCloudMsgs.isNotEmpty()) {
                    val merged = allCloudMsgs.map { cloudMsg ->
                        current.find { it.id == cloudMsg.id }?.let { if (it.lastModified > cloudMsg.lastModified) it else cloudMsg } ?: cloudMsg
                    } + pendingOrFailed
                    merged.sortedBy { it.timestamp }
                } else {
                    // 云端 index 存在但 shard 全为空 → 服务器记录被清空，通知用户选择
                    Log.w("ChatRepository", "Cloud index exists but all shards empty")
                    isRefreshingFromCloud.set(false)
                    historyConflict.tryEmit(HistoryConflictEvent(
                        reason = "empty",
                        message = "服务器上的聊天记录为空（可能是被其他人清空了）。\n\n" +
                                "• 用本地记录覆盖服务器：将本机聊天记录上传到服务器\n" +
                                "• 清空本地记录：清除本机所有记录，与服务器保持一致"
                    ))
                    return@withContext
                }
            }

            saveLocalHistory(config.id)
            _isServerConnected.value = true
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud refresh failed", e)
            _isServerConnected.value = provider.isReachable()
        } finally {
            _isSyncing.value = false
            isRefreshingFromCloud.set(false)
        }
    }

    // 用户选择「用本地覆盖服务器」：逐条上传到服务器，保留原始时间戳，显示进度
    suspend fun forceUploadLocalHistory() = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext
        val config = currentConfig ?: return@withContext
        // 禁止同步循环触发冲突检测
        isRefreshingFromCloud.set(true)
        _uploadProgressPercent.value = 0
        _uploadProgressText.value = "准备上传..."
        try {
            val allMessages = _messages.value
            // 过滤已删除的条目
            val messages = allMessages.filter { !it.isDeleted }
            if (messages.isEmpty()) {
                _uploadProgressPercent.value = -1
                isRefreshingFromCloud.set(false)
                return@withContext
            }
            val total = messages.size
            
            // 先统计文件数量
            var fileCount = 0
            messages.forEach { msg ->
                val fileName = msg.content
                if (msg.type in listOf(com.cloudchat.model.MessageType.IMAGE,
                        com.cloudchat.model.MessageType.VIDEO,
                        com.cloudchat.model.MessageType.AUDIO) && fileName.isNotBlank()) {
                    val localFile = getLocalFile(msg.id, fileName)
                    if (localFile.exists() && localFile.length() > 0) fileCount++
                    if (!msg.thumbnailUrl.isNullOrBlank()) fileCount++
                }
            }
            _uploadProgressText.value = "共 ${total} 条记录，${fileCount} 个文件，开始上传..."
            
            // 先删除服务器旧文件
            try { provider.recycleFile("chat_history.json") } catch (e: Exception) {}
            
            // 按 shard 分批上传（每批 100 条），保留原始时间戳
            val shards = messages.chunked(100)
            val shardNames = shards.mapIndexed { i, _ -> "chat_shard_${i}.json" }
            var uploaded = 0
            var uploadedFiles = 0
            shards.forEachIndexed { i, chunk ->
                // 先上传每条消息的附件文件
                chunk.forEach { msg ->
                    val fileName = msg.content
                    if (msg.type in listOf(com.cloudchat.model.MessageType.IMAGE,
                            com.cloudchat.model.MessageType.VIDEO,
                            com.cloudchat.model.MessageType.AUDIO) && fileName.isNotBlank()) {
                        val localFile = getLocalFile(msg.id, fileName)
                        if (localFile.exists() && localFile.length() > 0) {
                            val contentType = when (msg.type) {
                                com.cloudchat.model.MessageType.IMAGE -> "image/jpeg"
                                com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                                else -> "application/octet-stream"
                            }
                            try {
                                provider.uploadFile(localFile.inputStream(), fileName, contentType, localFile.length()) { _ -> }
                                uploadedFiles++
                            } catch (e: Exception) {
                                Log.w("ChatRepository", "Failed to upload file $fileName for msg ${msg.id}", e)
                            }
                        }
                        // 上传缩略图
                        val thumbName = msg.thumbnailUrl
                        if (!thumbName.isNullOrBlank()) {
                            val thumbFile = File(localFile.parentFile, thumbName)
                            if (thumbFile.exists() && thumbFile.length() > 0) {
                                try {
                                    provider.uploadFile(thumbFile.inputStream(), thumbName, "image/jpeg", thumbFile.length()) { _ -> }
                                    uploadedFiles++
                                } catch (e: Exception) {
                                    Log.w("ChatRepository", "Failed to upload thumbnail $thumbName", e)
                                }
                            }
                        }
                    }
                }
                // 上传 JSON shard
                provider.uploadText(gson.toJson(chunk), shardNames[i])
                uploaded += chunk.size
                val pct = (uploaded * 100 / total).coerceAtMost(100)
                _uploadProgressPercent.value = pct
                _uploadProgressText.value = "正在上传记录 ${uploaded}/${total}，文件 ${uploadedFiles}/${fileCount}..."
            }
            provider.uploadText(gson.toJson(shardNames), "chat_index.json")
            _uploadProgressPercent.value = 100
            _uploadProgressText.value = "history 修复完成！共上传 ${total} 条记录，${uploadedFiles} 个文件"
            Log.i("ChatRepository", "forceUploadLocalHistory: uploaded $total messages, $uploadedFiles files in ${shards.size} shards")
        } catch (e: Exception) {
            Log.e("ChatRepository", "forceUploadLocalHistory failed", e)
            _uploadProgressPercent.value = -1
            _uploadProgressText.value = "上传失败：${e.message}"
        } finally {
            isRefreshingFromCloud.set(false)
        }
    }
    
    fun resetUploadProgress() {
        _uploadProgressPercent.value = -1
        _uploadProgressText.value = ""
    }

    // 用户选择「清空本地记录」：清除 _messages 和本地缓存
    suspend fun clearLocalHistory() {
        val config = currentConfig ?: return
        _messages.value = emptyList()
        withContext(Dispatchers.IO) {
            try {
                getLocalHistoryFile(config.id).delete()
                context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).edit()
                    .remove("history_${config.id}").apply()
            } catch (e: Exception) {
                Log.e("ChatRepository", "clearLocalHistory failed", e)
            }
        }
    }

    fun getAuthHeaders(): Map<String, String> {
        val config = currentConfig ?: return emptyMap()
        if (config.type == com.cloudchat.model.StorageType.WEBDAV && !config.webDavUser.isNullOrEmpty()) {
             val user = config.webDavUser ?: ""
             val pass = config.webDavPass ?: ""
             val credentials = try { okhttp3.Credentials.basic(user, pass) } catch (e: Exception) { null }
             return if (credentials != null) mapOf("Authorization" to credentials) else emptyMap()
        }
        return emptyMap()
    }

    fun resolveUrl(urlOrPath: String?): String? {
        if (urlOrPath == null) return null
        if (urlOrPath.startsWith("http") || urlOrPath.startsWith("content://") || urlOrPath.startsWith("file://")) return urlOrPath
        return storageProvider?.getFullUrl(urlOrPath)
    }

    suspend fun updateMessageCategories(messageId: String, categoryIds: List<String>) {
        _messages.update { list ->
            list.map { if (it.id == messageId) it.copy(categories = categoryIds) else it }
        }
        syncHistory()
    }

    suspend fun addMessageCategory(messageId: String, categoryId: String) {
        _messages.update { list ->
            list.map { 
                if (it.id == messageId) {
                    val updated = (it.safeCategories + categoryId).distinct()
                    it.copy(categories = updated)
                } else it 
            }
        }
        syncHistory()
    }

    suspend fun removeMessagesFromCategory(messageIds: List<String>, categoryId: String) {
        _messages.update { list ->
            list.map { 
                if (messageIds.contains(it.id)) {
                    val updated = it.safeCategories - categoryId
                    it.copy(categories = updated)
                } else it 
            }
        }
        syncHistory()
    }

    suspend fun toggleHideMessages(messageIds: Set<String>) {
        _messages.update { list ->
            list.map {
                if (messageIds.contains(it.id)) {
                    it.copy(isHidden = !it.isHidden, lastModified = System.currentTimeMillis())
                } else it
            }
        }
        syncHistory()
    }

    suspend fun groupSelectedMessages(messageIds: Set<String>, newGroupId: String) {
        _messages.update { list ->
            val selected = list.filter { messageIds.contains(it.id) }
            val selectedIds = selected.map { it.id }.toSet()

            if (selectedIds.isEmpty()) return@update list

            val existingGroupIds = selected
                .mapNotNull { it.groupId?.takeIf { g -> g.isNotBlank() } }
                .toSet()

            val membersOfExistingGroups = list
                .filter { !it.groupId.isNullOrEmpty() && existingGroupIds.contains(it.groupId) }
                .map { it.id }
                .toSet()

            val allTargetIds = selectedIds union membersOfExistingGroups

            val targetGroupId = if (existingGroupIds.size == 1) {
                existingGroupIds.first()
            } else {
                newGroupId
            }

            list.map {
                if (allTargetIds.contains(it.id)) {
                    it.copy(groupId = targetGroupId, lastModified = System.currentTimeMillis())
                } else it
            }
        }
        syncHistory()
    }

    suspend fun ungroupMessages(messages: List<ChatMessage>) {
        val selectedIds = messages.map { it.id }.toSet()

        _messages.update { list ->
            // Group the selected messages by their current groupId (ignore ungrouped ones).
            val selectedByGroup = list
                .filter { selectedIds.contains(it.id) && !it.groupId.isNullOrEmpty() }
                .groupBy { it.groupId!! }

            if (selectedByGroup.isEmpty()) return@update list

            // id -> new groupId (null means detach)
            val reassign = HashMap<String, String?>()
            var splitCounter = 0L
            val splitBase = System.currentTimeMillis()

            selectedByGroup.forEach { (groupId, selectedInGroup) ->
                val allInGroup = list.filter { it.groupId == groupId }
                val remaining = allInGroup.filter { !selectedIds.contains(it.id) }

                if (remaining.isEmpty()) {
                    // Whole group selected -> fully ungroup.
                    allInGroup.forEach { reassign[it.id] = null }
                } else {
                    // Subset selected -> split the subset into a new group.
                    if (selectedInGroup.size >= 2) {
                        val newSplitGroupId = "group_${splitBase + splitCounter}"
                        splitCounter++
                        selectedInGroup.forEach { reassign[it.id] = newSplitGroupId }
                    } else {
                        // A single item cannot form a grid -> detach it.
                        selectedInGroup.forEach { reassign[it.id] = null }
                    }
                    // Remaining stays in the original group, unless only one is left.
                    if (remaining.size < 2) {
                        remaining.forEach { reassign[it.id] = null }
                    }
                }
            }

            list.map {
                if (reassign.containsKey(it.id)) it.copy(groupId = reassign[it.id], lastModified = System.currentTimeMillis()) else it
            }
        }
        syncHistory()
    }

    suspend fun editTextMessage(messageId: String, newContent: String) {
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map {
                if (it.id == messageId) {
                    it.copy(content = newContent, isEdited = true, lastModified = now)
                } else it
            }
        }
        syncHistory()
    }

    suspend fun updateMessageCaption(messageId: String, newCaption: String?) {
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map {
                if (it.id == messageId) {
                    it.copy(caption = newCaption?.ifBlank { null }, lastModified = now)
                } else it
            }
        }
        syncHistory()
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map {
                if (it.id == folderId && it.type == com.cloudchat.model.MessageType.FOLDER) {
                    it.copy(content = trimmed, lastModified = now)
                } else it
            }
        }
        syncHistory()
    }

    suspend fun packIntoFolder(messages: List<com.cloudchat.model.ChatMessage>, annotation: String, existingFolderId: String? = null) {
        if (messages.isEmpty()) return
        
        val folderId = existingFolderId ?: "folder_${java.util.UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        
        if (existingFolderId == null) {
            val config = currentConfig ?: return
            val folderMsg = com.cloudchat.model.ChatMessage(
                id = folderId,
                sender = config.username,
                content = annotation,
                type = com.cloudchat.model.MessageType.FOLDER,
                timestamp = now,
                isOutgoing = true,
                status = com.cloudchat.model.MessageStatus.SUCCESS,
                lastModified = now
            )
            
            _messages.update { list ->
                val updatedList = list.toMutableList()
                updatedList.add(folderMsg)
                updatedList.map { msg ->
                    if (messages.any { it.id == msg.id }) {
                        msg.copy(folderId = folderId, lastModified = now)
                    } else msg
                }
            }
        } else {
            _messages.update { list ->
                list.map { msg ->
                    if (messages.any { it.id == msg.id }) {
                        msg.copy(folderId = folderId, lastModified = now)
                    } else if (msg.id == folderId) {
                        val newContent = if (annotation.isNotBlank() && annotation != msg.content) {
                            annotation
                        } else {
                            msg.content
                        }
                        msg.copy(content = newContent, lastModified = now)
                    } else msg
                }
            }
        }
        syncHistory()
    }

    suspend fun unpackFolder(folderId: String) {
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == folderId) {
                    msg.copy(isDeleted = true, lastModified = now)
                } else if (msg.folderId == folderId) {
                    msg.copy(folderId = null, lastModified = now)
                } else msg
            }
        }
        syncHistory()
    }

    suspend fun removeFromFolder(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val now = System.currentTimeMillis()
        _messages.update { list ->
            list.map { msg ->
                if (msg.id in messageIds) {
                    msg.copy(folderId = null, lastModified = now)
                } else msg
            }
        }
        syncHistory()
    }

    suspend fun syncAllMediaFiles(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext
        val allMediaMsgs = _messages.value.filter { 
            it.type in listOf(com.cloudchat.model.MessageType.IMAGE, com.cloudchat.model.MessageType.VIDEO, com.cloudchat.model.MessageType.AUDIO, com.cloudchat.model.MessageType.FILE) 
        }
        val total = allMediaMsgs.size
        if (total == 0) {
            onProgress(0, 0)
            return@withContext
        }
        
        var current = 0
        _mediaSyncProgress.value = 0f
        allMediaMsgs.forEach { msg ->
            current++
            _mediaSyncProgress.value = current.toFloat() / total.toFloat()
            onProgress(current, total)
            
            val fileName = msg.content
            if (fileName.isBlank()) return@forEach
            
            val localFile = getLocalFile(msg.id, fileName)
            val cloudLastModified = provider.getLastModified(fileName)
            val cloudExists = cloudLastModified > 0
            val localExists = localFile.exists()
            
            if (localExists && !cloudExists) {
                // Upload to server
                try {
                    provider.uploadFile(localFile.inputStream(), fileName, "application/octet-stream", localFile.length(), null)
                    msg.thumbnailUrl?.let { thumbUrl ->
                        val thumbFile = File(context.cacheDir, "thumb_$fileName")
                        if (thumbFile.exists()) {
                            provider.uploadFile(thumbFile.inputStream(), "thumb_$fileName", "image/jpeg", thumbFile.length(), null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Sync upload failed for $fileName", e)
                }
            } else if (!localExists && cloudExists) {
                // Download to local
                try {
                    downloadFileToCache(msg.id, fileName, msg.remoteUrl ?: "")
                    msg.thumbnailUrl?.let { thumbUrl ->
                        val thumbFile = File(context.cacheDir, "thumb_$fileName")
                        provider.downloadFile("thumb_$fileName", thumbFile, null)
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Sync download failed for $fileName", e)
                }
            }
        }
    }

    suspend fun listDiaryFiles(): List<DiaryFileItem> = withContext(Dispatchers.IO) {
        val config = currentConfig ?: SettingsRepository(context).currentConfig.firstOrNull() ?: return@withContext emptyList()
        if (config.type != com.cloudchat.model.StorageType.WEBDAV || config.webDavUrl.isBlank()) {
            return@withContext emptyList()
        }

        val baseUrl = config.webDavUrl.trimEnd('/')
        val root = config.serverPath.trim('/')
        val userDirClean = config.saveDir.trim('/')

        val parts = mutableListOf<String>()
        if (root.isNotEmpty()) parts.add(root)
        if (userDirClean.isNotEmpty()) parts.add(userDirClean)
        parts.add("diary")

        val diaryUrl = "$baseUrl/${parts.joinToString("/")}"
        val fallbackDiaryUrl = "$baseUrl/${if (root.isNotEmpty()) "$root/" else ""}diary"

        val targetUrls = listOf(diaryUrl, fallbackDiaryUrl)
        val result = mutableListOf<DiaryFileItem>()
        val seenNames = mutableSetOf<String>()
        val cleanBaseUrl = config.diaryBaseUrl.trim().trimEnd('/')

        val client = NetworkUtils.getUnsafeOkHttpClient().build()
        val credential = Credentials.basic(config.webDavUser, config.webDavPass)

        for (url in targetUrls) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .method("PROPFIND", null)
                    .header("Authorization", credential)
                    .header("Depth", "1")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val xmlText = response.body?.string() ?: ""
                    response.close()

                    if (xmlText.isNotEmpty()) {
                        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                        factory.isNamespaceAware = true
                        val parser = factory.newPullParser()
                        parser.setInput(java.io.StringReader(xmlText))

                        var eventType = parser.eventType
                        var currentHref: String? = null
                        var currentSize: Long = 0L
                        var currentLastModified: Long = 0L
                        var isCollection = false

                        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            val tag = parser.name
                            when (eventType) {
                                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                                    if (tag.equals("response", ignoreCase = true)) {
                                        currentHref = null
                                        currentSize = 0L
                                        currentLastModified = 0L
                                        isCollection = false
                                    } else if (tag.equals("href", ignoreCase = true)) {
                                        currentHref = parser.nextText()
                                    } else if (tag.equals("collection", ignoreCase = true)) {
                                        isCollection = true
                                    } else if (tag.equals("getcontentlength", ignoreCase = true)) {
                                        currentSize = parser.nextText().toLongOrNull() ?: 0L
                                    } else if (tag.equals("getlastmodified", ignoreCase = true)) {
                                        val dateStr = parser.nextText()
                                        try {
                                            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                                            currentLastModified = format.parse(dateStr)?.time ?: 0L
                                        } catch (e: Exception) {
                                            currentLastModified = System.currentTimeMillis()
                                        }
                                    }
                                }
                                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                                    if (tag.equals("response", ignoreCase = true)) {
                                        val href = currentHref ?: ""
                                        val cleanHref = href.trimEnd('/')
                                        val cleanTarget = url.trimEnd('/')

                                        if (href.isNotEmpty() && !cleanHref.endsWith("/diary") && cleanHref != cleanTarget) {
                                            val itemName = java.net.URLDecoder.decode(cleanHref.substringAfterLast('/'), "UTF-8")
                                            if (itemName.isNotEmpty() && seenNames.add(itemName)) {
                                                val webUrl = if (isCollection) {
                                                    if (cleanBaseUrl.isNotEmpty()) {
                                                        // diaryBaseUrl 已是完整地址（等价 webdav目录/diary），直接拼子目录
                                                        "$cleanBaseUrl/${java.net.URLEncoder.encode(itemName, "UTF-8")}/index.html"
                                                    } else {
                                                        // 未设 diaryBaseUrl：用 webdav 打开的 cleanTarget（已含 diary）拼子目录
                                                        "$cleanTarget/${java.net.URLEncoder.encode(itemName, "UTF-8")}/index.html"
                                                    }
                                                } else {
                                                    if (cleanBaseUrl.isNotEmpty()) {
                                                        "$cleanBaseUrl/${java.net.URLEncoder.encode(itemName, "UTF-8")}"
                                                    } else {
                                                        if (href.startsWith("http")) href else "$baseUrl${if (href.startsWith("/")) "" else "/"}$href"
                                                    }
                                                }
                                                val displayName = if (isCollection) "$itemName/index.html" else itemName
                                                result.add(DiaryFileItem(displayName, webUrl, currentSize, currentLastModified))
                                            }
                                        }
                                    }
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                } else {
                    response.close()
                }
                if (result.isNotEmpty()) break
            } catch (e: Exception) {
                Log.w("ChatRepository", "PROPFIND error for diary files: $url", e)
            }
        }

        result.sortByDescending { it.lastModified }
        result
    }

    suspend fun deleteDiaryFile(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext false
        val cleanName = fileName.removeSuffix("/index.html").trim('/')
        val diaryDir = "diary/$cleanName"
        try {
            // 删除整个日记目录（含 assets 等静态资源）
            provider.deleteDirectory(diaryDir)
            true
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to delete diary directory: $fileName", e)
            false
        }
    }

    /**
     * 生成静态日记网页并上传到 WebDAV。
     * @param folderName 日记标题
     * @param author 作者
     * @param templateId "wechat" | "journal"
     * @param password 访问密码（空则不加密）
     * @param messages 要归档的消息列表
     * @param onProgress 进度回调 (百分比, 文字)
     * @return 生成后的公开访问 URL（成功）或 null
     */
    suspend fun generateDiary(
        folderName: String,
        author: String,
        templateId: String,
        password: String,
        coverUri: android.net.Uri?,
        messages: List<ChatMessage>,
        onProgress: ((Int, String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val provider = storageProvider ?: return@withContext null
        val config = currentConfig ?: SettingsRepository(context).currentConfig.firstOrNull() ?: return@withContext null
        if (config.type != com.cloudchat.model.StorageType.WEBDAV || config.webDavUrl.isBlank()) {
            return@withContext null
        }

        val title = folderName.ifBlank { "我的日记" }
        val authorStr = author.ifBlank { "CloudChat User" }
        val cleanDir = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val targetDir = "diary/$cleanDir"
        val targetAssetsDir = "$targetDir/assets"

        // 防御性过滤：跳过删除/隐藏条目
        val effectiveMessages = messages.filter { !it.isDeleted && it.isHidden != true }

        onProgress?.invoke(10, "正在创建服务器目录...")
        provider.mkdirRecursive(targetAssetsDir)

        onProgress?.invoke(20, "正在准备媒体资源...")

        // 过滤出媒体消息，上传资源到 assets
        val mediaMsgs = effectiveMessages.filter {
            it.type == com.cloudchat.model.MessageType.IMAGE ||
            it.type == com.cloudchat.model.MessageType.VIDEO ||
            it.type == com.cloudchat.model.MessageType.AUDIO
        }
        val totalMedia = mediaMsgs.size
        val mediaUrlMap = mutableMapOf<String, String>()
        var processed = 0

        for (msg in mediaMsgs) {
            val fileName = msg.content.ifBlank { "${msg.id}.jpg" }
            val baseName = DiaryGenerator.cleanFileName(fileName).substringBeforeLast('.', DiaryGenerator.cleanFileName(fileName))
            val localFile = getLocalFile(msg.id, fileName)
            var uploaded = false
            var uploadedName: String? = null

            if (msg.type == com.cloudchat.model.MessageType.IMAGE) {
                // 图片：统一压缩为 WebP 上传（减小体积，加快页面加载）
                var srcFile: File? = localFile
                var needCleanup = false
                // 本地不存在：从远程下载到本地临时文件
                if (!localFile.exists() || localFile.length() <= 0) {
                    if (fileName.isNotBlank()) {
                        val tempFile = File(context.cacheDir, "diary_src_${System.currentTimeMillis()}_$fileName")
                        try {
                            provider.downloadFile(fileName, tempFile)
                            if (tempFile.exists() && tempFile.length() > 0) {
                                srcFile = tempFile
                                needCleanup = true
                            }
                        } catch (e: Exception) {
                            Log.w("ChatRepository", "Failed to download image for compress: $fileName", e)
                        }
                    }
                }
                if (srcFile != null && srcFile.exists() && srcFile.length() > 0) {
                    val webpName = "$baseName.webp"
                    val webpFile = compressImageToWebp(srcFile)
                    if (needCleanup) srcFile.delete()
                    if (webpFile != null && webpFile.length() > 0) {
                        uploaded = provider.uploadFileToPath(webpFile.inputStream(), "$targetAssetsDir/$webpName", "image/webp")
                        webpFile.delete()
                        if (uploaded) uploadedName = webpName
                    }
                }
                // 压缩仍失败：远程 COPY 原图兜底
                if (!uploaded && fileName.isNotBlank()) {
                    uploaded = provider.copyRemoteFile(fileName, "$targetAssetsDir/${DiaryGenerator.cleanFileName(fileName)}")
                    if (uploaded) uploadedName = DiaryGenerator.cleanFileName(fileName)
                }
            } else {
                // 视频/音频：优先远程 COPY，失败回退本地
                val cleanName = DiaryGenerator.cleanFileName(fileName)
                if (fileName.isNotBlank()) {
                    uploaded = provider.copyRemoteFile(fileName, "$targetAssetsDir/$cleanName")
                }
                if (!uploaded && localFile.exists() && localFile.length() > 0) {
                    val contentType = when (msg.type) {
                        com.cloudchat.model.MessageType.VIDEO -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    uploaded = provider.uploadFileToPath(localFile.inputStream(), "$targetAssetsDir/$cleanName", contentType)
                }
                if (uploaded) uploadedName = cleanName
            }

            if (uploaded && uploadedName != null) {
                mediaUrlMap[msg.id] = "assets/$uploadedName"
            } else {
                // 都失败：尝试用原始 content 作为远程引用
                mediaUrlMap[msg.id] = msg.remoteUrl ?: msg.content
            }
            processed++
            if (totalMedia > 0) {
                val pct = 20 + (processed * 40 / totalMedia)
                onProgress?.invoke(pct, "正在准备媒体资源 [ $processed / $totalMedia ]...")
            }
        }

        onProgress?.invoke(70, "正在上传头像资源...")

        // 上传头像文件到 assets 目录，映射 avatar 文件名 -> 相对路径
        val avatarUrlMap = mutableMapOf<String, String>()
        val distinctAvatars = effectiveMessages.mapNotNull { it.senderAvatar }
            .filter { it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") &&
                    !it.startsWith("data:") && !it.startsWith("file://") && !it.startsWith("content://") }
            .distinct()
        for (avatarName in distinctAvatars) {
            val ext = avatarName.substringAfterLast('.', "png")
            val cleanAvatar = "avatar_${avatarName.hashCode().toUInt()}.$ext"
            var uploadedAvatar = false
            // 优先 WebDAV 远程 COPY（头像在根目录，直接复制到 assets）
            uploadedAvatar = provider.copyRemoteFile(avatarName, "$targetAssetsDir/$cleanAvatar")
            // COPY 失败则回退：下载到本地再上传
            if (!uploadedAvatar) {
                val tempAvatar = File(context.cacheDir, "avatar_tmp_${System.currentTimeMillis()}_$avatarName")
                try {
                    provider.downloadFile(avatarName, tempAvatar)
                    if (tempAvatar.exists() && tempAvatar.length() > 0) {
                        val contentType = when (ext.lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        }
                        uploadedAvatar = provider.uploadFileToPath(tempAvatar.inputStream(), "$targetAssetsDir/$cleanAvatar", contentType)
                    }
                } catch (e: Exception) {
                    Log.w("ChatRepository", "Failed to upload avatar $avatarName", e)
                } finally {
                    tempAvatar.delete()
                }
            }
            if (uploadedAvatar) {
                avatarUrlMap[avatarName] = "assets/$cleanAvatar"
            }
        }

        onProgress?.invoke(75, "正在上传背景图...")

        // 上传背景图到 assets（如果有）
        var coverUrl: String? = null
        if (coverUri != null) {
            try {
                val ext = when {
                    coverUri.lastPathSegment?.contains(".png") == true -> "png"
                    coverUri.lastPathSegment?.contains(".jpg") == true -> "jpg"
                    coverUri.lastPathSegment?.contains(".jpeg") == true -> "jpeg"
                    coverUri.lastPathSegment?.contains(".webp") == true -> "webp"
                    else -> "jpg"
                }
                val coverName = "cover.$ext"
                val inputStream = context.contentResolver.openInputStream(coverUri)
                if (inputStream != null) {
                    val contentType = when (ext) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    val ok = provider.uploadFileToPath(inputStream, "$targetAssetsDir/$coverName", contentType)
                    if (ok) coverUrl = "assets/$coverName"
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to upload cover image", e)
            }
        }

        onProgress?.invoke(78, "正在生成 HTML 页面...")

        // 构造媒体解析器
        val resolver = object : DiaryGenerator.MediaUrlResolver {
            override fun resolve(msg: ChatMessage): String {
                mediaUrlMap[msg.id]?.let { return it }
                return msg.remoteUrl ?: msg.content
            }
            override fun resolveAvatar(msg: ChatMessage, default: String): String {
                val displayName = msg.senderName?.ifEmpty { msg.sender } ?: msg.sender ?: authorStr
                val fallback = "https://api.dicebear.com/7.x/bottts/png?seed=${java.net.URLEncoder.encode(displayName, "UTF-8")}"
                val raw = msg.senderAvatar
                if (raw.isNullOrEmpty()) return fallback
                // 完整 URL 直接使用
                if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") ||
                    raw.startsWith("file://") || raw.startsWith("content://")) {
                    return raw
                }
                // 优先使用已上传到 assets 的头像相对路径
                avatarUrlMap[raw]?.let { return it }
                // fallback：解析为服务器完整 URL
                return provider.getFullUrl(raw) ?: fallback
            }
        }

        val html = DiaryGenerator.generateHtml(title, authorStr, templateId, password, effectiveMessages, resolver, coverUrl)

        onProgress?.invoke(85, "正在上传 index.html...")
        val htmlOk = provider.uploadFileToPath(html.byteInputStream(Charsets.UTF_8), "$targetDir/index.html", "text/html; charset=utf-8")
        if (!htmlOk) return@withContext null

        onProgress?.invoke(100, "日记生成完成！")

        // 计算公开访问 URL
        val cleanBaseUrl = config.diaryBaseUrl.trim().trimEnd('/')
        return@withContext if (cleanBaseUrl.isNotEmpty()) {
            "$cleanBaseUrl/${java.net.URLEncoder.encode(cleanDir, "UTF-8").replace("+", "%20")}/index.html"
        } else {
            provider.getFullUrl("diary/$cleanDir/index.html")
        }
    }
}

data class DiaryFileItem(
    val name: String,
    val webUrl: String,
    val size: Long,
    val lastModified: Long
)
