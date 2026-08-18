package com.cloudchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudchat.model.ServerConfig
import com.cloudchat.model.StorageType

@Composable
fun SettingsContent(
    config: ServerConfig,
    onConfigChange: (ServerConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        OutlinedTextField(
            value = config.username,
            onValueChange = { onConfigChange(config.copy(username = it)) },
            label = { Text("聊天昵称") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("存储引擎", style = MaterialTheme.typography.titleMedium)
        
        Row {
            RadioButton(
                selected = config.type == StorageType.WEBDAV,
                onClick = { onConfigChange(config.copy(type = StorageType.WEBDAV)) }
            )
            Text("WebDAV", modifier = Modifier.padding(top = 12.dp))
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(
                selected = config.type == StorageType.S3,
                onClick = { onConfigChange(config.copy(type = StorageType.S3)) }
            )
            Text("S3", modifier = Modifier.padding(top = 12.dp))
        }

        if (config.type == StorageType.S3) {
            OutlinedTextField(
                value = config.endpoint,
                onValueChange = { onConfigChange(config.copy(endpoint = it)) },
                label = { Text("S3 Endpoint 服务地址 (可选)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.bucket,
                onValueChange = { onConfigChange(config.copy(bucket = it)) },
                label = { Text("S3 存储桶名称 (Bucket)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.accessKey,
                onValueChange = { onConfigChange(config.copy(accessKey = it)) },
                label = { Text("Access Key ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.secretKey,
                onValueChange = { onConfigChange(config.copy(secretKey = it)) },
                label = { Text("Secret Access Key") },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = config.webDavUrl,
                onValueChange = { onConfigChange(config.copy(webDavUrl = it)) },
                label = { Text("WebDAV 服务器 URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.webDavUser,
                onValueChange = { onConfigChange(config.copy(webDavUser = it)) },
                label = { Text("WebDAV 用户名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.webDavPass,
                onValueChange = { onConfigChange(config.copy(webDavPass = it)) },
                label = { Text("WebDAV 密码") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { onConfigChange(config.copy(webDavIgnoreCert = !config.webDavIgnoreCert)) }
            ) {
                Checkbox(
                    checked = config.webDavIgnoreCert,
                    onCheckedChange = { onConfigChange(config.copy(webDavIgnoreCert = it)) }
                )
                Text("忽略证书校验（自签名/私有 CA，如 Lucky）")
            }
        }
    }
}
