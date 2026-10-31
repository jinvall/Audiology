package com.audiology2.stream.assist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audiology2.stream.config.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Built-in assistant provider presets. [GROQ_FREE] is the default free tier:
 * no credit card, sub-second latency off-device, ~1,000 req/day headroom.
 * Keys are NOT shared between providers - switching preset means entering the
 * matching key.
 */
enum class AssistantProvider(
    val label: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean = true
) {
    GROQ_FREE("Groq Free", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
    OPENROUTER_FREE("OpenRouter Free", "https://openrouter.ai/api/v1", "openai/gpt-oss-120b:free"),
    GEMINI_FREE("Gemini Free", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-3.8-flash"),
    LOCAL_OLLAMA("Local (Ollama)", "http://10.0.0.147:11434/v1", "llama3", needsKey = false),
    CUSTOM("Custom", "", "");

    companion object {
        /** Free-tier default. */
        val DEFAULT = GROQ_FREE

        /** Best preset match for a stored base URL + model, else null. */
        fun match(baseUrl: String, model: String): AssistantProvider? {
            val b = baseUrl.trim().trimEnd('/')
            val m = model.trim()
            return entries.firstOrNull {
                it != CUSTOM && it.baseUrl.trimEnd('/').equals(b, ignoreCase = true) &&
                    it.model.equals(m, ignoreCase = true)
            }
        }
    }
}

/**
 * Assistant connection settings, persisted in the shared DataStore.
 * The API key is stored encrypted (Android Keystore AES-GCM).
 */
object AssistantStore {

    data class Config(
        val baseUrl: String = AssistantProvider.DEFAULT.baseUrl,
        val model: String = AssistantProvider.DEFAULT.model,
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
            baseUrl = prefs[KEY_BASE_URL] ?: AssistantProvider.DEFAULT.baseUrl,
            model = prefs[KEY_MODEL] ?: AssistantProvider.DEFAULT.model,
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
            prefs[KEY_BASE_URL] = baseUrl.trim().ifEmpty { AssistantProvider.DEFAULT.baseUrl }
            prefs[KEY_MODEL] = model.trim().ifEmpty { AssistantProvider.DEFAULT.model }
            if (encrypted.isNotEmpty()) prefs[KEY_ENCRYPTED] = encrypted
        }
    }

    suspend fun clearKey() {
        store.edit { it.remove(KEY_ENCRYPTED) }
    }
}