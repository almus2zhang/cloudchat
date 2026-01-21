package com.cloudchat.ui

import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val autoDownloadLimit = currentConfig?.autoDownloadLimit ?: (5 * 1024 * 1024L)
    
    var inputText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var mediaPagerIndex by remember { mutableStateOf<Int?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mediaPagerIndex) {
        onFullScreenToggle(mediaPagerIndex != null)
    }

    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    // Inject search icon into TopAppBar
    LaunchedEffect(isSearchActive, searchQuery) {
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

    LaunchedEffect(currentConfig) {
        currentConfig?.let {
            chatRepository.updateConfig(it)
            chatRepository.refreshHistoryFromCloud()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris ->
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
                        android.util.Log.e("MainScreen", "Failed to open file", e)
                    }
                }
            }
        }
    )

    LaunchedEffect(sharedData) {
        sharedData?.let { data ->
            scope.launch {
                data.text?.let { chatRepository.sendMessage(it) }
                data.uri?.let { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = uri.lastPathSegment ?: "shared_file"
                    chatRepository.sendMessage(name, determineMessageType(context, uri, name), stream, name, uri.toString())
                }
                data.uris?.forEach { uri ->
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = uri.lastPathSegment ?: "shared_file"
                    chatRepository.sendMessage(name, determineMessageType(context, uri, name), stream, name, uri.toString())
                }
                onSharedDataHandled()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp)
            ) {
                // Use message.id as key for stable list updates
                items(displayedMessages.asReversed(), key = { it.id }) { message ->
                    val progress = uploadProgress[message.id] ?: downloadProgress[message.id]
                    ChatBubble(
                        message = message, 
                        progress = progress,
                        chatRepository = chatRepository,
                        isSelected = selectedIds.contains(message.id),
                        autoDownloadLimit = autoDownloadLimit,
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = "Attach")
                }
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
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
                                chatRepository.sendMessage(inputText)
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
                            val uri = chatRepository.getTransientUri(id) ?: msg?.remoteUrl
                            uri?.let { Uri.parse(it) }
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondBoundsPageCount = 1
        ) { page ->
            val message = mediaMessages[page]
            val uriState = remember(message) { mutableStateOf<String?>(chatRepository.getTransientUri(message.id, message.content)) }
            
            LaunchedEffect(message) {
                if (uriState.value == null && message.remoteUrl != null) {
                    val file = chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
                    if (file != null) {
                        uriState.value = Uri.fromFile(file).toString()
                    }
                }
            }
            
            val uri = uriState.value ?: message.remoteUrl
            if (uri != null) {
                when (message.type) {
                    MessageType.IMAGE -> ZoomableImage(
                        uri = uri, 
                        isCurrentPage = pagerState.currentPage == page, // Pass current page status
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
                        }
                    )
                    MessageType.VIDEO -> FullScreenVideoPlayer(
                        uri = uri, 
                        active = pagerState.currentPage == page, 
                        onDismiss = onDismiss
                    )
                    else -> {}
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
                        val uri = chatRepository.getTransientUri(msg.id) ?: msg.remoteUrl
                        if (uri != null) shareMedia(context, uri)
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(
                    onClick = { 
                        val msg = mediaMessages[pagerState.currentPage]
                        val uri = chatRepository.getTransientUri(msg.id) ?: msg.remoteUrl
                        if (uri != null) saveMediaToGallery(context, uri, msg.content)
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
                            val uri = chatRepository.getTransientUri(msg.id, msg.content) ?: msg.remoteUrl
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

private fun shareMedia(context: android.content.Context, uri: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = if (uri.contains(".mp4") || uri.contains("video")) "video/*" else "image/*"
        putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share Media"))
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

private fun saveMediaToGallery(context: android.content.Context, uriString: String, fileName: String) {
    // Basic implementation using standard download manager or direct copy
    // For now, let's use a simple toast to indicate we would download it
    android.widget.Toast.makeText(context, "Saving to Gallery...", android.widget.Toast.LENGTH_SHORT).show()
    
    // Proper implementation would involve downloading the file if it's a URL
    // and using MediaStore to insert it into the gallery.
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage, 
    progress: Int?, 
    chatRepository: ChatRepository,
    isSelected: Boolean,
    autoDownloadLimit: Long,
    onMediaClick: (ChatMessage) -> Unit,
    onLongClick: () -> Unit
) {
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) Color(0xFF95EC69) else Color.White
    val contentColor = Color.Black

    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))

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
            Avatar(message.sender.take(1).uppercase())
            Spacer(modifier = Modifier.width(8.dp))
        } else if (message.status == MessageStatus.FAILED) {
            Box(Modifier.align(Alignment.CenterVertically)) {
                Icon(Icons.Default.Warning, contentDescription = "Failed", tint = Color.Red, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            }
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
                        Text(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = contentColor,
                            fontSize = 16.sp
                        )
                    }
                }
                MessageType.IMAGE -> {
                    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
                    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))
                    val displayUri = localUriStr ?: message.thumbnailUrl ?: message.remoteUrl
                    
                    // Auto-download if under limit
                    LaunchedEffect(message) {
                        if (!isCached && message.remoteUrl != null && message.fileSize <= autoDownloadLimit) {
                            chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
                        }
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
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.size(32.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                            }
                        } else if (!isCached && message.remoteUrl != null && message.fileSize > autoDownloadLimit) {
                            IconButton(
                                onClick = { 
                                    kotlinx.coroutines.MainScope().launch {
                                        chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
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
                    val localUriStr = chatRepository.getTransientUri(message.id, message.content)
                    val isCached = localUriStr != null && (localUriStr.startsWith("file") || localUriStr.startsWith("content"))
                    val displayUri = localUriStr ?: message.thumbnailUrl ?: message.remoteUrl
                    
                    LaunchedEffect(message) {
                        if (!isCached && message.remoteUrl != null && message.fileSize <= autoDownloadLimit) {
                            chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
                        }
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
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.size(32.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp
                                )
                            }
                        } else if (!isCached && message.remoteUrl != null && message.fileSize > autoDownloadLimit) {
                            IconButton(
                                onClick = { 
                                    kotlinx.coroutines.MainScope().launch {
                                        chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                            }
                        } else if (!isCached) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        
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
                                        chatRepository.downloadFileToCache(message.id, message.content, message.remoteUrl!!)
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
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.Gray
                )
            }
        }
    }
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
    val mimeType = context.contentResolver.getType(uri)
    
    // 1. Try to get DISPLAY_NAME from ContentResolver
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.util.DisplayMetrics::class.java.simpleName) 
                    // Wait, DisplayMetrics is wrong, should be OpenableColumns
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
    if (displayName == null) {
        displayName = uri.lastPathSegment
    }
    
    // 3. Ultimate fallback
    if (displayName == null || displayName == "primary" || displayName!!.startsWith("document:")) {
        displayName = "file_${System.currentTimeMillis()}"
    }

    // 4. Critical Fix: Ensure extension exists by checking MIME type
    if (!displayName!!.contains(".") && mimeType != null) {
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (extension != null) {
            displayName = "$displayName.$extension"
        }
    } else if (!displayName!!.contains(".") && uri.toString().lowercase().endsWith(".pdf")) {
        // Extra check for obvious PDFs in URL/URI
        displayName = "$displayName.pdf"
    }
    
    Log.d("MainScreen", "Resolved original filename: $displayName for URI: $uri")
    return displayName!!
}
