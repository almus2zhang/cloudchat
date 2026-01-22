package com.cloudchat.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cloudchat.utils.NetworkUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

import androidx.compose.ui.input.pointer.positionChange

@Composable
fun ZoomableImage(
    uri: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    onSwipeToNext: () -> Unit = {},
    onSwipeToPrev: () -> Unit = {},
    backgroundUri: String? = null,
    isHighRes: Boolean = true // Only fade out background when High Res image is loaded
) {
    val zoomState = rememberZoomState(
        maxScale = 5f,
    )

    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Reset zoom when swiping away
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            zoomState.reset()
            offsetY.snapTo(0f)
        }
    }

    val alpha = remember(offsetY.value) {
        // Slower fade: 1500f instead of 600f
        (1f - (abs(offsetY.value) / 1500f)).coerceIn(0f, 1f)
    }

    // Track if the image has finished loading
    // CRITICAL: Reset state when URI changes (e.g. from Thumbnail URL to Local File Path)
    var isImageLoaded by remember(uri) { mutableStateOf(false) }
    
    // Animate background opacity: 
    // - If it's the High Res image AND it's loaded -> Fade out (0f)
    // - Otherwise (it's a thumbnail, or high res is still loading) -> Keep visible (0.6f)
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isHighRes && isImageLoaded) 0f else 0.6f,
        animationSpec = tween(durationMillis = 500)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        var isVerticalDismissing = false

                        drag(down.id) { change ->
                            val dragAmount = change.positionChange()
                            
                            // Only allow vertical dismiss when zoom is at 1.0 (not zoomed)
                            val isAtNormalScale = zoomState.scale <= 1.01f
                            
                            // If the internal zoomable image didn't consume this (meaning boundary reached or 1x)
                            if (!change.isConsumed && isAtNormalScale) {
                                if (!isVerticalDismissing && abs(dragAmount.y) > abs(dragAmount.x) && abs(dragAmount.y) > 10) {
                                    isVerticalDismissing = true
                                }

                                if (isVerticalDismissing) {
                                    change.consume()
                                    scope.launch {
                                        offsetY.snapTo(offsetY.value + dragAmount.y)
                                    }
                                }
                            } else if (isVerticalDismissing && isAtNormalScale) {
                                // Once we are in dismissing mode, we stay in it until release
                                change.consume()
                                scope.launch {
                                    offsetY.snapTo(offsetY.value + dragAmount.y)
                                }
                            }
                        }

                        if (isVerticalDismissing) {
                            // If user pulls back up, offsetY decreases. 
                            // Only close if the final position is significantly offset.
                            if (abs(offsetY.value) > 400f) {
                                onTap()
                            } else {
                                scope.launch {
                                    offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                translationY = offsetY.value
                // Keep image opaque during drag, only fade background
                this.alpha = 1f 
                // Add a slight scale effect for a "detaching" feeling
                val scale = (1f - (abs(offsetY.value) / 4000f)).coerceIn(0.9f, 1f)
                scaleX = scale
                scaleY = scale
            }
            .clipToBounds()
    ) {
        // Show background if available and main image isn't loaded yet (or animating out)
        if (backgroundUri != null && backgroundAlpha > 0f) {
             AsyncImage(
                 model = backgroundUri,
                 contentDescription = null,
                 contentScale = ContentScale.Crop,
                 modifier = Modifier
                     .fillMaxSize()
                     .graphicsLayer { 
                         this.alpha = backgroundAlpha // Fade out when main image loads
                     }
             )
        }

        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { isImageLoaded = true }, // Trigger fade out checks
            modifier = Modifier
                .fillMaxSize()
                .zoomable(
                    zoomState = zoomState,
                    onTap = { onTap() },
                    enableOneFingerZoom = false
                )
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    uri: String,
    active: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isControlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(active) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val exoPlayer = remember {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    LaunchedEffect(uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        exoPlayer.prepare()
    }

    LaunchedEffect(active) {
        exoPlayer.playWhenReady = active
        isPlaying = active
        if (!active) {
            exoPlayer.pause()
        } else {
            isControlsVisible = false
        }
    }

    LaunchedEffect(active, isPlaying) {
        if (active && isPlaying) {
            while (true) {
                currentPosition = exoPlayer.currentPosition
                duration = exoPlayer.duration.coerceAtLeast(0L)
                kotlinx.coroutines.delay(500)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isControlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        isPlaying = !isPlaying
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { 
                            exoPlayer.seekTo(it.toLong())
                            currentPosition = it.toLong()
                        },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    
                    Text(
                        text = formatTime(currentPosition) + " / " + formatTime(duration),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
