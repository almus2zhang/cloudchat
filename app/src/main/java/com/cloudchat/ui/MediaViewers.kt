package com.cloudchat.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cloudchat.utils.NetworkUtils

@Composable
fun ZoomableImage(
    uri: String,
    onTap: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Manual gesture detection to allow Pager to work when scale is 1
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        
                        // If we are zoomed in, OR we are starting a zoom (zoom != 1f)
                        // Then we consume the gesture. Otherwise (panning at scale 1), we let it go to Pager.
                        if (scale > 1f || zoom != 1f) {
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (scale > 1f) {
                                offset += pan
                                event.changes.forEach { it.consume() }
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
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
    var isControlsVisible by remember { mutableStateOf(true) }

    val exoPlayer = remember {
        val okHttpClient = NetworkUtils.getUnsafeOkHttpClient().build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                prepare()
            }
    }

    LaunchedEffect(active) {
        exoPlayer.playWhenReady = active
        if (!active) {
            exoPlayer.pause()
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
                    useController = true
                    hideController()
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                if (isControlsVisible) it.showController() else it.hideController()
            }
        )
    }
}
