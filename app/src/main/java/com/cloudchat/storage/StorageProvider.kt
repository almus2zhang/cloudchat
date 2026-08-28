package com.cloudchat.storage

import com.cloudchat.model.ChatMessage
import java.io.File
import java.io.InputStream

interface StorageProvider {
    suspend fun testConnection(): Result<String>
    suspend fun uploadFile(
        inputStream: InputStream, 
        fileName: String, 
        contentType: String,
        contentLength: Long = -1,
        onProgress: ((Int) -> Unit)? = null
    ): String
    suspend fun uploadText(text: String, fileName: String): String
    suspend fun downloadText(fileName: String): String?
    suspend fun listMessages(): List<ChatMessage>
    suspend fun downloadFile(fileName: String, destination: File, onProgress: ((Int) -> Unit)? = null)
    suspend fun getFileSize(fileName: String): Long // For verification
    suspend fun getLastModified(fileName: String): Long
    /** 任一配置地址（主/备用）能否连通。默认 true；WebDAV 会实测两个地址。 */
    suspend fun isReachable(): Boolean = true
    suspend fun deleteFile(fileName: String)
    fun getFullUrl(fileName: String): String
    suspend fun uploadFileRange(
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        startByte: Long,
        endByte: Long,
        totalLength: Long,
        onProgress: ((Int) -> Unit)? = null
    ): String {
        throw UnsupportedOperationException("Range upload not supported by this provider")
    }
    suspend fun recycleFile(fileName: String) {
        deleteFile(fileName)
    }

    // ---- 日记子目录操作（支持子路径，绕过 safeFileName 的单层限制） ----
    /** 创建目录（含多级），返回是否成功 */
    suspend fun mkdirRecursive(dirPath: String): Boolean = false
    /** 上传文件到子路径（如 diary/name/assets/xxx.jpg） */
    suspend fun uploadFileToPath(inputStream: InputStream, filePath: String, contentType: String): Boolean = false
    /** 删除整个目录（含其中所有文件），返回是否成功 */
    suspend fun deleteDirectory(dirPath: String): Boolean = false
    /** 远程复制文件（WebDAV COPY），从 srcPath 到 destPath，返回是否成功 */
    suspend fun copyRemoteFile(srcPath: String, destPath: String): Boolean = false
    /** 拷贝文件或文本到远程 share/ 子目录 */
    suspend fun copyToShare(fileName: String?, textContent: String?): Boolean = false
}
