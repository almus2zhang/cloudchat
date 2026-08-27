package com.cloudchat.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

data class AudioChunk(
    val file: File,
    val startSec: Long,
    val endSec: Long,
    val index: Int,
    val total: Int
) {
    val timeLabel: String
        get() {
            val startMin = startSec / 60
            val startS = startSec % 60
            val endMin = endSec / 60
            val endS = endSec % 60
            return String.format("[%02d:%02d - %02d:%02d]", startMin, startS, endMin, endS)
        }
}

object AudioSplitter {
    private const val TAG = "AudioSplitter"

    /**
     * Gets audio duration in seconds using MediaExtractor
     */
    fun getAudioDurationSec(audioFile: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(audioFile.absolutePath)
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                        break
                    }
                }
            }
            (durationUs / 1_000_000L).coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get duration via extractor: ${e.message}")
            0L
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Splits an audio file into chunks of chunkDurationSec with overlapSec overlap.
     * If duration <= chunkDurationSec, returns a single chunk referencing the original file.
     */
    fun splitAudio(
        audioFile: File,
        cacheDir: File,
        chunkDurationSec: Long = 300L,
        overlapSec: Long = 3L
    ): List<AudioChunk> {
        val totalDurationSec = getAudioDurationSec(audioFile)
        if (totalDurationSec <= chunkDurationSec + 10L) {
            return listOf(
                AudioChunk(
                    file = audioFile,
                    startSec = 0,
                    endSec = totalDurationSec,
                    index = 0,
                    total = 1
                )
            )
        }

        val chunks = mutableListOf<AudioChunk>()
        val chunkDir = File(cacheDir, "audio_chunks_${System.currentTimeMillis()}").apply { mkdirs() }

        var curStartSec = 0L
        var chunkIndex = 0

        val stepSec = (chunkDurationSec - overlapSec).coerceAtLeast(1L)
        val estimatedTotal = (((totalDurationSec - chunkDurationSec + stepSec - 1) / stepSec) + 1).toInt().coerceAtLeast(1)

        while (curStartSec < totalDurationSec) {
            val curEndSec = (curStartSec + chunkDurationSec).coerceAtMost(totalDurationSec)
            val chunkFile = File(chunkDir, "chunk_${chunkIndex}_${curStartSec}_${curEndSec}.m4a")

            val success = extractAudioSegment(
                sourceFile = audioFile,
                destFile = chunkFile,
                startUs = curStartSec * 1_000_000L,
                endUs = curEndSec * 1_000_000L
            )

            if (success && chunkFile.exists() && chunkFile.length() > 0) {
                chunks.add(
                    AudioChunk(
                        file = chunkFile,
                        startSec = curStartSec,
                        endSec = curEndSec,
                        index = chunkIndex,
                        total = estimatedTotal
                    )
                )
            } else {
                Log.e(TAG, "Failed to extract segment $curStartSec to $curEndSec, fallback to whole file")
                break
            }

            chunkIndex++
            curStartSec += stepSec
            if (curEndSec >= totalDurationSec) break
        }

        return if (chunks.isNotEmpty()) {
            val actualTotal = chunks.size
            chunks.map { it.copy(total = actualTotal) }
        } else {
            listOf(
                AudioChunk(file = audioFile, startSec = 0, endSec = totalDurationSec, index = 0, total = 1)
            )
        }
    }

    private fun extractAudioSegment(
        sourceFile: File,
        destFile: File,
        startUs: Long,
        endUs: Long
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                128 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxBufferSize.coerceAtLeast(128 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                if (bufferInfo.presentationTimeUs > endUs) {
                    break
                }
                if (bufferInfo.presentationTimeUs >= startUs) {
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }
                extractor.advance()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "extractAudioSegment failed: ${e.message}", e)
            false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
