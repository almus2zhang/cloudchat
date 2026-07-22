package com.cloudchat.model

import java.util.UUID

enum class MessageType {
    TEXT, IMAGE, VIDEO, AUDIO, FILE, FOLDER
}

enum class MessageStatus {
    SENDING, SUCCESS, FAILED
}

enum class AppMode {
    NOT_SET, SELF_BUILT, FULL
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val senderName: String? = null, // Display name
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val remoteUrl: String? = null,
    val fileSize: Long = 0,
    val videoDuration: Long = 0,
    val isOutgoing: Boolean = true,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default for incoming or history
    val thumbnailUrl: String? = null,
    val categories: List<String>? = emptyList(), // Added category support
    val locationAddress: String? = null,
    val isChunked: Boolean = false,
    val chunkSize: Long = 0L,
    val totalChunks: Int = 0,
    val groupId: String? = null,
    val isHidden: Boolean = false,
    val caption: String? = null,
    val isEdited: Boolean = false,
    val folderId: String? = null // For packing messages into a FOLDER
) {
    val safeCategories: List<String>
        get() = categories ?: emptyList()
}

enum class StorageType {
    S3, WEBDAV
}

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: StorageType = StorageType.WEBDAV,
    val endpoint: String = "",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val saveDir: String = "", // Replaced old username (path)
    val username: String = "", // New Display Name
    val serverPath: String = "",
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPass: String = "",
    val webDavFallbackUrl: String = "", // Added WebDAV fallback URL
    val autoDownloadLimit: Long = 5 * 1024 * 1024L,
    val configPassword: String? = null,
    val fullModePath: String? = null,
    val webDavIgnoreCert: Boolean = false, // 忽略证书校验（用于自签名/私有 CA 的 WebDAV，如 Lucky）
    val webDavChunkSize: Long = 0L // 0 means no chunking, >0 means chunk size in bytes
)

data class ChatCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isEncrypted: Boolean = false,
    val passwordHash: String? = null
)

