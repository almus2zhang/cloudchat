package com.cloudchat.ui

import android.net.Uri
import android.util.Log
import android.content.ContentValues
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.ContentUris
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.*
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
    onFullScreenToggle: (Boolean) -> Unit,
    onSharedDataHandled: () -> Unit,
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
    val autoDownloadLimit = currentConfig?.autoDownloadLimit ?: (5 * 1024 * 1024L)
    
    var inputText by remember { mutableStateOf("") }
    var isVoiceMode by remember { mutableStateOf(false) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var mediaPagerIndex by remember { mutableStateOf<Int?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    var activeCategory by remember { mutableStateOf("all") } // "all" or "diary"
    var diaryFiles by remember { mutableStateOf<List<com.cloudchat.repository.DiaryFileItem>>(emptyList()) }
    var isLoadingDiaryFiles by remember { mutableStateOf(false) }

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
                currentFolderId = null
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
        } else if (isTextSelected) {
            textSelectionClearKey++
            isTextSelected = false
        } else if (isAttachmentPanelVisible) {
            isAttachmentPanelVisible = false
        } else if (currentFolderId != null) {
            currentFolderId = null
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
    LaunchedEffect(isSearchActive, searchQuery, syncInterval, isServerConnected, isPrivacyMode, viewOnlyPrivacyItems, currentFolderId) {
        setTopBarActions {
            if (isSearchActive) {
                // Auto-focus when search is activated
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                }
                
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search messages...") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .focusRequester(searchFocusRequester),
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(containerColor = Color.Transparent)
                )
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    isSearchActive = false
                    searchQuery = ""
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close Search")
                }
            } else if (isPrivacyMode) {
                // Privacy space controls: view-only toggle / change password / exit.
                IconButton(modifier = Modifier.size(40.dp), onClick = { viewOnlyPrivacyItems = !viewOnlyPrivacyItems }) {
                    Icon(
                        imageVector = if (viewOnlyPrivacyItems) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "只查看隐私条目",
                        tint = if (viewOnlyPrivacyItems) Color(0xFFFFC107) else Color.Gray
                    )
                }
                IconButton(modifier = Modifier.size(40.dp), onClick = { showChangePrivacyPasswordDialog = true }) {
                    Icon(Icons.Default.Lock, contentDescription = "修改密码")
                }
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    isPrivacyMode = false
                    viewOnlyPrivacyItems = false
                }) {
                    Icon(Icons.Default.Logout, contentDescription = "退出隐私模式")
                }
            } else {
                val isFast = syncInterval == 1000L
                
                // Manual Refresh Button
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    scope.launch { chatRepository.refreshHistoryFromCloud() }
                }) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "立即刷新",
                        tint = if (isServerConnected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                }

                // Sync All Media Files Button
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    if (isMediaSyncing) return@IconButton
                    isMediaSyncing = true
                    scope.launch {
                        chatRepository.syncAllMediaFiles { current, total ->
                            if (current >= total || total == 0) isMediaSyncing = false
                        }
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = "同步所有文件",
                        tint = if (isMediaSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Sync Interval Toggle (Bolt icon colored by mode)
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    chatRepository.setSyncInterval(if (isFast) 5000L else 1000L)
                }) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = if (isFast) "快速同步" else "普通同步",
                        tint = if (isServerConnected) (if (isFast) Color(0xFFFFC107) else Color.Gray) else MaterialTheme.colorScheme.error
                    )
                }
                
                IconButton(modifier = Modifier.size(40.dp), onClick = { isSearchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }

                IconButton(modifier = Modifier.size(40.dp), onClick = { showCalendarDialog = true }) {
                    Icon(Icons.Default.DateRange, contentDescription = "日历视图")
                }

                // Rename current folder (only visible when inside a folder)
                if (currentFolderId != null) {
                    IconButton(modifier = Modifier.size(40.dp), onClick = {
                        renameTargetFolderId = currentFolderId
                        renameFolderText = messages.find { it.id == currentFolderId }?.content ?: ""
                        showRenameFolderDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "重命名文件夹")
                    }
                }
            }
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

    val mediaMessages = remember(messages, isPrivacyMode, viewOnlyPrivacyItems, currentFolderId) {
        messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
            .filter { msg ->
                // Hide privacy-space content unless explicitly viewing privacy space
                val matchesPrivacy = if (isPrivacyMode) {
                    if (viewOnlyPrivacyItems) msg.isHidden == true else true
                } else {
                    msg.isHidden != true
                }
                if (!matchesPrivacy) return@filter false
                // When inside a folder, only show media that belongs to that folder
                if (currentFolderId != null) {
                    msg.folderId == currentFolderId
                } else {
                    msg.folderId.isNullOrEmpty()
                }
            }
    }


    LaunchedEffect(currentConfig, appMode) {
        currentConfig?.let {
            chatRepository.updateConfig(it, appMode)
            chatRepository.refreshHistoryFromCloud()
            
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

    Box(modifier = Modifier.fillMaxSize().pointerInput(isPrivacyMode) {
        if (isPrivacyMode) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    lastPrivacyActivity = System.currentTimeMillis()
                }
            }
        }
    }) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Folder header: show the folder name/annotation while browsing inside it
            if (currentFolderId != null) {
                val folder = messages.find { it.id == currentFolderId }
                if (folder != null) {
                    Surface(
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (folder.content.isNotBlank()) folder.content else "文件夹",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

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
                            currentFolderId = null
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
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "打开",
                                                tint = MaterialTheme.colorScheme.primary,
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(dragModifier),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(chatUiItems.asReversed(), key = { it.id }) { uiItem ->
                    when (uiItem) {
                        is ChatUiItem.SingleMessage -> {
                            val message = uiItem.message
                            val progress = uploadProgress[message.id] ?: downloadProgress[message.id]
                            val isDownloading = activeDownloadIds.contains(message.id)
                            
                            ChatBubble(
                                message = message, 
                                progress = progress,
                                chatRepository = chatRepository,
                                isSelected = selectedIds.contains(message.id),
                                autoDownloadLimit = autoDownloadLimit,
                                downloadProgress = downloadProgress,
                                isDownloading = isDownloading,
                                playingMessageId = playingMessageId,
                                onPlayAudio = { playAudioMessage(it) },
                                onMediaClick = { clickedMsg ->
                                    if (selectedIds.isNotEmpty()) {
                                        selectedIds = if (selectedIds.contains(clickedMsg.id)) {
                                            selectedIds - clickedMsg.id
                                        } else {
                                            selectedIds + clickedMsg.id
                                        }
                                    } else {
                                        if (clickedMsg.type == MessageType.IMAGE || clickedMsg.type == MessageType.VIDEO) {
                                            val index = mediaMessages.indexOfFirst { it.id == clickedMsg.id }
                                            if (index != -1) {
                                                mediaPagerIndex = index
                                            }
                                        } else if (clickedMsg.type == MessageType.AUDIO) {
                                            playAudioMessage(clickedMsg)
                                        } else if (clickedMsg.type == MessageType.FOLDER) {
                                            currentFolderId = clickedMsg.id
                                        } else if (clickedMsg.type == MessageType.FILE) {
                                            openFileWithDefaultApp(context, chatRepository, clickedMsg)
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (selectedIds.isEmpty()) {
                                        selectedIds = setOf(message.id)
                                    }
                                }
                            )
                        }
                        is ChatUiItem.ImageGroup -> {
                            ImageGroupBubble(
                                group = uiItem,
                                chatRepository = chatRepository,
                                selectedIds = selectedIds,
                                onSelectToggle = { clickedMsg ->
                                    selectedIds = if (selectedIds.contains(clickedMsg.id)) {
                                        selectedIds - clickedMsg.id
                                    } else {
                                        selectedIds + clickedMsg.id
                                    }
                                },
                                onMediaClick = { clickedMsg ->
                                    val index = mediaMessages.indexOfFirst { it.id == clickedMsg.id }
                                    if (index != -1) {
                                        mediaPagerIndex = index
                                    }
                                },
                                onLongClick = { clickedMsg ->
                                    if (selectedIds.isEmpty()) {
                                        selectedIds = setOf(clickedMsg.id)
                                    }
                                },
                                onClickGroup = {
                                    if (selectedIds.isNotEmpty()) {
                                        val groupIds = uiItem.messages.map { it.id }.toSet()
                                        if (selectedIds.containsAll(groupIds)) {
                                            selectedIds = selectedIds - groupIds
                                        } else {
                                            selectedIds = selectedIds + groupIds
                                        }
                                    }
                                },
                                onLongClickGroup = {
                                    val groupIds = uiItem.messages.map { it.id }.toSet()
                                    if (selectedIds.containsAll(groupIds)) {
                                        selectedIds = selectedIds - groupIds
                                    } else {
                                        selectedIds = selectedIds + groupIds
                                    }
                                }
                            )
                        }
                    }
                }
            }

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
                            isVoiceMode = !isVoiceMode 
                            if (isVoiceMode) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                isAttachmentPanelVisible = false
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
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .onFocusChanged { 
                                    if (it.isFocused) {
                                        isAttachmentPanelVisible = false
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
                                attachLocationEnabled = !attachLocationEnabled
                                if (attachLocationEnabled && !hasLocationPermission) {
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
                                            inputText = ""
                                        }
                                    },
                                    onLongClick = {
                                        if (inputText.startsWith("##") && inputText.endsWith("##")) {
                                            val pin = inputText.substring(2, inputText.length - 2)
                                            if (pin == privacyPin) {
                                                isPrivacyMode = !isPrivacyMode
                                                viewOnlyPrivacyItems = isPrivacyMode
                                            }
                                            inputText = ""
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
                                isAttachmentPanelVisible = !isAttachmentPanelVisible
                                if (isAttachmentPanelVisible) {
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
                            showImagePicker = true
                            isAttachmentPanelVisible = false
                        },
                        onFileClick = {
                            filePickerLauncher.launch("*/*")
                            isAttachmentPanelVisible = false
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
                            isAttachmentPanelVisible = false
                        },
                        deleteSourceAfterSend = deleteSourceAfterSend,
                        onToggleDeleteSource = {
                            deleteSourceAfterSend = it
                            sharedPrefs.edit().putBoolean("delete_source_after_send", it).apply()
                        }
                    )
                }
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

        // Selection Toolbar (Bottom floating pill bar)
        if (selectedIds.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp) // Lift it slightly above bottom input bar
                    .navigationBarsPadding()
                    .wrapContentWidth(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }

                    // 1. Copy (only for text messages)
                    if (selectedIds.any { id -> messages.find { it.id == id }?.type == MessageType.TEXT }) {
                        val clipboardManager = LocalClipboardManager.current
                        IconButton(onClick = {
                            val textToCopy = selectedIds.mapNotNull { id ->
                                messages.find { it.id == id }
                            }.sortedBy { it.timestamp }
                             .joinToString("\n") { it.content }
                            
                            clipboardManager.setText(AnnotatedString(textToCopy))
                            android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }



                    // 2. Edit Text / Caption / Rename Folder
                    if (selectedIds.size == 1) {
                        val firstId = selectedIds.firstOrNull()
                        val singleMsg = if (firstId != null) messages.find { it.id == firstId } else null
                        if (singleMsg != null) {
                            IconButton(onClick = {
                                if (singleMsg.type == MessageType.FOLDER) {
                                    // Rename the folder
                                    renameTargetFolderId = singleMsg.id
                                    renameFolderText = singleMsg.content
                                    showRenameFolderDialog = true
                                } else {
                                    editingTargetMessage = singleMsg
                                    if (singleMsg.type == MessageType.TEXT) {
                                        showEditTextDialog = true
                                    } else {
                                        showEditCaptionDialog = true
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                        }
                    }

                    // 3. Pack Folder
                    IconButton(onClick = {
                        val selectedMessages = messages.filter { selectedIds.contains(it.id) }
                        val existingFolder = selectedMessages.find { it.type == MessageType.FOLDER }
                            ?: if (currentFolderId != null) messages.find { it.id == currentFolderId } else null

                        folderAnnotation = existingFolder?.content ?: ""
                        showPackFolderDialog = true
                    }) {
                        Icon(Icons.Default.Folder, contentDescription = "Pack Folder")
                    }

                    // 4. Unpack Folder / Move out of folder
                    val hasFolderToUnpack = selectedIds.any { id -> messages.find { it.id == id }?.type == MessageType.FOLDER }
                    val hasItemsInFolder = currentFolderId != null || selectedIds.any { id -> messages.find { it.id == id }?.folderId != null }

                    if (hasFolderToUnpack || hasItemsInFolder) {
                        IconButton(onClick = {
                            showUnpackFolderConfirmDialog = true
                        }) {
                            Icon(Icons.Default.FolderOff, contentDescription = "Unpack Folder")
                        }
                    }

                    // 5. Combine / Merge (Text & Grid)
                    if (selectedIds.size >= 2) {
                        IconButton(onClick = {
                            val newGroupId = "group_${System.currentTimeMillis()}"
                            scope.launch {
                                val selectedTextMsgs = selectedIds.mapNotNull { id -> messages.find { it.id == id } }.filter { it.type == MessageType.TEXT }
                                val textIds = selectedTextMsgs.map { it.id }.toSet()
                                val nonTextIds = selectedIds - textIds

                                // Merge texts
                                if (selectedTextMsgs.size >= 2) {
                                    val mergedText = selectedTextMsgs.sortedBy { it.timestamp }.joinToString("\n") { it.content }
                                    chatRepository.deleteMessages(textIds.toList())
                                    chatRepository.sendMessage(
                                        content = mergedText,
                                        type = MessageType.TEXT,
                                        inputStream = null,
                                        fileName = null,
                                        localUri = null,
                                        locationAddress = null,
                                        folderId = currentFolderId,
                                        deleteSourceFile = false
                                    )
                                }

                                // Group images (and other types)
                                if (nonTextIds.size >= 2) {
                                    chatRepository.groupSelectedMessages(nonTextIds, newGroupId)
                                } else if (nonTextIds.size == 1 && selectedTextMsgs.isEmpty()) {
                                    // Normally not reachable because total size >= 2
                                }

                                selectedIds = emptySet()
                            }
                        }) {
                            Icon(Icons.Default.GroupWork, contentDescription = "Combine")
                        }
                    }

                    // 6. Split / Ungroup
                    if (selectedIds.any { id -> !messages.find { it.id == id }?.groupId.isNullOrEmpty() }) {
                        IconButton(onClick = {
                            scope.launch {
                                val selectedMsgs = messages.filter { selectedIds.contains(it.id) }
                                chatRepository.ungroupMessages(selectedMsgs)
                                selectedIds = emptySet()
                            }
                        }) {
                            Icon(Icons.Default.CallSplit, contentDescription = "Uncombine")
                        }
                    }

                    // 7. Share
                    IconButton(onClick = { 
                        val uris = selectedIds.mapNotNull { id ->
                            val msg = messages.find { it.id == id }
                            val fileName = msg?.content ?: ""
                            val localFile = chatRepository.getLocalFile(id, fileName)
                            
                            if (localFile.exists()) {
                                try {
                                    val authority = "${context.packageName}.fileprovider"
                                    androidx.core.content.FileProvider.getUriForFile(context, authority, localFile)
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                msg?.remoteUrl?.let { chatRepository.resolveUrl(it)?.let { url -> Uri.parse(url) } }
                            }
                        }
                        if (uris.isNotEmpty()) {
                            shareMediaMultiple(context, uris)
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }

                    // 移出隐私空间（仅在隐私模式下显示）
                    if (isPrivacyMode) {
                        IconButton(onClick = {
                            scope.launch {
                                chatRepository.toggleHideMessages(selectedIds)
                                selectedIds = emptySet()
                                android.widget.Toast.makeText(context, "已移出隐私空间", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "移出隐私空间",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 删除图标：短按确认删除；长按移入隐私空间
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
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
                                            // 长按：移入隐私空间（无任何提示）
                                            scope.launch {
                                                chatRepository.toggleHideMessages(selectedIds)
                                                selectedIds = emptySet()
                                            }
                                        }
                                        if (!longPressHandled) {
                                            // 短按：确认删除
                                            showDeleteMessagesConfirmDialog = true
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                        },
                        onCancel = {
                            showImagePicker = false
                        }
                    )
                }
            }
        }

        MainScreenDialogs(
            showPackFolderDialog = showPackFolderDialog,
            onPackFolderDismiss = { showPackFolderDialog = false },
            folderAnnotation = folderAnnotation,
            onFolderAnnotationChange = { folderAnnotation = it },
            onPackFolderConfirm = { annotation ->
                showPackFolderDialog = false
                scope.launch {
                    val selectedMessages = messages.filter { selectedIds.contains(it.id) }
                    val existingFolder = selectedMessages.find { it.type == MessageType.FOLDER }
                    chatRepository.packIntoFolder(selectedMessages.filter { it.type != MessageType.FOLDER }, annotation, existingFolder?.id)
                    selectedIds = emptySet()
                }
            },
            showUnpackFolderConfirmDialog = showUnpackFolderConfirmDialog,
            onUnpackConfirmDismiss = { showUnpackFolderConfirmDialog = false },
            onUnpackConfirmConfirm = {
                showUnpackFolderConfirmDialog = false
                scope.launch {
                    selectedIds.forEach { id ->
                        val msg = messages.find { it.id == id }
                        if (msg?.type == MessageType.FOLDER) {
                            chatRepository.unpackFolder(id)
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

private fun shareMediaMultiple(context: android.content.Context, uris: List<Uri>) {
    val intent = android.content.Intent(if (uris.size > 1) android.content.Intent.ACTION_SEND_MULTIPLE else android.content.Intent.ACTION_SEND).apply {
        if (uris.size > 1) {
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        } else {
            putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        }
        type = "*/*"
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share Media"))
}

private fun openFileWithDefaultApp(context: android.content.Context, chatRepository: ChatRepository, message: ChatMessage) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch {
        Log.d("MainScreen", "Attempting to open file: ${message.content}")
        val toast = android.widget.Toast.makeText(context, "Preparing file...", android.widget.Toast.LENGTH_SHORT)
        toast.show()
        
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

        if (file != null && file.exists()) {
            try {
                val authority = "${context.packageName}.fileprovider"
                val contentUri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                
                // 1. Try to get MIME from extension
                val extension = file.extension.lowercase()
                var mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                
                // 2. Manual overrides for common types
                if (mimeType == null) {
                    mimeType = when (extension) {
                        "pdf" -> "application/pdf"
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "mp4" -> "video/mp4"
                        "txt" -> "text/plain"
                        else -> context.contentResolver.getType(contentUri)
                    }
                }
                
                // 3. Last resort
                if (mimeType == null || mimeType == "application/octet-stream") {
                    mimeType = "*/*"
                }

                Log.d("MainScreen", "Resolved File: ${file.absolutePath}, Name: ${file.name}, Ext: $extension, MIME: $mimeType")

                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                // Check if any app can handle it
                val packageManager = context.packageManager
                if (intent.resolveActivity(packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    // Try with */* if specific MIME failed
                    Log.w("MainScreen", "Specific MIME failed, trying */*")
                    intent.setDataAndType(contentUri, "*/*")
                    context.startActivity(android.content.Intent.createChooser(intent, "Open with..."))
                }
            } catch (e: Exception) {
                Log.e("MainScreen", "Crash opening file", e)
                android.widget.Toast.makeText(context, "Error opening ${file.name}: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            Log.e("MainScreen", "File not found at cache path")
            android.widget.Toast.makeText(context, "File not ready or failed to download", android.widget.Toast.LENGTH_SHORT).show()
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
    chatRepository: ChatRepository
): String {
    val displayName = senderName?.ifEmpty { "User" } ?: "User"
    val fallback = "https://api.dicebear.com/7.x/bottts/png?seed=${Uri.encode(displayName)}"

    val avatarName = rawAvatar?.ifEmpty { null }
    if (avatarName == null) return fallback
    if (avatarName.startsWith("http://") || avatarName.startsWith("https://") || avatarName.startsWith("file://") || avatarName.startsWith("data:") || avatarName.startsWith("content://")) {
        return avatarName
    }

    var resolvedUrl by remember(avatarName) { mutableStateOf<String?>(null) }
    LaunchedEffect(avatarName) {
        val path = chatRepository.resolveAvatarPath(avatarName)
        resolvedUrl = path
    }
    return resolvedUrl ?: fallback
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
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Left Side Avatar for Incoming Messages (44dp - ~9x area)
        if (!isOutgoing) {
            AsyncImage(
                model = userAvatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier
                    .padding(end = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.LightGray, CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        if (isOutgoing && message.status == MessageStatus.FAILED) {
            // Retry icon for failed outgoing messages (left of the bubble)
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .clickable {
                        scope.launch {
                            chatRepository.retryMessage(message.id)
                        }
                    }
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Retry", tint = Color.Red, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            }
        }

        Column(
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = nameColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp)
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
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displayUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            contentScale = ContentScale.FillWidth
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.content,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    fontSize = 15.sp,
                                    color = Color.Black
                                )
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

        // Right Side Avatar for Outgoing Messages (44dp - ~9x area)
        if (isOutgoing) {
            AsyncImage(
                model = userAvatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier
                    .padding(start = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                contentScale = ContentScale.Crop
            )
        }
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
    val bubbleColor = if (isOutgoing) Color(0xFF95EC69) else Color.White
    val contentColor = Color.Black
    
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
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onClickGroup,
                onLongClick = onLongClickGroup
            ),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Left Side Avatar for Incoming Group Messages
        if (!isOutgoing) {
            AsyncImage(
                model = userAvatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier
                    .padding(end = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.LightGray, CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.padding(2.dp)
            ) {
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

        // Right Side Avatar for Outgoing Group Messages
        if (isOutgoing) {
            AsyncImage(
                model = userAvatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier
                    .padding(start = 8.dp, top = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun MainScreenDialogs(
    showPackFolderDialog: Boolean,
    onPackFolderDismiss: () -> Unit,
    folderAnnotation: String,
    onFolderAnnotationChange: (String) -> Unit,
    onPackFolderConfirm: (String) -> Unit,
    showUnpackFolderConfirmDialog: Boolean = false,
    onUnpackConfirmDismiss: () -> Unit = {},
    onUnpackConfirmConfirm: () -> Unit = {},
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
            "确定要解散选中的文件夹并将所有条目移出吗？"
        } else {
            "确定要将选中的 ${selectedCount} 个条目移出文件夹吗？"
        }

        AlertDialog(
            onDismissRequest = onUnpackConfirmDismiss,
            title = { Text(titleText) },
            text = { Text(bodyText) },
            confirmButton = {
                Button(
                    onClick = onUnpackConfirmConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("确认移出")
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
        AlertDialog(
            onDismissRequest = onEditCaptionDismiss,
            title = { Text("修改文件注释") },
            text = {
                OutlinedTextField(
                    value = captionValue,
                    onValueChange = { captionValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
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

