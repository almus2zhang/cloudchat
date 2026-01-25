package com.cloudchat.ui

import android.net.Uri
import android.util.Log
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.snapshotFlow
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    sharedData: com.cloudchat.SharedData?,
    onFullScreenToggle: (Boolean) -> Unit,
    onSharedDataHandled: () -> Unit,
    setTopBarActions: (@Composable RowScope.() -> Unit) -> Unit
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
    val autoDownloadLimit = currentConfig?.autoDownloadLimit ?: (5 * 1024 * 1024L)
    
    var inputText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var mediaPagerIndex by remember { mutableStateOf<Int?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var isAttachmentPanelVisible by remember { mutableStateOf(false) }
    val appMode by settingsRepository.appMode.collectAsState(initial = com.cloudchat.model.AppMode.NOT_SET)
    val isSecurityAuthenticated by chatRepository.isSecurityAuthenticated.collectAsState()
    var showSecurityOverlay by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Handle back button to clear selection or close panel
    BackHandler(enabled = selectedIds.isNotEmpty() || isAttachmentPanelVisible) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = emptySet()
        } else {
            isAttachmentPanelVisible = false
        }
    }

    LaunchedEffect(mediaPagerIndex) {
        onFullScreenToggle(mediaPagerIndex != null)
    }

    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    // Inject search and sync icons into TopAppBar
    LaunchedEffect(isSearchActive, searchQuery, syncInterval) {
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
                IconButton(onClick = { 
                    isSearchActive = false
                    searchQuery = ""
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close Search")
                }
            } else {
                val isFast = syncInterval == 1000L
                
                // Manual Refresh Button
                IconButton(onClick = { 
                    scope.launch { chatRepository.refreshHistoryFromCloud() }
                }) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "立即刷新"
                    )
                }

                // Sync Interval Toggle (Bolt icon colored by mode)
                IconButton(onClick = { 
                    chatRepository.setSyncInterval(if (isFast) 5000L else 1000L) 
                }) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = if (isFast) "快速同步" else "普通同步",
                        tint = if (isFast) Color(0xFFFFC107) else Color.Gray
                    )
                }
                
                IconButton(onClick = { isSearchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
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

    val displayedMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages
        else messages.filter { 
            it.content.contains(searchQuery, ignoreCase = true) || 
            it.sender.contains(searchQuery, ignoreCase = true)
        }
    }

    val mediaMessages = remember(messages) {
        messages.filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
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
                    chatRepository.sendMessage(
                        content = name, 
                        type = type, 
                        inputStream = stream, 
                        fileName = name,
                        localUri = uri.toString()
                    )
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to open file", e)
                }
            }
        }
    }

    val multimediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = handleUris
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = handleUris
    )

    LaunchedEffect(sharedData) {
        sharedData?.let { data ->
            scope.launch {
                data.text?.let { chatRepository.sendMessage(it) }
                data.uri?.let { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = getFileName(context, uri)
                    chatRepository.sendMessage(name, determineMessageType(context, uri, name), stream, name, uri.toString())
                }
                data.uris?.forEach { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = getFileName(context, uri)
                    chatRepository.sendMessage(name, determineMessageType(context, uri, name), stream, name, uri.toString())
                }
                onSharedDataHandled()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp)
            ) {
                // Use message.id as key for stable list updates
                items(displayedMessages.asReversed(), key = { it.id }) { message ->
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
                                } else if (clickedMsg.type == MessageType.FILE || clickedMsg.type == MessageType.AUDIO) {
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
            }

            Column(modifier = Modifier.navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        isAttachmentPanelVisible = !isAttachmentPanelVisible
                        if (isAttachmentPanelVisible) {
                            keyboardController?.hide()
                        }
                    }) {
                        Icon(
                            if (isAttachmentPanelVisible) Icons.Default.Close else Icons.Default.Add, 
                            contentDescription = "Attach"
                        )
                    }
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).onFocusChanged { 
                            if (it.isFocused) {
                                isAttachmentPanelVisible = false
                                keyboardController?.show()
                            }
                        },
                        placeholder = { Text("Type a message") },
                        shape = MaterialTheme.shapes.medium,
                        colors = TextFieldDefaults.textFieldColors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                scope.launch {
                                    if (inputText.startsWith("/link ", ignoreCase = true)) {
                                        val fileName = inputText.substring(6).trim()
                                        if (fileName.isNotEmpty()) {
                                            chatRepository.linkServerFile(fileName)
                                        }
                                    } else {
                                        chatRepository.sendMessage(inputText)
                                    }
                                    inputText = ""
                                }
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }

                AnimatedVisibility(
                    visible = isAttachmentPanelVisible,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    AttachmentPanel(
                        onImageClick = {
                            multimediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            isAttachmentPanelVisible = false
                        },
                        onFileClick = {
                            filePickerLauncher.launch("*/*")
                            isAttachmentPanelVisible = false
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
                    onSelectIndex = { mediaPagerIndex = it }
                )
            }
        }

        // Selection Toolbar
        if (selectedIds.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                    Text(
                        text = "${selectedIds.size} selected", 
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
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
                    IconButton(onClick = { 
                        scope.launch {
                            chatRepository.deleteMessages(selectedIds.toList())
                            selectedIds = emptySet()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }

        if (showSecurityOverlay) {
            SecurityOverlay(chatRepository = chatRepository)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPagerOverlay(
    mediaMessages: List<ChatMessage>,
    initialIndex: Int,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit
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
                                    .clickable { 
                                        scope.launch {
                                            pagerState.scrollToPage(index)
                                            onSelectIndex(index)
                                            showGrid = false
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
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
        if (!isOutgoing) {
            Avatar(displayName.take(1).uppercase())
            Spacer(modifier = Modifier.width(8.dp))
            
            // Name on the left side, aligned top
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = nameColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp) // Tiny top padding for visual alignment with bubble top
                    .widthIn(max = 64.dp) // Constrain width to prevent squeezing message too much
            )
        } else {
            // Outgoing
            if (message.status == MessageStatus.FAILED) {
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
            
            // Name on the LEFT of the bubble
             Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = nameColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .widthIn(max = 64.dp) 
            )
        }

        Column(
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            when (message.type) {
                MessageType.TEXT -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bubbleColor),
                        shape = MaterialTheme.shapes.medium,
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
                    ) {
                        // Reverted to simple text content
                        Text(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = contentColor,
                            fontSize = 16.sp
                        )
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
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.Gray
                )
            }
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
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 32.dp, horizontal = 16.dp)
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
                icon = Icons.Default.InsertDriveFile,
                label = "Files",
                color = Color(0xFF2196F3),
                onClick = onFileClick
            )
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
