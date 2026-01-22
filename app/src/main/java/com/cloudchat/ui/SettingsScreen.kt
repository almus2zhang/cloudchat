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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    val accounts by settingsRepository.accounts.collectAsState(initial = emptyList())
    val currentConfig by settingsRepository.currentConfig.collectAsState(initial = null)

    var editingConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (editingConfig == null) {
            // Account List View
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Accounts", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(accounts) { account ->
                        AccountItem(
                            account = account,
                            isSelected = account.id == currentConfig?.id,
                            onSelect = {
                                coroutineScope.launch { 
                                    settingsRepository.switchAccount(account.id)
                                    onBack() // Auto close after switch
                                }
                            },
                            onEdit = { editingConfig = account },
                            onDelete = { 
                                coroutineScope.launch { settingsRepository.deleteAccount(account.id) }
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
                
                Button(
                    onClick = { editingConfig = ServerConfig() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New Account")
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
                    value = config.saveDir,
                    onValueChange = { editingConfig = config.copy(saveDir = it) },
                    label = { Text("Save Directory (User ID)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Unique folder name, e.g. user_ken") }
                )

                TextField(
                    value = config.username,
                    onValueChange = { editingConfig = config.copy(username = it) },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name shown in chat") }
                )
                
                TextField(
                    value = config.serverPath,
                    onValueChange = { editingConfig = config.copy(serverPath = it) },
                    label = { Text("Server Root Path") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. /mydata/backups") }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = config.type == StorageType.WEBDAV, onClick = { editingConfig = config.copy(type = StorageType.WEBDAV) })
                    Text("WebDAV")
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = config.type == StorageType.S3, onClick = { editingConfig = config.copy(type = StorageType.S3) })
                    Text("S3")
                }

                TextField(
                    value = if (config.type == StorageType.WEBDAV) config.webDavUrl else config.endpoint,
                    onValueChange = { 
                        editingConfig = if (config.type == StorageType.WEBDAV) config.copy(webDavUrl = it) else config.copy(endpoint = it)
                    },
                    label = { Text(if (config.type == StorageType.S3) "S3 Endpoint" else "WebDAV URL") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (config.type == StorageType.WEBDAV) {
                    TextField(
                        value = config.webDavUser,
                        onValueChange = { editingConfig = config.copy(webDavUser = it) },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = config.webDavPass,
                        onValueChange = { editingConfig = config.copy(webDavPass = it) },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextField(
                        value = config.accessKey,
                        onValueChange = { editingConfig = config.copy(accessKey = it) },
                        label = { Text("Access Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = config.secretKey,
                        onValueChange = { editingConfig = config.copy(secretKey = it) },
                        label = { Text("Secret Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Auto-download Limit (MB)", style = MaterialTheme.typography.titleMedium)
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
                Text("Files larger than this will only show thumbnails.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isTesting = true
                                testResult = "Testing..."
                                val provider = if (config.type == StorageType.S3) {
                                    S3StorageProvider(config, config.saveDir)
                                } else {
                                    WebDavStorageProvider(config, config.saveDir)
                                }
                                val result = provider.testConnection()
                                testResult = if (result.isSuccess) "Success!" else "Failed: ${result.exceptionOrNull()?.message}"
                                isTesting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTesting
                    ) {
                        Text(if (isTesting) "..." else "Test")
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                settingsRepository.saveAccount(config)
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
                    Text(it, color = if (it == "Success!") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AccountItem(
    account: ServerConfig,
    isSelected: Boolean,
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
                Text("ID: ${account.saveDir}", style = MaterialTheme.typography.bodySmall)
                Text("${account.type} - ${account.serverPath}", style = MaterialTheme.typography.bodySmall)
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
