package com.audiology2.stream.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.audiology2.stream.dsp.AudioConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private object Keys {
    val SETTINGS = stringPreferencesKey("settings_json")
    val PRESETS = stringPreferencesKey("presets_json")
    val ACTIVE_PRESET = stringPreferencesKey("active_preset")
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * App-wide singleton holder for the DataStore. Initialized from
 * [com.audiology2.stream.StreamApp] so both the Activity and the foreground
 * Service can read/write settings without holding a Context.
 */
object SettingsStore {
    private lateinit var dataStore: DataStore<Preferences>

    fun init(context: android.content.Context) {
        if (!::dataStore.isInitialized) {
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                context.applicationContext.preferencesDataStoreFile("audiology2_settings")
            }
        }
    }

    private fun safeParse(raw: String): AppSettings =
        runCatching { json.decodeFromString<AppSettings>(raw) }
            .getOrDefault(AppSettings())

    // Lazily built so they don't touch [dataStore] during the object's <clinit>
    // (which runs before [init]); collected only after the DataStore exists.
    val settingsFlow: Flow<AppSettings> by lazy {
        dataStore.data
            .map { prefs ->
                val raw = prefs[Keys.SETTINGS]
                if (raw == null) AppSettings() else safeParse(raw)
            }
    }

    val savedPresetsFlow: Flow<List<Preset>> by lazy {
        dataStore.data
            .map { prefs ->
                val raw = prefs[Keys.PRESETS]
                if (raw.isNullOrEmpty()) emptyList()
                else runCatching { json.decodeFromString<List<Preset>>(raw) }.getOrDefault(emptyList())
            }
    }

    suspend fun saveSettings(s: AppSettings) {
        dataStore.edit { it[Keys.SETTINGS] = json.encodeToString(AppSettings.serializer(), s) }
    }

    suspend fun savePresets(presets: List<Preset>) {
        dataStore.edit { it[Keys.PRESETS] = json.encodeToString(ListSerializer(Preset.serializer()), presets) }
    }

    /** Blocking read for the Service recovery path (started on a background thread). */
    suspend fun currentSettings(): AppSettings = settingsFlow.first()

    /** Blocking read of saved presets for the Service recovery path. */
    suspend fun currentPresets(): List<Preset> = savedPresetsFlow.first()
}
