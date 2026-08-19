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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudchat.ui.theme.CloudChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 快捷操作 Activity：从桌面快捷方式/Widget 触发。
 * 不自己发送消息（避免独立 ChatRepository 实例导致消息丢失），
 * 而是把数据通过 Intent 传回 MainActivity 统一发送。
 */
class QuickActionActivity : ComponentActivity() {

    private var actionType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionType = intent.getStringExtra("quick_action")

        setContent {
            CloudChatTheme {
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
                            "send_image" -> ImageActionView { finish() }
                            "send_voice" -> VoiceActionView { finish() }
                            "send_text" -> TextActionView { finish() }
                            "send_location" -> LocationActionView { finish() }
                            else -> LaunchedEffect(Unit) { finish() }
                        }
                    }
                }
            }
        }
    }

    /**
     * 把数据通过 Intent 发送给 MainActivity，由它统一处理发送。
     * 消息将进入 MainActivity 的 ChatRepository，不会丢失。
     */
    private fun deliverToMainActivity(data: QuickActionData) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("quick_action_data_type", data.type)
            data.text?.let { putExtra("quick_action_text", it) }
            data.filePath?.let { putExtra("quick_action_file_path", it) }
            data.uri?.let { putExtra("quick_action_uri", it.toString()) }
        }
        startActivity(intent)
        finish()
    }

    @Composable
    private fun ImageActionView(onDismiss: () -> Unit) {
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNullOrEmpty()) {
                onDismiss()
                return@rememberLauncherForActivityResult
            }
            uris.forEach { uri ->
                deliverToMainActivity(QuickActionData(type = "image", uri = uri))
            }
            onDismiss()
        }

        LaunchedEffect(Unit) {
            imagePickerLauncher.launch("image/*")
        }
    }

    @Composable
    private fun VoiceActionView(onDismiss: () -> Unit) {
        val context = this@QuickActionActivity
        val repo = remember { com.cloudchat.repository.ChatRepository(context.applicationContext) }

        var hasAudioPermission by remember {
            mutableStateOf(
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
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

        LaunchedEffect(Unit) {
            if (!hasAudioPermission) {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                com.cloudchat.utils.VoiceRecordingManager.startRecording(context, repo, isShortcut = true)
            }
        }

        val amplitude = com.cloudchat.utils.VoiceRecordingManager.currentAmplitude
        val seconds = com.cloudchat.utils.VoiceRecordingManager.elapsedSeconds
        val timeText = String.format("%02d:%02d", seconds / 60, seconds % 60)

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("语音录音", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = timeText,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                com.cloudchat.ui.VoiceWaveformVisualizer(amplitude = amplitude)

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            com.cloudchat.utils.VoiceRecordingManager.cancelRecording(context) {
                                Toast.makeText(applicationContext, "已取消录音", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(
                        onClick = {
                            com.cloudchat.utils.VoiceRecordingManager.moveToBackground(context)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("后台")
                    }
                    Button(
                        onClick = {
                            com.cloudchat.utils.VoiceRecordingManager.stopAndSend(context, repo) {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("发送", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun TextActionView(onDismiss: () -> Unit) {
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

            // 把文本传给 MainActivity，由它来发送
            deliverToMainActivity(QuickActionData(type = "text", text = text))
            onDismiss()
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

    @Composable
    private fun LocationActionView(onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        var hasPermission by remember {
            mutableStateOf(
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val granted = perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            hasPermission = granted
            if (!granted) {
                Toast.makeText(applicationContext, "发送位置需要定位权限", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }

        fun fetchAndSendLocation() {
            Toast.makeText(applicationContext, "正在获取并发送位置...", Toast.LENGTH_SHORT).show()
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

            val provider = when {
                isNetworkEnabled -> android.location.LocationManager.NETWORK_PROVIDER
                isGpsEnabled -> android.location.LocationManager.GPS_PROVIDER
                else -> null
            }

            if (provider == null) {
                Toast.makeText(applicationContext, "无法获取位置：GPS或网络定位未开启", Toast.LENGTH_SHORT).show()
                onDismiss()
                return
            }

            fun sendLocationObj(location: android.location.Location) {
                scope.launch(Dispatchers.IO) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    var addressText = "纬度: ${location.latitude}, 经度: ${location.longitude}"
                    try {
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val sb = StringBuilder()
                            address.adminArea?.let { sb.append(it) }
                            val locality = address.locality
                            if (locality != null && !sb.contains(locality)) {
                                sb.append(locality)
                            }
                            address.subLocality?.let { sb.append(it) }
                            address.thoroughfare?.let { sb.append(it) }
                            address.subThoroughfare?.let { sb.append(it) }
                            addressText = if (sb.isNotEmpty()) sb.toString() else (address.getAddressLine(0) ?: addressText)
                        }
                    } catch (e: Exception) {
                        Log.e("QuickActionActivity", "Geocoder failed", e)
                    }

                    // 把位置文本传给 MainActivity，由它来发送
                    withContext(Dispatchers.Main) {
                        deliverToMainActivity(QuickActionData(type = "text", text = "[位置] $addressText"))
                        onDismiss()
                    }
                }
            }

            try {
                val lastLoc = locationManager.getLastKnownLocation(provider)
                if (lastLoc != null) {
                    sendLocationObj(lastLoc)
                } else {
                    locationManager.requestSingleUpdate(provider, object : android.location.LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            sendLocationObj(loc)
                        }
                        override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
                        override fun onProviderEnabled(p0: String) {}
                        override fun onProviderDisabled(p0: String) {}
                    }, null)
                }
            } catch (e: SecurityException) {
                Toast.makeText(applicationContext, "定位失败：无权限", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }

        LaunchedEffect(hasPermission) {
            if (!hasPermission) {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchAndSendLocation()
            }
        }
    }
}

/**
 * 快捷操作产生的数据，由 QuickActionActivity 传给 MainActivity。
 * MainActivity 使用自己的 ChatRepository 实例统一发送。
 */
data class QuickActionData(
    val type: String,       // "text" | "image" | "voice"
    val text: String? = null,
    val filePath: String? = null,
    val uri: Uri? = null
)
