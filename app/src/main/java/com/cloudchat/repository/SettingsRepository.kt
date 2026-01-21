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

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val gson = Gson()

    companion object {
        private val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        private val CURRENT_ACCOUNT_ID = stringPreferencesKey("current_account_id")
    }

    // List of all saved accounts
    val accounts: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        val json = prefs[ACCOUNTS_JSON] ?: return@map emptyList<ServerConfig>()
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        gson.fromJson(json, type)
    }

    // The currently active account id
    val currentAccountId: Flow<String?> = context.dataStore.data.map { it[CURRENT_ACCOUNT_ID] }

    // Flow for the actual current configuration
    val currentConfig: Flow<ServerConfig?> = context.dataStore.data.map { prefs ->
        val id = prefs[CURRENT_ACCOUNT_ID] ?: return@map null
        val json = prefs[ACCOUNTS_JSON] ?: return@map null
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        val list: List<ServerConfig> = gson.fromJson(json, type)
        list.find { it.id == id }
    }

    suspend fun saveAccount(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_JSON] ?: "[]"
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            val list: MutableList<ServerConfig> = gson.fromJson(json, type)
            
            val index = list.indexOfFirst { it.id == config.id }
            if (index != -1) {
                list[index] = config
            } else {
                list.add(config)
            }
            
            prefs[ACCOUNTS_JSON] = gson.toJson(list)
            prefs[CURRENT_ACCOUNT_ID] = config.id
        }
    }

    suspend fun switchAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            prefs[CURRENT_ACCOUNT_ID] = accountId
        }
    }

    suspend fun deleteAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_JSON] ?: "[]"
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            val list: MutableList<ServerConfig> = gson.fromJson(json, type)
            list.removeAll { it.id == accountId }
            prefs[ACCOUNTS_JSON] = gson.toJson(list)
            if (prefs[CURRENT_ACCOUNT_ID] == accountId) {
                val newCurrentId = list.firstOrNull()?.id
                if (newCurrentId != null) {
                    prefs[CURRENT_ACCOUNT_ID] = newCurrentId
                } else {
                    prefs.remove(CURRENT_ACCOUNT_ID)
                }
            }
        }
    }
}
