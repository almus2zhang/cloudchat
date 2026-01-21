package com.cloudchat.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    sharedData: SharedData?,
    onSharedDataHandled: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val chatRepository = remember { ChatRepository(context) }
    
    val currentConfig by settingsRepository.currentConfig.collectAsState(initial = null)
    val messages by chatRepository.messages.collectAsState()
    val uploadProgress by chatRepository.uploadProgress.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var mediaPagerIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

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
                    val stream = context.contentResolver.openInputStream(uri)
                    val name = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
                    val type = determineMessageType(context, uri, name)
                    chatRepository.sendMessage(
                        content = name, 
                        type = type, 
                        inputStream = stream, 
                        fileName = name,
                        localUri = uri.toString()
                    )
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
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 4.dp) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(displayedMessages.asReversed()) { message ->
                    val progress = uploadProgress[message.id]
                    ChatBubble(
                        message = message, 
                        progress = progress,
                        chatRepository = chatRepository,
                        onMediaClick = { clickedMsg ->
                            val index = mediaMessages.indexOfFirst { it.id == clickedMsg.id }
                            if (index != -1) {
                                mediaPagerIndex = index
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
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
        mediaPagerIndex?.let { initialIndex ->
            MediaPagerOverlay(
                mediaMessages = mediaMessages,
                initialIndex = initialIndex,
                chatRepository = chatRepository,
                onDismiss = { mediaPagerIndex = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPagerOverlay(
    mediaMessages: List<ChatMessage>,
    initialIndex: Int,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaMessages.size })
    
    BackHandler { onDismiss() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondBoundsPageCount = 1
        ) { page ->
            val message = mediaMessages[page]
            val uri = chatRepository.getTransientUri(message.id) ?: message.remoteUrl
            if (uri != null) {
                when (message.type) {
                    MessageType.IMAGE -> ZoomableImage(uri = uri, onTap = onDismiss)
                    MessageType.VIDEO -> FullScreenVideoPlayer(
                        uri = uri, 
                        active = pagerState.currentPage == page, 
                        onDismiss = onDismiss
                    )
                    else -> {}
                }
            }
        }

        // Overlay UI: Only page indicator
        Text(
            text = "${pagerState.currentPage + 1} / ${mediaMessages.size}",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            fontSize = 14.sp
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage, 
    progress: Int?, 
    chatRepository: ChatRepository,
    onMediaClick: (ChatMessage) -> Unit
) {
    val isOutgoing = message.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOutgoing) {
            Avatar(message.sender.take(1).uppercase())
            Spacer(modifier = Modifier.width(8.dp))
        } else if (message.status == MessageStatus.FAILED) {
            Icon(Icons.Default.Warning, contentDescription = "Failed", tint = Color.Red, modifier = Modifier.size(20.dp).padding(end = 4.dp))
        }

        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(containerColor = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                when (message.type) {
                    MessageType.TEXT -> Text(text = message.content)
                    MessageType.IMAGE -> {
                        val displayUri = chatRepository.getTransientUri(message.id) ?: message.remoteUrl
                        Box(contentAlignment = Alignment.BottomEnd) {
                            AsyncImage(
                                model = displayUri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).clip(MaterialTheme.shapes.small).clickable { onMediaClick(message) },
                                contentScale = ContentScale.FillWidth
                            )
                            if (message.fileSize > 0) FileSizeBadge(message.fileSize)
                        }
                    }
                    MessageType.VIDEO -> {
                        val displayUri = chatRepository.getTransientUri(message.id) ?: message.remoteUrl
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16/9f).clip(MaterialTheme.shapes.small).background(Color.Black).clickable { onMediaClick(message) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
                            if (message.videoDuration > 0) {
                                Text(
                                    text = formatDuration(message.videoDuration),
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small).padding(horizontal = 4.dp),
                                    fontSize = 10.sp
                                )
                            }
                            if (message.fileSize > 0) FileSizeBadge(message.fileSize)
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = message.content, fontWeight = FontWeight.Bold, maxLines = 1)
                                if (message.fileSize > 0) Text(text = formatFileSize(message.fileSize), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (progress != null && progress != -1) {
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp).clip(MaterialTheme.shapes.small))
                }
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
