package com.cloudchat.model

data class AiConfig(
    val provider: String = "gemini", // "gemini" or "openai"
    
    // Gemini Settings
    val geminiApiKey: String = "",
    val geminiBaseUrl: String = "https://generativelanguage.googleapis.com",
    val geminiModel: String = "gemini-2.5-flash",
    
    // OpenAI Compatible Settings
    val openaiApiKey: String = "",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val openaiWhisperModel: String = "whisper-1",
    val openaiChatModel: String = "gpt-4o-mini",
    
    // Prompt
    val summaryPrompt: String = "请将这段语音内容准确转写为文字，并提炼输出结构清晰的 Markdown 分级总结，格式如下：\n### 📝 语音转写\n(此处为转写原文)\n\n### 💡 要点总结\n- (核心要点1)\n- (核心要点2)"
)
