package com.cloudchat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Storage Back-end", style = MaterialTheme.typography.titleMedium)
        
        Row {
            RadioButton(
                selected = config.type == StorageType.S3,
                onClick = { onConfigChange(config.copy(type = StorageType.S3)) }
            )
            Text("S3", modifier = Modifier.padding(top = 12.dp))
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(
                selected = config.type == StorageType.WEBDAV,
                onClick = { onConfigChange(config.copy(type = StorageType.WEBDAV)) }
            )
            Text("WebDAV", modifier = Modifier.padding(top = 12.dp))
        }

        if (config.type == StorageType.S3) {
            OutlinedTextField(
                value = config.endpoint,
                onValueChange = { onConfigChange(config.copy(endpoint = it)) },
                label = { Text("Endpoint (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.bucket,
                onValueChange = { onConfigChange(config.copy(bucket = it)) },
                label = { Text("Bucket Name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.accessKey,
                onValueChange = { onConfigChange(config.copy(accessKey = it)) },
                label = { Text("Access Key") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.secretKey,
                onValueChange = { onConfigChange(config.copy(secretKey = it)) },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = config.webDavUrl,
                onValueChange = { onConfigChange(config.copy(webDavUrl = it)) },
                label = { Text("WebDAV URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.webDavUser,
                onValueChange = { onConfigChange(config.copy(webDavUser = it)) },
                label = { Text("WebDAV Username") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.webDavPass,
                onValueChange = { onConfigChange(config.copy(webDavPass = it)) },
                label = { Text("WebDAV Password") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
