package com.cloudchat.utils

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cloudchat.model.MessageType
import com.cloudchat.repository.ChatRepository
import com.cloudchat.service.VoiceRecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object VoiceRecordingManager {

    var isRecording by mutableStateOf(false)
        private set

    var isRecordingInBackground by mutableStateOf(false)
        private set

    var recordStartTime by mutableLongStateOf(0L)
        private set

    var elapsedSeconds by mutableIntStateOf(0)
        private set

    var currentAmplitude by mutableFloatStateOf(0f)
        private set

    private var recordFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    var currentFolderId: String? = null
    var isFromShortcut: Boolean = false
    var activeRepository: ChatRepository? = null

    fun startRecording(context: Context, chatRepository: ChatRepository? = null, folderId: String? = null, isShortcut: Boolean = false) {
        if (isRecording) return
        
        currentFolderId = folderId
        isFromShortcut = isShortcut
        if (chatRepository != null) {
            activeRepository = chatRepository
        }

        val dir = File(context.cacheDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "voice_${System.currentTimeMillis()}.mp4")
        recordFile = file
        recordStartTime = System.currentTimeMillis()
        elapsedSeconds = 0
        currentAmplitude = 0f

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            isRecordingInBackground = false

            startTimerJob()
        } catch (e: Exception) {
            Log.e("VoiceRecordingManager", "Failed to start recorder: ${e.message}", e)
            Toast.makeText(context, "无法启动录音设备", Toast.LENGTH_SHORT).show()
            file.delete()
            resetState()
        }
    }

    private fun startTimerJob() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isRecording) {
                try {
                    val durationMs = System.currentTimeMillis() - recordStartTime
                    elapsedSeconds = (durationMs / 1000).toInt()

                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    if (maxAmp > 0) {
                        val rawAmp = (maxAmp.toFloat() / 15000f).coerceIn(0f, 1f)
                        currentAmplitude = kotlin.math.sqrt(rawAmp)
                    }
                } catch (e: Exception) {
                    // ignore
                }
                delay(50)
            }
        }
    }

    fun moveToBackground(context: Context) {
        if (!isRecording) return
        isRecordingInBackground = true
        startForegroundService(context)
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }

    fun restoreToForeground(context: Context) {
        if (!isRecording) return
        isRecordingInBackground = false
    }

    private fun startForegroundService(context: Context) {
        try {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = VoiceRecordingService.ACTION_START_FOREGROUND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("VoiceRecordingManager", "Failed to start VoiceRecordingService: ${e.message}")
        }
    }

    private fun stopForegroundService(context: Context) {
        try {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = VoiceRecordingService.ACTION_STOP_FOREGROUND
            }
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e("VoiceRecordingManager", "Failed to stop VoiceRecordingService: ${e.message}")
        }
    }

    fun stopAndSend(
        context: Context,
        chatRepository: ChatRepository? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val recorder = mediaRecorder
        val file = recordFile
        val folderId = currentFolderId
        val repo = chatRepository ?: activeRepository ?: ChatRepository(context.applicationContext)

        stopForegroundService(context)
        timerJob?.cancel()
        timerJob = null

        if (recorder != null) {
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.e("VoiceRecordingManager", "Stop recorder error: ${e.message}")
            } finally {
                recorder.release()
            }
        }
        mediaRecorder = null

        val durationMs = System.currentTimeMillis() - recordStartTime
        resetState()

        if (file == null || durationMs < 1000) {
            Toast.makeText(context, "录音时间太短", Toast.LENGTH_SHORT).show()
            file?.delete()
            onComplete?.invoke()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = file.inputStream()
                repo.sendMessage(
                    content = file.name,
                    type = MessageType.AUDIO,
                    inputStream = inputStream,
                    fileName = file.name,
                    localUri = Uri.fromFile(file).toString(),
                    folderId = folderId
                )
                scope.launch(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e("VoiceRecordingManager", "Send voice message failed: ${e.message}", e)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "发送语音失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                }
            }
        }
    }

    fun cancelRecording(context: Context, onComplete: (() -> Unit)? = null) {
        val recorder = mediaRecorder
        val file = recordFile

        stopForegroundService(context)
        timerJob?.cancel()
        timerJob = null

        if (recorder != null) {
            try {
                recorder.stop()
            } catch (e: Exception) {
                // ignore
            } finally {
                recorder.release()
            }
        }
        mediaRecorder = null
        file?.delete()
        recordFile = null

        resetState()
        onComplete?.invoke()
    }

    private fun resetState() {
        isRecording = false
        isRecordingInBackground = false
        recordStartTime = 0L
        elapsedSeconds = 0
        currentAmplitude = 0f
        mediaRecorder = null
        recordFile = null
        activeRepository = null
    }
}
