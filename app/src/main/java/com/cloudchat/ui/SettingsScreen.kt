package com.cloudchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cloudchat.model.ServerConfig
import com.cloudchat.model.StorageType
import com.cloudchat.repository.SettingsRepository
import com.cloudchat.storage.S3StorageProvider
import com.cloudchat.storage.WebDavStorageProvider
import com.cloudchat.utils.DebugLogger
import kotlinx.coroutines.launch

import android.net.Uri
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip

val PRESET_AVATARS = listOf(
    "https://api.dicebear.com/7.x/bottts/png?seed=Felix",
    "https://api.dicebear.com/7.x/bottts/png?seed=Aria",
    "https://api.dicebear.com/7.x/bottts/png?seed=Zack",
    "https://api.dicebear.com/7.x/bottts/png?seed=Luna",
    "https://api.dicebear.com/7.x/bottts/png?seed=Leo",
    "https://api.dicebear.com/7.x/bottts/png?seed=Maya",
    "https://api.dicebear.com/7.x/bottts/png?seed=Milo",
    "https://api.dicebear.com/7.x/bottts/png?seed=Nova",
    "https://api.dicebear.com/7.x/bottts/png?seed=Kira",
    "https://api.dicebear.com/7.x/bottts/png?seed=Orion"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var editingConfig by remember { mutableStateOf<ServerConfig?>(null) }
    val repo = remember { com.cloudchat.repository.ChatRepository(context) }

    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
            pendingAvatarUri = it
            editingConfig = editingConfig?.copy(avatarUrl = it.toString())
        }
    }

    val accounts by settingsRepository.accounts.collectAsState(initial = emptyList())
    val currentConfig by settingsRepository.currentConfig.collectAsState(initial = null)
    val appMode by settingsRepository.appMode.collectAsState(initial = com.cloudchat.model.AppMode.SELF_BUILT)

    var deletingAccountConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var showDebugLogsModal by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordTargetAccount by remember { mutableStateOf<ServerConfig?>(null) }
    var passwordTargetAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }

    val checkPasswordAndProceed = { account: ServerConfig, action: () -> Unit ->
        if (account.configPassword.isNullOrEmpty()) {
            action()
        } else {
            passwordTargetAccount = account
            passwordTargetAction = action
            passwordInput = ""
            passwordError = false
            showPasswordDialog = true
        }
    }

    // Auto-open editor for Full mode if no account exists
    LaunchedEffect(appMode, accounts) {
        if (appMode == com.cloudchat.model.AppMode.FULL && accounts.isEmpty() && editingConfig == null) {
            editingConfig = SettingsRepository.FIXED_FULL_CONFIG.copy(id = java.util.UUID.randomUUID().toString())
        }
    }

    DebugLogsDialog(show = showDebugLogsModal, onDismiss = { showDebugLogsModal = false })

    Column(modifier = Modifier.fillMaxSize()) {
        if (editingConfig == null) {
            // Account List View
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Accounts", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { 
                        coroutineScope.launch { 
                            settingsRepository.setAppMode(com.cloudchat.model.AppMode.NOT_SET)
                            // Navigation is handled by MainActivity's LaunchedEffect, 
                            // but we can be explicit here to ensure immediate transition
                        }
                    }) {
                        Text("切换版本 (Switch Mode)")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(accounts) { account ->
                        AccountItem(
                            account = account,
                            isSelected = account.id == currentConfig?.id,
                            appMode = appMode,
                            onSelect = {
                                checkPasswordAndProceed(account) {
                                    coroutineScope.launch { 
                                        settingsRepository.switchAccount(account.id)
                                        onBack() 
                                    }
                                }
                            },
                            onEdit = { 
                                checkPasswordAndProceed(account) {
                                    editingConfig = if (appMode == com.cloudchat.model.AppMode.FULL) {
                                        account.copy(
                                            webDavUrl = SettingsRepository.FIXED_FULL_CONFIG.webDavUrl,
                                            webDavFallbackUrl = SettingsRepository.FIXED_FULL_CONFIG.webDavFallbackUrl,
                                            serverPath = SettingsRepository.FIXED_FULL_CONFIG.serverPath,
                                            webDavUser = SettingsRepository.FIXED_FULL_CONFIG.webDavUser,
                                            webDavPass = SettingsRepository.FIXED_FULL_CONFIG.webDavPass,
                                            type = SettingsRepository.FIXED_FULL_CONFIG.type
                                        )
                                    } else {
                                        account
                                    }
                                }
                            },
                            onDelete = { 
                                deletingAccountConfig = account
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                val accountsList by settingsRepository.accounts.collectAsState(initial = emptyList())
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { out ->
                            val json = com.google.gson.Gson().toJson(accountsList)
                            out.write(json.toByteArray())
                        }
                    }
                }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            val json = input.bufferedReader().readText()
                            val type = object : com.google.gson.reflect.TypeToken<List<ServerConfig>>() {}.type
                            val imported: List<ServerConfig> = com.google.gson.Gson().fromJson(json, type)
                            coroutineScope.launch {
                                imported.forEach { settingsRepository.saveAccount(it) }
                            }
                        }
                    }
                }

                if (appMode == com.cloudchat.model.AppMode.SELF_BUILT) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { exportLauncher.launch("cloudchat_accounts.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export JSON")
                        }
                        Button(
                            onClick = { importLauncher.launch("application/json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import JSON")
                        }
                    }
                }
                
                Button(
                    onClick = { 
                        editingConfig = if (appMode == com.cloudchat.model.AppMode.FULL) {
                            SettingsRepository.FIXED_FULL_CONFIG.copy(id = java.util.UUID.randomUUID().toString())
                        } else {
                            ServerConfig() 
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New Account")
                }

                Spacer(modifier = Modifier.height(12.dp))
                var debugLogEnabled by remember { mutableStateOf(DebugLogger.isEnabled) }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("调试日志写入 (Debug Log File)", style = MaterialTheme.typography.titleMedium)
                            Text("关闭后停止向 debug_log.txt 写入文件与调试日志", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = debugLogEnabled,
                            onCheckedChange = {
                                debugLogEnabled = it
                                DebugLogger.setLogEnabled(it)
                            }
                        )
                    }
                }

                }
            } else {
            // Edit/Add Form
            val config = editingConfig!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(if (config.id.isEmpty()) "Add Account" else "Edit Account", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = config.username,
                    onValueChange = { editingConfig = config.copy(username = it) },
                    label = { Text("用户昵称 (Name)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("在此输入您的名字") }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("个人头像设置 (Avatar)", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val avatarSrc = rememberSettingsAvatarUrl(config.avatarUrl, config.username, repo)
                    AsyncImage(
                        model = avatarSrc,
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop
                    )

                    Button(onClick = { avatarPickerLauncher.launch("image/*") }) {
                        Text("选择本地图片做头像")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("系统内置精美头像：", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    PRESET_AVATARS.forEach { url ->
                        val isSelected = config.avatarUrl == url
                        AsyncImage(
                            model = url,
                            contentDescription = "Preset Avatar",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    CircleShape
                                )
                                .clickable {
                                    editingConfig = config.copy(avatarUrl = url)
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = config.configPassword ?: "",
                    onValueChange = { 
                        editingConfig = config.copy(configPassword = it.ifEmpty { null })
                    },
                    label = { Text("配置保护密码 (Config Password - Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("设置后，编辑或切换此配置需输入密码") },
                    visualTransformation = PasswordVisualTransformation()
                )

                if (appMode == com.cloudchat.model.AppMode.FULL) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = config.fullModePath ?: "",
                        onValueChange = { 
                            editingConfig = config.copy(fullModePath = it.ifEmpty { null })
                        },
                        label = { Text("子路径设置 (Custom Sub-path)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("默认为手机 ID 后六位") }
                    )
                    Text("文件将保存在 /public/子路径 下", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                }

                if (appMode == com.cloudchat.model.AppMode.SELF_BUILT) {
                    val isJianguoyun = config.webDavUrl == "https://dav.jianguoyun.com/dav/" || config.serverPath == "CloudChat" && config.type == StorageType.WEBDAV && config.webDavFallbackUrl.isEmpty() && config.diaryBaseUrl.isEmpty()
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = config.saveDir,
                        onValueChange = { editingConfig = config.copy(saveDir = it) },
                        label = { Text("存储目录 / 用户ID (Save Directory)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("唯一标识，如 user_ken") }
                    )
                    
                    TextField(
                        value = if (isJianguoyun) "CloudChat" else config.serverPath,
                        onValueChange = { if (!isJianguoyun) editingConfig = config.copy(serverPath = it) },
                        enabled = !isJianguoyun,
                        label = { Text(if (isJianguoyun) "服务器根路径 (坚果云固定锁定为 CloudChat)" else "服务器根路径 (Server Root Path)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 /cloudchat") }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val selectedPreset = if (isJianguoyun) "JIANGUOYUN" else if (config.type == StorageType.S3) "S3" else "WEBDAV"

                        RadioButton(
                            selected = selectedPreset == "WEBDAV", 
                            onClick = { 
                                editingConfig = config.copy(type = StorageType.WEBDAV, webDavUrl = if (config.webDavUrl == "https://dav.jianguoyun.com/dav/") "" else config.webDavUrl) 
                            }
                        )
                        Text("WebDAV")
                        Spacer(modifier = Modifier.width(12.dp))

                        RadioButton(
                            selected = selectedPreset == "S3", 
                            onClick = { editingConfig = config.copy(type = StorageType.S3) }
                        )
                        Text("S3")
                        Spacer(modifier = Modifier.width(12.dp))

                        RadioButton(
                            selected = selectedPreset == "JIANGUOYUN", 
                            onClick = { 
                                editingConfig = config.copy(
                                    type = StorageType.WEBDAV,
                                    webDavUrl = "https://dav.jianguoyun.com/dav/",
                                    serverPath = "CloudChat",
                                    webDavFallbackUrl = "",
                                    diaryBaseUrl = ""
                                ) 
                            }
                        )
                        Text("坚果云")
                    }

                    TextField(
                        value = if (config.type == StorageType.S3) config.endpoint else config.webDavUrl,
                        onValueChange = { 
                            if (!isJianguoyun) {
                                editingConfig = if (config.type == StorageType.WEBDAV) config.copy(webDavUrl = it) else config.copy(endpoint = it)
                            }
                        },
                        enabled = !isJianguoyun,
                        label = { Text(if (config.type == StorageType.S3) "S3 Endpoint 地址" else if (isJianguoyun) "WebDAV 服务器 URL (坚果云专用)" else "WebDAV 服务器 URL") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (config.type == StorageType.WEBDAV) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = if (isJianguoyun) "" else config.webDavFallbackUrl,
                            onValueChange = { if (!isJianguoyun) editingConfig = config.copy(webDavFallbackUrl = it) },
                            enabled = !isJianguoyun,
                            label = { Text(if (isJianguoyun) "WebDAV 局域网回退 URL (坚果云模式不可用)" else "WebDAV 局域网/回退 URL (可选)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = if (isJianguoyun) "" else config.diaryBaseUrl,
                            onValueChange = { if (!isJianguoyun) editingConfig = config.copy(diaryBaseUrl = it) },
                            enabled = !isJianguoyun,
                            label = { Text(if (isJianguoyun) "日记对外访问根 URL (坚果云模式不可用)" else "日记对外访问根 URL (Diary Base URL)") },
                            placeholder = { Text("例如: https://diary.example.com") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (config.type == StorageType.WEBDAV) {
                        if (isJianguoyun) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color(0x25F59E0B),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFF59E0B),
                                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "请按照此步骤获取应用密码：① 下载并安装坚果客户端；② 登录后前往 “设置” → “第三方应用管理” 进行关联获取密码。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFBBF24)
                                    )
                                }
                            }
                        }

                        TextField(
                            value = config.webDavUser,
                            onValueChange = { editingConfig = config.copy(webDavUser = it) },
                            label = { Text(if (isJianguoyun) "坚果云账号 (邮箱)" else "WebDAV 用户名") },
                            placeholder = { if (isJianguoyun) Text("your_email@domain.com") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = config.webDavPass,
                            onValueChange = { editingConfig = config.copy(webDavPass = it) },
                            label = { Text(if (isJianguoyun) "坚果云应用密码" else "WebDAV 密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        var chunkSizeText by remember(config.webDavChunkSize) {
                            mutableStateOf(if (config.webDavChunkSize > 0L) (config.webDavChunkSize / (1024 * 1024L)).toString() else "")
                        }
                        TextField(
                            value = chunkSizeText,
                            onValueChange = {
                                chunkSizeText = it
                                val mb = it.toLongOrNull() ?: 0L
                                editingConfig = config.copy(webDavChunkSize = mb * 1024 * 1024L)
                            },
                            label = { Text("WebDAV 分块传输大小 (MB, 0 表示禁用分块)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        TextField(
                            value = config.accessKey,
                            onValueChange = { editingConfig = config.copy(accessKey = it) },
                            label = { Text("Access Key ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = config.secretKey,
                            onValueChange = { editingConfig = config.copy(secretKey = it) },
                            label = { Text("Secret Access Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("大文件自动下载限制 (MB)", style = MaterialTheme.typography.titleMedium)
                TextField(
                    value = (config.autoDownloadLimit / (1024 * 1024)).toString(),
                    onValueChange = { 
                        it.toLongOrNull()?.let { mb ->
                            editingConfig = config.copy(autoDownloadLimit = mb * 1024 * 1024L)
                        }
                    },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                Text("超过此限制的文件在浏览聊天时仅显示缩略图，点击后手动下载。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

                Spacer(modifier = Modifier.height(16.dp))
                Text("消息展示模板 (Message Template)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val templates = listOf("default" to "默认模板", "diary" to "日记模板")
                    templates.forEach { (id, label) ->
                        val isSelected = config.messageTemplate == id
                        OutlinedButton(
                            onClick = { editingConfig = config.copy(messageTemplate = id) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Text("切换后主界面和文件夹均使用新模板展示", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

                if (config.type == StorageType.WEBDAV) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("忽略证书错误 (允许自签名/私有 CA 证书)", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("自建 HTTPS 域名、自签名或反代报错 (SSLHandshakeException) 时开启", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Switch(
                                checked = config.webDavIgnoreCert,
                                onCheckedChange = { editingConfig = config.copy(webDavIgnoreCert = it) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isTesting = true
                                testResult = "验证中..."
                                val provider = if (config.type == StorageType.S3) {
                                    S3StorageProvider(config, config.saveDir)
                                } else {
                                    WebDavStorageProvider(config, config.saveDir, appMode == com.cloudchat.model.AppMode.FULL)
                                }
                                val result = provider.testConnection()
                                val detailMsg = if (result.isSuccess) result.getOrNull() else result.exceptionOrNull()?.message
                                com.cloudchat.utils.DebugLogger.log("TestConn", "Result (Success=${result.isSuccess}): $detailMsg")
                                testResult = if (result.isSuccess) "验证成功\n${detailMsg ?: ""}" else "验证失败\n${detailMsg ?: "未知错误"}"
                                isTesting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTesting
                    ) {
                        Text(if (isTesting) "验证中..." else "测试验证")
                    }

                    Button(
                        onClick = { showDebugLogsModal = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("调试日志")
                    }

                    Button(
                        onClick = {
                            val dirError = settingsRepository.validateUserDir(config)
                            if (dirError != null) {
                                android.widget.Toast.makeText(context, dirError, android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            coroutineScope.launch {
                                var finalConfig = config
                                val uriToUpload = pendingAvatarUri
                                val avatarUrl = config.avatarUrl
                                if (uriToUpload != null && avatarUrl.startsWith("content://")) {
                                    val repo = com.cloudchat.repository.ChatRepository(context)
                                    val uploadedName = repo.uploadCustomAvatar(config, uriToUpload)
                                    if (!uploadedName.isNullOrEmpty()) {
                                        finalConfig = config.copy(avatarUrl = uploadedName)
                                    }
                                } else if (!avatarUrl.startsWith("avatar_") && !avatarUrl.startsWith("avatar____") && avatarUrl.isNotEmpty()) {
                                    val repo = com.cloudchat.repository.ChatRepository(context)
                                    try {
                                        val uploadedName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val url = java.net.URL(avatarUrl)
                                            val conn = url.openConnection()
                                            conn.connectTimeout = 5000
                                            conn.readTimeout = 5000
                                            val bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream())
                                            if (bmp != null) {
                                                repo.uploadAvatarFromBitmap(config, bmp)
                                            } else null
                                        }
                                        if (!uploadedName.isNullOrEmpty()) {
                                            finalConfig = config.copy(avatarUrl = uploadedName)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("SettingsScreen", "Failed to convert preset avatar to WebDAV file", e)
                                    }
                                }
                                settingsRepository.saveAccount(finalConfig)
                                repo.updateProfileAvatar(finalConfig.avatarUrl)
                                pendingAvatarUri = null
                                editingConfig = null
                                onBack() // Auto close after save
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                }
                
                TextButton(onClick = { editingConfig = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }

                testResult?.let {
                    Text(it, color = if (it.startsWith("验证成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("验证密码 (Verify Password)") },
            text = {
                Column {
                    Text("请输入该配置的访问密码：")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = passwordInput,
                        onValueChange = { 
                            passwordInput = it
                            passwordError = false
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        isError = passwordError,
                        label = { Text("Password") }
                    )
                    if (passwordError) {
                        Text("密码错误 (Incorrect password)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (passwordInput == passwordTargetAccount?.configPassword) {
                        showPasswordDialog = false
                        passwordTargetAction?.invoke()
                    } else {
                        passwordError = true
                    }
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val deletingAccount = deletingAccountConfig
    if (deletingAccount != null) {
        AlertDialog(
            onDismissRequest = { deletingAccountConfig = null },
            title = { Text("确认删除配置") },
            text = { Text("确定要删除配置“${deletingAccount.username}”吗？此操作将清除本地相关的连接信息。") },
            confirmButton = {
                TextButton(onClick = {
                    deletingAccountConfig = null
                    coroutineScope.launch {
                        settingsRepository.deleteAccount(deletingAccount.id)
                    }
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingAccountConfig = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AccountItem(
    account: ServerConfig,
    isSelected: Boolean,
    appMode: com.cloudchat.model.AppMode,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account.username, style = MaterialTheme.typography.titleMedium)
                if (appMode == com.cloudchat.model.AppMode.SELF_BUILT) {
                    Text("ID: ${account.saveDir}", style = MaterialTheme.typography.bodySmall)
                    Text("${account.type} - ${account.serverPath}", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onEdit) {
                Text("Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun rememberSettingsAvatarUrl(rawAvatar: String?, username: String, repo: com.cloudchat.repository.ChatRepository): String {
    val fallback = "https://api.dicebear.com/7.x/bottts/png?seed=${Uri.encode(username.ifEmpty { "User" })}"
    val avatarName = rawAvatar?.ifEmpty { null } ?: return fallback
    if (avatarName.startsWith("http://") || avatarName.startsWith("https://") || avatarName.startsWith("file://") || avatarName.startsWith("data:") || avatarName.startsWith("content://")) {
        return avatarName
    }
    var resolvedUrl by remember(avatarName) { mutableStateOf<String?>(null) }
    LaunchedEffect(avatarName) {
        resolvedUrl = repo.resolveAvatarPath(avatarName)
    }
    return resolvedUrl ?: fallback
}
