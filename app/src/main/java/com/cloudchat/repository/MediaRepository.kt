package com.cloudchat.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

data class MediaAlbum(
    val id: String,
    val name: String,
    val coverUri: Uri,
    val count: Int
)

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val dateAdded: Long,
    val path: String
)

class MediaRepository(private val context: Context) {
    suspend fun getAlbums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val albums = mutableMapOf<String, MediaAlbum>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(bucketIdColumn) ?: continue
                    val bucketName = cursor.getString(bucketNameColumn) ?: "Unknown"
                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                    val existing = albums[bucketId]
                    if (existing == null) {
                        albums[bucketId] = MediaAlbum(bucketId, bucketName, uri, 1)
                    } else {
                        albums[bucketId] = existing.copy(count = existing.count + 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Failed to get albums", e)
        }
        
        // Return sorted by count (or name)
        albums.values.sortedByDescending { it.count }
    }

    suspend fun getImagesInAlbum(bucketId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA
        )
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val path = cursor.getString(dataColumn)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    items.add(MediaItem(id, uri, dateAdded, path))
                }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Failed to get images", e)
        }
        items
    }

    suspend fun deleteImage(uri: Uri, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // If we have MANAGE_EXTERNAL_STORAGE, we can delete the file directly.
            val file = File(path)
            if (file.exists() && file.delete()) {
                // Remove from MediaStore
                context.contentResolver.delete(uri, null, null)
                true
            } else {
                // Fallback to content resolver
                val rows = context.contentResolver.delete(uri, null, null)
                rows > 0
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Failed to delete image $uri", e)
            false
        }
    }
}
