package com.cloudchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudchat.model.MessageType
import com.cloudchat.repository.ChatRepository
import com.cloudchat.repository.SettingsRepository
import com.cloudchat.ui.theme.CloudChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QuickActionActivity : ComponentActivity() {

    private var actionType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionType = intent.getStringExtra("quick_action")

        setContent {
            CloudChatTheme {
                val chatRepository = remember { ChatRepository(this@QuickActionActivity) }
                val scope = rememberCoroutineScope()

                val action = actionType ?: run {
                    finish()
                    return@CloudChatTheme
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            finish()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {} // Consume click inside dialog
                    ) {
                        when (action) {
                            "send_image" -> ImageActionView(chatRepository) { finish() }
                            "send_voice" -> VoiceActionView(chatRepository) { finish() }
                            "send_text" -> TextActionView(chatRepository) { finish() }
                            else -> LaunchedEffect(Unit) { finish() }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImageActionView(chatRepository: ChatRepository, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNullOrEmpty()) {
                onDismiss()
                return@rememberLauncherForActivityResult
            }
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "正在发送图片...", Toast.LENGTH_SHORT).show()
                }
                uris.forEach { uri ->
                    try {
                        val stream = contentResolver.openInputStream(uri)
                        val name = getFileName(uri)
                        chatRepository.sendMessage(
                            content = name,
                            type = MessageType.IMAGE,
                            inputStream = stream,
                            fileName = name,
                            localUri = uri.toString()
                        )
                    } catch (e: Exception) {
                        Log.e("QuickActionActivity", "Failed to send image", e)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "图片发送成功！", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }

        LaunchedEffect(Unit) {
            imagePickerLauncher.launch("image/*")
        }
    }

    @Composable
    private fun VoiceActionView(chatRepository: ChatRepository, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
        var recordFile by remember { mutableStateOf<File?>(null) }
        var isRecording by remember { mutableStateOf(false) }
        var amplitude by remember { mutableFloatStateOf(0f) }
        var recordStartTime by remember { mutableLongStateOf(0L) }

        var hasAudioPermission by remember {
            mutableStateOf(
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this@QuickActionActivity,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasAudioPermission = granted
            if (!granted) {
                Toast.makeText(applicationContext, "需要麦克风权限以发送语音", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }

        fun startRecording() {
            try {
                val dir = File(cacheDir, "recordings")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "voice_${System.currentTimeMillis()}.mp4")
                recordFile = file
                recordStartTime = System.currentTimeMillis()

                val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(this@QuickActionActivity)
                } else {
                    android.media.MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                mediaRecorder = recorder
                isRecording = true

                scope.launch(Dispatchers.Main) {
                    while (isRecording && mediaRecorder != null) {
                        try {
                            val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                            amplitude = (maxAmp.toFloat() / 32767f).coerceIn(0f, 1f)
                        } catch (e: Exception) {
                            // ignore
                        }
                        delay(80)
                    }
                }
            } catch (e: Exception) {
                Log.e("QuickActionActivity", "Start recorder failed", e)
                Toast.makeText(applicationContext, "录音初始化失败", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }

        fun stopAndSend() {
            val recorder = mediaRecorder ?: return
            mediaRecorder = null
            isRecording = false
            try { recorder.stop() } catch (e: Exception) {} finally { recorder.release() }

            val file = recordFile ?: run { onDismiss(); return }
            val duration = System.currentTimeMillis() - recordStartTime
            if (duration < 1000) {
                Toast.makeText(applicationContext, "录音时间太短", Toast.LENGTH_SHORT).show()
                file.delete()
                onDismiss()
                return
            }

            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "正在发送语音...", Toast.LENGTH_SHORT).show()
                }
                try {
                    val inputStream = file.inputStream()
                    chatRepository.sendMessage(
                        content = file.name,
                        type = MessageType.AUDIO,
                        inputStream = inputStream,
                        fileName = file.name,
                        localUri = Uri.fromFile(file).toString()
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "语音发送成功！", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("QuickActionActivity", "Send voice error", e)
                } finally {
                    withContext(Dispatchers.Main) { onDismiss() }
                }
            }
        }

        fun cancelRecording() {
            val recorder = mediaRecorder
            mediaRecorder = null
            isRecording = false
            try { recorder?.stop() } catch (e: Exception) {} finally { recorder?.release() }
            recordFile?.delete()
            onDismiss()
        }

        LaunchedEffect(Unit) {
            if (!hasAudioPermission) {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                startRecording()
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "快捷发送语音",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Dynamic Volume Amplitude Wave Bar (音量指示条)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(48.dp)
                        .padding(vertical = 4.dp)
                ) {
                    val barCount = 9
                    for (i in 0 until barCount) {
                        val phase = (i + 1) * 0.7f
                        val factor = 0.25f + 0.75f * Math.abs(Math.sin(phase.toDouble() + System.currentTimeMillis() * 0.008)).toFloat()
                        val hFraction = if (isRecording) (amplitude * factor).coerceIn(0.12f, 1f) else 0.12f
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .fillMaxHeight(hFraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isRecording) "🎙️ 正在录音中..." else "准备中...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { cancelRecording() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { stopAndSend() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text("停止录音并发送", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun TextActionView(chatRepository: ChatRepository, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        var textInput by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) {
            delay(100)
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        fun sendText() {
            val text = textInput.trim()
            if (text.isEmpty()) return
            isSending = true
            scope.launch(Dispatchers.IO) {
                try {
                    chatRepository.sendMessage(text)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "消息发送成功！", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("QuickActionActivity", "Send text failed", e)
                } finally {
                    withContext(Dispatchers.Main) { onDismiss() }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "快捷发送文本",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("请输入文本消息...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    maxLines = 6,
                    singleLine = false,
                    enabled = !isSending
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isSending
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { sendText() },
                        modifier = Modifier.weight(1f),
                        enabled = textInput.trim().isNotEmpty() && !isSending
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("发送", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = cursor.getString(idx)
                        if (!n.isNullOrEmpty()) name = n
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return name
    }
}
