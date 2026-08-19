package com.cloudchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cloudchat.MainActivity
import com.cloudchat.R
import com.cloudchat.utils.VoiceRecordingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceRecordingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FOREGROUND -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND_NOW -> {
                val repo = (applicationContext as? com.cloudchat.CloudChatApp)?.chatRepository
                if (repo != null) {
                    VoiceRecordingManager.stopAndSend(applicationContext, repo)
                }
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_NOW -> {
                VoiceRecordingManager.cancelRecording(applicationContext)
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                createNotificationChannel()
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
                startPeriodicNotificationUpdate()
            }
        }
        return START_STICKY
    }

    private fun startPeriodicNotificationUpdate() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (VoiceRecordingManager.isRecording) {
                val notification = buildNotification()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
                delay(1000)
            }
            stopForeground(true)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val seconds = VoiceRecordingManager.elapsedSeconds
        val timeText = String.format("%02d:%02d", seconds / 60, seconds % 60)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_RESTORE_RECORDING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val sendIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_SEND_NOW
        }
        val sendPendingIntent = PendingIntent.getService(
            this, 1, sendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cancelIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_CANCEL_NOW
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val iconRes = android.R.drawable.ic_btn_speak_now

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CloudChat 正在后台录音...")
            .setContentText("已录制: $timeText (点击唤回界面)")
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "发送", sendPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台语音录音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在后台保持语音录音不被终止"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "voice_recording_channel"
        const val NOTIFICATION_ID = 10086
        const val ACTION_START_FOREGROUND = "com.cloudchat.action.START_RECORDING_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.cloudchat.action.STOP_RECORDING_FOREGROUND"
        const val ACTION_RESTORE_RECORDING = "com.cloudchat.action.RESTORE_RECORDING"
        const val ACTION_SEND_NOW = "com.cloudchat.action.SEND_NOW"
        const val ACTION_CANCEL_NOW = "com.cloudchat.action.CANCEL_NOW"
    }
}
