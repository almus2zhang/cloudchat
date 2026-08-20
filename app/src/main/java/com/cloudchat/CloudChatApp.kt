package com.cloudchat

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cloudchat.utils.NetworkUtils

import coil.decode.VideoFrameDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.cloudchat.utils.DebugLogger

class CloudChatApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        installCrashLogger()
    }

    /**
     * 安装全局未捕获异常处理器：把崩溃堆栈写到外部文件目录，
     * 无需任何权限即可写入，方便无 adb 时远程排查。
     * 日志文件路径：Android/data/com.cloudchat/files/crash_logs/crash_<时间>.txt
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(getExternalFilesDir(null), "crash_logs")
                dir.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logFile = File(dir, "crash_$ts.txt")

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val content = buildString {
                    appendLine("CloudChat Crash Log")
                    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Package: $packageName")
                    appendLine()
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine("Stack Trace:")
                    appendLine(sw.toString())

                    // 记录 cause 链
                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && depth < 10) {
                        appendLine()
                        appendLine("Caused by ($depth): ${cause.javaClass.name}: ${cause.message}")
                        val csw = StringWriter()
                        cause.printStackTrace(PrintWriter(csw))
                        appendLine(csw.toString())
                        cause = cause.cause
                        depth++
                    }
                }
                logFile.writeText(content)
                Log.e("CloudChatCrash", "崩溃日志已写入: ${logFile.absolutePath}\n$content")
            } catch (e: Exception) {
                Log.e("CloudChatCrash", "写入崩溃日志失败", e)
            } finally {
                // 交给系统默认处理器（会弹出崩溃对话框或直接退出）
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                NetworkUtils.getUnsafeOkHttpClient().build()
            }
            .components {
                add(VideoFrameDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)   // 内存缓存 25%，减少滚动时重复解码
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.25)   // 磁盘缓存 25%（原 2% 太小，滚动时命中率低导致卡顿）
                    .build()
            }
            // 生产环境不开启 Debug 日志（每图打日志会拖慢滚动）
            .crossfade(300)
            .build()
    }
}
