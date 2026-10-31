package com.audiology2.stream.config

import com.audiology2.stream.dsp.AudioConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun validateHostAcceptsIpv4AndHostname() {
        assertTrue(Validators.validateHost("192.168.1.10"))
        assertTrue(Validators.validateHost("localhost"))
        assertTrue(Validators.validateHost("[::1]"))
        assertFalse(Validators.validateHost(""))
        assertFalse(Validators.validateHost("256.1.1.1"))
        assertFalse(Validators.validateHost("999.999.999.999"))
    }

    @Test
    fun validatePort() {
        assertTrue(Validators.validatePort(59090))
        assertFalse(Validators.validatePort(0))
        assertFalse(Validators.validatePort(70000))
    }

    @Test
    fun validateSampleRate() {
        assertTrue(Validators.validateSampleRate(48000))
        assertFalse(Validators.validateSampleRate(777777))
    }

    @Test
    fun validateAppSettingsAllowsEmptyHostForLocalMonitor() {
        // Blank host is valid now: capture-only local monitor (no stream).
        val local = AppSettings(network = NetworkConfig(host = "", port = 59090, sampleRate = 48000))
        val ok = Validators.validateAppSettings(local)
        assertTrue(ok.isOk, "empty host should be allowed for local monitoring")

        // An invalid non-blank host is still rejected.
        val bad = AppSettings(network = NetworkConfig(host = "999.999.999.999", port = 59090, sampleRate = 48000))
        val r = Validators.validateAppSettings(bad)
        assertFalse(r.isOk)
        assertTrue(r.errors.any { it.contains("host", true) })
    }

    @Test
    fun validateAppSettingsRejectsBadPort() {
        val bad = AppSettings(network = NetworkConfig(host = "192.168.1.5", port = 0, sampleRate = 48000))
        val r = Validators.validateAppSettings(bad)
        assertFalse(r.isOk)
    }

    @Test
    fun validateAppSettingsOkForGood() {
        val s = AppSettings(network = NetworkConfig(host = "10.0.0.5", port = 59090, sampleRate = 44100))
        val r = Validators.validateAppSettings(s)
        assertTrue(r.isOk)
    }

    @Test
    fun presetApplyOverridesActivePreset() {
        val base = AppSettings()
        val p = Presets.builtInByName("Maximum Sensitivity")!!
        val applied = p.settings
        assertEquals("Maximum Sensitivity", applied.activePreset)
        assertTrue(applied.capture.maxSensitivity)
        assertFalse(applied.dsp.compressorEnabled) // max-sensitivity must not compress
    }

    @Test
    fun serializationRoundTrip() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = AppSettings(network = NetworkConfig(host = "192.168.0.2", port = 59090, sampleRate = 48000),
            dsp = AudioConfig(inputGainDb = 12.0))
        val raw = json.encodeToString(AppSettings.serializer(), s)
        val back = json.decodeFromString<AppSettings>(raw)
        assertEquals("192.168.0.2", back.network.host)
        assertEquals(12.0, back.dsp.inputGainDb, 1e-9)
    }
}
