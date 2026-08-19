package com.cloudchat.ui

import android.net.Uri
import android.util.Log
import android.content.ContentValues
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlin.math.abs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
// import coil.request.ImageRequest (Not needed since using global interceptor)
import com.cloudchat.SharedData
import com.cloudchat.model.ChatMessage
import com.cloudchat.model.MessageStatus
import com.cloudchat.model.MessageType
import com.cloudchat.repository.ChatRepository
import com.cloudchat.repository.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.TimeoutCancellationException
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.snapshotFlow
import java.io.File

val LocalTextSelectionClearKey = compositionLocalOf { 0 }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    sharedData: com.cloudchat.SharedData?,
    quickAction: String? = null,
    onFullScreenToggle: (Boolean) -> Unit,
    onSharedDataHandled: () -> Unit,
    onQuickActionHandled: () -> Unit = {},
    setTopBarActions: (@Composable RowScope.() -> Unit) -> Unit,
    setTopBarTitle: (String) -> Unit,
    setTopBarTitleComposable: (((@Composable () -> Unit)?) -> Unit) = {},
    setTopBarNavigationIcon: ((() -> Unit)?) -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val chatRepository = remember { ChatRepository(context) }
    
    val currentConfig by settingsRepository.currentConfig.collectAsState(initial = null)
    val messages by chatRepository.messages.collectAsState()
    val uploadProgress by chatRepository.uploadProgress.collectAsState()
    val downloadProgress by chatRepository.downloadProgress.collectAsState()
    val activeDownloadIds by chatRepository.activeDownloadIds.collectAsState()
    val syncInterval by chatRepository.syncInterval.collectAsState()
    val isServerConnected by chatRepository.isServerConnected.collectAsState()
    val isSyncing by chatRepository.isSyncing.collectAsState()
    val mediaSyncProgress by chatRepository.mediaSyncProgress.collectAsState()
    val autoDownloadLimit = currentConfig?.autoDownloadLimit ?: (5 * 1024 * 1024L)
    
    var inputText by remember { mutableStateOf("") }
    var isVoiceMode by remember { mutableStateOf(false) }
    var folderStack by remember { mutableStateOf(listOf<String>()) }
    val currentFolderId = folderStack.lastOrNull()
    var searchQuery by remember { mutableStateOf("") }
    var mediaPagerIndex by remember { mutableStateOf<Int?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 范围选择（Shift 模式）：激活后点击任意条目，全选「锚点到该条目」之间
    var rangeSelectActive by remember { mutableStateOf(false) }
    var rangeAnchorId by remember { mutableStateOf<String?>(null) }
    var isAttachmentPanelVisible by remember { mutableStateOf(false) }
    var attachLocationEnabled by remember { mutableStateOf(false) }
    var isPrivacyMode by remember { mutableStateOf(false) }
    var lastPrivacyActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var viewOnlyPrivacyItems by remember { mutableStateOf(false) }
    var showChangePrivacyPasswordDialog by remember { mutableStateOf(false) }
    val sharedPrefs = remember { context.getSharedPreferences("cloudchat_privacy", android.content.Context.MODE_PRIVATE) }
    var privacyPin by remember { mutableStateOf(sharedPrefs.getString("pin", "1234") ?: "1234") }
    var deleteSourceAfterSend by remember { mutableStateOf(sharedPrefs.getBoolean("delete_source_after_send", false)) }
    val appMode by settingsRepository.appMode.collectAsState(initial = com.cloudchat.model.AppMode.NOT_SET)
    val isSecurityAuthenticated by chatRepository.isSecurityAuthenticated.collectAsState()
    var showSecurityOverlay by remember { mutableStateOf(false) }

    // --- Dialog and Action States ---
    var showDeleteMessagesConfirmDialog by remember { mutableStateOf(false) }
    var showPackFolderDialog by remember { mutableStateOf(false) }
    var showUnpackFolderConfirmDialog by remember { mutableStateOf(false) }
    var showChooseParentFolderDialog by remember { mutableStateOf(false) }
    var showMoveIntoFolderDialog by remember { mutableStateOf(false) }
    var folderAnnotation by remember { mutableStateOf("") }
    var showRenameFolderDialog by remember { mutableStateOf(false) }
    var renameFolderText by remember { mutableStateOf("") }
    var renameTargetFolderId by remember { mutableStateOf<String?>(null) }
    var showEditTextDialog by remember { mutableStateOf(false) }
    var showEditCaptionDialog by remember { mutableStateOf(false) }
    var editingTargetMessage by remember { mutableStateOf<com.cloudchat.model.ChatMessage?>(null) }
    var isMediaSyncing by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    // --- Quick Action States ---
    var showQuickTextDialog by remember { mutableStateOf(false) }
    var quickTextInput by remember { mutableStateOf("") }
    var showQuickVoiceDialog by remember { mutableStateOf(false) }
    // --- Selection and Haptic States ---
    var textSelectionClearKey by remember { mutableStateOf(0) }
    var isTextSelected by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // —— 移动模式：上传成功后删除源文件（图库需用户授权确认，避免误删）——
    val sourceDeleteQueue = remember { mutableStateListOf<Uri>() }
    val sourceDeleteLauncher = remember {
        mutableStateOf<androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>?>(null)
    }

    // 把图库返回的 SAF 代理 URI（content://com.android.providers.media.documents/...）
    // 解析为真正的 MediaStore URI（content://media/external/...），createWriteRequest 才能生效
    fun resolveMediaStoreUri(uri: Uri): Uri {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size == 2) {
                    val type = split[0]
                    val id = split[1].toLongOrNull() ?: return uri
                    val contentUri = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    return ContentUris.withAppendedId(contentUri, id)
                }
            } catch (e: Exception) {
                Log.w("MainScreen", "Failed to resolve media document uri", e)
            }
        }
        return uri
    }

    fun advanceSourceDelete() {
        val uri = sourceDeleteQueue.firstOrNull() ?: return
        when (uri.scheme) {
            "file" -> {
                try { File(uri.path ?: "").delete() }
                catch (e: Exception) { Log.w("MainScreen", "Failed to delete source (file)", e) }
                sourceDeleteQueue.removeAt(0)
                advanceSourceDelete()
            }
            "content" -> {
                // 有「所有文件访问权限」时直接删除，跳过系统删除确认框
                val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    android.os.Environment.isExternalStorageManager()
                if (hasAllFilesAccess) {
                    val realUri = resolveMediaStoreUri(uri)
                    try { context.contentResolver.delete(realUri, null, null) }
                    catch (e: Exception) { Log.w("MainScreen", "Failed to delete source (content)", e) }
                    sourceDeleteQueue.removeAt(0)
                    advanceSourceDelete()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val realUri = resolveMediaStoreUri(uri)
                    try {
                        val req = MediaStore.createWriteRequest(context.contentResolver, listOf(realUri))
                        sourceDeleteLauncher.value?.launch(IntentSenderRequest.Builder(req.intentSender).build())
                    } catch (e: Exception) {
                        // 部分 content URI（如 SAF 文档）不支持 createWriteRequest，回退直接删除
                        Log.w("MainScreen", "createWriteRequest failed, fallback to direct delete", e)
                        try { context.contentResolver.delete(realUri, null, null) }
                        catch (e2: Exception) { Log.w("MainScreen", "Fallback delete failed", e2) }
                        sourceDeleteQueue.removeAt(0)
                        advanceSourceDelete()
                    }
                } else {
                    try { context.contentResolver.delete(uri, null, null) }
                    catch (e: Exception) { Log.w("MainScreen", "Failed to delete source (content)", e) }
                    sourceDeleteQueue.removeAt(0)
                    advanceSourceDelete()
                }
            }
        }
    }

    sourceDeleteLauncher.value = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = sourceDeleteQueue.firstOrNull()
        if (uri != null) {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val realUri = resolveMediaStoreUri(uri)
                try { context.contentResolver.delete(realUri, null, null) }
                catch (e: Exception) { Log.w("MainScreen", "Failed to delete source (content)", e) }
            } else {
                Log.i("MainScreen", "User denied deleting source: $uri")
            }
            sourceDeleteQueue.removeAt(0)
            advanceSourceDelete()
        }
    }

    LaunchedEffect(chatRepository) {
        chatRepository.sourceReadyToDelete.collect { uriStr ->
            sourceDeleteQueue.add(Uri.parse(uriStr))
            if (sourceDeleteQueue.size == 1) advanceSourceDelete()
        }
    }
    
    // 服务器 history 异常冲突弹窗
    var historyConflictEvent by remember { mutableStateOf<com.cloudchat.repository.ChatRepository.HistoryConflictEvent?>(null) }
    LaunchedEffect(chatRepository) {
        chatRepository.historyConflict.collect { event ->
            historyConflictEvent = event
        }
    }
    
    if (historyConflictEvent != null) {
        val event = historyConflictEvent!!
        AlertDialog(
            onDismissRequest = { historyConflictEvent = null },
            title = { Text("聊天记录冲突") },
            text = { Text(event.message) },
            confirmButton = {
                TextButton(onClick = {
                    // 用本地记录覆盖服务器
                    historyConflictEvent = null
                    scope.launch {
                        chatRepository.forceUploadLocalHistory()
                    }
                }) { Text("用本地覆盖服务器") }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        // 忽略冲突，本次会话内不再重复提示
                        historyConflictEvent = null
                        chatRepository.suppressHistoryConflict()
                    }) { Text("忽略") }
                    TextButton(onClick = {
                        // 重新检查服务器记录（先清除忽略标志）
                        historyConflictEvent = null
                        chatRepository.clearHistoryConflictSuppression()
                        scope.launch {
                            chatRepository.refreshHistoryFromCloud()
                        }
                    }) { Text("重新检查") }
                    TextButton(onClick = {
                        // 清空本地记录
                        historyConflictEvent = null
                        chatRepository.suppressHistoryConflict()
                        scope.launch {
                            chatRepository.clearLocalHistory()
                        }
                    }) { Text("清空本地记录") }
                }
            }
        )
    }
    
    // 上传进度弹窗
    val uploadSyncProgress by chatRepository.uploadProgressPercent.collectAsState()
    val uploadSyncText by chatRepository.uploadProgressText.collectAsState()
    if (uploadSyncProgress >= 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("同步记录") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (uploadSyncProgress in 1..99) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(uploadSyncText)
                }
            },
            confirmButton = {
                if (uploadSyncProgress == 100 || uploadSyncProgress == -1) {
                    TextButton(onClick = {
                        chatRepository.resetUploadProgress()
                    }) { Text("确定") }
                }
            }
        )
    }

    var activeCategory by remember { mutableStateOf("all") } // "all" or "diary"
    var diaryFiles by remember { mutableStateOf<List<com.cloudchat.repository.DiaryFileItem>>(emptyList()) }
    var isLoadingDiaryFiles by remember { mutableStateOf(false) }
    var showDiaryGenerateDialog by remember { mutableStateOf(false) }
    var diaryGenerateTargetIds by remember { mutableStateOf<Set<String>?>(null) }
    var diaryGenerateFolderId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeCategory) {
        if (activeCategory == "diary") {
            isLoadingDiaryFiles = true
            diaryFiles = chatRepository.listDiaryFiles()
            isLoadingDiaryFiles = false
        }
    }

    LaunchedEffect(currentFolderId, activeCategory, messages) {
        if (currentFolderId != null) {
            setTopBarTitle("")
            setTopBarTitleComposable(null)
            setTopBarNavigationIcon {
                folderStack = folderStack.dropLast(1)
            }
        } else {
            setTopBarNavigationIcon(null)
            setTopBarTitle(if (activeCategory == "diary") "日记" else "CloudChat")
            setTopBarTitleComposable {
                var showTitleDropdown by remember { mutableStateOf(false) }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showTitleDropdown = true }.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (activeCategory == "diary") "日记" else "CloudChat",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "切换分类",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = showTitleDropdown,
                        onDismissRequest = { showTitleDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("CloudChat", fontWeight = if (activeCategory == "all") FontWeight.Bold else FontWeight.Normal)
                                }
                            },
                            onClick = {
                                activeCategory = "all"
                                showTitleDropdown = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("日记", fontWeight = if (activeCategory == "diary") FontWeight.Bold else FontWeight.Normal)
                                }
                            },
                            onClick = {
                                activeCategory = "diary"
                                showTitleDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            setTopBarTitle("CloudChat")
            setTopBarTitleComposable(null)
            setTopBarNavigationIcon(null)
        }
    }

    var playingMessageId by remember { mutableStateOf<String?>(null) }
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        playingMessageId = null
                    }
                }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    val playAudioMessage: (com.cloudchat.model.ChatMessage) -> Unit = { message ->
        if (playingMessageId == message.id) {
            exoPlayer.stop()
            playingMessageId = null
        } else {
            val localFile = chatRepository.getLocalFile(message.id, message.content)
            if (localFile.exists()) {
                exoPlayer.stop()
                val mediaItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(localFile))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
                playingMessageId = message.id
            } else {
                scope.launch {
                    chatRepository.downloadFileToCache(
                        message.id,
                        message.content,
                        chatRepository.resolveUrl(message.remoteUrl) ?: ""
                    )?.let { file ->
                        exoPlayer.stop()
                        val mediaItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file))
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        playingMessageId = message.id
                    }
                }
            }
        }
    }

    // Handle back button: clear selection / close panel / exit folder / exit privacy mode
    BackHandler(enabled = selectedIds.isNotEmpty() || isAttachmentPanelVisible || isTextSelected || currentFolderId != null || isPrivacyMode) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = emptySet()
            rangeSelectActive = false
            rangeAnchorId = null
        } else if (isTextSelected) {
            textSelectionClearKey++
            isTextSelected = false
        } else if (isAttachmentPanelVisible) {
            isAttachmentPanelVisible = false
        } else if (currentFolderId != null) {
            folderStack = folderStack.dropLast(1)
        } else if (isPrivacyMode) {
            isPrivacyMode = false
            viewOnlyPrivacyItems = false
        }
    }

    // 隐私模式：5 分钟无操作自动退出
    LaunchedEffect(isPrivacyMode) {
        if (isPrivacyMode) {
            lastPrivacyActivity = System.currentTimeMillis()
            while (isPrivacyMode) {
                delay(15_000)
                if (System.currentTimeMillis() - lastPrivacyActivity > 5 * 60 * 1000) {
                    isPrivacyMode = false
                    viewOnlyPrivacyItems = false
                    break
                }
            }
        }
    }


    // --- Voice Recording & Playing States ---
    var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var recordStartTime by remember { mutableLongStateOf(0L) }
    var isRecordingVoiceState by remember { mutableStateOf(false) }
    var currentAmplitude by remember { mutableStateOf(0f) }
    var amplitudeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // --- Permission Launchers ---
    var hasAudioPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            sendNativeLocation(context, chatRepository, scope, folderId = currentFolderId)
        }
    }

    fun startVoiceRecording() {
        val dir = File(context.cacheDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "voice_${System.currentTimeMillis()}.mp4")
        recordFile = file
        recordStartTime = System.currentTimeMillis()
        
        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.media.MediaRecorder(context)
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

        isRecordingVoiceState = true
        currentAmplitude = 0f
        amplitudeJob = scope.launch(Dispatchers.Main) {
            while (mediaRecorder != null) {
                try {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    currentAmplitude = (maxAmp.toFloat() / 32767f).coerceIn(0f, 1f)
                } catch (e: Exception) {
                    // ignore
                }
                delay(100)
            }
        }
    }

    fun stopAndSendVoice() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        isRecordingVoiceState = false
        amplitudeJob?.cancel()
        amplitudeJob = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e("MainScreen", "Stop recorder failed", e)
        } finally {
            recorder.release()
        }
        
        val file = recordFile ?: return
        recordFile = null
        val durationMs = System.currentTimeMillis() - recordStartTime
        
        if (durationMs < 1000) {
            android.widget.Toast.makeText(context, "录音时间太短", android.widget.Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        
        scope.launch {
            val inputStream = file.inputStream()
            chatRepository.sendMessage(
                content = file.name,
                type = MessageType.AUDIO,
                inputStream = inputStream,
                fileName = file.name,
                localUri = Uri.fromFile(file).toString(),
                folderId = currentFolderId
            )
        }
    }

    fun doSendLocation(location: android.location.Location) {
        // Geocoder 必须在主线程执行，否则返回空
        var addressText = "纬度: ${location.latitude}, 经度: ${location.longitude}"
        try {
            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val sb = StringBuilder()
                address.adminArea?.let { sb.append(it) }
                val locality = address.locality
                if (locality != null && !sb.contains(locality)) sb.append(locality)
                address.subLocality?.let { sb.append(it) }
                address.thoroughfare?.let { sb.append(it) }
                address.subThoroughfare?.let { sb.append(it) }
                addressText = if (sb.isNotEmpty()) sb.toString() else (address.getAddressLine(0) ?: addressText)
            }
        } catch (e: Exception) {
            Log.e("MainScreen", "Geocoder failed", e)
        }
        scope.launch {
            chatRepository.sendMessage(
                content = "[位置] $addressText",
                type = MessageType.TEXT,
                folderId = currentFolderId
            )
        }
    }

    fun sendLocation() {
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        val provider = when {
            isNetworkEnabled -> android.location.LocationManager.NETWORK_PROVIDER
            isGpsEnabled -> android.location.LocationManager.GPS_PROVIDER
            else -> null
        }

        if (provider == null) {
            android.widget.Toast.makeText(context, "无法获取位置：GPS或网络定位未开启", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                doSendLocation(lastLoc)
            } else {
                locationManager.requestSingleUpdate(provider, object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        doSendLocation(loc)
                    }
                    override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
                    override fun onProviderEnabled(p0: String) {}
                    override fun onProviderDisabled(p0: String) {}
                }, null)
            }
        } catch (e: SecurityException) {
            android.widget.Toast.makeText(context, "定位失败：无权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelVoiceRecording() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        isRecordingVoiceState = false
        amplitudeJob?.cancel()
        amplitudeJob = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            // ignore
        } finally {
            recorder.release()
        }
        recordFile?.delete()
        recordFile = null
    }

    LaunchedEffect(mediaPagerIndex) {
        onFullScreenToggle(mediaPagerIndex != null)
    }

    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    // Inject search and sync icons into TopAppBar
    LaunchedEffect(isSearchActive, searchQuery, syncInterval, isServerConnected, isPrivacyMode, viewOnlyPrivacyItems, currentFolderId, isSyncing) {
        setTopBarActions {
            TopBarActionsContent(
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                syncInterval = syncInterval,
                isServerConnected = isServerConnected,
                isPrivacyMode = isPrivacyMode,
                viewOnlyPrivacyItems = viewOnlyPrivacyItems,
                currentFolderId = currentFolderId,
                isSyncing = isSyncing,
                chatRepository = chatRepository,
                scope = scope,
                searchFocusRequester = searchFocusRequester,
                messages = messages,
                onSearchQueryChange = { searchQuery = it },
                onSearchActiveChange = { isSearchActive = it },
                onViewOnlyPrivacyItemsChange = { viewOnlyPrivacyItems = it },
                onShowChangePrivacyPasswordDialog = { showChangePrivacyPasswordDialog = true },
                onPrivacyModeChange = { isPrivacyMode = it },
                onShowCalendarDialog = { showCalendarDialog = true },
                onRenameFolder = { id, name -> renameTargetFolderId = id; renameFolderText = name; showRenameFolderDialog = true },
                onGenerateFolderDiary = { id -> diaryGenerateFolderId = id; diaryGenerateTargetIds = null; showDiaryGenerateDialog = true }
            )
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val displayedMessages = remember(messages, searchQuery, isPrivacyMode, viewOnlyPrivacyItems, currentFolderId, activeCategory) {
        val filtered = messages.filter {
            if (it.isDeleted) return@filter false
            val matchesPrivacy = if (isPrivacyMode) {
                if (viewOnlyPrivacyItems) it.isHidden == true else true
            } else {
                it.isHidden != true
            }
            if (!matchesPrivacy) return@filter false

            if (currentFolderId != null) {
                if (it.folderId != currentFolderId) return@filter false
            } else {
                if (!it.folderId.isNullOrEmpty()) return@filter false
            }

            if (activeCategory == "diary") {
                val isDiary = it.categories?.any { c -> c.equals("diary", ignoreCase = true) || c == "日记" } == true
                if (!isDiary) return@filter false
            }

            true
        }
        if (searchQuery.isBlank()) filtered
        else filtered.filter { 
            it.content.contains(searchQuery, ignoreCase = true) || 
            it.sender.contains(searchQuery, ignoreCase = true)
        }
    }

    val mediaMessages = remember(displayedMessages) {
        displayedMessages.filter { (it.type == MessageType.IMAGE || it.type == MessageType.VIDEO) && !it.isDeleted }
    }

    // —— 范围选择逻辑 ——
    fun toggleRangeSelect() {
        if (rangeSelectActive) {
            rangeSelectActive = false
            rangeAnchorId = null
            return
        }
        if (selectedIds.isEmpty()) return
        val anchor = displayedMessages.firstOrNull { selectedIds.contains(it.id) }
        if (anchor == null) return
        rangeAnchorId = anchor.id
        rangeSelectActive = true
    }

    fun selectRangeTo(targetId: String) {
        val anchorId = rangeAnchorId
        if (anchorId == null) {
            rangeSelectActive = false
            selectedIds = if (selectedIds.contains(targetId)) selectedIds - targetId else selectedIds + targetId
            return
        }
        val anchorIdx = displayedMessages.indexOfFirst { it.id == anchorId }
        val targetIdx = displayedMessages.indexOfFirst { it.id == targetId }
        if (anchorIdx == -1 || targetIdx == -1) {
            rangeSelectActive = false
            rangeAnchorId = null
            return
        }
        val lo = minOf(anchorIdx, targetIdx)
        val hi = maxOf(anchorIdx, targetIdx)
        selectedIds = displayedMessages.subList(lo, hi + 1).map { it.id }.toSet()
        rangeSelectActive = false
        rangeAnchorId = null
    }


    LaunchedEffect(currentConfig, appMode) {
        currentConfig?.let {
            chatRepository.updateConfig(it, appMode)
            
            if (appMode == com.cloudchat.model.AppMode.FULL) {
                chatRepository.checkSecurityAuth()
            }
        }
    }

    LaunchedEffect(isSecurityAuthenticated, appMode) {
        showSecurityOverlay = (appMode == com.cloudchat.model.AppMode.FULL && !isSecurityAuthenticated)
    }

    val handleUris = { uris: List<Uri> ->
        uris.forEach { uri ->
            scope.launch {
                try {
                    val name = getFileName(context, uri)
                    val stream = context.contentResolver.openInputStream(uri)
                    val type = determineMessageType(context, uri, name)
                    val address = if (attachLocationEnabled) fetchAddressQuickly(context) else null
                    chatRepository.sendMessage(
                        content = name, 
                        type = type, 
                        inputStream = stream, 
                        fileName = name,
                        localUri = uri.toString(),
                        locationAddress = address,
                        folderId = currentFolderId,
                        deleteSourceFile = deleteSourceAfterSend && type == com.cloudchat.model.MessageType.IMAGE
                    )
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to open file", e)
                }
            }
        }
    }

    val multimediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = handleUris
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = handleUris
    )

    LaunchedEffect(sharedData) {
        sharedData?.let { data ->
            scope.launch {
                data.text?.let { chatRepository.sendMessage(it, folderId = currentFolderId) }
                data.uri?.let { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = getFileName(context, uri)
                    val type = determineMessageType(context, uri, name)
                    chatRepository.sendMessage(name, type, stream, name, uri.toString(), folderId = currentFolderId, deleteSourceFile = deleteSourceAfterSend && type == com.cloudchat.model.MessageType.IMAGE)
                }
                data.uris?.forEach { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = getFileName(context, uri)
                    val type = determineMessageType(context, uri, name)
                    chatRepository.sendMessage(name, type, stream, name, uri.toString(), folderId = currentFolderId, deleteSourceFile = deleteSourceAfterSend && type == com.cloudchat.model.MessageType.IMAGE)
                }
                onSharedDataHandled()
            }
        }
    }

    LaunchedEffect(quickAction) {
        when (quickAction) {
            "send_image" -> {
                multimediaPickerLauncher.launch("image/*")
                onQuickActionHandled()
            }
            "send_voice" -> {
                if (!hasAudioPermission) {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    startVoiceRecording()
                    showQuickVoiceDialog = true
                }
                onQuickActionHandled()
            }
            "send_text" -> {
                quickTextInput = ""
                showQuickTextDialog = true
                onQuickActionHandled()
            }
            "send_location" -> {
                sendLocation()
                onQuickActionHandled()
            }
        }
    }

    val isQuickDialogShowing = showQuickTextDialog || showQuickVoiceDialog
    Box(modifier = Modifier.fillMaxSize()
        .graphicsLayer { alpha = if (isQuickDialogShowing) 0f else 1f }
        .pointerInput(isPrivacyMode) {
        if (isPrivacyMode) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    lastPrivacyActivity = System.currentTimeMillis()
                }
            }
        }
    }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (selectedIds.isNotEmpty()) {
                            selectedIds = emptySet()
                        }
                        if (isTextSelected) {
                            textSelectionClearKey++
                            isTextSelected = false
                        }
                        keyboardController?.hide()
                    }
                }
        ) {

            // Folder breadcrumb: show multi-level path (A -> B -> C), each level clickable
            FolderBreadcrumb(
                folderStack = folderStack,
                messages = messages,
                onNavigateTo = { targetId ->
                    val idx = folderStack.indexOf(targetId)
                    if (idx >= 0) folderStack = folderStack.take(idx + 1)
                },
                onNavigateHome = { folderStack = emptyList() }
            )

            val chatUiItems = remember(displayedMessages) {
                groupMessages(displayedMessages)
            }

            if (showCalendarDialog) {
                CalendarDialog(
                    onDismissRequest = { showCalendarDialog = false },
                    messages = displayedMessages,
                    onSelectDate = { selectedDateStr ->
                        val targetMsg = displayedMessages.find { msg ->
                            if (msg.timestamp <= 0) false
                            else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(msg.timestamp)) == selectedDateStr
                        }
                        if (targetMsg != null) {
                            val targetIndexInUiItems = chatUiItems.indexOfFirst { uiItem ->
                                when (uiItem) {
                                    is ChatUiItem.SingleMessage -> uiItem.message.id == targetMsg.id
                                    is ChatUiItem.ImageGroup -> uiItem.messages.any { m -> m.id == targetMsg.id }
                                }
                            }
                            if (targetIndexInUiItems != -1) {
                                val reversedIndex = (chatUiItems.size - 1 - targetIndexInUiItems).coerceAtLeast(0)
                                scope.launch {
                                    listState.animateScrollToItem(reversedIndex)
                                }
                            }
                        }
                    }
                )
            }

            val dragModifier = if (currentFolderId != null) {
                Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        if (dragAmount > 50) { // Swipe right to exit folder
                            folderStack = folderStack.dropLast(1)
                            change.consume()
                        }
                    }
                }
            } else {
                Modifier
            }

            if (activeCategory == "diary") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "WebDAV 网页日记列表 (${diaryFiles.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { showDiaryGenerateDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "生成日记", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("生成日记", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        isLoadingDiaryFiles = true
                                        diaryFiles = chatRepository.listDiaryFiles()
                                        isLoadingDiaryFiles = false
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        if (isLoadingDiaryFiles) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在加载 WebDAV 日记...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (diaryFiles.isEmpty()) {
                            Text(
                                text = "WebDAV `diary` 目录下暂无 HTML 日记文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                diaryFiles.take(10).forEach { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                try {
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(item.webUrl))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(context, "无法打开链接: ${item.webUrl}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = item.webUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.OpenInNew,
                                                    contentDescription = "打开",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            val ok = chatRepository.deleteDiaryFile(item.name)
                                                            if (ok) {
                                                                diaryFiles = chatRepository.listDiaryFiles()
                                                                android.widget.Toast.makeText(context, "已删除日记页面", android.widget.Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                android.widget.Toast.makeText(context, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "删除",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showDiaryGenerateDialog) {
                // 确定要归档的消息：多选目标 / 文件夹内所有条目 / 全部，跳过隐藏和删除
                val diaryTargetMessages = remember(messages, diaryGenerateTargetIds, diaryGenerateFolderId, showDiaryGenerateDialog) {
                    when {
                        diaryGenerateTargetIds != null -> messages.filter { it.id in diaryGenerateTargetIds!! }
                        diaryGenerateFolderId != null -> chatRepository.collectFolderMessagesRecursive(diaryGenerateFolderId!!)
                        else -> messages
                    }.filter { !it.isDeleted && it.isHidden != true && it.type != MessageType.FOLDER }
                }
                // 文件夹生成时，默认标题用文件夹名
                val diaryDefaultTitle = diaryGenerateFolderId?.let { folderId ->
                    messages.find { it.id == folderId }?.content ?: "我的日记"
                } ?: "我的日记"
                DiaryGenerateDialog(
                    messages = diaryTargetMessages,
                    defaultTitle = diaryDefaultTitle,
                    onDismiss = {
                        showDiaryGenerateDialog = false
                        diaryGenerateTargetIds = null
                        diaryGenerateFolderId = null
                    },
                    onGenerate = { title, author, templateId, password, coverUri, onProgress ->
                        chatRepository.generateDiary(
                            title, author, templateId, password, coverUri, diaryTargetMessages, onProgress,
                            rootFolderId = diaryGenerateFolderId
                        )
                    },
                    onSuccess = {
                        showDiaryGenerateDialog = false
                        diaryGenerateTargetIds = null
                        diaryGenerateFolderId = null
                        scope.launch {
                            diaryFiles = chatRepository.listDiaryFiles()
                        }
                    }
                )
            }

            val isDiaryTemplate = currentConfig?.messageTemplate == "diary"

            // For diary mode: precompute date group headers
            val diaryDateGroups = if (isDiaryTemplate) {
                remember(chatUiItems) {
                    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val headerFmt = SimpleDateFormat("M月d日 EEE", Locale.CHINESE)
                    val grouped = linkedMapOf<String, MutableList<String>>() // date -> list of uiItem ids
                    chatUiItems.forEach { item ->
                        val ts = when (item) {
                            is ChatUiItem.SingleMessage -> item.message.timestamp
                            is ChatUiItem.ImageGroup -> item.messages.firstOrNull()?.timestamp ?: 0L
                        }
                        if (ts > 0) {
                            val key = dayFmt.format(Date(ts))
                            grouped.getOrPut(key) { mutableListOf() }.add(item.id)
                        }
                    }
                    // Map from uiItem id -> header label (only for the first item of each day)
                    val headerMap = mutableMapOf<String, String>()
                    grouped.forEach { (dateKey, ids) ->
                        val count = ids.size
                        val ts = chatUiItems.firstOrNull { it.id == ids.first() }?.let { item ->
                            when (item) {
                                is ChatUiItem.SingleMessage -> item.message.timestamp
                                is ChatUiItem.ImageGroup -> item.messages.firstOrNull()?.timestamp ?: 0L
                            }
                        } ?: 0L
                        val label = if (ts > 0) "${headerFmt.format(Date(ts))} · ${count}条" else "$dateKey · ${count}条"
                        headerMap[ids.first()] = label
                    }
                    headerMap
                }
            } else emptyMap()

            ChatMessageList(
                listState = listState,
                dragModifier = dragModifier,
                chatUiItems = chatUiItems,
                isDiaryTemplate = isDiaryTemplate,
                diaryDateGroups = diaryDateGroups,
                chatRepository = chatRepository,
                context = context,
                currentConfig = currentConfig,
                uploadProgress = uploadProgress,
                downloadProgress = downloadProgress,
                activeDownloadIds = activeDownloadIds,
                autoDownloadLimit = autoDownloadLimit,
                playingMessageId = playingMessageId,
                mediaMessages = mediaMessages,
                selectedIds = selectedIds,
                onSelectionChange = { selectedIds = it },
                onMediaPagerIndexChange = { mediaPagerIndex = it },
                onEnterFolder = { folderStack = folderStack + it },
                onPlayAudio = { playAudioMessage(it) },
                rangeSelectActive = rangeSelectActive,
                onRangeSelect = { targetId -> selectRangeTo(targetId) }
            )


            if (selectedIds.isEmpty()) {
                ChatInputBar(
                    context = context,
                    chatRepository = chatRepository,
                    scope = scope,
                    keyboardController = keyboardController,
                    focusManager = focusManager,
                    sharedPrefs = sharedPrefs,
                    currentFolderId = currentFolderId,
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    isVoiceMode = isVoiceMode,
                    onVoiceModeChange = { isVoiceMode = it },
                    isAttachmentPanelVisible = isAttachmentPanelVisible,
                    onAttachmentPanelVisibleChange = { isAttachmentPanelVisible = it },
                    attachLocationEnabled = attachLocationEnabled,
                    onAttachLocationEnabledChange = { attachLocationEnabled = it },
                    hasAudioPermission = hasAudioPermission,
                    hasLocationPermission = hasLocationPermission,
                    deleteSourceAfterSend = deleteSourceAfterSend,
                    onDeleteSourceAfterSendChange = { deleteSourceAfterSend = it },
                    privacyPin = privacyPin,
                    onPrivacyModeChange = { isPrivacyMode = it },
                    onViewOnlyPrivacyItemsChange = { viewOnlyPrivacyItems = it },
                    audioPermissionLauncher = audioPermissionLauncher,
                    locationPermissionLauncher = locationPermissionLauncher,
                    filePickerLauncher = filePickerLauncher,
                    onShowImagePicker = { showImagePicker = true },
                    startVoiceRecording = ::startVoiceRecording,
                    stopAndSendVoice = ::stopAndSendVoice,
                    cancelVoiceRecording = ::cancelVoiceRecording
                )
            } else {
                // Selection Toolbar replaces the input bar during multi-select
                SelectionToolbar(
                    selectedIds = selectedIds,
                    messages = messages,
                    currentFolderId = currentFolderId,
                    isPrivacyMode = isPrivacyMode,
                    context = context,
                    chatRepository = chatRepository,
                    scope = scope,
                    onSelectionChange = { selectedIds = it },
                    onRenameFolder = { id, name -> renameTargetFolderId = id; renameFolderText = name; showRenameFolderDialog = true },
                    onEditMessage = { msg ->
                        editingTargetMessage = msg
                        if (msg.type == MessageType.TEXT) showEditTextDialog = true else showEditCaptionDialog = true
                    },
                    onPackFolder = { existingFolderName ->
                        folderAnnotation = existingFolderName
                        showPackFolderDialog = true
                    },
                    onChooseParentFolder = { showChooseParentFolderDialog = true },
                    onUnpack = { showUnpackFolderConfirmDialog = true },
                    onMoveIntoFolder = { showMoveIntoFolderDialog = true },
                    onGenerateDiary = { ids ->
                        diaryGenerateTargetIds = ids
                        showDiaryGenerateDialog = true
                    },
                    onDelete = { showDeleteMessagesConfirmDialog = true },
                    rangeSelectActive = rangeSelectActive,
                    onToggleRangeSelect = { toggleRangeSelect() }
                )
            }
        }

        // WeChat Style Media Pager
        AnimatedVisibility(
            visible = mediaPagerIndex != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            mediaPagerIndex?.let { initialIndex ->
                MediaPagerOverlay(
                    mediaMessages = mediaMessages,
                    initialIndex = initialIndex,
                    chatRepository = chatRepository,
                    onDismiss = { mediaPagerIndex = null },
                    onSelectIndex = { mediaPagerIndex = it },
                    selectedIds = selectedIds,
                    onToggleSelect = { clickedMsg ->
                        selectedIds = if (selectedIds.contains(clickedMsg.id)) {
                            selectedIds - clickedMsg.id
                        } else {
                            selectedIds + clickedMsg.id
                        }
                    }
                )
            }
        }

        if (isRecordingVoiceState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.size(160.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Recording",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth().height(32.dp)
                        ) {
                            val barCount = 7
                            for (i in 0 until barCount) {
                                val factor = when (i) {
                                    0, 6 -> 0.3f
                                    1, 5 -> 0.6f
                                    2, 4 -> 0.9f
                                    else -> 1.0f
                                }
                                val barHeight = (currentAmplitude * 32.dp.value * factor).coerceAtLeast(4f)
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(4.dp)
                                        .height(barHeight.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Recording...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (showSecurityOverlay) {
            SecurityOverlay(chatRepository = chatRepository)
        }

        if (showImagePicker) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showImagePicker = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ImagePickerScreen(
                        onResult = { selectedItems, isMove ->
                            showImagePicker = false
                            scope.launch {
                                selectedItems.forEach { item ->
                                    launch {
                                        try {
                                            val name = getFileName(context, item.uri)
                                            val stream = context.contentResolver.openInputStream(item.uri)
                                            val type = determineMessageType(context, item.uri, name)
                                            val address = if (attachLocationEnabled) fetchAddressQuickly(context) else null
                                            chatRepository.sendMessage(
                                                content = name, 
                                                type = type, 
                                                inputStream = stream, 
                                                fileName = name,
                                                localUri = item.uri.toString(),
                                                locationAddress = address,
                                                folderId = currentFolderId,
                                                deleteSourceFile = isMove
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.e("MainScreen", "Failed to open file", e)
                                        }
                                    }
                                }
                            }
                        },
                        onCancel = {
                            showImagePicker = false
                        }
                    )
                }
            }
        }

        MainScreenDialogs(
            chatRepository = chatRepository,
            showPackFolderDialog = showPackFolderDialog,
            onPackFolderDismiss = { showPackFolderDialog = false },
            folderAnnotation = folderAnnotation,
            onFolderAnnotationChange = { folderAnnotation = it },
            onPackFolderConfirm = { annotation ->
                showPackFolderDialog = false
                scope.launch {
                    val selectedMessages = messages.filter { selectedIds.contains(it.id) }
                    val existingFolder = selectedMessages.find { it.type == MessageType.FOLDER }
                    // 若在文件夹内打包且无已选文件夹，则新建子文件夹挂到当前文件夹下
                    val parentFolderId = if (existingFolder == null) currentFolderId else null
                    chatRepository.packIntoFolder(
                        selectedMessages.filter { it.type != MessageType.FOLDER },
                        annotation,
                        existingFolder?.id,
                        parentFolderId
                    )
                    selectedIds = emptySet()
                }
            },
            showUnpackFolderConfirmDialog = showUnpackFolderConfirmDialog,
            onUnpackConfirmDismiss = { showUnpackFolderConfirmDialog = false },
            onUnpackConfirmConfirm = { recursive ->
                showUnpackFolderConfirmDialog = false
                scope.launch {
                    selectedIds.forEach { id ->
                        val msg = messages.find { it.id == id }
                        if (msg?.type == MessageType.FOLDER) {
                            chatRepository.unpackFolder(id, recursive)
                        }
                    }
                    val nonFolderIds = selectedIds.filter { id ->
                        messages.find { it.id == id }?.type != MessageType.FOLDER
                    }
                    if (nonFolderIds.isNotEmpty()) {
                        chatRepository.removeFromFolder(nonFolderIds)
                    }
                    selectedIds = emptySet()
                }
            },
            isUnpackingFolderObj = selectedIds.any { id -> messages.find { it.id == id }?.type == MessageType.FOLDER },
            selectedCount = selectedIds.size,
            showEditTextDialog = showEditTextDialog,
            onEditTextDismiss = {
                showEditTextDialog = false
                editingTargetMessage = null
            },
            editingTargetMessage = editingTargetMessage,
            onEditTextConfirm = { newText ->
                val target = editingTargetMessage
                if (target != null && newText.isNotBlank()) {
                    scope.launch {
                        chatRepository.editTextMessage(target.id, newText.trim())
                    }
                }
                showEditTextDialog = false
                editingTargetMessage = null
                selectedIds = emptySet()
            },
            showEditCaptionDialog = showEditCaptionDialog,
            onEditCaptionDismiss = {
                showEditCaptionDialog = false
                editingTargetMessage = null
            },
            onEditCaptionConfirm = { newCaption ->
                val target = editingTargetMessage
                if (target != null) {
                    scope.launch {
                        chatRepository.updateMessageCaption(target.id, newCaption.trim())
                    }
                }
                showEditCaptionDialog = false
                editingTargetMessage = null
                selectedIds = emptySet()
            },
            showChangePrivacyPasswordDialog = showChangePrivacyPasswordDialog,
            onChangePrivacyPasswordDismiss = { showChangePrivacyPasswordDialog = false },
            onChangePrivacyPasswordConfirm = { newPin ->
                privacyPin = newPin
                sharedPrefs.edit().putString("pin", privacyPin).apply()
                android.widget.Toast.makeText(context, "密码修改成功", android.widget.Toast.LENGTH_SHORT).show()
                showChangePrivacyPasswordDialog = false
            },
            showRenameFolderDialog = showRenameFolderDialog,
            onRenameFolderDismiss = { showRenameFolderDialog = false },
            renameFolderText = renameFolderText,
            onRenameFolderTextChange = { renameFolderText = it },
            onRenameFolderConfirm = { newName ->
                val folderId = renameTargetFolderId
                if (folderId != null) {
                    scope.launch {
                        chatRepository.renameFolder(folderId, newName)
                    }
                }
                renameTargetFolderId = null
                showRenameFolderDialog = false
            },
            showDeleteMessagesConfirmDialog = showDeleteMessagesConfirmDialog,
            onDeleteConfirmDismiss = { showDeleteMessagesConfirmDialog = false },
            onDeleteConfirmConfirm = {
                scope.launch {
                    chatRepository.deleteMessages(selectedIds.toList())
                    selectedIds = emptySet()
                }
                showDeleteMessagesConfirmDialog = false
            }
        )

        // 选择父文件夹 / 移入文件夹 对话框
        FolderActionDialogs(
            chatRepository = chatRepository,
            messages = messages,
            currentFolderId = currentFolderId,
            selectedIds = selectedIds,
            showChooseParentFolderDialog = showChooseParentFolderDialog,
            onChooseParentDismiss = { showChooseParentFolderDialog = false },
            onChooseParentConfirm = { parentId ->
                showChooseParentFolderDialog = false
                scope.launch {
                    val others = selectedIds.filter { it != parentId }
                    chatRepository.moveIntoFolder(others.toList(), parentId)
                    selectedIds = emptySet()
                }
            },
            showMoveIntoFolderDialog = showMoveIntoFolderDialog,
            onMoveIntoDismiss = { showMoveIntoFolderDialog = false },
            onMoveIntoConfirm = { targetFolderId ->
                showMoveIntoFolderDialog = false
                scope.launch {
                    chatRepository.moveIntoFolder(selectedIds.toList(), targetFolderId)
                    selectedIds = emptySet()
                }
            }
        )

        QuickActionDialogs(
            showQuickTextDialog = showQuickTextDialog,
            quickTextInput = quickTextInput,
            onQuickTextInputChange = { quickTextInput = it },
            onQuickTextSend = { text ->
                scope.launch {
                    chatRepository.sendMessage(text, folderId = currentFolderId)
                }
                showQuickTextDialog = false
            },
            onQuickTextDismiss = { showQuickTextDialog = false },
            showQuickVoiceDialog = showQuickVoiceDialog,
            isRecordingVoiceState = isRecordingVoiceState,
            voiceAmplitude = currentAmplitude,
            onStopAndSendVoice = {
                if (isRecordingVoiceState) {
                    stopAndSendVoice()
                }
                showQuickVoiceDialog = false
            },
            onCancelVoice = {
                if (isRecordingVoiceState) {
                    cancelVoiceRecording()
                }
                showQuickVoiceDialog = false
            }
        )
    }
}

@Composable
fun QuickActionDialogs(
    showQuickTextDialog: Boolean,
    quickTextInput: String,
    onQuickTextInputChange: (String) -> Unit,
    onQuickTextSend: (String) -> Unit,
    onQuickTextDismiss: () -> Unit,
    showQuickVoiceDialog: Boolean,
    isRecordingVoiceState: Boolean,
    voiceAmplitude: Float = 0f,
    onStopAndSendVoice: () -> Unit,
    onCancelVoice: () -> Unit
) {
    if (showQuickTextDialog) {
        AlertDialog(
            onDismissRequest = onQuickTextDismiss,
            title = { Text("发送文本", fontWeight = FontWeight.Bold) },
            text = {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                OutlinedTextField(
                    value = quickTextInput,
                    onValueChange = onQuickTextInputChange,
                    label = { Text("请输入发送内容...") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    maxLines = 6
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = quickTextInput.trim()
                        if (text.isNotEmpty()) {
                            onQuickTextSend(text)
                        }
                    }
                ) {
                    Text("发送")
                }
            },
            dismissButton = {
                TextButton(onClick = onQuickTextDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showQuickVoiceDialog) {
        AlertDialog(
            onDismissRequest = onCancelVoice,
            title = { Text("发送语音", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    Text(
                        text = if (isRecordingVoiceState) "正在录音中..." else "录音就绪",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // 音量条
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(40.dp).padding(vertical = 4.dp)
                    ) {
                        val barCount = 9
                        for (i in 0 until barCount) {
                            val phase = (i + 1) * 0.7f
                            val factor = 0.25f + 0.75f * Math.abs(
                                Math.sin(phase.toDouble() + System.currentTimeMillis() * 0.008)
                            ).toFloat()
                            val hFraction = if (isRecordingVoiceState)
                                (voiceAmplitude * factor).coerceIn(0.12f, 1f) else 0.12f
                            Box(
                                modifier = Modifier
                                    .width(5.dp)
                                    .fillMaxHeight(hFraction)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Mic",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onStopAndSendVoice,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("停止录音并发送")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelVoice) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPagerOverlay(
    mediaMessages: List<ChatMessage>,
    initialIndex: Int,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (ChatMessage) -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaMessages.size })
    var showGrid by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadProgress by chatRepository.downloadProgress.collectAsState()
    
    // Immersive Mode
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        controller?.let {
            it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler { onDismiss() }
    
    // Use Transparent background so that individual pages (ZoomableImage) can control opacity/fade-out
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondBoundsPageCount = 1
        ) { page ->
            val message = mediaMessages[page]
            
            // Track download attempts for this message to prevent loops
            val downloadAttempted = remember(message.id) { mutableStateOf(false) }
            
            // Check file existence reactively
            val progressValue = downloadProgress[message.id]
            val localFile = remember(message.id) {
                chatRepository.getLocalFile(message.id, message.content)
            }
            
            val uriState = remember(message.id, progressValue) {
                val uri = chatRepository.getTransientUri(message.id, message.content)
                    ?: if (localFile.exists()) "file://${localFile.absolutePath}" else null
                Log.d("MediaViewer", "Page $page ${message.id}: URI=$uri progress=$progressValue fileExists=${localFile.exists()}")
                uri
            }
            
            // Only auto-download if this is the CURRENT page being viewed, not pre-loaded pages
            val isCurrentPage = pagerState.currentPage == page
            LaunchedEffect(message.id, isCurrentPage) {
                if (isCurrentPage && uriState == null && !downloadAttempted.value && message.remoteUrl != null) {
                    downloadAttempted.value = true
                    Log.d("MediaViewer", "Auto-download started for ${message.id} (current page)")
                    chatRepository.downloadFileToCache(message.id, message.content, chatRepository.resolveUrl(message.remoteUrl)!!)
                }
            }
            
            // Determine display URI: use local file if exists, otherwise thumbnail, otherwise remote
            val displayUri = uriState ?: chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
            val downloadingProgress = downloadProgress[message.id]
            val isDownloading = downloadingProgress != null && downloadingProgress >= 0 && downloadingProgress < 100
            val showThumbnail = uriState == null && message.thumbnailUrl != null
            
            if (displayUri != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (message.type) {
                        MessageType.IMAGE -> ZoomableImage(
                            uri = displayUri, 
                            isCurrentPage = pagerState.currentPage == page,
                            onTap = onDismiss,
                            onSwipeToNext = { 
                                scope.launch { 
                                    if (pagerState.currentPage < mediaMessages.size - 1)
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                            onSwipeToPrev = {
                                scope.launch {
                                    if (pagerState.currentPage > 0)
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            backgroundUri = chatRepository.resolveUrl(message.thumbnailUrl),
                            isHighRes = uriState != null // Only true when we display the Local File
                        )
                        MessageType.VIDEO -> FullScreenVideoPlayer(
                            uri = displayUri, 
                            active = pagerState.currentPage == page, 
                            onDismiss = onDismiss
                        )
                        else -> {}
                    }
                    
                    // Show download progress overlay when downloading
                    if (isDownloading || showThumbnail) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (showThumbnail) 0.3f else 0f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isDownloading) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        progress = (downloadingProgress ?: 0) / 100f,
                                        modifier = Modifier.size(64.dp),
                                        color = Color.White,
                                        strokeWidth = 4.dp
                                    )
                                    Text(
                                        text = "下载中 ${downloadingProgress}%",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Overlay UI: Only page indicator (Hide for videos)
        val currentMsg = mediaMessages[pagerState.currentPage]
        if (currentMsg.type != MessageType.VIDEO) {
            Text(
                text = "${pagerState.currentPage + 1} / ${mediaMessages.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                fontSize = 14.sp
            )
        }

        // Action Buttons: Only for images
        if (currentMsg.type == MessageType.IMAGE) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(
                    onClick = { showGrid = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.GridView, contentDescription = "Grid", tint = Color.White)
                }
                IconButton(
                    onClick = { 
                        val msg = mediaMessages[pagerState.currentPage]
                        shareMedia(context, chatRepository, msg.id, msg.content, msg.remoteUrl)
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(
                    onClick = { 
                        val msg = mediaMessages[pagerState.currentPage]
                        kotlinx.coroutines.MainScope().launch {
                            saveMediaToGallery(context, chatRepository, msg)
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Save", tint = Color.White)
                }
            }
        }

        // Grid View Overlay
        if (showGrid) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("All Media", color = Color.White, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showGrid = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(mediaMessages.size) { index ->
                            val msg = mediaMessages[index]
                            val uri = chatRepository.getTransientUri(msg.id, msg.content) ?: chatRepository.resolveUrl(msg.remoteUrl)
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(Color.Transparent)
                                    .then(
                                        if (selectedIds.contains(msg.id))
                                            Modifier.border(2.dp, Color(0xFF81C784))
                                        else Modifier
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (selectedIds.isNotEmpty()) {
                                                onToggleSelect(msg)
                                            } else {
                                                scope.launch {
                                                    pagerState.scrollToPage(index)
                                                    onSelectIndex(index)
                                                    showGrid = false
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectedIds.contains(msg.id)) {
                                                onToggleSelect(msg)
                                            }
                                        }
                                    )
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                if (selectedIds.contains(msg.id)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = Color(0xFF81C784),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                if (msg.type == MessageType.VIDEO) {
                                    Icon(
                                        Icons.Default.PlayArrow, 
                                        contentDescription = null, 
                                        tint = Color.White, 
                                        modifier = Modifier.align(Alignment.Center).size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shareMedia(context: android.content.Context, chatRepository: ChatRepository, messageId: String, fileName: String, remoteUrl: String?) {
    val localFile = chatRepository.getLocalFile(messageId, fileName)
    
    if (localFile.exists()) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = androidx.core.content.FileProvider.getUriForFile(context, authority, localFile)
            
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                val extension = localFile.extension.lowercase()
                type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Media"))
        } catch (e: Exception) {
            Log.e("MainScreen", "Failed to share local file", e)
            android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    } else if (remoteUrl != null) {
        // Fallback or Trigger Download
        android.widget.Toast.makeText(context, "Downloading for share...", android.widget.Toast.LENGTH_SHORT).show()
        kotlinx.coroutines.MainScope().launch {
            val file = chatRepository.downloadFileToCache(messageId, fileName, remoteUrl)
            if (file != null && file.exists()) {
                shareMedia(context, chatRepository, messageId, fileName, null) // Recursive call once downloaded
            }
        }
    }
}

private fun shareSelectedMessages(
    context: android.content.Context,
    chatRepository: ChatRepository,
    allMessages: List<ChatMessage>,
    selectedIds: Set<String>
) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        val selectedMsgs = allMessages.filter { it.id in selectedIds }
        if (selectedMsgs.isEmpty()) return@launch

        val textParts = selectedMsgs.filter { it.type == com.cloudchat.model.MessageType.TEXT || (it.content.isNotBlank() && it.type == com.cloudchat.model.MessageType.TEXT) }
            .map { it.content }
            .filter { it.isNotBlank() }

        val fileUris = ArrayList<Uri>()
        val authority = "${context.packageName}.fileprovider"

        selectedMsgs.filter { it.type != com.cloudchat.model.MessageType.TEXT && it.type != com.cloudchat.model.MessageType.FOLDER }.forEach { msg ->
            val localFile = if (msg.remoteUrl != null) {
                chatRepository.downloadFileToCache(msg.id, msg.content, msg.remoteUrl!!)
            } else {
                val f = chatRepository.getLocalFile(msg.id, msg.content)
                if (f.exists()) f else null
            }
            if (localFile != null && localFile.exists() && localFile.length() > 0) {
                try {
                    fileUris.add(androidx.core.content.FileProvider.getUriForFile(context, authority, localFile))
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to get URI for share", e)
                }
            }
        }

        withContext(Dispatchers.Main) {
            try {
                if (fileUris.isNotEmpty()) {
                    val intent = android.content.Intent(if (fileUris.size > 1) android.content.Intent.ACTION_SEND_MULTIPLE else android.content.Intent.ACTION_SEND).apply {
                        if (fileUris.size > 1) {
                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, fileUris)
                        } else {
                            putExtra(android.content.Intent.EXTRA_STREAM, fileUris[0])
                        }
                        if (textParts.isNotEmpty()) {
                            putExtra(android.content.Intent.EXTRA_TEXT, textParts.joinToString("\n\n"))
                        }
                        type = "*/*"
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享至..."))
                } else if (textParts.isNotEmpty()) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, textParts.joinToString("\n\n"))
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享文字"))
                } else {
                    android.widget.Toast.makeText(context, "准备分享文件失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainScreen", "Failed to start share chooser", e)
                android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun saveFileToDownloadDir(context: android.content.Context, chatRepository: ChatRepository, message: ChatMessage) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        try {
            val fileName = message.content
            val localFile = if (message.remoteUrl != null) {
                chatRepository.downloadFileToCache(message.id, fileName, message.remoteUrl!!)
            } else {
                val cached = chatRepository.getLocalFile(message.id, fileName)
                if (cached.exists()) cached else null
            }

            if (localFile != null && localFile.exists()) {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CloudChat")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()

                val cleanName = fileName.replace("^\\d+_".toRegex(), "")
                val targetFile = File(downloadsDir, cleanName)
                localFile.copyTo(targetFile, overwrite = true)

                android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "已保存至 Download/CloudChat/${targetFile.name}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "文件未能就绪，请先等待下载", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainScreen", "Failed to save file to Download/CloudChat", e)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun openFileWithDefaultApp(context: android.content.Context, chatRepository: ChatRepository, message: ChatMessage) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        Log.d("MainScreen", "Attempting to open file: ${message.content}")
        val file = if (message.remoteUrl != null) {
            chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
        } else {
            val fileName = message.content
            val cachedFile = chatRepository.getLocalFile(message.id, fileName)
            if (cachedFile.exists()) {
                cachedFile
            } else {
                val uriStr = chatRepository.getTransientUri(message.id, message.content)
                if (uriStr != null) {
                    val uri = Uri.parse(uriStr)
                    if (uri.scheme == "file") File(uri.path!!)
                    else if (uri.scheme == "content") chatRepository.getLocalFile(message.id, message.content)
                    else null
                } else null
            }
        }

        withContext(Dispatchers.Main) {
            if (file != null && file.exists()) {
                try {
                    val authority = "${context.packageName}.fileprovider"
                    val contentUri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                    
                    val extension = file.extension.lowercase()
                    var mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    
                    if (mimeType == null) {
                        mimeType = when (extension) {
                            "pdf" -> "application/pdf"
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "mp4" -> "video/mp4"
                            "txt" -> "text/plain"
                            "doc", "docx" -> "application/msword"
                            "xls", "xlsx" -> "application/vnd.ms-excel"
                            "apk" -> "application/vnd.android.package-archive"
                            "zip", "rar", "7z" -> "application/zip"
                            else -> context.contentResolver.getType(contentUri)
                        }
                    }
                    
                    if (mimeType == null || mimeType == "application/octet-stream") {
                        mimeType = "*/*"
                    }

                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    
                    try {
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        val chooser = android.content.Intent.createChooser(intent, "选择打开方式 ${file.name}")
                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(chooser)
                    }
                } catch (e: Exception) {
                    Log.e("MainScreen", "Crash opening file", e)
                    android.widget.Toast.makeText(context, "打开文件失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(context, "文件未能就绪或下载失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun saveMediaToGallery(context: android.content.Context, chatRepository: ChatRepository, message: ChatMessage) = withContext(Dispatchers.IO) {
    val fileName = message.content
    val localFile = chatRepository.getLocalFile(message.id, fileName)
    
    val fileToSave = if (localFile.exists()) {
        localFile
    } else if (message.remoteUrl != null) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Downloading to save...", android.widget.Toast.LENGTH_SHORT).show()
        }
        chatRepository.downloadFileToCache(message.id, fileName, message.remoteUrl!!) ?: return@withContext
    } else {
        return@withContext
    }

    if (!fileToSave.exists()) return@withContext

    try {
        val extension = fileToSave.extension.lowercase()
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
        val isVideo = mimeType.startsWith("video/")
        
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/CloudChat" else "Pictures/CloudChat")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (isVideo) {
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, contentValues)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                fileToSave.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Saved to Gallery", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Log.e("MainScreen", "Failed to save to gallery", e)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberAvatarUrl(
    rawAvatar: String?,
    senderName: String?,
    isOutgoing: Boolean,
    chatRepository: ChatRepository,
    currentConfig: com.cloudchat.model.ServerConfig? = null
): String? {
    val avatarName = rawAvatar?.ifEmpty { null }
        ?: if (isOutgoing) currentConfig?.avatarUrl?.ifEmpty { null } else null

    if (avatarName == null) return null
    if (avatarName.startsWith("http://") || avatarName.startsWith("https://") || avatarName.startsWith("file://") || avatarName.startsWith("data:") || avatarName.startsWith("content://")) {
        return avatarName
    }

    var resolvedUrl by remember(avatarName) { mutableStateOf<String?>(null) }
    LaunchedEffect(avatarName) {
        val path = chatRepository.resolveAvatarPath(avatarName)
        resolvedUrl = path
    }
    return resolvedUrl
}

@Composable
fun UserAvatar(
    avatarUrl: String?,
    displayName: String,
    modifier: Modifier = Modifier
        .size(44.dp)
        .clip(RoundedCornerShape(6.dp))
) {
    var hasError by remember(avatarUrl) { mutableStateOf(false) }

    val initialChar = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "U"
    val avatarBgColor = getUserColor(displayName)

    Box(
        modifier = modifier
            .background(if (!avatarUrl.isNullOrBlank() && !hasError) Color.Transparent else avatarBgColor)
            .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank() && !hasError) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { hasError = true }
            )
        } else {
            Text(
                text = initialChar,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DiaryBubble(
    message: ChatMessage,
    progress: Int?,
    chatRepository: ChatRepository,
    isSelected: Boolean,
    autoDownloadLimit: Long,
    downloadProgress: Map<String, Int>,
    isDownloading: Boolean,
    playingMessageId: String?,
    onPlayAudio: (ChatMessage) -> Unit,
    onMediaClick: (ChatMessage) -> Unit,
    onLongClick: () -> Unit
) {
    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))
    val displayName = message.senderName ?: message.sender

    val userAvatarUrl = rememberAvatarUrl(
        rawAvatar = message.senderAvatar,
        senderName = displayName,
        isOutgoing = message.isOutgoing,
        chatRepository = chatRepository
    )

    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = { onMediaClick(message) }, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Left column: Avatar (ALWAYS ON THE LEFT IN DIARY VIEW)
        UserAvatar(
            avatarUrl = userAvatarUrl,
            displayName = displayName,
            modifier = Modifier
                .padding(end = 8.dp, top = 2.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Right column: Name + Time + Content (LEFT ALIGNED)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0xFF999999)
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            when (message.type) {
                MessageType.TEXT -> {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF222222),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                    if (!message.locationAddress.isNullOrBlank()) {
                        Text(text = message.locationAddress, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 2.dp), textAlign = TextAlign.Start)
                    }
                    // SENDING / FAILED 状态标记
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        when (message.status) {
                            MessageStatus.SENDING -> {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("发送中...", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            MessageStatus.FAILED -> {
                                val scope = rememberCoroutineScope()
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "发送失败",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "发送失败，点击重试",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF5252),
                                    modifier = Modifier.clickable {
                                        scope.launch { chatRepository.resendMessage(message.id) }
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                }
                MessageType.IMAGE -> {
                    val localFile = remember(message.id) { chatRepository.getLocalFile(message.id, message.content) }
                    val displayUri = remember(message.id) {
                        chatRepository.getTransientUri(message.id, message.content)
                            ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                            else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                    }
                    val isUploading = message.status == MessageStatus.SENDING && message.status != MessageStatus.SUCCESS && message.status != MessageStatus.FAILED
                    Box(modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5F5F5)), contentAlignment = Alignment.Center) {
                        AsyncImage(model = displayUri, contentDescription = null, modifier = Modifier.widthIn(max = 200.dp).heightIn(max = 240.dp), contentScale = ContentScale.Fit)
                        
                        if (isUploading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (progress != null && progress in 0..100) {
                                        CircularProgressIndicator(
                                            progress = progress / 100f,
                                            modifier = Modifier.size(40.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(40.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                    }
                                    IconButton(
                                        onClick = { chatRepository.cancelUpload(message.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "取消上传",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        } else if (message.status == MessageStatus.FAILED) {
                            val scope = rememberCoroutineScope()
                            val context = LocalContext.current
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(Color.Red.copy(alpha = 0.85f), CircleShape)
                                    .clickable {
                                        scope.launch {
                                            android.widget.Toast.makeText(context, "正在检测服务器并重新发送...", android.widget.Toast.LENGTH_SHORT).show()
                                            chatRepository.resendMessage(message.id)
                                        }
                                    }
                                    .padding(6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "点击重新发送", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }

                        if (message.fileSize > 0) Box(modifier = Modifier.align(Alignment.TopStart)) { FileSizeBadge(message.fileSize) }
                    }
                    if (!message.caption.isNullOrBlank()) {
                        Text(text = message.caption, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 2.dp), textAlign = TextAlign.Start)
                    }
                }
                MessageType.VIDEO -> {
                    val localFile = remember(message.id) { chatRepository.getLocalFile(message.id, message.content) }
                    val displayUri = remember(message.id) {
                        chatRepository.getTransientUri(message.id, message.content)
                            ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                            else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                    }
                    Box(modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).aspectRatio(16 / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                        AsyncImage(model = displayUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.8f)
                        if (progress != null && progress in 0..100) {
                            Box(modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = progress / 100f, modifier = Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                            }
                        } else if (!isCached) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        if (message.videoDuration > 0) {
                            Text(text = formatDuration(message.videoDuration), color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                        }
                        if (message.fileSize > 0) Box(modifier = Modifier.align(Alignment.TopStart)) { FileSizeBadge(message.fileSize) }
                    }
                    if (!message.caption.isNullOrBlank()) {
                        Text(text = message.caption, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 2.dp), textAlign = TextAlign.Start)
                    }
                }
                MessageType.AUDIO -> {
                    val isPlaying = playingMessageId == message.id
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { onPlayAudio(message) }
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeMute, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "${message.videoDuration}s", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                    if (!message.caption.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = message.caption, style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444), textAlign = TextAlign.Start)
                    }
                }
                MessageType.FILE -> {
                    val fileProgress = downloadProgress[message.id]
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.widthIn(max = 220.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                val isLong = message.content.length > 25
                                var isExpanded by remember(message.id) { mutableStateOf(false) }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                        overflow = if (isExpanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (isLong) {
                                        Text(
                                            text = if (isExpanded) "收起" else "展开全文",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { isExpanded = !isExpanded }
                                        )
                                    }
                                    if (message.fileSize > 0) {
                                        Text(text = formatFileSize(message.fileSize), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                            if (fileProgress != null && fileProgress in 0..99) {
                                LinearProgressIndicator(progress = fileProgress / 100f, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 6.dp))
                            }
                        }
                    }
                    if (!message.caption.isNullOrBlank()) {
                        Text(text = message.caption, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 2.dp), textAlign = TextAlign.Start)
                    }
                }
                MessageType.FOLDER -> {
                    FolderBubble(
                        message = message,
                        isSelected = isSelected,
                        onSelectToggle = { onMediaClick(message) },
                        onLongClick = onLongClick
                    )
                }
                else -> {
                    Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF222222), textAlign = TextAlign.Start)
                }
            }
            // 非图片类型（已在图片内部处理）的 SENDING / FAILED 状态
            if (message.type != MessageType.IMAGE && message.type != MessageType.FOLDER) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    when (message.status) {
                        MessageStatus.SENDING -> {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("发送中...", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        MessageStatus.FAILED -> {
                            val scope = rememberCoroutineScope()
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "发送失败",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "发送失败，点击重试",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF5252),
                                modifier = Modifier.clickable {
                                    scope.launch { chatRepository.resendMessage(message.id) }
                                }
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage, 
    progress: Int?, 
    chatRepository: ChatRepository,
    isSelected: Boolean,
    autoDownloadLimit: Long,
    downloadProgress: Map<String, Int>,
    isDownloading: Boolean,
    playingMessageId: String?,
    onPlayAudio: (ChatMessage) -> Unit,
    onMediaClick: (ChatMessage) -> Unit,
    onLongClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) Color(0xFF95EC69) else Color.White
    val contentColor = Color.Black

    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))

    val displayName = message.senderName ?: message.sender
    val nameColor = getUserColor(displayName)
    
    val userAvatarUrl = rememberAvatarUrl(
        rawAvatar = message.senderAvatar,
        senderName = displayName,
        isOutgoing = isOutgoing,
        chatRepository = chatRepository
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(
                onClick = { onMediaClick(message) },
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {

        if (isOutgoing && message.status == MessageStatus.FAILED) {
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .clickable {
                        scope.launch {
                            chatRepository.resendMessage(message.id)
                        }
                    }
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Retry", tint = Color.Red, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = nameColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(bottom = 2.dp, end = 2.dp)
            )
            when (message.type) {
                MessageType.TEXT -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bubbleColor),
                        shape = MaterialTheme.shapes.medium,
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = contentColor,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
                MessageType.IMAGE -> {
                    val localFile = remember(message.id) {
                        chatRepository.getLocalFile(message.id, message.content)
                    }
                    
                    val fileExistsState = produceState(
                        initialValue = chatRepository.getTransientUri(message.id, message.content) != null || localFile.exists(),
                        message.id
                    ) {
                        snapshotFlow { downloadProgress[message.id] }
                            .collect {
                                val exists = chatRepository.getTransientUri(message.id, message.content) != null || localFile.exists()
                                value = exists
                            }
                    }
                    val fileExists = fileExistsState.value
                    
                    val displayUri = remember(fileExists) {
                        chatRepository.getTransientUri(message.id, message.content)
                            ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                            else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                    }

                    Box(
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displayUri,
                            contentDescription = null,
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .heightIn(max = 240.dp),
                            contentScale = ContentScale.Fit
                        )
                        

                        // Download button overlay for large files
                        if (progress != null && progress in 0..100) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                                IconButton(
                                    onClick = { chatRepository.cancelDownload(message.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Cancel", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        } else if (!isCached && message.remoteUrl != null && message.fileSize > autoDownloadLimit) {
                            IconButton(
                                onClick = { 
                                    kotlinx.coroutines.MainScope().launch {
                                        chatRepository.downloadFileToCache(message.id, message.content, chatRepository.resolveUrl(message.remoteUrl) ?: "")
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                            }
                        }
                        
                        if (message.fileSize > 0) Box(modifier = Modifier.align(Alignment.TopStart)) { FileSizeBadge(message.fileSize) }
                    }
                }
                MessageType.VIDEO -> {
                    val localFile = remember(message.id) {
                        chatRepository.getLocalFile(message.id, message.content)
                    }
                    
                    val fileExistsState = produceState(
                        initialValue = chatRepository.getTransientUri(message.id, message.content) != null || localFile.exists(),
                        message.id
                    ) {
                        snapshotFlow { downloadProgress[message.id] }
                            .collect {
                                val exists = chatRepository.getTransientUri(message.id, message.content) != null || localFile.exists()
                                value = exists
                            }
                    }
                    val fileExists = fileExistsState.value
                    
                    val displayUri = remember(fileExists) {
                        chatRepository.getTransientUri(message.id, message.content)
                            ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                            else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                    }

                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .aspectRatio(16/9f)
                            .background(Color.Black), 
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displayUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.8f
                        )
                        

                        if (progress != null && progress in 0..100) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                                IconButton(
                                    onClick = { chatRepository.cancelDownload(message.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Cancel", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        } else if (!isCached && message.remoteUrl != null && message.fileSize > autoDownloadLimit) {
                            IconButton(
                                onClick = { 
                                    kotlinx.coroutines.MainScope().launch {
                                        chatRepository.downloadFileToCache(message.id, message.content, chatRepository.resolveUrl(message.remoteUrl) ?: "")
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                            }
                        } else if (!isCached) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        
                        // Video duration
                        if (message.videoDuration > 0) {
                            Text(
                                text = formatDuration(message.videoDuration),
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 4.dp),
                                fontSize = 10.sp
                            )
                        }
                        
                        if (message.fileSize > 0) Box(modifier = Modifier.align(Alignment.TopStart)) { FileSizeBadge(message.fileSize) }
                    }
                }
                MessageType.FOLDER -> {
                    FolderBubble(
                        message = message,
                        isSelected = isSelected,
                        onSelectToggle = { onMediaClick(message) },
                        onLongClick = onLongClick
                    )
                }
                MessageType.AUDIO -> {
                    val localFile = remember(message.id) {
                        chatRepository.getLocalFile(message.id, message.content)
                    }
                    val isPlaying = playingMessageId == message.id

                    Card(
                        colors = CardDefaults.cardColors(containerColor = bubbleColor),
                        shape = MaterialTheme.shapes.medium,
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                        modifier = Modifier
                            .clickable {
                                onPlayAudio(message)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .width(Math.min(80 + (message.videoDuration * 4).toInt(), 200).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${message.videoDuration}\"",
                                color = contentColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> {
                    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
                    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = MaterialTheme.shapes.medium,
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Reverted to simple layout
                            val isLong = message.content.length > 25
                            var isExpanded by remember(message.id) { mutableStateOf(false) }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.content,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                    overflow = if (isExpanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    fontSize = 15.sp,
                                    color = Color.Black
                                )
                                if (isLong) {
                                    Text(
                                        text = if (isExpanded) "收起" else "展开全文",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { isExpanded = !isExpanded }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (message.fileSize > 0) formatFileSize(message.fileSize) else "Document",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            if (progress != null && progress in 0..100) {
                                CircularProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else if (!isCached && message.remoteUrl != null) {
                                IconButton(onClick = {
                                    kotlinx.coroutines.MainScope().launch {
                                        chatRepository.downloadFileToCache(message.id, message.content, chatRepository.resolveUrl(message.remoteUrl) ?: "")
                                    }
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                }
                            } else {
                                Icon(
                                    imageVector = if (message.content.lowercase().endsWith(".pdf")) Icons.Default.Description else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Status and Time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (message.status == MessageStatus.SENDING) {
                    val progressText = if (progress != null && progress != -1) "$progress%" else "..."
                    Text(
                        text = "Sending $progressText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (!message.caption.isNullOrBlank()) {
                    Text(
                        text = message.caption,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.Gray,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                // 地址（点亮地址图标时附加）：小字显示，样式与注释一致，位于时间之前
                if (!message.locationAddress.isNullOrBlank()) {
                    Text(
                        text = message.locationAddress,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.Gray,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.Gray
                )
            }
        }

        // Right Side Avatar for ALL Messages in Default View
        UserAvatar(
            avatarUrl = userAvatarUrl,
            displayName = displayName,
            modifier = Modifier
                .padding(start = 8.dp, top = 2.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
        )
    }
}

fun getUserColor(name: String): Color {
    val colors = listOf(
        Color(0xFFF44336), // Red 500
        Color(0xFFE91E63), // Pink 500
        Color(0xFF9C27B0), // Purple 500
        Color(0xFF673AB7), // Deep Purple 500
        Color(0xFF3F51B5), // Indigo 500
        Color(0xFF2196F3), // Blue 500
        Color(0xFF03A9F4), // Light Blue 500
        Color(0xFF00BCD4), // Cyan 500
        Color(0xFF009688), // Teal 500
        Color(0xFF4CAF50), // Green 500
        Color(0xFF8BC34A), // Light Green 500
        Color(0xFFCDDC39), // Lime 500
        Color(0xFFFFEB3B), // Yellow 500
        Color(0xFFFFC107), // Amber 500
        Color(0xFFFF9800), // Orange 500
        Color(0xFFFF5722), // Deep Orange 500
        Color(0xFF795548), // Brown 500
        Color(0xFF607D8B)  // Blue Grey 500
    )
    val hash = name.hashCode()
    val index = kotlin.math.abs(hash) % colors.size
    return colors[index]
}

@Composable
fun Avatar(label: String) {
    Box(modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(label)
    }
}

@Composable
fun FileSizeBadge(size: Long) {
    Text(
        text = formatFileSize(size),
        color = Color.White,
        modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small).padding(horizontal = 4.dp),
        fontSize = 10.sp
    )
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}

private fun formatTimestamp(timestamp: Long): String {
    val messageDate = java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp
    }
    val today = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    
    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val time = timeFormat.format(timestamp)
    
    return when {
        messageDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
        messageDate.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> {
            time  // Today: just show time
        }
        messageDate.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
        messageDate.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> {
            "昨天 $time"  // Yesterday
        }
        else -> {
            val dateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            dateFormat.format(timestamp)  // Other days: show date + time
        }
    }
}

private fun determineMessageType(context: android.content.Context, uri: Uri, fileName: String): MessageType {
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType != null) {
        if (mimeType.startsWith("image/")) return MessageType.IMAGE
        if (mimeType.startsWith("video/")) return MessageType.VIDEO
        if (mimeType.startsWith("audio/")) return MessageType.AUDIO
    }
    val name = fileName.lowercase()
    return when {
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> MessageType.IMAGE
        name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov") -> MessageType.VIDEO
        name.endsWith(".mp3") || name.endsWith(".wav") -> MessageType.AUDIO
        else -> MessageType.FILE
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var displayName: String? = null
    val contentResolver = context.contentResolver
    val mimeType = contentResolver.getType(uri)
    
    // 1. Try to get DISPLAY_NAME from ContentResolver
    if (uri.scheme == "content") {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainScreen", "Query display name failed", e)
        }
    }
    
    // 2. Fallback to last path segment
    if (displayName.isNullOrBlank()) {
        displayName = uri.lastPathSegment
    }
    
    // 3. Ultimate fallback
    if (displayName.isNullOrBlank() || displayName == "primary" || displayName.startsWith("document:")) {
        displayName = "file_${System.currentTimeMillis()}"
    }

    // 4. Critical Fix: Ensure extension exists by checking MIME type
    // Handle cases where the name might have a dot but it's not an extension (e.g. "com.android.providers.media.documents/123")
    val hasExtension = displayName!!.contains(".") && displayName.substringAfterLast(".").length in 2..4
    
    if (!hasExtension && mimeType != null) {
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (!extension.isNullOrEmpty()) {
            displayName = if (displayName.endsWith(".$extension", ignoreCase = true)) {
                displayName
            } else {
                "$displayName.$extension"
            }
        }
    }
    
    // Extra safety: remove illegal characters for filesystems
    displayName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    
    Log.d("MainScreen", "Resolved original filename: $displayName for URI: $uri, MIME: $mimeType")
    return displayName
}

@Composable
fun AttachmentPanel(
    onImageClick: () -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    deleteSourceAfterSend: Boolean = false,
    onToggleDeleteSource: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AttachmentOption(
                    icon = Icons.Default.Image,
                    label = "Images",
                    color = Color(0xFF4CAF50),
                    onClick = onImageClick
                )
                AttachmentOption(
                    icon = Icons.Default.LocationOn,
                    label = "Location",
                    color = Color(0xFFFF9800),
                    onClick = onLocationClick
                )
                AttachmentOption(
                    icon = Icons.Default.InsertDriveFile,
                    label = "Files",
                    color = Color(0xFF2196F3),
                    onClick = onFileClick
                )
            }
        }
    }
}

@Composable
fun AttachmentOption(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.1f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecurityOverlay(chatRepository: ChatRepository) {
    val authId = chatRepository.authId
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val securityError by chatRepository.securityError.collectAsState()
    var isVerifying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            chatRepository.checkSecurityAuth()
            delay(5000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
            .pointerInput(Unit) { }, // Block pointer events
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).widthIn(max = 400.dp).verticalScroll(rememberScrollState()).imePadding()
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "设备认证 (Device Auth)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            /* 
            Text(
                "当前账号未通过认证。请点击 ID 复制后通过手动认证，或输入 2FA 动态码通过认证。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            */
            
            if (securityError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                // Text(
                //     text = securityError!!,
                //     color = MaterialTheme.colorScheme.error,
                //     style = MaterialTheme.typography.bodySmall,
                //     textAlign = androidx.compose.ui.text.style.TextAlign.Center
                // )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Auth ID", authId)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "ID 已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    }
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "认证 ID (点击复制):",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = authId,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = code,
                onValueChange = { 
                    if (it.length <= 6) {
                        code = it
                        if (it.length == 6) {
                            scope.launch {
                                isVerifying = true
                                chatRepository.verify2FAServer(it)
                                isVerifying = false
                            }
                        }
                    }
                },
                label = { Text("6位 2FA 验证码") },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                enabled = !isVerifying,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                singleLine = true,
                placeholder = { Text("000000") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 20.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    scope.launch {
                        isVerifying = true
                        val success = chatRepository.verify2FAServer(code)
                        isVerifying = false
                    }
                },
                enabled = code.length == 6 && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Text("进行 2FA 验证", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

fun sendNativeLocation(
    context: android.content.Context, 
    chatRepository: ChatRepository, 
    scope: kotlinx.coroutines.CoroutineScope,
    categories: List<String>? = null,
    folderId: String? = null
) {
    val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    
    val provider = when {
        isNetworkEnabled -> android.location.LocationManager.NETWORK_PROVIDER
        isGpsEnabled -> android.location.LocationManager.GPS_PROVIDER
        else -> null
    }
    
    if (provider == null) {
        android.widget.Toast.makeText(context, "无法获取位置：GPS 或网络定位未开启", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    
    try {
        val location = locationManager.getLastKnownLocation(provider)
        if (location != null) {
            resolveAndSendLocation(context, location, chatRepository, scope, categories, folderId)
        } else {
            locationManager.requestSingleUpdate(provider, object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    resolveAndSendLocation(context, loc, chatRepository, scope, categories, folderId)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, null)
        }
    } catch (e: SecurityException) {
        android.widget.Toast.makeText(context, "获取位置失败：无权限", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun resolveAndSendLocation(
    context: android.content.Context,
    location: android.location.Location,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    categories: List<String>? = null,
    folderId: String? = null
) {
    scope.launch(Dispatchers.IO) {
        val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
        var addressText = "纬度: ${location.latitude}, 经度: ${location.longitude}"
        try {
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val sb = java.lang.StringBuilder()
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
            Log.e("MainScreen", "Geocoder failed", e)
        }
        
        withContext(Dispatchers.Main) {
            scope.launch {
                chatRepository.sendMessage(
                    content = "[位置] $addressText",
                    type = MessageType.TEXT,
                    categories = categories,
                    folderId = folderId
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FolderBubble(
    message: ChatMessage,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .width(160.dp)
                .combinedClickable(
                    onClick = onSelectToggle,
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "文件夹",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (message.content.isNotBlank()) message.content else "文件夹",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
            }
        }
    }
}

suspend fun fetchAddressQuickly(context: android.content.Context): String? = withContext(Dispatchers.IO) {
    val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    val provider = when {
        isNetworkEnabled -> android.location.LocationManager.NETWORK_PROVIDER
        isGpsEnabled -> android.location.LocationManager.GPS_PROVIDER
        else -> null
    } ?: return@withContext null
    
    try {
        val location = locationManager.getLastKnownLocation(provider) ?: return@withContext null
        val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0]
            val sb = java.lang.StringBuilder()
            address.adminArea?.let { sb.append(it) }
            val locality = address.locality
            if (locality != null && !sb.contains(locality)) {
                sb.append(locality)
            }
            address.subLocality?.let { sb.append(it) }
            address.thoroughfare?.let { sb.append(it) }
            address.subThoroughfare?.let { sb.append(it) }
            if (sb.isNotEmpty()) sb.toString() else (address.getAddressLine(0) ?: "未知位置")
        } else {
            "纬度: ${location.latitude}, 经度: ${location.longitude}"
        }
    } catch (e: Exception) {
        null
    }
}

fun getImageCaptureTime(context: android.content.Context, chatRepository: ChatRepository, message: com.cloudchat.model.ChatMessage): String {
    val localFile = chatRepository.getLocalFile(message.id, message.content)
    if (localFile.exists()) {
        try {
            val exifInterface = androidx.exifinterface.media.ExifInterface(localFile)
            val dateStr = exifInterface.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exifInterface.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
            if (!dateStr.isNullOrBlank()) {
                return dateStr
            }
        } catch (e: Exception) {
            android.util.Log.w("MainScreen", "Failed to read EXIF date: ${e.message}")
        }
    }
    val ts = if (message.timestamp > 0) message.timestamp else System.currentTimeMillis()
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

suspend fun getImageAddress(context: android.content.Context, chatRepository: ChatRepository, message: com.cloudchat.model.ChatMessage): String? = withContext(Dispatchers.IO) {
    if (!message.locationAddress.isNullOrBlank()) {
        return@withContext message.locationAddress
    }
    val localFile = chatRepository.getLocalFile(message.id, message.content)
    if (localFile.exists()) {
        try {
            val exifInterface = androidx.exifinterface.media.ExifInterface(localFile)
            val latLong = FloatArray(2)
            if (exifInterface.getLatLong(latLong)) {
                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLong[0].toDouble(), latLong[1].toDouble(), 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val sb = StringBuilder()
                    addr.adminArea?.let { sb.append(it) }
                    addr.locality?.let { if (!sb.contains(it)) sb.append(it) }
                    addr.subLocality?.let { sb.append(it) }
                    addr.thoroughfare?.let { sb.append(it) }
                    addr.subThoroughfare?.let { sb.append(it) }
                    if (sb.isNotEmpty()) return@withContext sb.toString()
                }
                return@withContext "Lat: ${latLong[0]}, Lon: ${latLong[1]}"
            }
        } catch (e: Exception) {
            android.util.Log.w("MainScreen", "Failed to read EXIF GPS: ${e.message}")
        }
    }
    fetchAddressQuickly(context)
}

sealed interface ChatUiItem {
    val id: String
    val timestamp: Long
    val sender: String
    
    data class SingleMessage(val message: com.cloudchat.model.ChatMessage) : ChatUiItem {
        override val id: String = message.id
        override val timestamp: Long = message.timestamp
        override val sender: String = message.sender
    }
    
    data class ImageGroup(val messages: List<com.cloudchat.model.ChatMessage>) : ChatUiItem {
        override val id: String = messages.first().id
        override val timestamp: Long = messages.first().timestamp
        override val sender: String = messages.first().sender
    }
}

fun groupMessages(messages: List<com.cloudchat.model.ChatMessage>): List<ChatUiItem> {
    val emitted = mutableSetOf<String>()
    val result = mutableListOf<ChatUiItem>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (emitted.contains(msg.id)) { i++; continue }

        // 1. Manual group (has groupId explicitly set).
        // Gather ALL members sharing this groupId across the whole list so that
        // merged/split grids still render as one bubble even when non-contiguous.
        if (!msg.groupId.isNullOrEmpty()) {
            val groupId = msg.groupId
            val group = messages.filter { it.groupId == groupId }
            group.forEach { emitted.add(it.id) }
            if (group.size > 1) {
                result.add(ChatUiItem.ImageGroup(group))
            } else {
                result.add(ChatUiItem.SingleMessage(msg))
            }
            i++
            continue
        }

        // 2. Normal message rendering (no auto-grouping)
        result.add(ChatUiItem.SingleMessage(msg))
        emitted.add(msg.id)
        i++
    }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGroupBubble(
    group: ChatUiItem.ImageGroup,
    chatRepository: ChatRepository,
    selectedIds: Set<String>,
    template: String = "default",
    downloadProgress: Map<String, Int> = emptyMap(),
    playingMessageId: String? = null,
    onPlayAudio: (com.cloudchat.model.ChatMessage) -> Unit = {},
    onFileClick: (com.cloudchat.model.ChatMessage) -> Unit = {},
    onSelectToggle: (com.cloudchat.model.ChatMessage) -> Unit,
    onMediaClick: (com.cloudchat.model.ChatMessage) -> Unit,
    onLongClick: (com.cloudchat.model.ChatMessage) -> Unit,
    onLongClickGroup: () -> Unit = {},
    onClickGroup: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isOutgoing = group.messages.first().isOutgoing
    val count = group.messages.size
    val cols = when (count) {
        2 -> 2
        4 -> 2
        else -> 3
    }
    val contentColor = Color.Black
    val isAllMedia = group.messages.all { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
    val isDefaultTemplate = template == "default"
    
    val displayName = group.messages.first().senderName ?: group.messages.first().sender
    val userAvatarUrl = rememberAvatarUrl(
        rawAvatar = group.messages.first().senderAvatar,
        senderName = displayName,
        isOutgoing = isOutgoing,
        chatRepository = chatRepository
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClickGroup,
                onLongClick = onLongClickGroup
            ),
        horizontalArrangement = if (isDefaultTemplate) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Left Side Avatar for Diary Template
        if (!isDefaultTemplate) {
            UserAvatar(
                avatarUrl = userAvatarUrl,
                displayName = displayName,
                modifier = Modifier
                    .padding(end = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        }

        Column(
            horizontalAlignment = if (isDefaultTemplate) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 252.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isAllMedia) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isAllMedia) 0.dp else 1.dp),
                modifier = Modifier
                    .widthIn(max = 252.dp)
                    .padding(2.dp)
                    .then(
                        if (!isAllMedia) Modifier.border(
                            0.5.dp,
                            Color(0xFFE2E2E2),
                            RoundedCornerShape(14.dp)
                        ) else Modifier
                    )
            ) {
                if (isAllMedia) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        val rows = (count + cols - 1) / cols
                        for (r in 0 until rows) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (c in 0 until cols) {
                                    val idx = r * cols + c
                                    if (idx < count) {
                                        val message = group.messages[idx]
                                        val localFile = remember(message.id) {
                                            chatRepository.getLocalFile(message.id, message.content)
                                        }
                                        val displayUri = remember(localFile) {
                                            chatRepository.getTransientUri(message.id, message.content)
                                                ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                                                else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                                        }
                                        
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .padding(2.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(Color.LightGray)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (selectedIds.isNotEmpty()) {
                                                            onSelectToggle(message)
                                                        } else {
                                                            onMediaClick(message)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        onLongClick(message)
                                                    }
                                                )
                                        ) {
                                            coil.compose.AsyncImage(
                                                model = displayUri,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                            if (selectedIds.contains(message.id)) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.4f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Selected",
                                                        tint = Color(0xFF81C784),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f).padding(2.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Composite Group Bubble (Text, Audio, File, etc.)
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    ) {
                        group.messages.forEachIndexed { index, message ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .height(0.5.dp)
                                        .background(Color(0xFFE5E5E5))
                                )
                            }

                            val isItemSelected = selectedIds.contains(message.id)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isItemSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clip(RoundedCornerShape(6.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (selectedIds.isNotEmpty()) {
                                                onSelectToggle(message)
                                            } else {
                                                when (message.type) {
                                                    MessageType.IMAGE, MessageType.VIDEO -> onMediaClick(message)
                                                    MessageType.AUDIO -> onPlayAudio(message)
                                                    MessageType.FILE -> onFileClick(message)
                                                    else -> {}
                                                }
                                            }
                                        },
                                        onLongClick = { onLongClick(message) }
                                    )
                                    .padding(vertical = 2.dp)
                            ) {
                                when (message.type) {
                                    MessageType.TEXT -> {
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            Text(
                                                text = message.content,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF222222),
                                                fontSize = 14.5.sp,
                                                lineHeight = 20.sp
                                            )
                                        }
                                        if (!message.locationAddress.isNullOrBlank()) {
                                            Text(
                                                text = message.locationAddress,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                    MessageType.AUDIO -> {
                                        val isPlaying = playingMessageId == message.id
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(Color(0xFF07C160))
                                                .clickable { onPlayAudio(message) }
                                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "${message.videoDuration}s",
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
                                            )
                                        }
                                        if (!message.caption.isNullOrBlank()) {
                                            Text(
                                                text = message.caption,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(top = 1.dp)
                                            )
                                        }
                                    }
                                    MessageType.FILE -> {
                                        val progress = downloadProgress[message.id]
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onFileClick(message) }
                                                .padding(vertical = 1.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.InsertDriveFile,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val isLong = message.content.length > 25
                                            var isExpanded by remember(message.id) { mutableStateOf(false) }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = message.content,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                                    overflow = if (isExpanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    fontSize = 12.5.sp
                                                )
                                                if (isLong) {
                                                    Text(
                                                        text = if (isExpanded) "收起" else "展开全文",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.clickable { isExpanded = !isExpanded }
                                                    )
                                                }
                                                if (message.fileSize > 0) {
                                                    Text(
                                                        text = formatFileSize(message.fileSize),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.Gray
                                                    )
                                                }
                                                if (progress != null && progress in 0..99) {
                                                    LinearProgressIndicator(
                                                        progress = progress / 100f,
                                                        modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    MessageType.IMAGE, MessageType.VIDEO -> {
                                        val localFile = remember(message.id) { chatRepository.getLocalFile(message.id, message.content) }
                                        val displayUri = remember(localFile) {
                                            chatRepository.getTransientUri(message.id, message.content)
                                                ?: if (localFile.exists()) "file://${localFile.absolutePath}"
                                                else chatRepository.resolveUrl(message.thumbnailUrl) ?: chatRepository.resolveUrl(message.remoteUrl)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 160.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Black.copy(alpha = 0.05f))
                                        ) {
                                            coil.compose.AsyncImage(
                                                model = displayUri,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxWidth(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                                            )
                                            if (message.type == MessageType.VIDEO) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Play",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(28.dp).align(Alignment.Center)
                                                )
                                            }
                                        }
                                    }
                                    MessageType.FOLDER -> {
                                        FolderBubble(
                                            message = message,
                                            isSelected = isItemSelected,
                                            onSelectToggle = { onSelectToggle(message) },
                                            onLongClick = { onLongClick(message) }
                                        )
                                    }
                                    else -> {
                                        Text(text = message.content, style = MaterialTheme.typography.bodyMedium, fontSize = 13.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Merged captions: join every non-blank member caption with a comma.
            val mergedCaption = group.messages
                .mapNotNull { it.caption?.takeIf { c -> c.isNotBlank() } }
                .joinToString("，")
            if (mergedCaption.isNotBlank()) {
                Text(
                    text = mergedCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp, start = 4.dp)
            ) {
                Text(
                    text = formatTimestamp(group.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }

        // Right Side Avatar for Default Template
        if (isDefaultTemplate) {
            UserAvatar(
                avatarUrl = userAvatarUrl,
                displayName = displayName,
                modifier = Modifier
                    .padding(start = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
fun ChooseParentFolderDialog(
    folderMessages: List<com.cloudchat.model.ChatMessage>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择父文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选中了多个文件夹，请选择其中一个作为父文件夹，其余所有条目将放入其中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                folderMessages.forEach { folder ->
                    val isSelected = selectedId == folder.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { selectedId = folder.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = folder.content.ifBlank { "文件夹" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let(onConfirm) },
                enabled = selectedId != null
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun MoveIntoFolderDialog(
    chatRepository: ChatRepository,
    currentFolderId: String?,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val allMessages by chatRepository.messages.collectAsState()
    var selectedFolderId by remember { mutableStateOf<String?>(null) }

    // 从 home（根目录）列出完整文件夹树：顶层文件夹 folderId 为空
    val rootFolders = allMessages.filter {
        it.type == MessageType.FOLDER && !it.isDeleted && it.folderId.isNullOrEmpty()
    }

    // 不能移入的目标：选中项中的文件夹自身 + 它们的全部后代（防循环嵌套）
    val disabledIds = remember(allMessages, selectedIds) {
        val movingFolderIds = allMessages.filter {
            it.type == MessageType.FOLDER && it.id in selectedIds
        }.map { it.id }.toSet()
        val disabled = mutableSetOf<String>()
        movingFolderIds.forEach { fid ->
            disabled += fid
            disabled += chatRepository.collectDescendantFolderIds(fid)
        }
        disabled
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移入文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rootFolders.isEmpty()) {
                    Text(
                        "还没有任何文件夹，请先打包创建文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    rootFolders.forEach { folder ->
                        FolderTreeNode(
                            folder = folder,
                            allMessages = allMessages,
                            selectedFolderId = selectedFolderId,
                            onSelect = { selectedFolderId = it },
                            depth = 0,
                            disabledIds = disabledIds
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedFolderId?.let(onConfirm) },
                enabled = selectedFolderId != null
            ) {
                Text("移入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun FolderTreeNode(
    folder: com.cloudchat.model.ChatMessage,
    allMessages: List<com.cloudchat.model.ChatMessage>,
    selectedFolderId: String?,
    onSelect: (String) -> Unit,
    depth: Int,
    ancestorIds: Set<String> = emptySet(),
    disabledIds: Set<String> = emptySet()
) {
    var expanded by remember { mutableStateOf(false) }
    // 防循环嵌套：纯过滤（无副作用），排除祖先链上已出现的文件夹
    val children = allMessages.filter {
        it.type == MessageType.FOLDER && !it.isDeleted && it.folderId == folder.id && it.id !in ancestorIds
    }
    val isSelected = selectedFolderId == folder.id
    val isDisabled = folder.id in disabledIds

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
                .then(
                    if (isDisabled) Modifier.alpha(0.4f)
                    else Modifier.clickable { onSelect(folder.id) }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (children.isNotEmpty()) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "折叠" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(28.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = folder.content.ifBlank { "文件夹" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (expanded && children.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                val childAncestors = ancestorIds + folder.id
                children.forEach { child ->
                    FolderTreeNode(
                        folder = child,
                        allMessages = allMessages,
                        selectedFolderId = selectedFolderId,
                        onSelect = onSelect,
                        depth = depth + 1,
                        ancestorIds = childAncestors,
                        disabledIds = disabledIds
                    )
                }
            }
        }
    }
}

@Composable
fun FolderBreadcrumb(
    folderStack: List<String>,
    messages: List<com.cloudchat.model.ChatMessage>,
    onNavigateTo: (String) -> Unit,
    onNavigateHome: () -> Unit
) {
    if (folderStack.isEmpty()) return
    val breadcrumbNames = folderStack.map { id ->
        messages.find { it.id == id }?.content?.ifBlank { "文件夹" } ?: "文件夹"
    }
    // 过长时折叠中间层级：始终显示首层和末层，中间用 "..." 省略
    val displayItems: List<Pair<String, String?>> =
        if (breadcrumbNames.size <= 4) {
            breadcrumbNames.mapIndexed { idx, name -> name to folderStack[idx] }
        } else {
            val first = breadcrumbNames.first() to folderStack.first()
            val last = breadcrumbNames.last() to folderStack.last()
            listOf(first, "..." to null, last)
        }
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            // 回到 Home（根目录）的入口
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "回到主界面",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onNavigateHome() }
                    .padding(2.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            displayItems.forEachIndexed { index, (label, targetId) ->
                if (index > 0) {
                    Text(
                        text = "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (targetId == null) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val isLast = targetId == folderStack.lastOrNull()
                    Text(
                        text = label,
                        color = if (isLast) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onNavigateTo(targetId) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FolderActionDialogs(
    chatRepository: ChatRepository,
    messages: List<com.cloudchat.model.ChatMessage>,
    currentFolderId: String?,
    selectedIds: Set<String>,
    showChooseParentFolderDialog: Boolean,
    onChooseParentDismiss: () -> Unit,
    onChooseParentConfirm: (String) -> Unit,
    showMoveIntoFolderDialog: Boolean,
    onMoveIntoDismiss: () -> Unit,
    onMoveIntoConfirm: (String) -> Unit
) {
    if (showChooseParentFolderDialog) {
        val folderMsgs = messages.filter { selectedIds.contains(it.id) && it.type == MessageType.FOLDER }
        ChooseParentFolderDialog(
            folderMessages = folderMsgs,
            onDismiss = onChooseParentDismiss,
            onConfirm = onChooseParentConfirm
        )
    }
    if (showMoveIntoFolderDialog) {
        MoveIntoFolderDialog(
            chatRepository = chatRepository,
            currentFolderId = currentFolderId,
            selectedIds = selectedIds,
            onDismiss = onMoveIntoDismiss,
            onConfirm = onMoveIntoConfirm
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatInputBar(
    context: android.content.Context,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    sharedPrefs: android.content.SharedPreferences,
    currentFolderId: String?,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isVoiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    isAttachmentPanelVisible: Boolean,
    onAttachmentPanelVisibleChange: (Boolean) -> Unit,
    attachLocationEnabled: Boolean,
    onAttachLocationEnabledChange: (Boolean) -> Unit,
    hasAudioPermission: Boolean,
    hasLocationPermission: Boolean,
    deleteSourceAfterSend: Boolean,
    onDeleteSourceAfterSendChange: (Boolean) -> Unit,
    privacyPin: String,
    onPrivacyModeChange: (Boolean) -> Unit,
    onViewOnlyPrivacyItemsChange: (Boolean) -> Unit,
    audioPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onShowImagePicker: () -> Unit,
    startVoiceRecording: () -> Unit,
    stopAndSendVoice: () -> Unit,
    cancelVoiceRecording: () -> Unit
) {
    Column(modifier = Modifier.navigationBarsPadding().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // 1. Voice / Keyboard Toggle
            IconButton(
                onClick = {
                    onVoiceModeChange(!isVoiceMode)
                    if (!isVoiceMode) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onAttachmentPanelVisibleChange(false)
                    } else {
                        focusManager.clearFocus()
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isVoiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                    contentDescription = "Voice Mode",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. Input Area (Text field or Hold-to-Talk button)
            if (isVoiceMode) {
                var isRecording by remember { mutableStateOf(false) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .padding(horizontal = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (isRecording) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (!hasAudioPermission) {
                                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        try {
                                            isRecording = true
                                            startVoiceRecording()
                                            val released = tryAwaitRelease()
                                            if (released) {
                                                stopAndSendVoice()
                                            } else {
                                                cancelVoiceRecording()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MainScreen", "Recording error", e)
                                            cancelVoiceRecording()
                                        } finally {
                                            isRecording = false
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    Text(
                        text = if (isRecording) "松开 发送" else "按住 说话",
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onAttachmentPanelVisibleChange(false)
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4
                )
            }

            // 3. Location Attachment Toggle Button
            if (!isVoiceMode && inputText.isBlank()) {
                IconButton(
                    onClick = {
                        onAttachLocationEnabledChange(!attachLocationEnabled)
                        if (!attachLocationEnabled && !hasLocationPermission) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Attach Location Toggle",
                        tint = if (attachLocationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. "+" Add or Send Button
            if (inputText.isNotBlank() && !isVoiceMode) {
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(40.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                scope.launch {
                                    if (inputText.startsWith("/link ", ignoreCase = true)) {
                                        val fileName = inputText.substring(6).trim()
                                        if (fileName.isNotEmpty()) {
                                            chatRepository.linkServerFile(fileName)
                                        }
                                    } else {
                                        var finalContent = inputText
                                        if (attachLocationEnabled) {
                                            val address = fetchAddressQuickly(context)
                                            if (address != null) {
                                                finalContent = "$finalContent\n[位置] $address"
                                            }
                                        }
                                        chatRepository.sendMessage(finalContent, folderId = currentFolderId)
                                    }
                                    onInputTextChange("")
                                }
                            },
                            onLongClick = {
                                if (inputText.startsWith("##") && inputText.endsWith("##")) {
                                    val pin = inputText.substring(2, inputText.length - 2)
                                    if (pin == privacyPin) {
                                        onPrivacyModeChange(true)
                                        onViewOnlyPrivacyItemsChange(true)
                                    }
                                    onInputTextChange("")
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        onAttachmentPanelVisibleChange(!isAttachmentPanelVisible)
                        if (!isAttachmentPanelVisible) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = "Attachment Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isAttachmentPanelVisible,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            AttachmentPanel(
                onImageClick = {
                    onShowImagePicker()
                    onAttachmentPanelVisibleChange(false)
                },
                onFileClick = {
                    filePickerLauncher.launch("*/*")
                    onAttachmentPanelVisibleChange(false)
                },
                onLocationClick = {
                    if (!hasLocationPermission) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        sendNativeLocation(context, chatRepository, scope, folderId = currentFolderId)
                    }
                    onAttachmentPanelVisibleChange(false)
                },
                deleteSourceAfterSend = deleteSourceAfterSend,
                onToggleDeleteSource = { v ->
                    onDeleteSourceAfterSendChange(v)
                    sharedPrefs.edit().putBoolean("delete_source_after_send", v).apply()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun androidx.compose.foundation.layout.ColumnScope.SelectionToolbar(
    selectedIds: Set<String>,
    messages: List<com.cloudchat.model.ChatMessage>,
    currentFolderId: String?,
    isPrivacyMode: Boolean,
    context: android.content.Context,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelectionChange: (Set<String>) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onEditMessage: (com.cloudchat.model.ChatMessage) -> Unit,
    onPackFolder: (String) -> Unit,
    onChooseParentFolder: () -> Unit,
    onUnpack: () -> Unit,
    onMoveIntoFolder: () -> Unit,
    onGenerateDiary: (Set<String>) -> Unit,
    onDelete: () -> Unit,
    rangeSelectActive: Boolean = false,
    onToggleRangeSelect: () -> Unit = {}
) {
    if (selectedIds.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolbarAction(icon = Icons.Default.Close, contentDescription = "Cancel") {
                onSelectionChange(emptySet())
            }

            // 范围选择（Shift 模式）：全选「第一条到最后一条」之间
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (rangeSelectActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else Color.Transparent
                    )
                    .clickable(onClick = onToggleRangeSelect),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = "范围选择",
                    tint = if (rangeSelectActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 1. Copy (only for text messages)
            if (selectedIds.any { id -> messages.find { it.id == id }?.type == MessageType.TEXT }) {
                val clipboardManager = LocalClipboardManager.current
                ToolbarAction(icon = Icons.Default.ContentCopy, contentDescription = "Copy") {
                    val textToCopy = selectedIds.mapNotNull { id ->
                        messages.find { it.id == id }
                    }.sortedBy { it.timestamp }
                        .joinToString("\n") { it.content }
                    clipboardManager.setText(AnnotatedString(textToCopy))
                    android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    onSelectionChange(emptySet())
                }
            }

            // 2. Edit Text / Caption / Rename Folder
            if (selectedIds.size == 1) {
                val firstId = selectedIds.firstOrNull()
                val singleMsg = if (firstId != null) messages.find { it.id == firstId } else null
                if (singleMsg != null) {
                    ToolbarAction(icon = Icons.Default.Edit, contentDescription = "Edit") {
                        if (singleMsg.type == MessageType.FOLDER) {
                            onRenameFolder(singleMsg.id, singleMsg.content)
                        } else {
                            onEditMessage(singleMsg)
                        }
                    }
                }
            }

            // 3. Pack Folder
            ToolbarAction(icon = Icons.Default.Folder, contentDescription = "Pack Folder") {
                val selectedMessages = messages.filter { selectedIds.contains(it.id) }
                val folderMsgs = selectedMessages.filter { it.type == MessageType.FOLDER }
                if (folderMsgs.size >= 2) {
                    onChooseParentFolder()
                } else {
                    val existingFolder = selectedMessages.find { it.type == MessageType.FOLDER }
                        ?: if (currentFolderId != null) messages.find { it.id == currentFolderId } else null
                    onPackFolder(existingFolder?.content ?: "")
                }
            }

            // 4. Unpack Folder / Move out of folder
            val hasFolderToUnpack = selectedIds.any { id -> messages.find { it.id == id }?.type == MessageType.FOLDER }
            val hasItemsInFolder = currentFolderId != null || selectedIds.any { id -> messages.find { it.id == id }?.folderId != null }
            if (hasFolderToUnpack || hasItemsInFolder) {
                ToolbarAction(icon = Icons.Default.FolderOff, contentDescription = "Unpack Folder") {
                    onUnpack()
                }
            }

            // 4b. Move into folder (移入文件夹)
            ToolbarAction(icon = Icons.Default.DriveFileMove, contentDescription = "移入文件夹") {
                onMoveIntoFolder()
            }

            // 5. Combine / Merge（过滤文件夹）
            if (selectedIds.count { id -> messages.find { it.id == id }?.type != MessageType.FOLDER } >= 2) {
                ToolbarAction(icon = Icons.Default.GroupWork, contentDescription = "Combine") {
                    val newGroupId = "group_${System.currentTimeMillis()}"
                    scope.launch {
                        chatRepository.groupSelectedMessages(selectedIds, newGroupId)
                        onSelectionChange(emptySet())
                    }
                }
            }

            // 6. Split / Ungroup
            if (selectedIds.any { id -> !messages.find { it.id == id }?.groupId.isNullOrEmpty() }) {
                ToolbarAction(icon = Icons.Default.CallSplit, contentDescription = "Uncombine") {
                    scope.launch {
                        val selectedMsgs = messages.filter { selectedIds.contains(it.id) }
                        chatRepository.ungroupMessages(selectedMsgs)
                        onSelectionChange(emptySet())
                    }
                }
            }

            // 6b. Download to Download/CloudChat
            if (selectedIds.any { id -> messages.find { it.id == id }?.type != MessageType.TEXT && messages.find { it.id == id }?.type != MessageType.FOLDER }) {
                ToolbarAction(icon = Icons.Default.Download, contentDescription = "保存到 Download/CloudChat") {
                    selectedIds.forEach { id ->
                        val msg = messages.find { it.id == id }
                        if (msg != null && msg.type != MessageType.TEXT && msg.type != MessageType.FOLDER) {
                            saveFileToDownloadDir(context, chatRepository, msg)
                        }
                    }
                }
            }

            // 7. Share
            ToolbarAction(icon = Icons.Default.Share, contentDescription = "分享消息与文件") {
                shareSelectedMessages(context, chatRepository, messages, selectedIds)
            }

            // 移出隐私空间（仅在隐私模式下显示）
            if (isPrivacyMode) {
                ToolbarAction(
                    icon = Icons.Default.LockOpen,
                    contentDescription = "移出隐私空间",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    scope.launch {
                        chatRepository.toggleHideMessages(selectedIds)
                        onSelectionChange(emptySet())
                    }
                }
            }

            // 8. 生成日记（多选）
            ToolbarAction(icon = Icons.Default.MenuBook, contentDescription = "生成日记") {
                onGenerateDiary(selectedIds)
                onSelectionChange(emptySet())
            }

            // 删除图标：短按确认删除；长按移入隐私空间
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { _ ->
                                var longPressHandled = false
                                try {
                                    withTimeout(800L) {
                                        tryAwaitRelease()
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    longPressHandled = true
                                    scope.launch {
                                        chatRepository.toggleHideMessages(selectedIds)
                                        onSelectionChange(emptySet())
                                    }
                                }
                                if (!longPressHandled) {
                                    onDelete()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// 紧凑工具栏按钮：40dp 触摸区域，22dp 图标，带涟漪反馈
@Composable
private fun ToolbarAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun rememberFastFlingBehavior(): FlingBehavior {
    val defaultFling = ScrollableDefaults.flingBehavior()
    return remember(defaultFling) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                return with(defaultFling) { performFling(initialVelocity * 1.5f) }
            }
        }
    }
}

@Composable
fun BoxScope.FastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    totalItemsCount: Int,
    modifier: Modifier = Modifier
) {
    if (totalItemsCount <= 3) return

    var isDragging by remember { mutableStateOf(false) }
    var wasDragging by remember { mutableStateOf(false) }
    var draggedTopOffsetPx by remember { mutableFloatStateOf(0f) }
    var isVisible by remember { mutableStateOf(false) }
    var scrollStartItemIndex by remember { mutableIntStateOf(-1) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            scrollStartItemIndex = -1
            val hideDelay = if (wasDragging) 500L else 1000L
            kotlinx.coroutines.delay(hideDelay)
            isVisible = false
            wasDragging = false
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, isDragging) {
        if (isDragging) {
            wasDragging = true
            isVisible = true
        } else if (listState.isScrollInProgress) {
            if (scrollStartItemIndex < 0) {
                scrollStartItemIndex = listState.firstVisibleItemIndex
            }
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val itemsScrolled = kotlin.math.abs(listState.firstVisibleItemIndex - scrollStartItemIndex)
            if (itemsScrolled > visibleCount * 1.5f) {
                isVisible = true
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(300), label = "alpha"
    )

    if (alpha <= 0.01f && !isVisible) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(64.dp)
            .graphicsLayer { this.alpha = alpha }
            .pointerInput(totalItemsCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val heightPx = size.height.toFloat()
                        val thumbSizePx = 56.dp.toPx()
                        val availableHeight = (heightPx - thumbSizePx).coerceAtLeast(1f)
                        draggedTopOffsetPx = (offset.y - thumbSizePx / 2f).coerceIn(0f, availableHeight)

                        val visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        val maxScrollIndex = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
                        val yFraction = draggedTopOffsetPx / availableHeight
                        val targetIndex = ((1f - yFraction) * maxScrollIndex).roundToInt().coerceIn(0, totalItemsCount - 1)
                        coroutineScope.launch {
                            listState.scrollToItem(targetIndex)
                        }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val heightPx = size.height.toFloat()
                        val thumbSizePx = 56.dp.toPx()
                        val availableHeight = (heightPx - thumbSizePx).coerceAtLeast(1f)
                        draggedTopOffsetPx = (draggedTopOffsetPx + dragAmount).coerceIn(0f, availableHeight)

                        val visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        val maxScrollIndex = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
                        val yFraction = draggedTopOffsetPx / availableHeight
                        val targetIndex = ((1f - yFraction) * maxScrollIndex).roundToInt().coerceIn(0, totalItemsCount - 1)
                        coroutineScope.launch {
                            listState.scrollToItem(targetIndex)
                        }
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val heightPx = constraints.maxHeight.toFloat()
        val thumbSizePx = with(density) { 56.dp.toPx() }
        val availableHeight = (heightPx - thumbSizePx).coerceAtLeast(1f)

        val firstVisibleIndex = listState.firstVisibleItemIndex
        val visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val maxScrollIndex = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
        val scrollFraction = (firstVisibleIndex.toFloat() / maxScrollIndex.toFloat()).coerceIn(0f, 1f)

        val calculatedTopOffsetPx = ((1f - scrollFraction) * availableHeight).coerceIn(0f, availableHeight)
        val activeTopOffsetPx = if (isDragging) draggedTopOffsetPx else calculatedTopOffsetPx

        Box(
            modifier = Modifier
                .offset { IntOffset(x = 0, y = activeTopOffsetPx.roundToInt()) }
                .align(Alignment.TopEnd)
                .size(56.dp)
                .padding(end = 6.dp)
                .clip(CircleShape)
                .background(
                    if (isDragging) Color(0xFF6366F1) else Color(0xEE334155)
                )
                .border(
                    width = 2.dp,
                    color = if (isDragging) Color(0xFFA5B4FC) else Color(0x9994A3B8),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.UnfoldMore,
                contentDescription = "Fast Scroll",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun androidx.compose.foundation.layout.ColumnScope.ChatMessageList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    dragModifier: Modifier,
    chatUiItems: List<ChatUiItem>,
    isDiaryTemplate: Boolean,
    diaryDateGroups: Map<String, String>,
    chatRepository: ChatRepository,
    context: android.content.Context,
    currentConfig: com.cloudchat.model.ServerConfig?,
    uploadProgress: Map<String, Int>,
    downloadProgress: Map<String, Int>,
    activeDownloadIds: Set<String>,
    autoDownloadLimit: Long,
    playingMessageId: String?,
    mediaMessages: List<com.cloudchat.model.ChatMessage>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onMediaPagerIndexChange: (Int?) -> Unit,
    onEnterFolder: (String) -> Unit,
    onPlayAudio: (com.cloudchat.model.ChatMessage) -> Unit,
    rangeSelectActive: Boolean = false,
    onRangeSelect: (String) -> Unit = {}
) {
    val customFling = rememberFastFlingBehavior()

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = customFling,
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier),
            reverseLayout = true,
            contentPadding = PaddingValues(8.dp)
        ) {
        items(chatUiItems.asReversed(), key = { it.id }) { uiItem ->
            if (isDiaryTemplate) {
                val headerLabel = diaryDateGroups[uiItem.id]
                if (headerLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFCCCCCC)))
                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color(0xFF999999),
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFCCCCCC)))
                    }
                }
            }

            when (uiItem) {
                is ChatUiItem.SingleMessage -> {
                    val message = uiItem.message
                    val progress = uploadProgress[message.id] ?: downloadProgress[message.id]
                    val isDownloading = activeDownloadIds.contains(message.id)

                    val onMediaClickHandler: (com.cloudchat.model.ChatMessage) -> Unit = { clickedMsg ->
                        if (rangeSelectActive) {
                            onRangeSelect(clickedMsg.id)
                        } else if (selectedIds.isNotEmpty()) {
                            onSelectionChange(
                                if (selectedIds.contains(clickedMsg.id)) selectedIds - clickedMsg.id
                                else selectedIds + clickedMsg.id
                            )
                        } else {
                            when (clickedMsg.type) {
                                MessageType.IMAGE, MessageType.VIDEO -> {
                                    val index = mediaMessages.indexOfFirst { it.id == clickedMsg.id }
                                    if (index != -1) onMediaPagerIndexChange(index)
                                }
                                MessageType.AUDIO -> onPlayAudio(clickedMsg)
                                MessageType.FOLDER -> onEnterFolder(clickedMsg.id)
                                MessageType.FILE -> openFileWithDefaultApp(context, chatRepository, clickedMsg)
                                else -> {}
                            }
                        }
                    }
                    val onLongClickHandler: () -> Unit = {
                        if (rangeSelectActive) {
                            onRangeSelect(message.id)
                        } else if (selectedIds.isEmpty()) {
                            onSelectionChange(setOf(message.id))
                        }
                    }

                    if (isDiaryTemplate) {
                        DiaryBubble(
                            message = message,
                            progress = progress,
                            chatRepository = chatRepository,
                            isSelected = selectedIds.contains(message.id),
                            autoDownloadLimit = autoDownloadLimit,
                            downloadProgress = downloadProgress,
                            isDownloading = isDownloading,
                            playingMessageId = playingMessageId,
                            onPlayAudio = onPlayAudio,
                            onMediaClick = onMediaClickHandler,
                            onLongClick = onLongClickHandler
                        )
                    } else {
                        ChatBubble(
                            message = message,
                            progress = progress,
                            chatRepository = chatRepository,
                            isSelected = selectedIds.contains(message.id),
                            autoDownloadLimit = autoDownloadLimit,
                            downloadProgress = downloadProgress,
                            isDownloading = isDownloading,
                            playingMessageId = playingMessageId,
                            onPlayAudio = onPlayAudio,
                            onMediaClick = onMediaClickHandler,
                            onLongClick = onLongClickHandler
                        )
                    }
                }
                is ChatUiItem.ImageGroup -> {
                    ImageGroupBubble(
                        group = uiItem,
                        chatRepository = chatRepository,
                        selectedIds = selectedIds,
                        template = currentConfig?.messageTemplate ?: "default",
                        downloadProgress = downloadProgress,
                        playingMessageId = playingMessageId,
                        onPlayAudio = onPlayAudio,
                        onFileClick = { openFileWithDefaultApp(context, chatRepository, it) },
                        onSelectToggle = { clickedMsg ->
                            if (rangeSelectActive) {
                                onRangeSelect(clickedMsg.id)
                            } else {
                                onSelectionChange(
                                    if (selectedIds.contains(clickedMsg.id)) selectedIds - clickedMsg.id
                                    else selectedIds + clickedMsg.id
                                )
                            }
                        },
                        onMediaClick = { clickedMsg ->
                            val index = mediaMessages.indexOfFirst { it.id == clickedMsg.id }
                            if (index != -1) onMediaPagerIndexChange(index)
                        },
                        onLongClick = { clickedMsg ->
                            if (rangeSelectActive) {
                                onRangeSelect(clickedMsg.id)
                            } else if (selectedIds.isEmpty()) {
                                onSelectionChange(setOf(clickedMsg.id))
                            }
                        },
                        onClickGroup = {
                            if (selectedIds.isNotEmpty()) {
                                val groupIds = uiItem.messages.map { it.id }.toSet()
                                onSelectionChange(
                                    if (selectedIds.containsAll(groupIds)) selectedIds - groupIds
                                    else selectedIds + groupIds
                                )
                            }
                        },
                        onLongClickGroup = {
                            val groupIds = uiItem.messages.map { it.id }.toSet()
                            onSelectionChange(
                                if (selectedIds.containsAll(groupIds)) selectedIds - groupIds
                                else selectedIds + groupIds
                            )
                        }
                    )
                }
            }
        }
        }

        FastScrollbar(
            listState = listState,
            totalItemsCount = chatUiItems.size,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun androidx.compose.foundation.layout.RowScope.TopBarActionsContent(
    isSearchActive: Boolean,
    searchQuery: String,
    syncInterval: Long,
    isServerConnected: Boolean,
    isPrivacyMode: Boolean,
    viewOnlyPrivacyItems: Boolean,
    currentFolderId: String?,
    isSyncing: Boolean,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester,
    messages: List<com.cloudchat.model.ChatMessage>,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onViewOnlyPrivacyItemsChange: (Boolean) -> Unit,
    onShowChangePrivacyPasswordDialog: () -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onShowCalendarDialog: () -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onGenerateFolderDiary: (String) -> Unit
) {
    if (isSearchActive) {
        LaunchedEffect(Unit) {
            searchFocusRequester.requestFocus()
        }
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search messages...") },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .focusRequester(searchFocusRequester),
            singleLine = true,
            colors = TextFieldDefaults.textFieldColors(containerColor = Color.Transparent)
        )
        IconButton(modifier = Modifier.size(40.dp), onClick = {
            onSearchActiveChange(false)
            onSearchQueryChange("")
        }) {
            Icon(Icons.Default.Close, contentDescription = "Close Search")
        }
    } else if (isPrivacyMode) {
        IconButton(modifier = Modifier.size(40.dp), onClick = { onViewOnlyPrivacyItemsChange(!viewOnlyPrivacyItems) }) {
            Icon(
                imageVector = if (viewOnlyPrivacyItems) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "只查看隐私条目",
                tint = if (viewOnlyPrivacyItems) Color(0xFFFFC107) else Color.Gray
            )
        }
        IconButton(modifier = Modifier.size(40.dp), onClick = { onShowChangePrivacyPasswordDialog() }) {
            Icon(Icons.Default.Lock, contentDescription = "修改密码")
        }
        IconButton(modifier = Modifier.size(40.dp), onClick = {
            onPrivacyModeChange(false)
            onViewOnlyPrivacyItemsChange(false)
        }) {
            Icon(Icons.Default.Logout, contentDescription = "退出隐私模式")
        }
    } else {
        val isFast = syncInterval == 1000L
        IconButton(modifier = Modifier.size(40.dp), onClick = {
            scope.launch { chatRepository.refreshHistoryFromCloud() }
        }) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (isSyncing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
                val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
                val syncRotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = if (isSyncing) 360f else 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "syncRotation"
                )
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "立即刷新",
                    tint = if (isSyncing) MaterialTheme.colorScheme.primary
                    else if (isServerConnected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp).then(
                        if (isSyncing) Modifier.graphicsLayer { rotationZ = syncRotation } else Modifier
                    )
                )
            }
        }

        IconButton(modifier = Modifier.size(40.dp), onClick = {
            chatRepository.setSyncInterval(if (isFast) 5000L else 1000L)
        }) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = if (isFast) "快速同步" else "普通同步",
                tint = if (isServerConnected) (if (isFast) Color(0xFFFFC107) else Color.Gray) else MaterialTheme.colorScheme.error
            )
        }

        IconButton(modifier = Modifier.size(40.dp), onClick = { onSearchActiveChange(true) }) {
            Icon(Icons.Default.Search, contentDescription = "Search")
        }

        IconButton(modifier = Modifier.size(40.dp), onClick = { onShowCalendarDialog() }) {
            Icon(Icons.Default.DateRange, contentDescription = "日历视图")
        }

        if (currentFolderId != null) {
            IconButton(modifier = Modifier.size(40.dp), onClick = {
                val name = messages.find { it.id == currentFolderId }?.content ?: ""
                onRenameFolder(currentFolderId, name)
            }) {
                Icon(Icons.Default.Edit, contentDescription = "重命名文件夹")
            }
            IconButton(modifier = Modifier.size(40.dp), onClick = {
                onGenerateFolderDiary(currentFolderId)
            }) {
                Icon(Icons.Default.MenuBook, contentDescription = "生成日记")
            }
        }
    }
}

@Composable
fun MainScreenDialogs(
    chatRepository: ChatRepository,
    showPackFolderDialog: Boolean,
    onPackFolderDismiss: () -> Unit,
    folderAnnotation: String,
    onFolderAnnotationChange: (String) -> Unit,
    onPackFolderConfirm: (String) -> Unit,
    showUnpackFolderConfirmDialog: Boolean = false,
    onUnpackConfirmDismiss: () -> Unit = {},
    onUnpackConfirmConfirm: (Boolean) -> Unit = {},
    isUnpackingFolderObj: Boolean = false,
    selectedCount: Int = 0,
    showEditTextDialog: Boolean,
    onEditTextDismiss: () -> Unit,
    editingTargetMessage: com.cloudchat.model.ChatMessage?,
    onEditTextConfirm: (String) -> Unit,
    showEditCaptionDialog: Boolean,
    onEditCaptionDismiss: () -> Unit,
    onEditCaptionConfirm: (String) -> Unit,
    showChangePrivacyPasswordDialog: Boolean,
    onChangePrivacyPasswordDismiss: () -> Unit,
    onChangePrivacyPasswordConfirm: (String) -> Unit,
    showRenameFolderDialog: Boolean,
    onRenameFolderDismiss: () -> Unit,
    renameFolderText: String,
    onRenameFolderTextChange: (String) -> Unit,
    onRenameFolderConfirm: (String) -> Unit,
    showDeleteMessagesConfirmDialog: Boolean,
    onDeleteConfirmDismiss: () -> Unit,
    onDeleteConfirmConfirm: () -> Unit
) {
    val context = LocalContext.current
    if (showPackFolderDialog) {
        AlertDialog(
            onDismissRequest = onPackFolderDismiss,
            title = { Text("打包到文件夹") },
            text = {
                OutlinedTextField(
                    value = folderAnnotation,
                    onValueChange = onFolderAnnotationChange,
                    label = { Text("文件夹注释/名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { onPackFolderConfirm(folderAnnotation) }) {
                    Text("打包")
                }
            },
            dismissButton = {
                TextButton(onClick = onPackFolderDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showUnpackFolderConfirmDialog) {
        val titleText = if (isUnpackingFolderObj) "解散文件夹确认" else "移出文件夹确认"
        val bodyText = if (isUnpackingFolderObj) {
            "确定要解散选中的文件夹吗？"
        } else {
            "确定要将选中的 ${selectedCount} 个条目移出文件夹吗？"
        }

        AlertDialog(
            onDismissRequest = onUnpackConfirmDismiss,
            title = { Text(titleText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bodyText)
                    if (isUnpackingFolderObj) {
                        Text(
                            text = "请选择拆散方式：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (isUnpackingFolderObj) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { onUnpackConfirmConfirm(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("只拆散一级")
                        }
                        Button(
                            onClick = { onUnpackConfirmConfirm(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("全部拆散")
                        }
                    }
                } else {
                    Button(
                        onClick = { onUnpackConfirmConfirm(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("确认移出")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onUnpackConfirmDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showEditTextDialog && editingTargetMessage != null) {
        var textValue by remember { mutableStateOf(editingTargetMessage.content) }
        AlertDialog(
            onDismissRequest = onEditTextDismiss,
            title = { Text("修改文本内容") },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(onClick = { onEditTextConfirm(textValue) }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onEditTextDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showEditCaptionDialog && editingTargetMessage != null) {
        var captionValue by remember { mutableStateOf(editingTargetMessage.caption ?: "") }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = onEditCaptionDismiss,
            title = { Text("修改文件注释") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = captionValue,
                        onValueChange = { captionValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        label = { Text("注释内容") }
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val addr = getImageAddress(context, chatRepository, editingTargetMessage)
                                    if (!addr.isNullOrBlank()) {
                                        captionValue = if (captionValue.isBlank()) addr else "$captionValue $addr"
                                    }
                                }
                            },
                            label = { Text("📌 提取地址", fontSize = 12.sp) }
                        )
                        AssistChip(
                            onClick = {
                                val timeStr = getImageCaptureTime(context, chatRepository, editingTargetMessage)
                                captionValue = if (captionValue.isBlank()) timeStr else "$captionValue $timeStr"
                            },
                            label = { Text("⏰ 提取时间", fontSize = 12.sp) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onEditCaptionConfirm(captionValue) }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onEditCaptionDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showChangePrivacyPasswordDialog) {
        var newPinText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onChangePrivacyPasswordDismiss,
            title = { Text("修改隐私密码") },
            text = {
                OutlinedTextField(
                    value = newPinText,
                    onValueChange = { newPinText = it },
                    label = { Text("新密码 (数字)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPinText.isNotBlank()) {
                        onChangePrivacyPasswordConfirm(newPinText.trim())
                    }
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onChangePrivacyPasswordDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showRenameFolderDialog) {
        AlertDialog(
            onDismissRequest = onRenameFolderDismiss,
            title = { Text("重命名文件夹") },
            text = {
                OutlinedTextField(
                    value = renameFolderText,
                    onValueChange = onRenameFolderTextChange,
                    label = { Text("文件夹名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = { onRenameFolderConfirm(renameFolderText) }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onRenameFolderDismiss) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteMessagesConfirmDialog) {
        AlertDialog(
            onDismissRequest = onDeleteConfirmDismiss,
            title = { Text("删除消息") },
            text = { Text("确定删除选中的消息吗？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = onDeleteConfirmConfirm) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteConfirmDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun CalendarDialog(
    onDismissRequest: () -> Unit,
    messages: List<ChatMessage>,
    onSelectDate: (String) -> Unit
) {
    val today = Calendar.getInstance()
    var year by remember { mutableStateOf(today.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(today.get(Calendar.MONTH)) } // 0..11
    var viewMode by remember { mutableStateOf("DAY") } // "DAY", "MONTH", "YEAR"
    var showYearDropdown by remember { mutableStateOf(false) }
    var showMonthDropdown by remember { mutableStateOf(false) }

    val (datesWithMessages, yearsWithMessages, monthsWithMessages) = remember(messages) {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val sdfYear = SimpleDateFormat("yyyy", Locale.getDefault())
        val datesMap = mutableMapOf<String, Int>()
        val yearsSet = mutableSetOf<Int>()
        val monthsSet = mutableSetOf<String>()

        messages.forEach { msg ->
            if (msg.timestamp > 0) {
                val dateObj = Date(msg.timestamp)
                val dStr = sdfDate.format(dateObj)
                val mStr = sdfMonth.format(dateObj)
                val yInt = sdfYear.format(dateObj).toIntOrNull() ?: 0
                datesMap[dStr] = (datesMap[dStr] ?: 0) + 1
                if (yInt > 0) yearsSet.add(yInt)
                monthsSet.add(mStr)
            }
        }
        Triple(datesMap, yearsSet, monthsSet)
    }

    val monthCalendar = remember(year, month) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal
    }

    val firstDayOfWeek = monthCalendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
    val daysInMonth = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    // Generate years range (from 2010 or earliest message year up to today+2)
    val startYear = remember(yearsWithMessages) {
        val minYearInMsgs = if (yearsWithMessages.isNotEmpty()) yearsWithMessages.minOrNull() ?: 2015 else 2015
        minOf(2010, minYearInMsgs)
    }
    val availableYears = remember(year, startYear) {
        val maxYear = maxOf(today.get(Calendar.YEAR) + 2, year + 2)
        (maxYear downTo startYear).toList()
    }

    val monthNames = listOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月")

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("日历跳转", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = {
                        val now = Calendar.getInstance()
                        year = now.get(Calendar.YEAR)
                        month = now.get(Calendar.MONTH)
                        viewMode = "DAY"
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("今", fontSize = 13.sp)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Year/Month Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewMode == "DAY") {
                        IconButton(onClick = {
                            if (month == 0) {
                                month = 11
                                year--
                            } else {
                                month--
                            }
                        }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Year Dropdown / Toggle Button
                        Box {
                            TextButton(
                                onClick = { showYearDropdown = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                val hasMsgs = yearsWithMessages.contains(year)
                                Text(
                                    text = "${year}年${if (hasMsgs) " •" else ""}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = showYearDropdown,
                                onDismissRequest = { showYearDropdown = false },
                                modifier = Modifier.heightIn(max = 280.dp)
                            ) {
                                availableYears.forEach { y ->
                                    val hasMsgs = yearsWithMessages.contains(y)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(text = "${y}年", fontWeight = if (y == year) FontWeight.Bold else FontWeight.Normal)
                                                if (hasMsgs) {
                                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                                }
                                            }
                                        },
                                        onClick = {
                                            year = y
                                            showYearDropdown = false
                                            viewMode = "DAY"
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Month Dropdown / Toggle Button
                        Box {
                            TextButton(
                                onClick = { showMonthDropdown = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                val mKey = String.format(Locale.getDefault(), "%04d-%02d", year, month + 1)
                                val hasMsgs = monthsWithMessages.contains(mKey)
                                Text(
                                    text = "${month + 1}月${if (hasMsgs) " •" else ""}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = showMonthDropdown,
                                onDismissRequest = { showMonthDropdown = false }
                            ) {
                                monthNames.forEachIndexed { idx, name ->
                                    val mKey = String.format(Locale.getDefault(), "%04d-%02d", year, idx + 1)
                                    val hasMsgs = monthsWithMessages.contains(mKey)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(text = name, fontWeight = if (idx == month) FontWeight.Bold else FontWeight.Normal)
                                                if (hasMsgs) {
                                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                                }
                                            }
                                        },
                                        onClick = {
                                            month = idx
                                            showMonthDropdown = false
                                            viewMode = "DAY"
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (viewMode == "DAY") {
                        IconButton(onClick = {
                            if (month == 11) {
                                month = 0
                                year++
                            } else {
                                month++
                            }
                        }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // View Mode Tabs: Day / Month / Year
                TabRow(
                    selectedTabIndex = when (viewMode) {
                        "MONTH" -> 1
                        "YEAR" -> 2
                        else -> 0
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = viewMode == "DAY",
                        onClick = { viewMode = "DAY" },
                        text = { Text("日视图", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = viewMode == "MONTH",
                        onClick = { viewMode = "MONTH" },
                        text = {
                            val mKey = String.format(Locale.getDefault(), "%04d-%02d", year, month + 1)
                            Text("月视图${if (monthsWithMessages.contains(mKey)) " •" else ""}", fontSize = 12.sp)
                        }
                    )
                    Tab(
                        selected = viewMode == "YEAR",
                        onClick = { viewMode = "YEAR" },
                        text = {
                            Text("年视图${if (yearsWithMessages.contains(year)) " •" else ""}", fontSize = 12.sp)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (viewMode) {
                    "DAY" -> {
                        // Days of week header
                        val weekDays = listOf("日", "一", "二", "三", "四", "五", "六")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            weekDays.forEachIndexed { idx, day ->
                                Text(
                                    text = day,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (idx) {
                                        0 -> Color(0xFFE53935)
                                        6 -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Days Grid
                        val totalCells = firstDayOfWeek + daysInMonth
                        val totalRows = (totalCells + 6) / 7

                        Column {
                            for (row in 0 until totalRows) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    for (col in 0 until 7) {
                                        val cellIndex = row * 7 + col
                                        val dayNum = cellIndex - firstDayOfWeek + 1

                                        if (dayNum in 1..daysInMonth) {
                                            val dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayNum)
                                            val msgCount = datesWithMessages[dateStr] ?: 0
                                            val hasMessages = msgCount > 0
                                            val isToday = dateStr == todayStr

                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .padding(1.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                                        else Color.Transparent
                                                    )
                                                    .clickable(enabled = hasMessages) {
                                                        onSelectDate(dateStr)
                                                        onDismissRequest()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Text(
                                                        text = "$dayNum",
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (hasMessages) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                                    )
                                                    if (hasMessages) {
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
                                                                .clip(CircleShape)
                                                                .background(MaterialTheme.colorScheme.primary)
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "MONTH" -> {
                        // 3 Column Month Grid with Indicator Dots
                        Column(modifier = Modifier.fillMaxWidth()) {
                            for (r in 0..3) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    for (c in 0..2) {
                                        val mIdx = r * 3 + c
                                        val mKey = String.format(Locale.getDefault(), "%04d-%02d", year, mIdx + 1)
                                        val hasMsgs = monthsWithMessages.contains(mKey)
                                        val isCurrent = mIdx == month

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(54.dp)
                                                .padding(3.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                )
                                                .clickable {
                                                    month = mIdx
                                                    viewMode = "DAY"
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = monthNames[mIdx],
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (hasMsgs) {
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "YEAR" -> {
                        // 3 Column Year Grid with Indicator Dots
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            val rows = (availableYears.size + 2) / 3
                            for (r in 0 until rows) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    for (c in 0..2) {
                                        val yIdx = r * 3 + c
                                        if (yIdx < availableYears.size) {
                                            val yVal = availableYears[yIdx]
                                            val hasMsgs = yearsWithMessages.contains(yVal)
                                            val isCurrent = yVal == year

                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                                    .padding(3.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                    )
                                                    .clickable {
                                                        year = yVal
                                                        viewMode = "DAY"
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = "${yVal}年",
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (hasMsgs) {
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
                                                                .clip(CircleShape)
                                                                .background(MaterialTheme.colorScheme.primary)
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DiaryGenerateDialog(
    messages: List<ChatMessage>,
    defaultTitle: String = "我的日记",
    onDismiss: () -> Unit,
    onGenerate: suspend (title: String, author: String, templateId: String, password: String, coverUri: Uri?, onProgress: (Int, String) -> Unit) -> String?,
    onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember { mutableStateOf(defaultTitle) }
    var author by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf("wechat") }
    var enablePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    // 背景图：uri 形式，null 表示未设置
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var showCoverPicker by remember { mutableStateOf(false) }

    // 从手机相册选择背景图
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coverUri = uri
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        title = { Text("生成静态日记页面") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (resultUrl != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✅ 日记网页已生成！", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = resultUrl!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                try {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(resultUrl!!)))
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("打开预览") }
                            Button(onClick = { resultUrl = null; isGenerating = false; progress = 0 }) { Text("再次生成") }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("日记标题") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("作者署名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 模板选择
                    Text("选择模板", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = templateId == "wechat",
                            onClick = { templateId = "wechat" },
                            label = { Text("朋友圈九宫格") }
                        )
                        FilterChip(
                            selected = templateId == "journal",
                            onClick = { templateId = "journal" },
                            label = { Text("简约现代") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // 密码选项
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("启用访问密码", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = enablePassword, onCheckedChange = { enablePassword = it })
                    }
                    if (enablePassword) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("访问密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 背景图选择
                    Text("顶部背景图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (coverUri != null) {
                            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF0F0F0))) {
                                AsyncImage(model = coverUri, contentDescription = "背景图", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        // 从聊天条目选择背景图
                        OutlinedButton(onClick = { showCoverPicker = true }) {
                            Text("从聊天选择", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = {
                            // 从手机相册选择
                            coverPickerLauncher.launch("image/*")
                        }) {
                            Text("从相册选择", style = MaterialTheme.typography.labelSmall)
                        }
                        if (coverUri != null) {
                            TextButton(onClick = { coverUri = null }) {
                                Text("清除", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "将归档 ${messages.size} 条消息（媒体文件会一并上传到 diary 目录）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(statusText, style = MaterialTheme.typography.bodySmall)
                    }

                    if (errorMsg.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (resultUrl != null) {
                TextButton(onClick = {
                    onSuccess()
                }) { Text("完成") }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            errorMsg = ""
                            progress = 0
                            val url = onGenerate(title, author, templateId, if (enablePassword) password else "", coverUri, { p, t ->
                                progress = p
                                statusText = t
                            })
                            if (url != null) {
                                resultUrl = url
                            } else {
                                errorMsg = "生成失败，请检查服务器配置和网络"
                            }
                            isGenerating = false
                        }
                    },
                    enabled = !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("生成中...")
                    } else {
                        Text("生成日记")
                    }
                }
            }
        },
        dismissButton = {
            if (resultUrl == null) {
                TextButton(onClick = onDismiss, enabled = !isGenerating) { Text("取消") }
            }
        }
    )

    // 从聊天条目选择背景图
    if (showCoverPicker) {
        val chatImages = messages.filter { it.type == MessageType.IMAGE }
        AlertDialog(
            onDismissRequest = { showCoverPicker = false },
            title = { Text("从聊天选择背景图") },
            text = {
                if (chatImages.isEmpty()) {
                    Text("当前条目中没有图片消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(chatImages) { img ->
                            // 本地缓存文件路径：filesDir/media/{msg.id}_{fileName}
                            val localFile = File(context.filesDir, "media/${img.id}_${img.content}")
                            val displayUri = if (localFile.exists()) Uri.fromFile(localFile) else Uri.parse(img.remoteUrl ?: "")
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        coverUri = displayUri
                                        showCoverPicker = false
                                    }
                            ) {
                                AsyncImage(model = displayUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoverPicker = false }) { Text("取消") }
            }
        )
    }
}

