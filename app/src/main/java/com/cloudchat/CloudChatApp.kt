package com.cloudchat

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.cloudchat.utils.NetworkUtils

import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache

class CloudChatApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                NetworkUtils.getUnsafeOkHttpClient().build()
            }
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .logger(DebugLogger())
            .crossfade(true)
            .build()
    }
}
