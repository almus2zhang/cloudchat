package com.cloudchat.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "DebugLogger"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB

    private var activeLogFile: File? = null
    private var prefsContext: Context? = null

    @Volatile
    var isEnabled: Boolean = true

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    fun init(context: Context) {
        try {
            prefsContext = context.applicationContext
            val sp = prefsContext?.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
            isEnabled = sp?.getBoolean("debug_log_enabled", true) ?: true

            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, "debug_logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            activeLogFile = File(dir, "debug_log.txt")

            if (activeLogFile?.exists() == true && isEnabled) {
                try {
                    val lines = activeLogFile!!.readLines().takeLast(200)
                    _logLines.value = lines
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read initial log lines", e)
                }
            }
            log("System", "=== CloudChat DebugLogger Initialized (Enabled: $isEnabled) ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DebugLogger", e)
        }
    }

    fun setLogEnabled(enabled: Boolean) {
        isEnabled = enabled
        try {
            prefsContext?.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean("debug_log_enabled", enabled)
                ?.apply()
        } catch (e: Exception) {}
        if (!enabled) {
            _logLines.value = emptyList()
        } else {
            log("System", "=== DebugLogger Enabled ===")
        }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        if (!isEnabled) return

        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timeStr] [$tag] $message"

        Log.d(tag, message)

        val currentList = _logLines.value.toMutableList()
        currentList.add(logLine)
        if (currentList.size > 500) {
            currentList.removeAt(0)
        }
        _logLines.value = currentList

        try {
            val file = activeLogFile ?: return
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val oldFile = File(file.parentFile, "debug_log_old.txt")
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
            }
            file.appendText("$logLine\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log line to file", e)
        }
    }

    fun getLogFilePath(): String {
        return activeLogFile?.absolutePath ?: "N/A"
    }

    fun getLogFileText(): String {
        return try {
            if (!isEnabled) return "调试日志已禁用"
            activeLogFile?.readText() ?: ""
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clearLogs() {
        _logLines.value = emptyList()
        try {
            activeLogFile?.delete()
            activeLogFile?.createNewFile()
            if (isEnabled) {
                log("System", "=== Log Cleared ===")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }
}
