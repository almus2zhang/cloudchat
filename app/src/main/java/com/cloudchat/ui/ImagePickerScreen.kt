package com.cloudchat.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cloudchat.repository.MediaAlbum
import com.cloudchat.repository.MediaItem
import com.cloudchat.repository.MediaRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerScreen(
    onResult: (List<MediaItem>, Boolean) -> Unit, // Boolean true if 'Move' (delete source)
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaRepository = remember { MediaRepository(context) }
    
    var albums by remember { mutableStateOf<List<MediaAlbum>>(emptyList()) }
    var currentAlbum by remember { mutableStateOf<MediaAlbum?>(null) }
    var imagesInAlbum by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedImages by remember { mutableStateOf<Set<MediaItem>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        albums = mediaRepository.getAlbums()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentAlbum == null) "添加图片" else currentAlbum!!.name) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentAlbum != null) {
                            currentAlbum = null
                            selectedImages = emptySet()
                        } else {
                            onCancel()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (currentAlbum != null) {
                Surface(
                    color = Color(0xFF2D2D2D),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${selectedImages.size} 张",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = { onResult(selectedImages.toList(), true) },
                                enabled = selectedImages.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("移动")
                            }
                            Button(
                                onClick = { onResult(selectedImages.toList(), false) },
                                enabled = selectedImages.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("复制")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF121212))) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (currentAlbum == null) {
                // Albums Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(albums) { album ->
                        AlbumItem(album) {
                            currentAlbum = album
                            scope.launch {
                                isLoading = true
                                imagesInAlbum = mediaRepository.getImagesInAlbum(album.id)
                                isLoading = false
                            }
                        }
                    }
                }
            } else {
                // Images Grid
                val gridState = rememberLazyGridState()
                var dragStartItemIndex by remember { mutableStateOf<Int?>(null) }
                var inDragSelectionMode by remember { mutableStateOf(false) }
                var isSelectingMode by remember { mutableStateOf(true) }
                var initialSelectedImages by remember { mutableStateOf<Set<MediaItem>>(emptySet()) }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        offset.x >= item.offset.x && offset.x <= item.offset.x + item.size.width &&
                                        offset.y >= item.offset.y && offset.y <= item.offset.y + item.size.height
                                    }?.let { itemInfo ->
                                        dragStartItemIndex = itemInfo.index
                                        inDragSelectionMode = true
                                        initialSelectedImages = selectedImages
                                        
                                        val item = imagesInAlbum[itemInfo.index]
                                        isSelectingMode = !selectedImages.contains(item)
                                        
                                        if (isSelectingMode) {
                                            selectedImages = selectedImages + item
                                        } else {
                                            selectedImages = selectedImages - item
                                        }
                                    }
                            },
                            onDrag = { change, _ ->
                                if (inDragSelectionMode && dragStartItemIndex != null) {
                                    gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { item ->
                                            change.position.x >= item.offset.x && change.position.x <= item.offset.x + item.size.width &&
                                            change.position.y >= item.offset.y && change.position.y <= item.offset.y + item.size.height
                                        }?.let { itemInfo ->
                                            val currentIndex = itemInfo.index
                                            val startIndex = dragStartItemIndex!!
                                            val minIndex = minOf(startIndex, currentIndex)
                                            val maxIndex = maxOf(startIndex, currentIndex)
                                            
                                            val newSelected = initialSelectedImages.toMutableSet()
                                            for (i in minIndex..maxIndex) {
                                                if (i in imagesInAlbum.indices) {
                                                    if (isSelectingMode) {
                                                        newSelected.add(imagesInAlbum[i])
                                                    } else {
                                                        newSelected.remove(imagesInAlbum[i])
                                                    }
                                                }
                                            }
                                            selectedImages = newSelected
                                        }
                                }
                            },
                            onDragEnd = {
                                inDragSelectionMode = false
                                dragStartItemIndex = null
                            },
                            onDragCancel = {
                                inDragSelectionMode = false
                                dragStartItemIndex = null
                            }
                        )
                    }
                ) {
                    items(imagesInAlbum) { item ->
                        val isSelected = selectedImages.contains(item)
                        ImageGridItem(item, isSelected) {
                            selectedImages = if (isSelected) {
                                selectedImages - item
                            } else {
                                selectedImages + item
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumItem(album: MediaAlbum, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
    ) {
        Column {
            AsyncImage(
                model = album.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Text(
                text = "${album.name} (${album.count}张)",
                color = Color.White,
                modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ImageGridItem(item: MediaItem, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
            )
        }
    }
}
