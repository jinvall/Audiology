package com.audiology2.stream.assist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audiology2.stream.config.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Assistant connection settings, persisted in the shared DataStore.
 * The API key is stored encrypted (Android Keystore AES-GCM).
 */
object AssistantStore {

    data class Config(
        val baseUrl: String = "https://openrouter.ai/api/v1",
        val model: String = "openai/gpt-oss-120b:free",
        val apiKey: String = "",
        val hasKey: Boolean = false
    )

    private val KEY_BASE_URL = stringPreferencesKey("assist_base_url")
    private val KEY_MODEL = stringPreferencesKey("assist_model")
    private val KEY_ENCRYPTED = stringPreferencesKey("assist_key_enc")

    private val store: DataStore<Preferences>
        get() = SettingsStore.getSharedDataStore()

    val configFlow = store.data.map { prefs ->
        val encrypted = prefs[KEY_ENCRYPTED] ?: ""
        val key = if (encrypted.isNotEmpty()) KeystoreCrypto.decrypt(encrypted) ?: "" else ""
        Config(
            baseUrl = prefs[KEY_BASE_URL] ?: "https://openrouter.ai/api/v1",
            model = prefs[KEY_MODEL] ?: "openai/gpt-oss-120b:free",
            apiKey = key,
            hasKey = key.isNotEmpty()
        )
    }

    suspend fun currentConfig(): Config = configFlow.first()

    suspend fun saveConfig(baseUrl: String, model: String, apiKey: String) {
        val encrypted = if (apiKey.isEmpty()) {
            ""
        } else {
            KeystoreCrypto.encrypt(apiKey) ?: ""
        }
        store.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl.trim().ifEmpty { "https://openrouter.ai/api/v1" }
            prefs[KEY_MODEL] = model.trim().ifEmpty { "openai/gpt-oss-120b:free" }
            if (encrypted.isNotEmpty()) prefs[KEY_ENCRYPTED] = encrypted
        }
    }

    suspend fun clearKey() {
        store.edit { it.remove(KEY_ENCRYPTED) }
    }
}