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
import kotlinx.coroutines.withContext
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

    /** 文件已成功上传后，待删除的源文件 URI（图库需经用户授权确认才可删）。 */
    val sourceReadyToDelete = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val isServerConnected = _isServerConnected.asStateFlow()

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
            val avatarFileName = getSafeAvatarFileName(config.username)

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
            // 位置变了：清空旧消息，直接以新位置的云端历史为准（无历史则显示空）
            _messages.value = emptyList()
            refreshHistoryFromCloud()
        } else {
            // 位置未变（仅修改证书/分块等设置）：保留本地缓存，离线也能看
            loadLocalHistory(config.id)
            if (_messages.value.isEmpty()) {
                refreshHistoryFromCloud()
            }
        }
        uploadLoginLog(config)
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
        if (file.exists()) {
            try {
                val json = file.readText()
                val rawHistory: List<ChatMessage>? = try { gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) } catch (e: Exception) { null }
                val history = rawHistory?.mapNotNull { sanitizeMessage(it) } ?: emptyList()
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
            status = if (inputStream != null) MessageStatus.SENDING else MessageStatus.SUCCESS,
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
        saveLocalHistory(config.id)

        if (inputStream != null) {
            _uploadProgress.update { it + (newMessage.id to 0) }
        }

        withContext(Dispatchers.IO) {
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
                    provider.uploadText(content, "msg_${System.currentTimeMillis()}.txt")
                }
            } catch (e: Exception) {
                Log.e("ChatRepository", "Cloud upload failed for message ${newMessage.id}", e)
                _uploadProgress.update { it - newMessage.id }
                updateMessageStatus(newMessage.id, MessageStatus.FAILED)
            }
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.update { list ->
            list.map { if (it.id == messageId) it.copy(status = status) else it }
        }
        syncHistory()
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
                        val cloudMsg = cloudMap[localMsg.id]
                        if (cloudMsg == null || localMsg.lastModified > cloudMsg.lastModified || localMsg.status != MessageStatus.SUCCESS) {
                            cloudMap[localMsg.id] = localMsg
                            shardChanged = true
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
        val provider = storageProvider ?: run { isRefreshingFromCloud.set(false); return@withContext }
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
                    provider.uploadText("[]", "chat_index.json")
                    indexJson = "[]"
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

            _messages.update { current ->
                val pendingOrFailed = current.filter { it.status != MessageStatus.SUCCESS && it.id !in cloudIds }
                val merged = allCloudMsgs.map { cloudMsg ->
                    current.find { it.id == cloudMsg.id }?.let { if (it.lastModified > cloudMsg.lastModified) it else cloudMsg } ?: cloudMsg
                } + pendingOrFailed
                merged.sortedBy { it.timestamp }
            }

            saveLocalHistory(config.id)
            _isServerConnected.value = true
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud refresh failed", e)
            _isServerConnected.value = provider.isReachable()
        } finally {
            isRefreshingFromCloud.set(false)
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
            // Only IMAGE/VIDEO participate in grid aggregation; other types are filtered out.
            val mediaSelected = list.filter {
                messageIds.contains(it.id) &&
                    (it.type == com.cloudchat.model.MessageType.IMAGE ||
                        it.type == com.cloudchat.model.MessageType.VIDEO)
            }
            val mediaSelectedIds = mediaSelected.map { it.id }.toSet()

            if (mediaSelectedIds.isEmpty()) return@update list

            // Existing grids that any selected media already belongs to.
            val existingGroupIds = mediaSelected
                .mapNotNull { it.groupId?.takeIf { g -> g.isNotBlank() } }
                .toSet()

            // All members of those existing grids must join the merge.
            val membersOfExistingGroups = list
                .filter {
                    (it.type == com.cloudchat.model.MessageType.IMAGE ||
                        it.type == com.cloudchat.model.MessageType.VIDEO) &&
                        !it.groupId.isNullOrEmpty() && existingGroupIds.contains(it.groupId)
                }
                .map { it.id }
                .toSet()

            val allTargetIds = mediaSelectedIds union membersOfExistingGroups

            // Resulting group id:
            // - exactly one existing grid involved -> keep it (loose media enter that grid)
            // - several grids involved -> merge all of them into one new grid
            // - none involved -> brand new grid
            val targetGroupId = if (existingGroupIds.size == 1) {
                existingGroupIds.first()
            } else {
                newGroupId
            }

            list.map {
                if (allTargetIds.contains(it.id)) {
                    it.copy(groupId = targetGroupId)
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
                if (reassign.containsKey(it.id)) it.copy(groupId = reassign[it.id]) else it
            }
        }
        syncHistory()
    }

    suspend fun editTextMessage(messageId: String, newContent: String) {
        _messages.update { list ->
            list.map {
                if (it.id == messageId) {
                    it.copy(content = newContent, isEdited = true)
                } else it
            }
        }
        syncHistory()
    }

    suspend fun updateMessageCaption(messageId: String, newCaption: String?) {
        _messages.update { list ->
            list.map {
                if (it.id == messageId) {
                    it.copy(caption = newCaption?.ifBlank { null })
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
        allMediaMsgs.forEach { msg ->
            current++
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
}
