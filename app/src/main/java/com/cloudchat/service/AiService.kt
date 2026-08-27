package com.cloudchat.service

import android.util.Base64
import android.util.Log
import com.cloudchat.model.AiConfig
import com.cloudchat.utils.AudioChunk
import com.cloudchat.utils.AudioSplitter
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object AiService {
    private const val TAG = "AiService"
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes and summarizes an audio file using either Gemini or OpenAI compatible API.
     * Supports intelligent auto-segmentation (300s chunk + 3s overlap), context stitching,
     * transcribeOnly mode, and partial output preservation if cancelled.
     */
    suspend fun transcribeAndSummarize(
        audioFile: File,
        config: AiConfig,
        transcribeOnly: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(Exception("音频文件不存在或大小为0"))
        }

        val totalDurationSec = AudioSplitter.getAudioDurationSec(audioFile)
        val tempDir = audioFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")

        try {
            if (totalDurationSec > 300L) {
                // Long audio: chunked processing with 3s overlap
                val durationMin = totalDurationSec / 60
                val durationS = totalDurationSec % 60
                onProgress?.invoke("检测到长语音 (${durationMin}分${durationS}秒)，正在分段切片...")

                val chunks = AudioSplitter.splitAudio(
                    audioFile = audioFile,
                    cacheDir = tempDir,
                    chunkDurationSec = 300L,
                    overlapSec = 3L
                )

                val transcribedResults = mutableListOf<Pair<AudioChunk, String>>()

                try {
                    for ((idx, chunk) in chunks.withIndex()) {
                        val progressPercent = ((idx + 1) * 100) / chunks.size
                        onProgress?.invoke("正在转写第 ${idx + 1}/${chunks.size} 段 ${chunk.timeLabel} (${progressPercent}%)...")

                        val chunkResult = if (config.provider.lowercase() == "gemini") {
                            transcribeChunkWithGemini(chunk.file, config)
                        } else {
                            transcribeChunkWithOpenAi(chunk.file, config)
                        }

                        if (chunkResult.isSuccess) {
                            val text = chunkResult.getOrNull()?.trim() ?: ""
                            if (text.isNotBlank()) {
                                transcribedResults.add(Pair(chunk, text))
                            }
                        } else {
                            Log.w(TAG, "Chunk ${idx + 1} transcription failed: ${chunkResult.exceptionOrNull()?.message}")
                            val err = chunkResult.exceptionOrNull()?.message ?: "识别失败"
                            transcribedResults.add(Pair(chunk, "[本段转写异常: $err]"))
                        }

                        // Clean up temporary chunk file if different from original
                        if (chunk.file != audioFile) {
                            try { chunk.file.delete() } catch (_: Exception) {}
                        }
                    }
                } catch (e: CancellationException) {
                    // If user cancelled midway, output partial transcribed chunks
                    if (transcribedResults.isNotEmpty()) {
                        val partialText = buildPartialTranscribedText(transcribedResults)
                        return@withContext Result.success(partialText)
                    }
                    throw e
                }

                if (transcribedResults.isEmpty()) {
                    return@withContext Result.failure(Exception("未能从语音中识别出有效内容"))
                }

                // Assemble full text with time tags
                val fullTranscribedText = transcribedResults.joinToString("\n\n") { (chunk, text) ->
                    "**${chunk.timeLabel}**\n$text"
                }

                if (transcribeOnly) {
                    onProgress?.invoke("转写完成！")
                    return@withContext Result.success("### 📝 语音转写 (全文)\n$fullTranscribedText")
                }

                // Step 2: Global Summarization with full context
                onProgress?.invoke("分段转写完成，正在由大模型通读全文生成分级总结...")
                val summaryResult = summarizeTextWithChatModel(fullTranscribedText, config)

                val finalOutput = if (summaryResult.isSuccess) {
                    val summary = summaryResult.getOrNull() ?: ""
                    "### 📝 语音转写 (全文)\n$fullTranscribedText\n\n$summary"
                } else {
                    val err = summaryResult.exceptionOrNull()?.message ?: "大模型响应超时"
                    "### 📝 语音转写 (全文)\n$fullTranscribedText\n\n> ⚠️ *提示：大模型总结未成功 ($err)，已为您完整保留文字转写内容。*"
                }

                onProgress?.invoke("总结完成！")
                Result.success(finalOutput.trim())
            } else {
                // Short audio: Single pass processing
                if (transcribeOnly) {
                    onProgress?.invoke("正在进行语音识别转写...")
                    val textRes = if (config.provider.lowercase() == "gemini") {
                        transcribeChunkWithGemini(audioFile, config)
                    } else {
                        transcribeChunkWithOpenAi(audioFile, config)
                    }
                    if (textRes.isSuccess) {
                        onProgress?.invoke("转写完成！")
                        Result.success(textRes.getOrNull()?.trim() ?: "")
                    } else {
                        textRes
                    }
                } else {
                    onProgress?.invoke("正在准备音频文件 (${audioFile.length() / 1024} KB)...")
                    if (config.provider.lowercase() == "gemini") {
                        summarizeWithGemini(audioFile, config, onProgress)
                    } else {
                        summarizeWithOpenAi(audioFile, config, onProgress)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed", e)
            Result.failure(e)
        }
    }

    private fun buildPartialTranscribedText(transcribedResults: List<Pair<AudioChunk, String>>): String {
        val sb = StringBuilder()
        sb.append("### 📝 语音已转写内容 (中途取消)\n")
        transcribedResults.forEach { (chunk, text) ->
            sb.append("\n**${chunk.timeLabel}**\n")
            sb.append(text).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Transcribe a single chunk using OpenAI Whisper / SenseVoice
     */
    private suspend fun transcribeChunkWithOpenAi(
        audioFile: File,
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.openaiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 OpenAI / 硅基流动 API Key"))
        }

        val baseUrl = config.openaiBaseUrl.trimEnd('/')
        val whisperModel = config.openaiWhisperModel.trim().ifBlank { "FunAudioLLM/SenseVoiceSmall" }
        val transcriptionUrl = "$baseUrl/audio/transcriptions"

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }

        val uploadFileName = if (audioFile.name.contains('.')) audioFile.name else "${audioFile.name}.m4a"
        val audioRequestBody = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadFileName, audioRequestBody)
            .addFormDataPart("model", whisperModel)
            .addFormDataPart("response_format", "json")
            .build()

        val transRequest = Request.Builder()
            .url(transcriptionUrl)
            .addHeader("Authorization", "Bearer ${config.openaiApiKey.trim()}")
            .post(multipartBody)
            .build()

        val transResponse = httpClient.newCall(transRequest).execute()
        val transRespBody = transResponse.body?.string() ?: ""

        if (!transResponse.isSuccessful) {
            val errorMsg = try {
                val json = gson.fromJson(transRespBody, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString ?: transRespBody
            } catch (e: Exception) { transRespBody }
            return@withContext Result.failure(Exception("语音转写失败 (${transResponse.code}): $errorMsg"))
        }

        val transcribedText: String = try {
            val json = gson.fromJson(transRespBody, JsonObject::class.java)
            json.get("text")?.asString ?: transRespBody
        } catch (e: Exception) {
            transRespBody
        }

        Result.success(transcribedText.trim())
    }

    /**
     * Transcribe a single chunk using Gemini
     */
    private suspend fun transcribeChunkWithGemini(
        audioFile: File,
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.geminiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 Gemini API Key"))
        }

        val baseUrl = config.geminiBaseUrl.trimEnd('/')
        val model = config.geminiModel.trim().ifBlank { "gemini-2.5-flash" }
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=${config.geminiApiKey.trim()}"

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }

        val audioBytes = audioFile.readBytes()
        val base64Data = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val requestObj = JsonObject().apply {
            val contentsArray = com.google.gson.JsonArray().apply {
                val contentObj = JsonObject().apply {
                    val partsArray = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", "请将这段语音内容准确转写为文字输出，不要添加额外分析或开场白。") })
                        add(JsonObject().apply {
                            val inlineData = JsonObject().apply {
                                addProperty("mime_type", mimeType)
                                addProperty("data", base64Data)
                            }
                            add("inline_data", inlineData)
                        })
                    }
                    add("parts", partsArray)
                }
                add(contentObj)
            }
            add("contents", contentsArray)
        }

        val requestBody = requestObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString ?: respBody
            } catch (e: Exception) { respBody }
            return@withContext Result.failure(Exception("Gemini 转写失败 (${response.code}): $errorMsg"))
        }

        try {
            val json = gson.fromJson(respBody, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                return@withContext Result.failure(Exception("Gemini 未返回有效内容"))
            }
            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString

            Result.success(text.trim())
        } catch (e: Exception) {
            Result.failure(Exception("解析 Gemini 转写失败: ${e.message}"))
        }
    }

    /**
     * Global summarization across the entire assembled text using Chat Model
     */
    private suspend fun summarizeTextWithChatModel(
        fullText: String,
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.provider.lowercase() == "gemini") {
            val baseUrl = config.geminiBaseUrl.trimEnd('/')
            val model = config.geminiModel.trim().ifBlank { "gemini-2.5-flash" }
            val url = "$baseUrl/v1beta/models/$model:generateContent?key=${config.geminiApiKey.trim()}"

            val prompt = config.summaryPrompt.ifBlank {
                "请根据提供的语音转写全文，提炼输出结构清晰的 Markdown 分级总结，格式如下：\n### 💡 要点总结\n- (核心要点1)\n- (核心要点2)\n\n### 📌 待办与决议\n- (待办1)"
            }

            val requestObj = JsonObject().apply {
                val contentsArray = com.google.gson.JsonArray().apply {
                    val contentObj = JsonObject().apply {
                        val partsArray = com.google.gson.JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", "$prompt\n\n【语音转写全文】：\n$fullText") })
                        }
                        add("parts", partsArray)
                    }
                    add(contentObj)
                }
                add("contents", contentsArray)
            }

            val requestBody = requestObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Gemini 总结失败: $respBody"))
            }

            try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                val text = json.getAsJsonArray("candidates")[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString
                Result.success(text.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            val baseUrl = config.openaiBaseUrl.trimEnd('/')
            val chatModel = config.openaiChatModel.trim().ifBlank { "deepseek-ai/DeepSeek-V4-Flash" }
            val chatUrl = "$baseUrl/chat/completions"

            val prompt = config.summaryPrompt.ifBlank {
                "请根据提供的语音转写全文，提炼输出结构清晰的 Markdown 分级总结，格式如下：\n### 💡 要点总结\n- (核心要点1)\n- (核心要点2)\n\n### 📌 待办与决议\n- (待办1)"
            }

            val chatRequestJson = JsonObject().apply {
                addProperty("model", chatModel)
                val messagesArray = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", prompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "请根据以下语音转写全文生成全局总结：\n\n$fullText")
                    })
                }
                add("messages", messagesArray)
                addProperty("temperature", 0.3)
            }

            val chatRequestBody = chatRequestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val chatRequest = Request.Builder()
                .url(chatUrl)
                .addHeader("Authorization", "Bearer ${config.openaiApiKey.trim()}")
                .post(chatRequestBody)
                .build()

            val chatResponse = httpClient.newCall(chatRequest).execute()
            val chatRespBody = chatResponse.body?.string() ?: ""

            if (!chatResponse.isSuccessful) {
                return@withContext Result.failure(Exception("大模型总结失败: $chatRespBody"))
            }

            try {
                val json = gson.fromJson(chatRespBody, JsonObject::class.java)
                val text = json.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString
                Result.success(text.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Test connection / API key validity
     */
    suspend fun testConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (config.provider.lowercase() == "gemini") {
                if (config.geminiApiKey.isBlank()) {
                    return@withContext Result.failure(Exception("请先填写 Gemini API Key"))
                }
                val baseUrl = config.geminiBaseUrl.trimEnd('/')
                val url = "$baseUrl/v1beta/models/${config.geminiModel.trim()}:generateContent?key=${config.geminiApiKey.trim()}"
                val jsonBody = """
                    {
                        "contents": [
                            {"parts": [{"text": "Hello, please reply with 'OK'"}]}
                        ]
                    }
                """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder().url(url).post(jsonBody).build()
                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Gemini 连接失败 (${response.code}): $respBody"))
                }
                Result.success("Gemini API 连接成功！")
            } else {
                if (config.openaiApiKey.isBlank()) {
                    return@withContext Result.failure(Exception("请先填写 OpenAI API Key"))
                }
                val baseUrl = config.openaiBaseUrl.trimEnd('/')
                val url = "$baseUrl/chat/completions"
                val jsonBody = """
                    {
                        "model": "${config.openaiChatModel.trim()}",
                        "messages": [{"role": "user", "content": "Hello, please reply with 'OK'"}],
                        "max_tokens": 10
                    }
                """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.openaiApiKey.trim()}")
                    .post(jsonBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("OpenAI 连接失败 (${response.code}): $respBody"))
                }
                Result.success("OpenAI 兼容 API 连接成功！")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection error", e)
            Result.failure(e)
        }
    }

    private suspend fun summarizeWithGemini(
        audioFile: File,
        config: AiConfig,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.geminiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 Gemini API Key，请在【设置 -> AI大模型配置】中设置"))
        }

        val baseUrl = config.geminiBaseUrl.trimEnd('/')
        val model = config.geminiModel.trim().ifBlank { "gemini-2.5-flash" }
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=${config.geminiApiKey.trim()}"

        onProgress?.invoke("正在通过 Gemini ($model) 分析语音并生成总结...")

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "amr" -> "audio/amr"
            else -> "audio/mp4"
        }

        val audioBytes = audioFile.readBytes()
        val base64Data = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val prompt = config.summaryPrompt.ifBlank {
            "请将这段语音内容准确转写为文字，并提炼输出结构清晰的 Markdown 分级总结，格式如下：\n### 📝 语音转写\n(此处为转写原文)\n\n### 💡 要点总结\n- (核心要点1)\n- (核心要点2)"
        }

        val requestObj = JsonObject().apply {
            val contentsArray = com.google.gson.JsonArray().apply {
                val contentObj = JsonObject().apply {
                    val partsArray = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                        add(JsonObject().apply {
                            val inlineData = JsonObject().apply {
                                addProperty("mime_type", mimeType)
                                addProperty("data", base64Data)
                            }
                            add("inline_data", inlineData)
                        })
                    }
                    add("parts", partsArray)
                }
                add(contentObj)
            }
            add("contents", contentsArray)
        }

        val requestBody = requestObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString ?: respBody
            } catch (e: Exception) { respBody }
            return@withContext Result.failure(Exception("Gemini 请求失败 (${response.code}): $errorMsg"))
        }

        try {
            val json = gson.fromJson(respBody, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                return@withContext Result.failure(Exception("Gemini 未返回有效内容"))
            }
            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: $respBody", e)
            Result.failure(Exception("解析 Gemini 响应失败: ${e.message}"))
        }
    }

    private suspend fun summarizeWithOpenAi(
        audioFile: File,
        config: AiConfig,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.openaiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 OpenAI / 硅基流动 API Key，请在【设置 -> AI大模型配置】中设置"))
        }

        val baseUrl = config.openaiBaseUrl.trimEnd('/')
        val whisperModel = config.openaiWhisperModel.trim().ifBlank { "FunAudioLLM/SenseVoiceSmall" }
        val transcriptionUrl = "$baseUrl/audio/transcriptions"
        val whisperShortName = whisperModel.substringAfterLast('/')

        onProgress?.invoke("1/2 正在进行语音识别转写 ($whisperShortName)...")

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }

        val uploadFileName = if (audioFile.name.contains('.')) audioFile.name else "${audioFile.name}.m4a"
        val audioRequestBody = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadFileName, audioRequestBody)
            .addFormDataPart("model", whisperModel)
            .addFormDataPart("response_format", "json")
            .build()

        val transRequest = Request.Builder()
            .url(transcriptionUrl)
            .addHeader("Authorization", "Bearer ${config.openaiApiKey.trim()}")
            .post(multipartBody)
            .build()

        val transResponse = httpClient.newCall(transRequest).execute()
        val transRespBody = transResponse.body?.string() ?: ""

        if (!transResponse.isSuccessful) {
            val errorMsg = try {
                val json = gson.fromJson(transRespBody, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString ?: transRespBody
            } catch (e: Exception) { transRespBody }
            return@withContext Result.failure(Exception("语音转写失败 (${transResponse.code}): $errorMsg"))
        }

        val transcribedText: String = try {
            val json = gson.fromJson(transRespBody, JsonObject::class.java)
            json.get("text")?.asString ?: transRespBody
        } catch (e: Exception) {
            transRespBody
        }

        if (transcribedText.isBlank()) {
            return@withContext Result.failure(Exception("语音转写结果为空，未识别到有效语音"))
        }

        // Step 2: Summarize via Chat Completion
        val chatModel = config.openaiChatModel.trim().ifBlank { "deepseek-ai/DeepSeek-V4-Flash" }
        val chatUrl = "$baseUrl/chat/completions"
        val chatShortName = chatModel.substringAfterLast('/')

        onProgress?.invoke("2/2 正在通过 $chatShortName 生成分级要点总结...")

        val prompt = config.summaryPrompt.ifBlank {
            "请将这段语音内容准确转写为文字，并提炼输出结构清晰的 Markdown 分级总结，格式如下：\n### 📝 语音转写\n(此处为转写原文)\n\n### 💡 要点总结\n- (核心要点1)\n- (核心要点2)"
        }

        val chatRequestJson = JsonObject().apply {
            addProperty("model", chatModel)
            val messagesArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", prompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "请根据以下语音转写文本生成总结：\n\n$transcribedText")
                })
            }
            add("messages", messagesArray)
            addProperty("temperature", 0.3)
        }

        val chatRequestBody = chatRequestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val chatRequest = Request.Builder()
            .url(chatUrl)
            .addHeader("Authorization", "Bearer ${config.openaiApiKey.trim()}")
            .post(chatRequestBody)
            .build()

        val chatResponse = httpClient.newCall(chatRequest).execute()
        val chatRespBody = chatResponse.body?.string() ?: ""

        if (!chatResponse.isSuccessful) {
            val errorMsg = try {
                val json = gson.fromJson(chatRespBody, JsonObject::class.java)
                json.getAsJsonObject("error")?.get("message")?.asString ?: chatRespBody
            } catch (e: Exception) { chatRespBody }
            return@withContext Result.failure(Exception("大模型总结失败 (${chatResponse.code}): $errorMsg"))
        }

        try {
            val json = gson.fromJson(chatRespBody, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return@withContext Result.failure(Exception("大模型未返回有效内容"))
            }
            val text = choices[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString

            onProgress?.invoke("总结完成！")
            Result.success(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Chat response: $chatRespBody", e)
            Result.failure(Exception("解析总结结果失败: ${e.message}"))
        }
    }
}
