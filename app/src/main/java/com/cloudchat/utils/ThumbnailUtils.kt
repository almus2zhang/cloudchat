package com.cloudchat.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ThumbnailUtils {
    suspend fun getVideoThumbnail(context: Context, videoFile: File): File? = withContext(Dispatchers.IO) {
        try {
            val thumbName = "thumb_${videoFile.nameWithoutExtension}.jpg"
            val thumbFile = File(context.cacheDir, thumbName)
            if (thumbFile.exists()) return@withContext thumbFile

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(videoFile, Size(512, 512), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(videoFile.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            }

            bitmap?.let {
                FileOutputStream(thumbFile).use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                return@withContext thumbFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
