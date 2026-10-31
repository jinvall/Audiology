package com.audiology2.stream.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.audiology2.stream.audio.AudioFormat
import com.audiology2.stream.audio.CaptureStats
import com.audiology2.stream.audio.MicrophoneCapture
import com.audiology2.stream.audio.WavWriter
import com.audiology2.stream.config.AppSettings
import com.audiology2.stream.config.SettingsStore
import com.audiology2.stream.network.NetworkStats
import com.audiology2.stream.network.PacketizedFrame
import com.audiology2.stream.network.Protocol
import com.audiology2.stream.network.StreamSender
import com.audiology2.stream.network.StreamState
import com.audiology2.stream.ui.AudioProject
import com.audiology2.stream.ui.Clip
import com.audiology2.stream.ui.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.audiology2.stream.START"
        const val ACTION_STOP = "com.audiology2.stream.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.audiology2.stream.TOGGLE_PAUSE"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_PRESET_NAME = "preset"
        private const val TAG = "StreamService"
        private const val QUEUE_CAPACITY = 64
    }

    interface Callback {
        fun onCaptureStats(stats: CaptureStats)
        fun onNetworkStats(stats: NetworkStats)
        fun onServiceState(state: ServiceState)
    }

    private val binder = LocalBinder()
    private val callbacks = CopyOnWriteArrayList<Callback>()

    // Shared bounded queue: capture thread produces, sender thread consumes.
    private val queue = ArrayBlockingQueue<PacketizedFrame>(QUEUE_CAPACITY)

    private val capture = MicrophoneCapture(
        sink = queue,
        statsListener = { publishCapture(it) },
        audioThreadErrorHandler = { e ->
            Log.e(TAG, "capture thread error", e)
            publishCapture(CaptureStats(running = false, error = e.message ?: "capture error"))
            setState(ServiceState.ERROR)
        },
        recordingCallback = { samples, framesRead, channels ->
            writeRecordingSamples(samples, framesRead, channels)
        }
    )
    private val sender = StreamSender(
        queue = queue,
        statsListener = { publishNetwork(it) }
    )

    private val state = AtomicReference<ServiceState>(ServiceState.IDLE)
    private val captureStats = AtomicReference(CaptureStats(running = false))
    private val networkStats = AtomicReference(NetworkStats(state = StreamState.DISCONNECTED))
    private var settings: AppSettings = AppSettings()
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgJob = Job()
    private val bgScope = CoroutineScope(Dispatchers.IO + bgJob)

    // Recording state
    private var wavWriter: WavWriter? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordingSamples = AtomicLong(0L)
    private val recordingCallbacks = CopyOnWriteArrayList<(FloatArray, Int, Int) -> Unit>()

    fun registerRecordingCallback(cb: (FloatArray, Int, Int) -> Unit) {
        recordingCallbacks.add(cb)
    }

    fun unregisterRecordingCallback(cb: (FloatArray, Int, Int) -> Unit) {
        recordingCallbacks.remove(cb)
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    private fun publishCapture(s: CaptureStats) {
        captureStats.set(s)
        callbacks.forEach { it.onCaptureStats(s) }
    }

    private fun publishNetwork(s: NetworkStats) {
        networkStats.set(s)
        callbacks.forEach { it.onNetworkStats(s) }
    }

    private fun setState(s: ServiceState) {
        state.set(s)
        updateForeground(s)
        callbacks.forEach { it.onServiceState(s) }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_PAUSE -> {
                publishCapture(captureStats.get().copy(running = false)) // nudge
            }
            ACTION_START, null -> {
                val raw = intent?.getStringExtra(EXTRA_SETTINGS)
                if (raw != null) {
                    startStreaming(safeParse(raw))
                } else {
                    // Recovered after a kill: reload persisted settings.
                    bgScope.launch {
                        val persisted = try { SettingsStore.currentSettings() } catch (e: Exception) {
                            AppSettings()
                        }
                        mainHandler.post { startStreaming(persisted) }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(p1: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?) = true

    override fun onDestroy() {
        super.onDestroy()
        bgJob.cancel()
        stopStreaming()
        releaseWakeLock()
    }

    /** Foreground + capture + sender start. */
    private fun startStreaming(s: AppSettings) {
        settings = s
        Log.i(TAG, "startStreaming: host=${s.network.host} port=${s.network.port} transport=${s.network.transport}")
        val playbackMode = s.capture.inputSource == com.audiology2.stream.config.InputSource.PLAYBACK
        if (!playbackMode && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            publishCapture(CaptureStats(running = false, error = "microphone permission required"))
            setState(ServiceState.ERROR)
            return
        }
        acquireWakeLock()
        setState(ServiceState.CONNECTING)
        val notification = NotificationHelper.buildNotification(this, ServiceState.CONNECTING, settings).build()
        val isPlayback = settings.capture.inputSource == com.audiology2.stream.config.InputSource.PLAYBACK
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // API 34+: the FGS type must match the mode. mediaProjection needs an
            // active projection (set by the Activity before start); microphone
            // otherwise.
            val fgsType = if (isPlayback) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NotificationHelper.NOTIF_ID, notification, fgsType)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground(type=$fgsType) failed: ${e.message}")
                startForeground(NotificationHelper.NOTIF_ID, notification)
            }
        } else {
            startForeground(NotificationHelper.NOTIF_ID, notification)
        }
        if (capture.start(buildCaptureConfig(settings))) {
            sender.start(
                host = settings.network.host,
                port = settings.network.port,
                transport = settings.network.transport,
                autoReconnect = settings.network.autoReconnect
            )
            setState(ServiceState.STREAMING)
        } else {
            setState(ServiceState.ERROR)
        }
    }

    fun stopStreaming() {
        try { sender.stop() } catch (_: Exception) { }
        try { capture.stop() } catch (_: Exception) { }
        setState(ServiceState.IDLE)
        releaseWakeLock()
        publishCapture(captureStats.get().copy(running = false))
        publishNetwork(networkStats.get().copy(state = StreamState.DISCONNECTED))
    }

    fun requestPause(unpause: Boolean) {
        // Pausing would also need to pause capture; kept simple: stop streaming.
        if (!unpause) stopStreaming() else setState(ServiceState.IDLE)
    }

    private fun buildCaptureConfig(s: AppSettings): MicrophoneCapture.Config {
        val isPlayback = s.capture.inputSource == com.audiology2.stream.config.InputSource.PLAYBACK
        val projection = if (isPlayback) PlaybackCaptureHolder.projection else null
        return MicrophoneCapture.Config(
            sampleRate = s.network.sampleRate,
            channels = if (s.network.channelMode == com.audiology2.stream.config.ChannelMode.STEREO) 2 else 1,
            pcmFormat = s.network.pcmFormat,
            audioSource = AudioFormat.inputSourceToAudioSource(s.capture.inputSource),
            bufferMs = s.capture.bufferMs,
            packetMs = s.network.packetMs,
            maxPayloadBytes = Protocol.MAX_PAYLOAD,
            digitalGainDb = s.capture.digitalGainDb,
            rawBypass = s.capture.rawBypass,
            dspConfig = s.dsp,
            sessionId = if (s.sessionId == 0) (1..60000).random() else s.sessionId,
            usePlaybackCapture = isPlayback && projection != null,
            mediaProjection = projection
        )
    }

    private fun safeParse(raw: String): AppSettings = try {
        json.decodeFromString<AppSettings>(raw)
    } catch (e: Exception) {
        Log.e(TAG, "failed to parse settings", e)
        AppSettings()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Audiology2::MicStream")
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    private fun updateForeground(state: ServiceState) {
        val notif = NotificationHelper.buildNotification(this, state, settings).build()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationHelper.NOTIF_ID, notif)
        } catch (_: Exception) { }
    }

    fun registerCallback(cb: Callback) { if (!callbacks.contains(cb)) callbacks.add(cb) }
    fun unregisterCallback(cb: Callback) { callbacks.remove(cb) }

    fun latestCaptureStats(): CaptureStats = captureStats.get()
    fun latestNetworkStats(): NetworkStats = networkStats.get()
    fun latestState(): ServiceState = state.get()

    /** Start recording to WAV file. Returns the file path if successful. */
    fun startRecording(): String? {
        if (isRecording) return null
        
        val projectDir = File(getExternalFilesDir(null), "recordings")
        projectDir.mkdirs()
        
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_$timestamp.wav"
        recordingFile = File(projectDir, fileName)
        
        wavWriter = WavWriter(
            recordingFile!!,
            settings.network.sampleRate,
            if (settings.network.channelMode == com.audiology2.stream.config.ChannelMode.STEREO) 2 else 1,
            settings.network.pcmFormat
        )
        
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        recordingSamples.set(0)
        
        Log.i(TAG, "Started recording to ${recordingFile!!.absolutePath}")
        return recordingFile!!.absolutePath
    }

    /** Stop recording and return the file path. */
    fun stopRecording(): String? {
        if (!isRecording) return null
        
        isRecording = false
        wavWriter?.close()
        wavWriter = null
        
        val filePath = recordingFile?.absolutePath
        recordingFile = null
        
        Log.i(TAG, "Stopped recording: $filePath")
        return filePath
    }

    /** Check if currently recording. */
    fun isRecording(): Boolean = isRecording

    /** Get recording duration in milliseconds. */
    fun getRecordingDurationMs(): Long {
        if (!isRecording) return 0
        return System.currentTimeMillis() - recordingStartTime
    }

    /** Get recording sample count. */
    fun getRecordingSamples(): Long = recordingSamples.get()

    /** Called from capture thread to write audio samples to WAV file. */
    fun writeRecordingSamples(samples: FloatArray, framesRead: Int, channels: Int) {
        if (!isRecording || wavWriter == null) return
        
        wavWriter!!.writeSamples(samples, framesRead, channels)
        recordingSamples.addAndGet(framesRead.toLong())
        
        // Notify recording callbacks
        recordingCallbacks.forEach { it(samples, framesRead, channels) }
    }
}
