package com.cloudchat.utils

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

class ProgressRequestBody(
    private val contentType: MediaType?,
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val onProgress: (Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(8192)
        var uploaded = 0L
        try {
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                uploaded += read
                val progress = ((uploaded * 100) / contentLength).toInt()
                onProgress(progress)
            }
        } finally {
            inputStream.close()
        }
    }
}
