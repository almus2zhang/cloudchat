package com.cloudchat.model

import java.util.UUID

enum class MessageType {
    TEXT, IMAGE, VIDEO, AUDIO, FILE
}

enum class MessageStatus {
    SENDING, SUCCESS, FAILED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val remoteUrl: String? = null,
    val fileSize: Long = 0,
    val videoDuration: Long = 0,
    val isOutgoing: Boolean = true,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default for incoming or history
    val thumbnailUrl: String? = null
)

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
    val username: String = "",
    val serverPath: String = "",
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPass: String = "",
    val autoDownloadLimit: Long = 5 * 1024 * 1024L
)
