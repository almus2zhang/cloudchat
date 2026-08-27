package com.cloudchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudchat.model.AiConfig
import com.cloudchat.repository.SettingsRepository
import com.cloudchat.service.AiService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedConfig by settingsRepository.aiConfig.collectAsState(initial = AiConfig())
    
    var currentConfig by remember(savedConfig) { mutableStateOf(savedConfig) }
    
    var showGeminiKey by remember { mutableStateOf(false) }
    var showOpenAiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 760.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "AI 大模型配置 (AI Model Settings)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Provider Switcher Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前启用服务商 (Active Provider)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "两套配置均会独立保存，随时切换无需重新配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isGemini = currentConfig.provider == "gemini"

                        // Gemini Button
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { currentConfig = currentConfig.copy(provider = "gemini") },
                            color = if (isGemini) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = if (isGemini) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (isGemini) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Google Gemini",
                                    fontWeight = if (isGemini) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isGemini) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // OpenAI Button
                        val isOpenAi = currentConfig.provider == "openai"
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { currentConfig = currentConfig.copy(provider = "openai") },
                            color = if (isOpenAi) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = if (isOpenAi) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    tint = if (isOpenAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "OpenAI 兼容",
                                    fontWeight = if (isOpenAi) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isOpenAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Gemini Settings Card
            if (currentConfig.provider == "gemini") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gemini 原生多模态配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // API Key
                        OutlinedTextField(
                            value = currentConfig.geminiApiKey,
                            onValueChange = { currentConfig = currentConfig.copy(geminiApiKey = it) },
                            label = { Text("Gemini API Key (AI Studio / Vertex AI)") },
                            placeholder = { Text("AIzaSy...") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                    Icon(
                                        imageVector = if (showGeminiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Key Visibility"
                                    )
                                }
                            },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Model Name
                        OutlinedTextField(
                            value = currentConfig.geminiModel,
                            onValueChange = { currentConfig = currentConfig.copy(geminiModel = it) },
                            label = { Text("转写与总结模型 (Model Name)") },
                            placeholder = { Text("gemini-2.5-flash / gemini-2.0-flash") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Quick Model Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-2.5-pro").forEach { modelName ->
                                SuggestionChip(
                                    onClick = { currentConfig = currentConfig.copy(geminiModel = modelName) },
                                    label = { Text(modelName, fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Base URL
                        OutlinedTextField(
                            value = currentConfig.geminiBaseUrl,
                            onValueChange = { currentConfig = currentConfig.copy(geminiBaseUrl = it) },
                            label = { Text("API 接口基地址 (Base URL)") },
                            placeholder = { Text("https://generativelanguage.googleapis.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            } else {
                // 3. OpenAI Compatible Settings Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("OpenAI 兼容协议配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Base URL
                        OutlinedTextField(
                            value = currentConfig.openaiBaseUrl,
                            onValueChange = { currentConfig = currentConfig.copy(openaiBaseUrl = it) },
                            label = { Text("API 基地址 (Base URL)") },
                            placeholder = { Text("https://api.openai.com/v1 或中转反代地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // API Key
                        OutlinedTextField(
                            value = currentConfig.openaiApiKey,
                            onValueChange = { currentConfig = currentConfig.copy(openaiApiKey = it) },
                            label = { Text("OpenAI / 中转 API Key") },
                            placeholder = { Text("sk-...") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showOpenAiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showOpenAiKey = !showOpenAiKey }) {
                                    Icon(
                                        imageVector = if (showOpenAiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Key Visibility"
                                    )
                                }
                            },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Whisper Model
                        OutlinedTextField(
                            value = currentConfig.openaiWhisperModel,
                            onValueChange = { currentConfig = currentConfig.copy(openaiWhisperModel = it) },
                            label = { Text("Whisper 语音转文字模型") },
                            placeholder = { Text("whisper-1") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Chat Model
                        OutlinedTextField(
                            value = currentConfig.openaiChatModel,
                            onValueChange = { currentConfig = currentConfig.copy(openaiChatModel = it) },
                            label = { Text("提炼总结大模型 (Chat Model)") },
                            placeholder = { Text("gpt-4o-mini / deepseek-chat / qwen-turbo") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Quick Chat Model Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("gpt-4o-mini", "deepseek-chat", "gpt-4o", "qwen-turbo").forEach { mName ->
                                SuggestionChip(
                                    onClick = { currentConfig = currentConfig.copy(openaiChatModel = mName) },
                                    label = { Text(mName, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Custom Prompt Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("分级总结提示词 (Prompt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = currentConfig.summaryPrompt,
                        onValueChange = { currentConfig = currentConfig.copy(summaryPrompt = it) },
                        label = { Text("自定义总结提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Test Result Feedback
            if (testResult != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (testSuccess) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFF44336).copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (testSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (testSuccess) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = testResult!!,
                            color = if (testSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 6. Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Test Button
                OutlinedButton(
                    onClick = {
                        isTesting = true
                        testResult = null
                        coroutineScope.launch {
                            val res = AiService.testConnection(currentConfig)
                            isTesting = false
                            if (res.isSuccess) {
                                testSuccess = true
                                testResult = res.getOrNull() ?: "连接测试成功！"
                            } else {
                                testSuccess = false
                                testResult = res.exceptionOrNull()?.message ?: "连接失败"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试中...")
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试连通性")
                    }
                }

                // Save Button
                Button(
                    onClick = {
                        coroutineScope.launch {
                            settingsRepository.saveAiConfig(currentConfig)
                            android.widget.Toast.makeText(context, "✅ AI 大模型配置已保存", android.widget.Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存并应用")
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
