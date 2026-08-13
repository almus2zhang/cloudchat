package com.cloudchat.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cloudchat.model.ServerConfig
import com.cloudchat.model.StorageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import com.cloudchat.model.ChatCategory
import com.cloudchat.utils.ConfigHelper

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val gson = Gson()

    companion object {
        private val APP_MODE = stringPreferencesKey("app_mode")
        private val CATEGORIES_KEY = stringPreferencesKey("categories_json")

        private fun getAccountsKey(mode: String) = stringPreferencesKey("accounts_json_$mode")
        private fun getCurrentAccountIdKey(mode: String) = stringPreferencesKey("current_account_id_$mode")
        
        // Use obfuscated configuration for the FULL version
        val FIXED_FULL_CONFIG = ServerConfig(
            id = "fixed_full_id",
            type = StorageType.WEBDAV,
            webDavUrl = ConfigHelper.getUrl(),
            webDavFallbackUrl = ConfigHelper.getFallbackUrl(),
            serverPath = "/",
            saveDir = "public",
            webDavUser = ConfigHelper.getUser(),
            webDavPass = ConfigHelper.getPass()
        )
    }

    val appMode: Flow<com.cloudchat.model.AppMode> = context.dataStore.data.map { prefs ->
        try {
            com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
        } catch (e: Exception) {
            com.cloudchat.model.AppMode.NOT_SET
        }
    }

    suspend fun setAppMode(mode: com.cloudchat.model.AppMode) {
        context.dataStore.edit { it[APP_MODE] = mode.name }
    }

    // List of all saved accounts for the current mode
    val accounts: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        val mode = try {
            com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
        } catch (e: Exception) {
            com.cloudchat.model.AppMode.NOT_SET
        }
        val key = getAccountsKey(mode.name)
        val json = prefs[key] ?: return@map emptyList<ServerConfig>()
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        gson.fromJson(json, type)
    }

    // The currently active account id for the current mode
    val currentAccountId: Flow<String?> = context.dataStore.data.map { prefs ->
        val mode = try {
            com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
        } catch (e: Exception) {
            com.cloudchat.model.AppMode.NOT_SET
        }
        prefs[getCurrentAccountIdKey(mode.name)]
    }

    // Flow for the actual current configuration for the current mode
    val currentConfig: Flow<ServerConfig?> = context.dataStore.data.map { prefs ->
        val mode = try {
            com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
        } catch (e: Exception) {
            com.cloudchat.model.AppMode.NOT_SET
        }
        val id = prefs[getCurrentAccountIdKey(mode.name)] ?: return@map null
        val key = getAccountsKey(mode.name)
        val json = prefs[key] ?: return@map null
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        val list: List<ServerConfig> = gson.fromJson(json, type)
        list.find { it.id == id }
    }

    // 校验「设定的用户目录」合法性：目录各段只能包含数字、字母、下划线、连字符。
    // 返回 null 表示合法；否则返回错误提示文案。
    fun validateUserDir(config: ServerConfig): String? {
        val segments = buildList {
            val root = config.serverPath.trim().removePrefix("/").removeSuffix("/")
            if (root.isNotEmpty()) addAll(root.split('/').filter { it.isNotEmpty() })
            val dir = config.saveDir.trim().removePrefix("/").removeSuffix("/")
            if (dir.isNotEmpty()) addAll(dir.split('/').filter { it.isNotEmpty() })
        }
        if (segments.isEmpty()) {
            return "存储目录不能为空，请填写目录名"
        }
        val valid = Regex("^[a-zA-Z0-9_-]+$")
        for (seg in segments) {
            if (seg.length > 255) return "目录层级「$seg」过长（最多 255 字符）"
            if (seg == "." || seg == "..") return "目录层级不能为「$seg」"
            if (!valid.matches(seg)) return "目录层级「$seg」含非法字符，只能使用数字、字母、下划线(_)、连字符(-)"
        }
        return null
    }

    suspend fun saveAccount(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            val mode = try {
                com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
            } catch (e: Exception) {
                com.cloudchat.model.AppMode.NOT_SET
            }
            val accountsKey = getAccountsKey(mode.name)
            val currentIdKey = getCurrentAccountIdKey(mode.name)

            val json = prefs[accountsKey] ?: "[]"
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            val list: MutableList<ServerConfig> = gson.fromJson(json, type)
            
            val index = list.indexOfFirst { it.id == config.id }
            if (index != -1) {
                list[index] = config
            } else {
                list.add(config)
            }
            
            prefs[accountsKey] = gson.toJson(list)
            prefs[currentIdKey] = config.id
        }
    }

    suspend fun switchAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val mode = try {
                com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
            } catch (e: Exception) {
                com.cloudchat.model.AppMode.NOT_SET
            }
            prefs[getCurrentAccountIdKey(mode.name)] = accountId
        }
    }

    suspend fun deleteAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val mode = try {
                com.cloudchat.model.AppMode.valueOf(prefs[APP_MODE] ?: com.cloudchat.model.AppMode.NOT_SET.name)
            } catch (e: Exception) {
                com.cloudchat.model.AppMode.NOT_SET
            }
            val accountsKey = getAccountsKey(mode.name)
            val currentIdKey = getCurrentAccountIdKey(mode.name)

            val json = prefs[accountsKey] ?: "[]"
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            val list: MutableList<ServerConfig> = gson.fromJson(json, type)
            list.removeAll { it.id == accountId }
            prefs[accountsKey] = gson.toJson(list)
            if (prefs[currentIdKey] == accountId) {
                val newCurrentId = list.firstOrNull()?.id
                if (newCurrentId != null) {
                    prefs[currentIdKey] = newCurrentId
                } else {
                    prefs.remove(currentIdKey)
                }
            }
        }
    }

    val categories: Flow<List<ChatCategory>> = context.dataStore.data.map { prefs ->
        val json = prefs[CATEGORIES_KEY]
        if (json.isNullOrEmpty()) {
            val defaults = listOf(
                ChatCategory(id = "diary", name = "日记"),
                ChatCategory(id = "transfer", name = "传输"),
                ChatCategory(id = "work", name = "工作"),
                ChatCategory(id = "privacy", name = "隐私")
            )
            defaults
        } else {
            val type = object : TypeToken<List<ChatCategory>>() {}.type
            gson.fromJson(json, type)
        }
    }

    suspend fun saveCategories(list: List<ChatCategory>) {
        context.dataStore.edit { prefs ->
            prefs[CATEGORIES_KEY] = gson.toJson(list)
        }
    }
}
