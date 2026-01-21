package com.cloudchat

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.cloudchat.utils.NetworkUtils

class CloudChatApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                NetworkUtils.getUnsafeOkHttpClient().build()
            }
            .logger(DebugLogger()) // 开启调试日志，Coil 加载失败会在 Logcat 报错
            .crossfade(true)
            .build()
    }
}
