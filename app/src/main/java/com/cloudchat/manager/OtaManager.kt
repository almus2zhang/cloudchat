package com.cloudchat.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.cloudchat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class OtaVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val forceUpdate: Boolean = false
)

object OtaManager {
    private const val TAG = "OtaManager"
    const val OTA_VERSION_URL = "https://chat.a66.nasnas.site/web/c7x9k2m5p8q3v6w1n4t7b8d2/version.json"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun getIgnoredVersion(context: Context): Int {
        return context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
            .getInt("ignored_version_code", 0)
    }

    fun setIgnoredVersion(context: Context, versionCode: Int) {
        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("ignored_version_code", versionCode)
            .apply()
    }

    fun clearIgnoredVersion(context: Context) {
        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("ignored_version_code")
            .apply()
    }

    suspend fun checkUpdate(): Result<OtaVersionInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(OTA_VERSION_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.success(null)
                val json = JSONObject(body)
                val androidObj = json.optJSONObject("android") ?: return@withContext Result.success(null)

                val remoteCode = androidObj.optInt("versionCode", 0)
                val remoteName = androidObj.optString("versionName", "")
                val changelog = androidObj.optString("changelog", "")
                val apkUrl = androidObj.optString("apkUrl", "")
                val forceUpdate = androidObj.optBoolean("forceUpdate", false)

                if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                    Result.success(
                        OtaVersionInfo(
                            versionCode = remoteCode,
                            versionName = remoteName,
                            changelog = changelog,
                            apkUrl = apkUrl,
                            forceUpdate = forceUpdate
                        )
                    )
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate error", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            val updateFile = File(context.cacheDir, "update.apk")
            if (updateFile.exists()) {
                updateFile.delete()
            }

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onError("下载失败 (HTTP ${response.code})")
                    }
                    return@withContext
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) { onError("下载响应为空") }
                    return@withContext
                }

                val totalLength = body.contentLength()
                var bytesReadTotal = 0L

                body.byteStream().use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var lastReportTime = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesReadTotal += read
                            if (totalLength > 0) {
                                val progress = ((bytesReadTotal * 100) / totalLength).toInt()
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime > 100 || progress == 100) {
                                    lastReportTime = now
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Trigger Android Package Installer
            withContext(Dispatchers.Main) {
                try {
                    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            updateFile
                        )
                    } else {
                        Uri.fromFile(updateFile)
                    }

                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(installIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch installer", e)
                    onError("启动安装程序失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall error", e)
            withContext(Dispatchers.Main) {
                onError("下载出错: ${e.message}")
            }
        }
    }
}
