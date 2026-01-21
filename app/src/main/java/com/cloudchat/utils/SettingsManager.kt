package com.cloudchat.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.cloudchat.model.ServerConfig
import com.cloudchat.model.StorageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val STORAGE_TYPE = stringPreferencesKey("storage_type")
        val S3_ENDPOINT = stringPreferencesKey("s3_endpoint")
        val S3_BUCKET = stringPreferencesKey("s3_bucket")
        val S3_ACCESS_KEY = stringPreferencesKey("s3_access_key")
        val S3_SECRET_KEY = stringPreferencesKey("s3_secret_key")
        val CURRENT_USER = stringPreferencesKey("current_user")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
    }

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            type = StorageType.valueOf(prefs[STORAGE_TYPE] ?: StorageType.S3.name),
            endpoint = prefs[S3_ENDPOINT] ?: "",
            bucket = prefs[S3_BUCKET] ?: "",
            accessKey = prefs[S3_ACCESS_KEY] ?: "",
            secretKey = prefs[S3_SECRET_KEY] ?: "",
            username = prefs[CURRENT_USER] ?: "default",
            webDavUrl = prefs[WEBDAV_URL] ?: "",
            webDavUser = prefs[WEBDAV_USER] ?: "",
            webDavPass = prefs[WEBDAV_PASS] ?: ""
        )
    }

    suspend fun updateConfig(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[STORAGE_TYPE] = config.type.name
            prefs[S3_ENDPOINT] = config.endpoint
            prefs[S3_BUCKET] = config.bucket
            prefs[S3_ACCESS_KEY] = config.accessKey
            prefs[S3_SECRET_KEY] = config.secretKey
            prefs[CURRENT_USER] = config.username
            prefs[WEBDAV_URL] = config.webDavUrl
            prefs[WEBDAV_USER] = config.webDavUser
            prefs[WEBDAV_PASS] = config.webDavPass
        }
    }
}
