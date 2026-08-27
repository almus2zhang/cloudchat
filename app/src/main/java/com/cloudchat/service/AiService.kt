package com.cloudchat.service

import android.util.Base64
import android.util.Log
import com.cloudchat.model.AiConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes and summarizes an audio file using either Gemini or OpenAI compatible API.
     */
    suspend fun transcribeAndSummarize(
        audioFile: File,
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(Exception("音频文件不存在或大小为0"))
        }

        try {
            if (config.provider.lowercase() == "gemini") {
                summarizeWithGemini(audioFile, config)
            } else {
                summarizeWithOpenAi(audioFile, config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed", e)
            Result.failure(e)
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
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.geminiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 Gemini API Key，请在【设置 -> AI大模型配置】中设置"))
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
                        // 1. Prompt
                        add(JsonObject().apply { addProperty("text", prompt) })
                        // 2. Audio Data
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
            return@withContext Result.failure(Exception("Gemini API 请求失败 (${response.code}): $respBody"))
        }

        try {
            val json = gson.fromJson(respBody, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                return@withContext Result.failure(Exception("Gemini 未返回有效内容: $respBody"))
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
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.openaiApiKey.isBlank()) {
            return@withContext Result.failure(Exception("未配置 OpenAI API Key，请在【设置 -> AI大模型配置】中设置"))
        }

        val baseUrl = config.openaiBaseUrl.trimEnd('/')

        // Step 1: Transcribe via Whisper
        val whisperModel = config.openaiWhisperModel.trim().ifBlank { "whisper-1" }
        val transcriptionUrl = "$baseUrl/audio/transcriptions"

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }

        val audioRequestBody = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioRequestBody)
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
            return@withContext Result.failure(Exception("Whisper 转写失败 (${transResponse.code}): $transRespBody"))
        }

        val transcribedText: String = try {
            val json = gson.fromJson(transRespBody, JsonObject::class.java)
            json.get("text")?.asString ?: transRespBody
        } catch (e: Exception) {
            transRespBody
        }

        if (transcribedText.isBlank()) {
            return@withContext Result.failure(Exception("Whisper 未能识别到语音文本内容"))
        }

        // Step 2: Summarize via Chat Completion
        val chatModel = config.openaiChatModel.trim().ifBlank { "gpt-4o-mini" }
        val chatUrl = "$baseUrl/chat/completions"

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
            return@withContext Result.failure(Exception("Chat 模型总结失败 (${chatResponse.code}): $chatRespBody"))
        }

        try {
            val json = gson.fromJson(chatRespBody, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return@withContext Result.failure(Exception("模型未返回有效内容: $chatRespBody"))
            }
            val text = choices[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Chat response: $chatRespBody", e)
            Result.failure(Exception("解析 Chat 响应失败: ${e.message}"))
        }
    }
}
