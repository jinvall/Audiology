package com.audiology2.stream.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.audiology2.stream.R
import com.audiology2.stream.assist.AssistantProtocol
import com.audiology2.stream.assist.AssistantProtocol.ActionResult
import com.audiology2.stream.assist.AssistantProtocol.Tool.ArgSpec
import com.audiology2.stream.assist.AssistantProtocol.Tool
import com.audiology2.stream.assist.AssistantProvider
import com.audiology2.stream.assist.AssistantStore
import com.audiology2.stream.audio.AudioPlayer
import com.audiology2.stream.audio.CaptureStats
import com.audiology2.stream.config.AppSettings
import com.audiology2.stream.config.CaptureConfig
import com.audiology2.stream.config.ChannelMode
import com.audiology2.stream.config.InputSource
import com.audiology2.stream.config.NetworkConfig
import com.audiology2.stream.config.PcmFormat
import com.audiology2.stream.config.Preset
import com.audiology2.stream.config.Presets
import com.audiology2.stream.config.SettingsStore
import com.audiology2.stream.config.TransportType
import com.audiology2.stream.config.Validators
import com.audiology2.stream.databinding.ActivityMainBinding
import com.audiology2.stream.dsp.AudioConfig
import com.audiology2.stream.dsp.FFTProcessor
import com.audiology2.stream.dsp.dbDisplay
import com.audiology2.stream.export.AudioExporter
import com.audiology2.stream.export.ExportFormat
import com.audiology2.stream.importing.AudioImporter
import com.audiology2.stream.network.NetworkStats
import com.audiology2.stream.network.StreamState
import com.audiology2.stream.service.ServiceState
import com.audiology2.stream.service.StreamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import java.io.File

class MainActivity : AppCompatActivity(), StreamService.Callback {

    private lateinit var binding: ActivityMainBinding
    private var service: StreamService? = null
    private var bound = false
    /** Guards against listener feedback loops during programmatic updates. */
    private var updatingViews = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    // New editor components
    private val editorScope = CoroutineScope(Dispatchers.Default + Job())
    private val fftProcessor = FFTProcessor(fftSize = 2048, sampleRate = 48000)
    private lateinit var audioPlayer: AudioPlayer
    private val undoRedoManager = UndoRedoManager()
    private val snapGrid = SnapGrid(initialTempo = 120.0)
    private val audioImporter = AudioImporter(this, editorScope)
    private val audioExporter = AudioExporter(editorScope)
    private val clipEditor = ClipEditor(this)
    private val sampleMaker = SampleMaker(this)
    private val midiSampler = MidiSampler(this)
    
    // Recording callback reference for unregistering
    private var recordingCallback: ((FloatArray, Int, Int) -> Unit)? = null

    /** Most recently finished recording file (source for spectrogram selections). */
    private var lastRecordingPath: java.io.File? = null

    // ---- Spectral tools state (owned by SpectralCardController) ----
    private lateinit var spectralCard: SpectralCardController

    // Assistant
    private val assistantEngine = com.audiology2.stream.assist.AssistantEngine()
    private val assistantTools = com.audiology2.stream.assist.DawToolbox()
    private val assistantPairs = mutableListOf<Pair<String, String>>()
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var updatingAssistantProvider = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force LTR before super.onCreate so window is configured before view inflation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        super.onCreate(savedInstanceState)
        SettingsStore.init(applicationContext)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        spectralCard = SpectralCardController(
            this,
            binding.spectralPitchLabel,
            binding.spectralTunerBar,
            binding.spectralPeakLabel,
            binding.spectralF0Label,
            binding.spectralCentroidLabel,
            binding.spectralPartialsLabel
        )
        initViews()
        setupExpandableSections()
        loadSettings()
        requestNeededPermissions()
        
        // Initialize audio player
        audioPlayer = AudioPlayer(editorScope)
        setupAudioPlayerCallbacks()
        
        // Load last project
        loadLastProject()
    }

    private val permissionContract =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms.entries.all { it.value }
            if (granted) {
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            } else {
                val missing = perms.filterValues { !it }.keys.joinToString()
                Toast.makeText(this, "Permission denied: $missing", Toast.LENGTH_LONG).show()
            }
        }

    // ---- Playback capture (MediaProjection) ----

    private val mediaProjectionManager: android.media.projection.MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val mp = try {
                    mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                } catch (e: Exception) {
                    null
                }
                if (mp != null) {
                    // API 34+ tears the projection down immediately unless a
                    // callback is registered before capture starts.
                    if (Build.VERSION.SDK_INT >= 34) {
                        runCatching {
                            mp.registerCallback(
                                object : android.media.projection.MediaProjection.Callback() {},
                                android.os.Handler(android.os.Looper.getMainLooper())
                            )
                        }
                    }
                    com.audiology2.stream.service.PlaybackCaptureHolder.projection = mp
                    startStreaming()
                } else {
                    Toast.makeText(this, "Playback capture unavailable", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Playback capture cancelled", Toast.LENGTH_SHORT).show()
            }
        }

    /** Start, requesting the MediaProjection grant first when capturing playback. */
    private fun startWithInputSource() {
        val selected = readSettingsFromViews().capture.inputSource
        if (selected == InputSource.PLAYBACK) {
            if (Build.VERSION.SDK_INT < 29) {
                Toast.makeText(this, "Playback capture requires Android 10+", Toast.LENGTH_LONG).show()
                return
            }
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            return
        }
        com.audiology2.stream.service.PlaybackCaptureHolder.projection = null
        startStreaming()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = (binder as? StreamService.LocalBinder)
            service = b?.getService()
            bound = true
            service?.registerCallback(this@MainActivity)
            
            // Register the single live feed callback: waveform + FFT spectrogram.
            // This must happen here (on connect) - the earlier registration
            // path ran before the service bound and silently skipped setup.
            recordingCallback = { samples, framesRead, channels ->
                val sr = binding.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
                binding.mainWaveformView.setWaveform(samples, sr)

                val frames = fftProcessor.process(samples, framesRead, channels)
                frames?.let { spectrogramFrames ->
                    binding.mainSpectrogramView.updateSpectrogram(spectrogramFrames, sr, fftProcessor.fftSize)
                    spectralCard.onFrame(spectrogramFrames, sr, fftProcessor.fftSize)
                }
                binding.mainSpectrogramView.postInvalidate()
            }
            service?.registerRecordingCallback(recordingCallback!!)
            
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private fun initViews() {
        binding.startBtn.setOnClickListener { startWithInputSource() }
        binding.stopBtn.setOnClickListener { stopStreaming() }
        binding.reconnectSwitch.isChecked = true

        // Spinner adapters (one per array)
        binding.transportSpinner.adapter =
            ArrayAdapter.createFromResource(this, R.array.transport_options,
                android.R.layout.simple_spinner_item)
        binding.channelSpinner.adapter =
            ArrayAdapter.createFromResource(this, R.array.channel_options,
                android.R.layout.simple_spinner_item)
        binding.inputSourceSpinner.adapter =
            ArrayAdapter.createFromResource(this, R.array.input_source_options,
                android.R.layout.simple_spinner_item)
        binding.presetSpinner.adapter =
            ArrayAdapter.createFromResource(this, R.array.preset_options,
                android.R.layout.simple_spinner_item)

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingViews) updateSettingsFromViews()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(
            binding.transportSpinner, binding.channelSpinner,
            binding.inputSourceSpinner, binding.presetSpinner
        ).forEach { it.onItemSelectedListener = spinnerListener }

        binding.presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingViews) return
                val name = parent?.getItemAtPosition(position).toString()
                Presets.builtInByName(name)?.let { applyPreset(it.settings) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Editable field watchers
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, p: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!updatingViews) updateSettingsFromViews() }
        }
        listOf(
            binding.hostEdit, binding.portEdit, binding.sampleRateEdit,
            binding.packetMsEdit, binding.jitterMsEdit, binding.bufferMsEdit,
            binding.hpfHzEdit, binding.lpfHzEdit, binding.notchHzEdit, binding.notchQEdit,
            binding.eqHzEdit, binding.eqGainEdit, binding.eqBwEdit,
            binding.compThreshEdit, binding.compRatioEdit, binding.compAttackEdit, binding.compReleaseEdit,
            binding.echoDelayEdit, binding.echoFeedbackEdit, binding.echoMixEdit,
            binding.distDriveEdit, binding.tremoloRateEdit, binding.tremoloDepthEdit
        ).forEach { it.addTextChangedListener(watcher) }

        listOf(
            binding.transportSpinner, binding.channelSpinner, binding.inputSourceSpinner
        ).forEach { spin ->
            spin.onItemSelectedListener = spinnerListener
        }

        // Switch listeners
        listOf(
            binding.reconnectSwitch, binding.maxSensitivitySwitch, binding.rawBypassSwitch,
            binding.agcSwitch, binding.nsupSwitch, binding.hpfSwitch, binding.lpfSwitch,
            binding.notchSwitch, binding.eqSwitch, binding.compSwitch, binding.limiterSwitch,
            binding.echoSwitch, binding.distSwitch, binding.tremoloSwitch,
            binding.abSwitch
        ).forEach { sw -> sw.setOnCheckedChangeListener { _, _ -> if (!updatingViews) updateSettingsFromViews() } }

        // Gain rotary knob: -60..+60 dB
        binding.gainKnob.setOnValueChangeListener { value ->
            val db = value.toInt()
            binding.gainLabel.text = getString(R.string.label_digital_gain) + ": ${db}dB"
            if (!updatingViews) updateSettingsFromViews()
        }

        // Editor / Timeline initialization
        initEditor()

        binding.savePresetBtn.setOnClickListener { saveCustomPreset() }
        binding.resetPresetBtn.setOnClickListener { resetToDefaults() }
    }

    private fun initEditor() {
        // Initialize waveform view with live capture data
        binding.mainWaveformView.setTrackColor(0xFF00D4AA.toInt())
        
        // Spectrogram view - use SRP spectral gradient
        binding.mainSpectrogramView.setFrequencyRange(20.0, 20000.0)

        // Transport controls
        binding.btnPlay.setOnClickListener { togglePlayback() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnRewind.setOnClickListener { rewind() }
        binding.btnFastForward.setOnClickListener { fastForward() }
        
        // Editor toolbar controls
        binding.btnUndo.setOnClickListener { 
            if (undoRedoManager.undo()) {
                binding.btnUndo.isEnabled = undoRedoManager.canUndo
                binding.btnRedo.isEnabled = undoRedoManager.canRedo
                Toast.makeText(this, "Undo: ${undoRedoManager.undoDescription}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRedo.setOnClickListener { 
            if (undoRedoManager.redo()) {
                binding.btnUndo.isEnabled = undoRedoManager.canUndo
                binding.btnRedo.isEnabled = undoRedoManager.canRedo
                Toast.makeText(this, "Redo: ${undoRedoManager.redoDescription}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnZoomFit.setOnClickListener { binding.trackTimelineView.zoomToFit() }
        binding.btnZoomSelection.setOnClickListener { binding.trackTimelineView.zoomToSelection() }
        
        // Loop toggle: loops the selected range (or first clip extent when none).
        binding.btnLoop.setOnClickListener { toggleLoop() }
        
        // Export/Import
        binding.btnProjects.setOnClickListener { showProjectsDialog() }
        binding.btnExport.setOnClickListener { showExportDialog() }
        binding.btnImport.setOnClickListener { showImportDialog() }
        binding.btnLibrary.setOnClickListener { openLibrary() }
        
        // Snap grid spinner
        binding.spinnerSnapMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> SnapGrid.Mode.OFF
                    1 -> SnapGrid.Mode.SECONDS
                    2 -> SnapGrid.Mode.FRAMES
                    3 -> SnapGrid.Mode.BEATS
                    4 -> SnapGrid.Mode.MEASURES
                    5 -> SnapGrid.Mode.BEATS_8TH
                    6 -> SnapGrid.Mode.BEATS_16TH
                    7 -> SnapGrid.Mode.BEATS_TRIPLET
                    else -> SnapGrid.Mode.OFF
                }
                snapGrid.mode = mode
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // View toggles
        binding.switchWaveform.isChecked = true
        binding.switchSpectrogram.isChecked = false
        binding.switchWaveform.setOnCheckedChangeListener { _, checked ->
            binding.mainWaveformView.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.switchSpectrogram.setOnCheckedChangeListener { _, checked ->
            binding.mainSpectrogramView.onSelectionEnd = { (start, end) ->
            createClipFromSpectrogramSelection(start, end)
        }

        binding.mainSpectrogramView.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Initialize track timeline
        initTrackTimeline()
        
        // Real-time spectrogram feed is registered in onServiceConnected
        // (it must run after the service binds, since the callback lives on
        // the service's capture thread).
        
        // Setup snap grid
        setupSnapGrid()
    }

    private fun setupAudioPlayerCallbacks() {
        audioPlayer.onPositionUpdate = { timeSec ->
            binding.trackTimelineView.setPlayheadTime(timeSec)
            binding.timelineTimeDisplay.text = formatTime(timeSec) + " / " + formatTime(audioPlayer.getDurationSec())
        }
        audioPlayer.onPlaybackEnd = {
            pausePlayback()
            binding.trackTimelineView.clearPlayhead()
        }
        audioPlayer.onError = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun setupSnapGrid() {
        // Sync snap grid with project tempo
        val project = ProjectManager.getCurrentProject()
        project?.let {
            snapGrid.tempo = it.tempo
            snapGrid.timeSignatureNumerator = it.timeSignatureNumerator
            snapGrid.timeSignatureDenominator = it.timeSignatureDenominator
        }
        
        // Apply snapping to timeline
        binding.trackTimelineView.setSnapGrid(snapGrid)
    }

    private fun loadLastProject() {
        // Project loading/creation is handled by initTrackTimeline().
        // Keeping the hook here so future "most recently used project" logic has a place.
    }

    private fun initTrackTimeline() {
        // Ensure DataStore state is loaded, then create a default project if none exists
        editorScope.launch {
            ProjectManager.load()
            if (ProjectManager.getCurrentProject() == null) {
                ProjectManager.createNewProject("Session")
            }
            val project = ProjectManager.getCurrentProject() ?: return@launch

            // Ensure at least one track exists
            if (project.tracks.isEmpty()) {
                ProjectManager.updateCurrentProject { it.addTrack(name = "Track 1", color = 0xFF00D4AA.toInt()) }
            }

            setupTimeline(ProjectManager.getCurrentProject())
        }
    }

    private fun setupTimeline(project: AudioProject?) {
        binding.trackTimelineView.setProject(project)
        binding.trackTimelineView.setSampleRate(
            binding.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
        )

        // Wire up timeline callbacks
        binding.trackTimelineView.onSeekRequest = { time ->
            stopPlayback()
            audioPlayer.seek(time)
            binding.trackTimelineView.setPlayheadTime(time)
            binding.timelineTimeDisplay.text = formatTime(time) + " / " + formatTime(audioPlayer.getDurationSec())
        }
        binding.trackTimelineView.onTrackSelect = { track ->
            showTrackPropertiesDialog(track)
        }
        binding.trackTimelineView.onClipSelect = { clip ->
            Log.d("Editor", "Selected clip: ${clip.name}")
        }
        binding.trackTimelineView.onEmptySelect = { time, track ->
            Log.d("Editor", "Empty select at ${time}s on ${track.name}")
        }
        binding.trackTimelineView.onClipChanged = { updatedClip ->
            // Persist the in-place drag/resize change back into the current project
            val trackId = updatedClip.trackId
            ProjectManager.updateCurrentProject { p ->
                p.updateTrackClips(trackId, p.getTrack(trackId)?.clips?.map { if (it.id == updatedClip.id) updatedClip else it } ?: p.getTrack(trackId)?.clips ?: emptyList())
            }
            binding.trackTimelineView.setProject(ProjectManager.getCurrentProject())
            binding.trackTimelineView.postInvalidate()
        }

        // Track timeline controls
        binding.btnAddTrack.setOnClickListener { addTrack() }
        binding.btnDeleteTrack.setOnClickListener { deleteTrack() }
        binding.btnSplitClip.setOnClickListener { splitClip() }
        binding.btnDuplicateClip.setOnClickListener { duplicateClip() }
        binding.btnDeleteClip.setOnClickListener { deleteClip() }
        binding.btnPitchSpeed.setOnClickListener { showPitchSpeedDialog() }
        binding.btnEffects.setOnClickListener { showEffectsDialog() }
        binding.btnSpectralEdit.setOnClickListener { showSpectralEditorDialog() }
        binding.btnMakeSample.setOnClickListener { makeSampleFromSelection() }
        binding.btnAddMidi.setOnClickListener { addMidiClip() }
        binding.trackTimelineView.onMidiClipTap = { midi -> openMidiEditor(midi) }
        binding.btnSpectralCapture.setOnClickListener { capturePitchToMidi() }
        binding.btnSpectralCopy.setOnClickListener { spectralCard.copyPeak() }
        setupAssistant()
        setupMidiEditor()
        
        binding.btnZoomFit.setOnClickListener { binding.trackTimelineView.zoomToFit() }
        binding.btnZoomSelection.setOnClickListener { binding.trackTimelineView.zoomToSelection() }
    }

    // Track timeline actions
    private fun addTrack() {
        val project = ProjectManager.getCurrentProject() ?: return
        val trackNum = project.tracks.size + 1
        val colors = intArrayOf(
            0xFF00D4AA.toInt(), 0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt(), 
            0xFFFFE66D.toInt(), 0xFF95E1D3.toInt(), 0xFFDDA0DD.toInt()
        )
        val color = colors[(trackNum - 1) % colors.size]
        val updated = ProjectManager.updateCurrentProject { it.addTrack(name = "Track $trackNum", color = color) }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        Log.d("Editor", "Added track: Track $trackNum")
    }

    private fun deleteTrack() {
        val project = ProjectManager.getCurrentProject() ?: return
        if (project.tracks.size <= 1) {
            Toast.makeText(this, "Cannot delete last track", Toast.LENGTH_SHORT).show()
            return
        }
        val trackId = binding.trackTimelineView.currentSelectedTrackId
        if (trackId != null) {
            val updated = ProjectManager.updateCurrentProject { it.removeTrack(trackId) }
            binding.trackTimelineView.setProject(updated)
            binding.trackTimelineView.postInvalidate()
            Log.d("Editor", "Deleted track: $trackId")
        }
    }

    private fun splitClip() {
        val project = ProjectManager.getCurrentProject() ?: return
        val clipId = binding.trackTimelineView.currentSelectedClipId
        val trackId = binding.trackTimelineView.currentSelectedTrackId
        if (clipId == null || trackId == null) {
            Toast.makeText(this, "Select a clip to split", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clip = binding.trackTimelineView.findClip(clipId) ?: return
        val track = project.getTrack(trackId) ?: return
        
        // Split at playhead position if available, otherwise at clip middle
        val splitTime = if (binding.trackTimelineView.currentPlayheadTime > 0
            && binding.trackTimelineView.currentPlayheadTime > clip.startTime
            && binding.trackTimelineView.currentPlayheadTime < clip.endTime) {
            binding.trackTimelineView.currentPlayheadTime - clip.startTime
        } else {
            clip.duration / 2
        }
        
        val result = clip.splitAt(splitTime)
        result?.let { (left, right) ->
            val newClips = track.clips.filterNot { it.id == clipId }.toMutableList().apply {
                add(left); add(right)
            }.sortedBy { it.startTime }
            val updated = ProjectManager.updateCurrentProject { it.updateTrackClips(trackId, newClips) }
            binding.trackTimelineView.setProject(updated)
            binding.trackTimelineView.postInvalidate()
            Log.d("Editor", "Split clip at ${splitTime}s")
        }
    }

    private fun duplicateClip() {
        val project = ProjectManager.getCurrentProject() ?: return
        val clipId = binding.trackTimelineView.currentSelectedClipId
        val trackId = binding.trackTimelineView.currentSelectedTrackId
        if (clipId == null || trackId == null) {
            Toast.makeText(this, "Select a clip to duplicate", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clip = binding.trackTimelineView.findClip(clipId) ?: return
        val track = project.getTrack(trackId) ?: return
        
        val duplicate = clip.with(
            id = java.util.UUID.randomUUID().toString(),
            name = "${clip.name} (copy)",
            startTime = clip.endTime
        )
        val updatedTrack = track.appendClip(duplicate)
        val updated = ProjectManager.updateCurrentProject { it.updateTrack(updatedTrack) }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        Log.d("Editor", "Duplicated clip: ${clip.name}")
    }

    private fun deleteClip() {
        val project = ProjectManager.getCurrentProject() ?: return
        val clipId = binding.trackTimelineView.currentSelectedClipId
        val trackId = binding.trackTimelineView.currentSelectedTrackId
        if (clipId == null || trackId == null) {
            Toast.makeText(this, "Select a clip to delete", Toast.LENGTH_SHORT).show()
            return
        }
        
        val track = project.getTrack(trackId) ?: return
        val updatedTrack = track.removeClip(clipId)
        val updated = ProjectManager.updateCurrentProject { it.updateTrack(updatedTrack) }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        Log.d("Editor", "Deleted clip: $clipId")
    }

    // ===== Assistant (Tier 1: text/voice DAW commands) =====

    private fun setupAssistant() {
        registerAssistantTools()
        loadAssistantConfig()

        // Provider preset dropdown. Default = Groq Free (free tier); selecting
        // a preset fills base URL + model. Keys are per-provider.
        val providers = AssistantProvider.entries
        binding.assistantProviderSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.label }
        )
        binding.assistantProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingAssistantProvider) return
                val provider = providers.getOrNull(position) ?: return
                if (provider == AssistantProvider.CUSTOM) return
                binding.assistantBaseUrl.setText(provider.baseUrl)
                binding.assistantModel.setText(provider.model)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnAssistantSend.setOnClickListener { sendAssistantCommand(binding.assistantInput.text.toString()) }
        binding.assistantInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendAssistantCommand(binding.assistantInput.text.toString())
                true
            } else false
        }
        binding.btnAssistantMic.setOnClickListener { startAssistantVoiceInput() }
        binding.btnAssistantSaveKey.setOnClickListener { saveAssistantConfig() }
    }

    private fun refreshTimeline() {
        binding.trackTimelineView.setProject(ProjectManager.getCurrentProject())
        binding.trackTimelineView.postInvalidate()
    }

    private fun loadAssistantConfig() {
        lifecycleScope.launch {
            val cfg = AssistantStore.currentConfig()
            val providers = AssistantProvider.entries

            // Migration: adopt the free-tier default when still on the legacy
            // OpenAI default (never customized), keeping the encrypted key.
            val effective = if (cfg.baseUrl == "https://api.openai.com/v1" && cfg.model == "gpt-4o-mini") {
                AssistantStore.saveConfig(
                    AssistantProvider.DEFAULT.baseUrl,
                    AssistantProvider.DEFAULT.model,
                    cfg.apiKey
                )
                cfg.copy(
                    baseUrl = AssistantProvider.DEFAULT.baseUrl,
                    model = AssistantProvider.DEFAULT.model
                )
            } else cfg

            binding.assistantBaseUrl.setText(effective.baseUrl)
            binding.assistantModel.setText(effective.model)
            binding.assistantKey.setText(if (effective.hasKey) "••••••••" else "")

            val match = AssistantProvider.match(effective.baseUrl, effective.model)
                ?: AssistantProvider.CUSTOM
            val idx = providers.indexOf(match).coerceAtLeast(0)
            updatingAssistantProvider = true
            binding.assistantProviderSpinner.setSelection(idx, false)
            updatingAssistantProvider = false
        }
    }

    private fun saveAssistantConfig() {
        lifecycleScope.launch {
            val entered = binding.assistantKey.text.toString()
            val key = if (entered == "••••••••") {
                AssistantStore.currentConfig().apiKey
            } else entered
            AssistantStore.saveConfig(
                binding.assistantBaseUrl.text.toString(),
                binding.assistantModel.text.toString(),
                key.trim()
            )
            Toast.makeText(this@MainActivity, "Assistant settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun projectSnapshot(): com.audiology2.stream.assist.AssistantProtocol.ProjectContext {
        val p = ProjectManager.getCurrentProject() ?: return com.audiology2.stream.assist.AssistantProtocol.ProjectContext()
        return com.audiology2.stream.assist.AssistantProtocol.ProjectContext(
            name = p.name,
            tempo = p.tempo,
            timeSignature = "${p.timeSignatureNumerator}/${p.timeSignatureDenominator}",
            tracks = p.tracks.map { t ->
                com.audiology2.stream.assist.AssistantProtocol.TrackCtx(
                    id = t.id, name = t.name, muted = t.muted, solo = t.solo,
                    clips = t.clips.map { c ->
                        com.audiology2.stream.assist.AssistantProtocol.ClipCtx(
                            id = c.id, name = c.name, startSec = c.startTime,
                            durationSec = c.duration, pitchSemitones = c.pitchSemitones,
                            speedRatio = c.speedRatio
                        )
                    },
                    midiClips = t.midiClips.map { it.name }
                )
            }
        )
    }

    /** Resolve a clip by name substring; falls back to selection, then first clip. */
    private fun resolveAssistantClip(name: String?): com.audiology2.stream.ui.Clip? {
        val project = ProjectManager.getCurrentProject() ?: return null
        if (!name.isNullOrBlank()) {
            val hit = project.tracks.firstNotNullOfOrNull { t ->
                t.clips.firstOrNull { it.name.contains(name, ignoreCase = true) }
            }
            if (hit != null) return hit
        }
        return currentSelectionClip() ?: project.tracks.firstOrNull()?.clips?.firstOrNull()
    }

    private fun registerAssistantTools() {
        val toolbox = assistantTools
        toolbox.register(
            Tool("set_tempo", "Set project tempo (BPM)", mapOf("bpm" to ArgSpec("number", "BPM 40-300", true)))
        ) { call ->
            val bpm = call.argDouble("bpm") ?: return@register ActionResult(false, "No BPM given")
            ProjectManager.updateCurrentProject { it.copy(tempo = bpm.coerceIn(40.0, 300.0)) }
            snapGrid.tempo = bpm
            binding.trackTimelineView.setSnapGrid(snapGrid)
            refreshTimeline()
            ActionResult(true, "Tempo set to ${bpm.roundToInt()} BPM")
        }
        toolbox.register(
            Tool("add_track", "Add a new track", mapOf("name" to ArgSpec("string", "Track name (optional)")))
        ) { call ->
            val name = call.argString("name")
            val n = (ProjectManager.getCurrentProject()?.tracks?.size ?: 0) + 1
            ProjectManager.updateCurrentProject { it.addTrack(name = name ?: "Track $n") }
            refreshTimeline()
            ActionResult(true, "Added track '${name ?: "Track $n"}'")
        }
        toolbox.register(
            Tool("split_clip", "Split a clip", mapOf(
                "clipName" to ArgSpec("string", "Clip to split"),
                "atFraction" to ArgSpec("number", "0-1 fraction (default 0.5)")
            ))
        ) { call ->
            val clip = resolveAssistantClip(call.argString("clipName"))
                ?: return@register ActionResult(false, "No clip found to split")
            val fraction = (call.argDouble("atFraction") ?: 0.5).coerceIn(0.01, 0.99)
            val pair = clip.splitAt(clip.duration * fraction)
                ?: return@register ActionResult(false, "Split failed")
            val (left, right) = pair
            val track = ProjectManager.getCurrentProject()?.getTrack(clip.trackId)
                ?: return@register ActionResult(false, "Clip track missing")
            val remaining = track.clips.filterNot { it.id == clip.id }
            val newClips = (remaining + left + right).sortedBy { it.startTime }
            ProjectManager.updateCurrentProject { it.updateTrackClips(clip.trackId, newClips) }
            refreshTimeline()
            ActionResult(true, "Split '${clip.name}' at ${(fraction * 100).roundToInt()}%")
        }
        toolbox.register(
            Tool("duplicate_clip", "Duplicate a clip", mapOf("clipName" to ArgSpec("string", "Clip to duplicate")))
        ) { call ->
            val clip = resolveAssistantClip(call.argString("clipName"))
                ?: return@register ActionResult(false, "No clip found to duplicate")
            val track = ProjectManager.getCurrentProject()?.getTrack(clip.trackId)
                ?: return@register ActionResult(false, "Clip track missing")
            val dup = clip.with(id = java.util.UUID.randomUUID().toString(), name = "${clip.name} (copy)", startTime = clip.endTime)
            ProjectManager.updateCurrentProject { it.updateTrack(track.appendClip(dup)) }
            refreshTimeline()
            ActionResult(true, "Duplicated '${clip.name}'")
        }
        toolbox.register(
            Tool("delete_clip", "Delete a clip", mapOf("clipName" to ArgSpec("string", "Clip to delete")))
        ) { call ->
            val clip = resolveAssistantClip(call.argString("clipName"))
                ?: return@register ActionResult(false, "No clip found to delete")
            val track = ProjectManager.getCurrentProject()?.getTrack(clip.trackId)
                ?: return@register ActionResult(false, "Clip track missing")
            ProjectManager.updateCurrentProject { it.updateTrack(track.removeClip(clip.id)) }
            refreshTimeline()
            ActionResult(true, "Deleted '${clip.name}'")
        }
        toolbox.register(
            Tool("pitch_speed", "Change pitch/speed of a clip", mapOf(
                "clipName" to ArgSpec("string", "Clip to edit"),
                "semitones" to ArgSpec("number", "Semitones (-12..12)"),
                "speed" to ArgSpec("number", "Speed ratio (0.5-2)"),
                "stretch" to ArgSpec("number", "Stretch ratio (0.5-2)")
            ))
        ) { call ->
            val clip = resolveAssistantClip(call.argString("clipName"))
                ?: return@register ActionResult(false, "No clip found")
            val st = call.argDouble("semitones") ?: 0.0
            val sp = call.argDouble("speed") ?: 1.0
            val tt = call.argDouble("stretch") ?: 1.0
            val busy = java.util.concurrent.atomic.AtomicBoolean(false)
            if (!busy.compareAndSet(false, true)) return@register ActionResult(false, "Busy")
            editorScope.launch {
                val result = clipEditor.applyPitchSpeed(clip, st, sp, tt, preview = false)
                busy.set(false)
                if (result != null) {
                    ProjectManager.updateCurrentProject { project ->
                        val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                        project.updateTrack(track.updateClip(clip.copy(
                            sourcePath = result.outputFile.absolutePath, sourceOffset = 0.0,
                            duration = result.finalDurationSec, pitchSemitones = st, speedRatio = sp, stretchRatio = tt
                        )))
                    }
                    runOnUiThread { refreshTimeline() }
                    appendAssistantResult(ActionResult(true, "Applied pitch ${st}st speed ${sp}x to '${clip.name}'"))
                } else {
                    appendAssistantResult(ActionResult(false, "Pitch/speed failed on '${clip.name}'"))
                }
            }
            ActionResult(true, "Processing pitch/speed...")
        }
        toolbox.register(
            Tool("make_sample", "Make a beat sample from a clip", mapOf("clipName" to ArgSpec("string", "Clip to sample")))
        ) { call ->
            val clip = resolveAssistantClip(call.argString("clipName"))
                ?: return@register ActionResult(false, "No clip found")
            val project = ProjectManager.getCurrentProject() ?: return@register ActionResult(false, "No project")
            val busy = java.util.concurrent.atomic.AtomicBoolean(false)
            if (!busy.compareAndSet(false, true)) return@register ActionResult(false, "Busy")
            editorScope.launch {
                val res = sampleMaker.makeSample(clip, project, thresholdDb = -48.0, normalize = true, snapToBeat = true)
                busy.set(false)
                if (res != null) {
                    ProjectManager.updateCurrentProject { p ->
                        var bt = p.tracks.firstOrNull { it.name.equals("Beats", true) }
                        var w = p
                        if (bt == null) { w = p.addTrack(name = "Beats", color = 0xFFFFD700.toInt()); bt = w.tracks.last() }
                        val sc = Clip(name = "${clip.name} (sample)", sourcePath = res.sampleFile.absolutePath,
                            duration = res.durationSec, startTime = res.placedAt, trackId = bt!!.id, color = 0xFFFFD700.toInt())
                        w.updateTrack(bt.insertClip(sc, res.placedAt))
                    }
                    runOnUiThread { refreshTimeline() }
                    appendAssistantResult(ActionResult(true, "Sample '${clip.name}' placed on Beats at ${String.format("%.2f", res.placedAt)}s"))
                } else {
                    appendAssistantResult(ActionResult(false, "Sample creation failed"))
                }
            }
            ActionResult(true, "Making sample from '${clip.name}'...")
        }
        toolbox.register(
            Tool("set_loop", "Loop a region", mapOf(
                "clipName" to ArgSpec("string", "Clip to loop (optional)"),
                "startSec" to ArgSpec("number", "Loop start"),
                "endSec" to ArgSpec("number", "Loop end")
            ))
        ) { call ->
            val start = call.argDouble("startSec")
            val end = call.argDouble("endSec")
            when {
                start != null && end != null && end > start -> {
                    audioPlayer.setLoop(start, end)
                    binding.trackTimelineView.setLoopRegion(start, end)
                    binding.btnLoop.setColorFilter(0xFFFFB300.toInt())
                    loopActive = true
                    ActionResult(true, "Looping ${String.format("%.2f", start)}-${String.format("%.2f", end)}s")
                }
                else -> {
                    val clip = resolveAssistantClip(call.argString("clipName"))
                    if (clip != null) {
                        audioPlayer.setLoop(clip.startTime, clip.endTime)
                        binding.trackTimelineView.setLoopRegion(clip.startTime, clip.endTime)
                        binding.btnLoop.setColorFilter(0xFFFFB300.toInt())
                        loopActive = true
                        ActionResult(true, "Looping '${clip.name}'")
                    } else {
                        ActionResult(false, "Give a start/end or select a clip to loop")
                    }
                }
            }
        }
        toolbox.register(
            Tool("add_midi_clip", "Add a MIDI clip and open the editor", mapOf("name" to ArgSpec("string", "Name (optional)")))
        ) { call ->
            addMidiClip()
            ActionResult(true, "MIDI clip added - the editor is open below")
        }
        toolbox.register(
            Tool("export_wav", "Export the project to WAV", mapOf())
        ) {
            showExportDialog()
            ActionResult(true, "Export dialog opened")
        }
    }

    private fun appendAssistantLine(line: String) {
        runOnUiThread {
            val existing = binding.assistantTranscript.text.toString()
            val lines = (existing.split("\n") + ">> $line").takeLast(24)
            binding.assistantTranscript.text = lines.joinToString("\n")
        }
    }

    private fun appendAssistantResult(result: com.audiology2.stream.assist.AssistantProtocol.ActionResult) {
        val prefix = if (result.ok) "OK: " else "FAIL: "
        appendAssistantLine(prefix + result.message)
        binding.assistantTranscript.post {
            (binding.assistantTranscript.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun sendAssistantCommand(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        binding.assistantInput.setText("")
        appendAssistantLine("YOU: $text")
        if (!assistantTools.tools.any { it.name != "none" }) {
            registerAssistantTools()
        }
        editorScope.launch {
            val cfg = com.audiology2.stream.assist.AssistantStore.currentConfig()
            if (!cfg.hasKey) {
                appendAssistantResult(com.audiology2.stream.assist.AssistantProtocol.ActionResult(false, "No API key saved - enter one in this card's settings to enable the assistant"))
                return@launch
            }
            val prompt = com.audiology2.stream.assist.AssistantProtocol.buildSystemPrompt(assistantTools.tools, projectSnapshot())
            val call = assistantEngine.requestIntent(cfg.baseUrl, cfg.model, cfg.apiKey, prompt, assistantPairs.takeLast(6), text)
            if (call.intent.isBlank() || call.intent == "none") {
                appendAssistantLine("AI: ${call.reply.ifEmpty { "I didn't catch that." }}")
            } else {
                val result = assistantTools.execute(call)
                appendAssistantLine("AI: ${call.reply.ifEmpty { result.message }}")
                appendAssistantResult(result)
            }
            assistantPairs.add(text to (call.reply.ifEmpty { "" }))
            if (assistantPairs.size > 8) assistantPairs.removeAt(0)
        }
    }

    private fun startAssistantVoiceInput() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        binding.assistantInput.setText(text)
                        sendAssistantCommand(text)
                    } else {
                        appendAssistantLine("SPEECH: no words heard")
                    }
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                        else -> "error $error"
                    }
                    appendAssistantLine("SPEECH: $msg")
                }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partial: android.os.Bundle?) {}
                override fun onEvent(type: Int, p: android.os.Bundle?) {}
            })
        }
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
    }

    // ===== MIDI editor =====

    private var currentMidiClipId: String? = null
    private var auditionTrack: android.media.AudioTrack? = null

    /** Current MIDI clip being edited (from the live project). */
    private fun currentMidiClip(): MidiClip? {
        val id = currentMidiClipId ?: return null
        val project = ProjectManager.getCurrentProject() ?: return null
        return project.tracks.firstNotNullOfOrNull { t ->
            t.midiClips.firstOrNull { it.id == id }
        }
    }

    private fun setupMidiEditor() {
        binding.pianoRollView.onClipChanged = { updatedMidi ->
            val project = ProjectManager.getCurrentProject()
            val track = project?.getTrack(updatedMidi.trackId)
            if (project != null && track != null) {
                val updated = ProjectManager.updateCurrentProject { it.updateTrack(track.updateMidiClip(updatedMidi)) }
                binding.trackTimelineView.setProject(updated)
                updateMidiNoteInfo()
            }
        }
        binding.pianoRollView.onVelocityRequest = { note ->
            updateMidiNoteInfo()
            showMidiVelocityDialog(note)
        }
        binding.pianoRollView.onPitchAudition = { pitch -> auditionTone(pitch) }

        binding.btnMidiMode.setOnClickListener { toggleMidiMode() }
        binding.btnMidiPlay.setOnClickListener { playCurrentMidi() }
        binding.btnMidiDelete.setOnClickListener {
            binding.pianoRollView.deleteSelectedNote()
        }
        binding.btnMidiClose.setOnClickListener {
            binding.midiEditorPanel.visibility = View.GONE
            currentMidiClipId = null
        }
    }

    private fun addMidiClip() {
        val project = ProjectManager.getCurrentProject() ?: return
        var track = binding.trackTimelineView.currentSelectedTrackId
            ?.let { project.getTrack(it) }
            ?: project.tracks.firstOrNull()
        var work = project
        if (track == null) {
            work = project.addTrack(name = "Track 1", color = 0xFF00D4AA.toInt())
            track = work.tracks.first()
        }
        val start = if (binding.trackTimelineView.currentPlayheadTime > 0) {
            binding.trackTimelineView.currentPlayheadTime
        } else track.duration
        val midi = MidiClip(
            name = "MIDI ${track.midiClips.size + 1}",
            startTime = start,
            trackId = track.id
        )
        val updated = ProjectManager.updateCurrentProject {
            it.updateTrack(track!!.withMidiClips(track.midiClips + midi))
        }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        openMidiEditor(midi)
    }

    private fun openMidiEditor(midi: MidiClip) {
        currentMidiClipId = midi.id
        binding.midiEditorPanel.visibility = View.VISIBLE
        val project = ProjectManager.getCurrentProject()
        val bpm = project?.tempo ?: 120.0
        binding.midiClipLabel.text = midi.name
        binding.pianoRollView.setDrumMode(midi.drumMode)
        binding.pianoRollView.setClip(midi, bpm)
        updateMidiModeButton()
        updateMidiNoteInfo()
        binding.pianoRollView.post { binding.pianoRollView.zoomToFitBeats(4) }
    }

    private fun updateMidiModeButton() {
        val drum = binding.pianoRollView.isDrumModeActive
        binding.btnMidiMode.text = if (drum) "Drums \u2713" else "Piano \u2713"
    }

    private fun toggleMidiMode() {
        val midi = currentMidiClip() ?: return
        val drum = !binding.pianoRollView.isDrumModeActive
        val updated = midi.copy(drumMode = drum)
        binding.pianoRollView.setDrumMode(drum)
        binding.pianoRollView.setClip(updated, ProjectManager.getCurrentProject()?.tempo ?: 120.0)
        // Persist via the same update path.
        binding.pianoRollView.onClipChanged?.invoke(updated)
        updateMidiModeButton()
    }

    private fun updateMidiNoteInfo() {
        val note = binding.pianoRollView.selectedNote
        if (note == null) {
            binding.midiNoteInfo.text = ""
            return
        }
        binding.midiNoteInfo.text = "note ${note.pitch} vel ${note.velocity}"
    }

    private fun showMidiVelocityDialog(note: MidiNote) {
        val holder = arrayOf(note.copy())
        val bar = SeekBar(this).apply {
            max = 127
            progress = note.velocity.coerceIn(1, 127)
        }
        val label = TextView(this).apply {
            text = "Velocity: ${note.velocity}"
            setPadding(24, 16, 24, 0)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "Velocity: ${p.coerceAtLeast(1)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Note velocity")
            .setView(root)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        binding.pianoRollView.updateNote(holder[0].copy(velocity = bar.progress.coerceAtLeast(1)))
                        d.dismiss()
                    }
                }
                d.show()
            }
    }

    private fun playCurrentMidi() {
        val project = ProjectManager.getCurrentProject() ?: return
        val midi = currentMidiClip() ?: return
        if (midi.notes.isEmpty()) {
            Toast.makeText(this, "Add notes first (tap the grid)", Toast.LENGTH_SHORT).show()
            return
        }
        val track = project.getTrack(midi.trackId)
        val pool = track?.clips ?: emptyList()
        if (pool.isEmpty()) {
            Toast.makeText(this, "Add audio samples on this track first (import or Make Sample)", Toast.LENGTH_LONG).show()
            return
        }
        editorScope.launch {
            val file = midiSampler.renderClip(midi, pool, project.tempo, project.sampleRate)
            runOnUiThread {
                if (file != null) {
                    stopPlayback()
                    if (audioPlayer.loadFile(file)) {
                        audioPlayer.seek(0.0)
                        audioPlayer.play()
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                        Toast.makeText(this@MainActivity, "Rendered ${midi.notes.size} notes", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "MIDI render failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Audition a pitch with a short sine tone. */
    private fun auditionTone(pitch: Int) {
        try {
            val sr = 48000
            val durMs = 160
            val freq = 440.0 * Math.pow(2.0, ((pitch - 69).toDouble()) / 12.0)
            val n = sr * durMs / 1000
            val samples = ShortArray(n)
            for (i in 0 until n) {
                val env = minOf(1.0, i / 400.0, (n - i) / 800.0).coerceAtLeast(0.0)
                samples[i] = (Math.sin(2.0 * Math.PI * freq * i / sr) * 0.35 * env * Short.MAX_VALUE).toInt().toShort()
            }
            val at = android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                samples.size * 2,
                android.media.AudioTrack.MODE_STATIC
            )
            auditionTrack?.let { runCatching { it.stop(); it.release() } }
            auditionTrack = at
            if (auditionTrack != null && auditionTrack!!.state == android.media.AudioTrack.STATE_INITIALIZED) {
                auditionTrack!!.write(samples, 0, samples.size)
                auditionTrack!!.play()
                binding.pianoRollView.postDelayed({
                    runCatching { auditionTrack?.stop(); auditionTrack?.release() }
                    auditionTrack = null
                }, (durMs + 120).toLong())
            }
        } catch (e: Exception) {
            Log.w("Editor", "Audition failed", e)
        }
    }

    // ===== Pitch / Speed editing =====

    private fun currentSelectionClip(): Clip? {
        val clipId = binding.trackTimelineView.currentSelectedClipId
        return clipId?.let { binding.trackTimelineView.findClip(it) }
    }

    private fun requireSelectedClip(action: String): Clip? {
        val clip = currentSelectionClip()
        if (clip == null) {
            Toast.makeText(this, "Select a clip to $action", Toast.LENGTH_SHORT).show()
        }
        return clip
    }

    private fun showPitchSpeedDialog() {
        val clip = requireSelectedClip("edit pitch/speed") ?: return
        val context = this

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }

        // Semitones slider: -12..+12 semitones
        val semiLabel = TextView(context).apply { text = "Pitch: ${clip.pitchSemitones} st" }
        val semiBar = SeekBar(context).apply {
            max = 240
            progress = ((clip.pitchSemitones + 12) * 10).toInt()
        }
        // Speed slider: 50..200 %
        val speedLabel = TextView(context).apply { text = "Speed: ${(clip.speedRatio * 100).toInt()}%" }
        val speedBar = SeekBar(context).apply {
            max = 150
            progress = ((clip.speedRatio * 100).toInt() - 50)
        }
        // Stretch slider: 50..200 % (duration only)
        val stretchLabel = TextView(context).apply { text = "Tempo/Stretch: ${(clip.stretchRatio * 100).toInt()}%" }
        val stretchBar = SeekBar(context).apply {
            max = 150
            progress = ((clip.stretchRatio * 100).toInt() - 50)
        }

        fun currentValues(): Triple<Double, Double, Double> {
            val st = (semiBar.progress - 120) / 10.0
            val sp = (speedBar.progress + 50) / 100.0
            val t = (stretchBar.progress + 50) / 100.0
            return Triple(st, sp, t)
        }

        semiBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val (st, _, _) = currentValues()
                semiLabel.text = "Pitch: ${String.format("%+.1f", st)} st"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val (_, sp, _) = currentValues()
                speedLabel.text = "Speed: ${(sp * 100).toInt()}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        stretchBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val (_, _, t) = currentValues()
                stretchLabel.text = "Tempo/Stretch: ${(t * 100).toInt()}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        root.addView(semiLabel)
        root.addView(semiBar)
        root.addView(speedLabel)
        root.addView(speedBar)
        root.addView(stretchLabel)
        root.addView(stretchBar)

        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Pitch / Speed — ${clip.name}")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        val previewBtn = Button(context).apply { text = "Preview" }
        val applyBtn = Button(context).apply { text = "Apply to Clip" }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            previewBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            applyBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(previewBtn)
            addView(applyBtn)
        }
        root.addView(row)

        // Busy flag so preview and apply do not collide.
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        fun runProcess(preview: Boolean, done: (com.audiology2.stream.ui.EditResult) -> Unit) {
            if (busy.get()) return
            val (st, sp, t) = currentValues()
            if (st == 0.0 && sp == 1.0 && t == 1.0) {
                Toast.makeText(context, "No change", Toast.LENGTH_SHORT).show()
                return
            }
            busy.set(true)
            previewBtn.isEnabled = false
            applyBtn.isEnabled = false
            editorScope.launch {
                val result = clipEditor.applyPitchSpeed(clip, st, sp, t, preview = preview)
                runOnUiThread {
                    busy.set(false)
                    previewBtn.isEnabled = true
                    applyBtn.isEnabled = true
                    if (result != null) {
                        if (preview) {
                            stopPlayback()
                            audioPlayer.loadFile(result.outputFile)
                            audioPlayer.seek(0.0)
                            audioPlayer.play()
                            binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                            Toast.makeText(context, "Previewing processed audio", Toast.LENGTH_SHORT).show()
                        } else {
                            done(result)
                        }
                    } else {
                        Toast.makeText(context, "Process failed (readable WAV source required)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        previewBtn.setOnClickListener {
            runProcess(preview = true) {}
        }
        applyBtn.setOnClickListener {
            val (st, sp, t) = currentValues()
            if (st == 0.0 && sp == 1.0 && t == 1.0) {
                Toast.makeText(context, "No change", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val rangeRel = currentClipRelativeRange(clip)
            if (rangeRel != null) {
                // Direct range path: no whole-clip processing needed.
                busy.set(true)
                applyBtn.isEnabled = false
                editorScope.launch {
                    val newClips = clipEditor.applyRangeTransform(clip, rangeRel) { samples, sr, ch ->
                        com.audiology2.stream.dsp.TimeStretch.transform(samples, ch, st, sp, t)
                    }
                    runOnUiThread {
                        busy.set(false)
                        applyBtn.isEnabled = true
                        if (newClips != null) {
                            val updated = ProjectManager.updateCurrentProject { project ->
                                val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                                val remaining = track.clips.filterNot { it.id == clip.id }
                                project.updateTrack(track.copy(clips = (remaining + newClips).sortedBy { it.startTime }))
                            }
                            binding.trackTimelineView.setProject(updated)
                            binding.trackTimelineView.postInvalidate()
                            binding.trackTimelineView.clearRangeSelection()
                            dialog.dismiss()
                            Toast.makeText(context, "Pitch/speed applied to selection", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Range apply failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return@setOnClickListener
            }
            runProcess(preview = false) { result ->
                // Commit: whole-clip update.
                val updated = ProjectManager.updateCurrentProject { project ->
                    val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                    val newClip = clip.copy(
                        sourcePath = result.outputFile.absolutePath,
                        sourceOffset = 0.0,
                        duration = result.finalDurationSec,
                        pitchSemitones = st,
                        speedRatio = sp,
                        stretchRatio = t,
                        startTime = clip.startTime
                    )
                    project.updateTrack(track.updateClip(newClip))
                }
                binding.trackTimelineView.setProject(updated)
                binding.trackTimelineView.postInvalidate()
                dialog.dismiss()
                Toast.makeText(context, "Pitch/speed applied", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    /** Intersection of the active timeline range with [clip], in clip-relative seconds. */
    private fun currentClipRelativeRange(clip: Clip): Pair<Double, Double>? {
        if (!binding.trackTimelineView.hasRangeSelection) return null
        val (rs, re) = binding.trackTimelineView.selectedRange
        val clipStart = clip.startTime
        val clipEnd = clip.endTime
        val overlapStart = maxOf(rs, clipStart)
        val overlapEnd = minOf(re, clipEnd)
        if (overlapEnd <= overlapStart) return null
        return (overlapStart - clipStart) to (overlapEnd - clipStart)
    }

    // ===== Effects editing =====

    private fun showEffectsDialog() {
        val clip = requireSelectedClip("apply effects") ?: return
        val context = this
        val settings = com.audiology2.stream.dsp.EffectSettings.DEFAULT.copy()

        val root = android.widget.ScrollView(context)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        root.addView(inner)

        fun row(label: String, valueText: (Int) -> String, max: Int, def: Int): Pair<SeekBar, () -> Int> {
            val lbl = TextView(context).apply { text = "$label: ${valueText(def)}" }
            val bar = SeekBar(context).apply { this.max = max; progress = def }
            inner.addView(lbl)
            inner.addView(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    lbl.text = "$label: ${valueText(p)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            return bar to { bar.progress }
        }

        // Filter section
        val hpCb = CheckBox(context).apply { text = "High-pass"; setPadding(0, 12, 0, 0) }
        inner.addView(hpCb)
        val (hpBar, hpVal) = row("High-pass freq", { "${it * 20 + 20} Hz" }, 200, (settings.highPassHz / 20) - 1)
        val lpCb = CheckBox(context).apply { text = "Low-pass" }
        inner.addView(lpCb)
        val (lpBar, lpVal) = row("Low-pass freq", { "${it * 100 + 100} Hz" }, 390, (settings.lowPassHz / 100) - 1)

        val nCb = CheckBox(context).apply { text = "Notch filter" }
        inner.addView(nCb)
        val (nBar, nVal) = row("Notch freq", { "${it * 2 + 20} Hz" }, 340, (settings.notchHz.toInt() / 2) - 10)

        val eqCb = CheckBox(context).apply { text = "EQ (peaking)" }
        inner.addView(eqCb)
        val (eqBar, eqVal) = row("EQ gain", { "${it - 24} dB" }, 48, 24)

        // Dynamics section
        val compCb = CheckBox(context).apply { text = "Compressor" }
        inner.addView(compCb)
        val (threshBar, threshVal) = row("Threshold", { "${it * -2} dB" }, 30, 12)
        val (ratioBar, ratioVal) = row("Ratio", { "1:${it.toDouble() / 5.0}" }, 50, 20)

        val limCb = CheckBox(context).apply { text = "Limiter" }
        inner.addView(limCb)

        // Space section
        val echoCb = CheckBox(context).apply { text = "Echo" }
        inner.addView(echoCb)
        val (echoBar, echoVal) = row("Echo delay", { "${it * 20 + 20} ms" }, 240, 15)
        val (echoMixBar, echoMixVal) = row("Echo mix", { "${it}%" }, 100, 25)

        val distCb = CheckBox(context).apply { text = "Distortion" }
        inner.addView(distCb)
        val (driveBar, driveVal) = row("Drive", { "${it}%" }, 100, 20)

        val triCb = CheckBox(context).apply { text = "Tremolo" }
        inner.addView(triCb)
        val (triRateBar, triRateVal) = row("Rate", { "${(it + 1) * 0.5} Hz" }, 59, 9)

        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Effects — ${clip.name}")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        val previewBtn = Button(context).apply { text = "Preview" }
        val applyBtn = Button(context).apply { text = "Apply to Clip" }
        val rowButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            previewBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            applyBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(previewBtn)
            addView(applyBtn)
        }
        inner.addView(rowButtons)

        fun buildSettings(): com.audiology2.stream.dsp.EffectSettings = settings.copy(
            highPassEnabled = hpCb.isChecked,
            highPassHz = hpVal() * 20 + 20,
            lowPassEnabled = lpCb.isChecked,
            lowPassHz = lpVal() * 100 + 100,
            notchEnabled = nCb.isChecked,
            notchHz = (nVal() * 2 + 20).toDouble(),
            eqEnabled = eqCb.isChecked,
            eqGainDb = (eqVal() - 24).toDouble(),
            compressorEnabled = compCb.isChecked,
            thresholdDb = (threshVal() * -2).toDouble(),
            ratio = ratioVal().toDouble() / 5.0,
            limiterEnabled = limCb.isChecked,
            echoEnabled = echoCb.isChecked,
            echoDelayMs = (echoVal() * 20 + 20).toDouble(),
            echoMix = echoMixVal().toDouble() / 100.0,
            distortionEnabled = distCb.isChecked,
            distortionDrive = driveVal().toDouble(),
            tremoloEnabled = triCb.isChecked,
            tremoloRateHz = (triRateVal() + 1) * 0.5
        )

        val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        fun runEffects(preview: Boolean) {
            if (busy.get()) return
            val s = buildSettings()
            if (s.isNoOp) {
                Toast.makeText(context, "Enable an effect first", Toast.LENGTH_SHORT).show()
                return
            }
            busy.set(true)
            previewBtn.isEnabled = false
            applyBtn.isEnabled = false
            editorScope.launch {
                val result = clipEditor.applyEffects(clip, s, preview = preview)
                busy.set(false)
                previewBtn.isEnabled = true
                applyBtn.isEnabled = true
                if (result != null) {
                    if (preview) {
                        runOnUiThread {
                            stopPlayback()
                            audioPlayer.loadFile(result.outputFile)
                            audioPlayer.seek(0.0)
                            audioPlayer.play()
                            binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                        }
                    } else {
                        val updated = ProjectManager.updateCurrentProject { project ->
                            val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                            val newClip = clip.copy(
                                sourcePath = result.outputFile.absolutePath,
                                sourceOffset = 0.0,
                                duration = result.finalDurationSec
                            )
                            project.updateTrack(track.updateClip(newClip))
                        }
                        runOnUiThread {
                            binding.trackTimelineView.setProject(updated)
                            binding.trackTimelineView.postInvalidate()
                            dialog.dismiss()
                            Toast.makeText(context, "Effects applied", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread { Toast.makeText(context, "Effects failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        previewBtn.setOnClickListener { runEffects(true) }
        applyBtn.setOnClickListener {
            val s = buildSettings()
            if (s.isNoOp) {
                Toast.makeText(context, "Enable an effect first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val rangeRel = currentClipRelativeRange(clip)
            if (rangeRel != null) {
                // Direct range path.
                busy.set(true)
                applyBtn.isEnabled = false
                val sFinal = s
                editorScope.launch {
                    val newClips = clipEditor.applyRangeTransform(clip, rangeRel) { samples, sr, ch ->
                        com.audiology2.stream.dsp.AudioEffects.apply(samples, sr, ch, sFinal)
                    }
                    runOnUiThread {
                        busy.set(false)
                        applyBtn.isEnabled = true
                        if (newClips != null) {
                            val updated = ProjectManager.updateCurrentProject { project ->
                                val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                                val remaining = track.clips.filterNot { it.id == clip.id }
                                project.updateTrack(track.copy(clips = (remaining + newClips).sortedBy { it.startTime }))
                            }
                            binding.trackTimelineView.setProject(updated)
                            binding.trackTimelineView.postInvalidate()
                            binding.trackTimelineView.clearRangeSelection()
                            dialog.dismiss()
                            Toast.makeText(context, "Effects applied to selection", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Range apply failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return@setOnClickListener
            }
            runEffects(false)
        }
        dialog.show()
    }

    // ===== Track properties =====

    private fun showTrackPropertiesDialog(track: Track) {
        val context = this
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }

        val nameEdit = EditText(context).apply {
            setText(track.name)
            hint = "Track name"
        }
        root.addView(TextView(context).apply { text = "Name" })
        root.addView(nameEdit)

        val volumeLabel = TextView(context).apply { text = "Volume: ${(track.gain * 100).toInt()}%" }
        val volumeBar = SeekBar(context).apply {
            max = 200
            progress = (track.gain * 100).toInt().coerceIn(0, 200)
        }
        root.addView(volumeLabel)
        root.addView(volumeBar)

        val panLabel = TextView(context).apply { text = "Pan: ${(track.pan * 100).toInt()} (L-100 .. R+100)" }
        val panBar = SeekBar(context).apply {
            max = 200
            progress = ((track.pan + 1.0) * 100).toInt().coerceIn(0, 200)
        }
        root.addView(panLabel)
        root.addView(panBar)

        val muteCb = CheckBox(context).apply { text = "Mute"; isChecked = track.muted }
        val soloCb = CheckBox(context).apply { text = "Solo"; isChecked = track.solo }
        val armCb = CheckBox(context).apply { text = "Record armed"; isChecked = track.armed }
        val monitorCb = CheckBox(context).apply { text = "Input monitor"; isChecked = track.monitor }
        root.addView(muteCb)
        root.addView(soloCb)
        root.addView(armCb)
        root.addView(monitorCb)

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                volumeLabel.text = "Volume: ${p}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val pan = (p / 100.0) - 1.0
                val side = if (pan < -0.05) "L${(-pan * 100).toInt()}" else if (pan > 0.05) "R${(pan * 100).toInt()}" else "C"
                panLabel.text = "Pan: $side"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Track — ${track.name}")
            .setView(root)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updatedTrack = track.copy(
                            name = nameEdit.text.toString().trim().ifEmpty { track.name },
                            gain = volumeBar.progress / 100f,
                            pan = ((panBar.progress / 100.0) - 1.0).toFloat(),
                            muted = muteCb.isChecked,
                            solo = soloCb.isChecked,
                            armed = armCb.isChecked,
                            monitor = monitorCb.isChecked
                        )
                        val updated = ProjectManager.updateCurrentProject { it.updateTrack(updatedTrack) }
                        binding.trackTimelineView.setProject(updated)
                        binding.trackTimelineView.postInvalidate()
                        dialog.dismiss()
                        Toast.makeText(context, "Track updated", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show()
            }
    }

        // ===== Spectral clip editor (STFT brush) =====

    /** Rectangular time-freq spectral edit on the selected clip. */
    private fun showSpectralEditorDialog() {
        val clip = requireSelectedClip("edit spectrally") ?: return
        val context = this
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }

        fun row(label: String, hintText: String, def: String): EditText {
            root.addView(TextView(context).apply {
                text = label; setPadding(0, 8, 0, 0)
                textSize = 13f; setTextColor(0xFFAAAAAA.toInt())
            })
            return EditText(context).apply {
                setText(def)
                hint = hintText
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }.also { root.addView(it) }
        }

        // Frequency range (Hz) of the brush rectangle.
        val freqMinE = row("Frequency min (Hz)", "e.g. 2000", "0")
        val freqMaxE = row("Frequency max (Hz)", "e.g. 4000", "8000")
        // Time range as % of clip.
        val timeStartE = row("Time start (% of clip)", "0-100", "0")
        val timeEndE = row("Time end (% of clip)", "0-100", "100")
        // Gain inside rectangle.
        val gainDbE = row("Gain in region (dB, negative = attenuate)", "e.g. -60 mutes, +6 boosts", "-60")

        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Spectral Edit — ${clip.name}")
            .setMessage("STFT mask: gain applied to the selected time×frequency rectangle")
            .setView(root)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()

        val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy.get()) return@setOnClickListener
                val freqMin = freqMinE.text.toString().toDoubleOrNull() ?: 0.0
                val freqMax = freqMaxE.text.toString().toDoubleOrNull() ?: 8000.0
                val tStart = (timeStartE.text.toString().toDoubleOrNull() ?: 0.0) / 100.0
                val tEnd = (timeEndE.text.toString().toDoubleOrNull() ?: 100.0) / 100.0
                val gain = Math.pow(10.0, (gainDbE.text.toString().toDoubleOrNull() ?: -60.0) / 20.0)
                if (freqMax <= freqMin || freqMin < 0) {
                    Toast.makeText(context, "Invalid frequency range", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                busy.set(true)
                val progressToast = Toast.makeText(context, "Processing spectrum...", Toast.LENGTH_SHORT)
                progressToast.show()

                editorScope.launch {
                    val result = runSpectralEdit(clip, freqMin, freqMax, tStart, tEnd, gain)
                    runOnUiThread {
                        busy.set(false)
                        progressToast.cancel()
                        if (result != null) {
                            val updated = ProjectManager.updateCurrentProject { project ->
                                val track = project.getTrack(clip.trackId) ?: return@updateCurrentProject project
                                project.updateTrack(track.updateClip(clip.copy(sourcePath = result.outputFile.absolutePath)))
                            }
                            binding.trackTimelineView.setProject(updated)
                            binding.trackTimelineView.postInvalidate()
                            dialog.dismiss()
                            Toast.makeText(context, "Spectral edit applied", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Spectral edit failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** STFT -> rectangular mask -> ISTFT per channel; writes a new WAV. */
    private suspend fun runSpectralEdit(
        clip: Clip,
        freqMinHz: Double,
        freqMaxHz: Double,
        timeStart: Double,
        timeEnd: Double,
        gain: Double
    ): EditResult? {
        val src = clipEditor.readClipAudio(clip) ?: return null
        if (src.frames == 0) return null
        val fft = com.audiology2.stream.dsp.SpectralTransform.DEFAULT_FFT
        val hop = com.audiology2.stream.dsp.SpectralTransform.DEFAULT_HOP
        val sr = src.sampleRate
        val ch = src.channels

        val outSamples = FloatArray(src.samples.size)
        for (c in 0 until ch) {
            // Extract channel into a contiguous buffer.
            val mono = FloatArray(src.frames)
            var m = 0
            var i = c
            while (i < src.samples.size) {
                mono[m++] = src.samples[i]
                i += ch
            }
            if (mono.size < fft) continue

            val frames = com.audiology2.stream.dsp.SpectralTransform.stft(mono, fft, hop)
            val frameStart = (timeStart.coerceIn(0.0, 1.0) * frames.size).toInt().coerceIn(0, frames.size)
            val frameEnd = (timeEnd.coerceIn(0.0, 1.0) * frames.size).toInt().coerceIn(frameStart, frames.size)
            if (frameEnd <= frameStart) continue

            val mask = com.audiology2.stream.dsp.SpectralTransform.rectangularMask(
                frames.size, fft, sr, frameStart, frameEnd, freqMinHz, freqMaxHz, gain
            )
            val masked = com.audiology2.stream.dsp.SpectralTransform.applyMask(frames, fft, sr, mask)
            val recovered = com.audiology2.stream.dsp.SpectralTransform.istft(masked, fft, hop)

            // Write recovered channel back, trimmed to the same length.
            val n = minOf(mono.size, recovered.size)
            var o = 0
            var j = c
            while (j < src.samples.size && o < n) {
                outSamples[j] = recovered[o++]
                j += ch
            }
        }

        val out = java.io.File(this@MainActivity.filesDir, "edited").apply { mkdirs() }
        val name = clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val file = java.io.File(out, "${name}_spectral_${java.util.UUID.randomUUID().toString().take(8)}.wav")
        val buf = com.audiology2.stream.audio.AudioBuffer(outSamples, sr, ch, src.frames)
        if (!com.audiology2.stream.audio.WavUtils.write(file, buf)) return null
        return EditResult(file, src.frames.toDouble() / sr)
    }

    // ===== Record → beat sample =====

    private fun makeSampleFromSelection() {
        val clip = requireSelectedClip("make a sample") ?: return
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Make Beat Sample — ${clip.name}")
            .setMessage("Trim silence, normalize, snap loop length to project tempo, and place on the Beats track.")
            .setPositiveButton("Create") { _, _ -> makeSampleFromClip(clip) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Create a beat-ready sample from [clip] and place it on the Beats track. */
    private fun makeSampleFromClip(clip: Clip) {
        val project = ProjectManager.getCurrentProject() ?: return
        val threshold = -48.0 // dBFS gate default
        editorScope.launch {
            val beatSec = 60.0 / project.tempo.coerceIn(40.0, 300.0)
            val sampleResult = sampleMaker.makeSample(
                clip = clip,
                project = project,
                thresholdDb = threshold,
                normalize = true,
                snapToBeat = true
            )
            if (sampleResult == null) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Sample creation failed", Toast.LENGTH_LONG).show() }
                return@launch
            }

            val updated = ProjectManager.updateCurrentProject { p ->
                // Ensure a Beats track exists.
                var beatsTrack = p.tracks.firstOrNull { it.name.equals("Beats", ignoreCase = true) }
                var work = p
                if (beatsTrack == null) {
                    work = p.addTrack(name = "Beats", color = 0xFFFFD700.toInt())
                    beatsTrack = work.tracks.last()
                }
                val sampleClip = Clip(
                    name = "${clip.name} (sample)",
                    sourcePath = sampleResult.sampleFile.absolutePath,
                    duration = sampleResult.durationSec,
                    startTime = sampleResult.placedAt,
                    trackId = beatsTrack!!.id,
                    color = 0xFFFFD700.toInt()
                )
                work.updateTrack(beatsTrack.insertClip(sampleClip, sampleResult.placedAt))
            }

            runOnUiThread {
                binding.trackTimelineView.setProject(updated)
                binding.trackTimelineView.postInvalidate()
                val loopNote = if (sampleResult.loopLengthSec > 0) {
                    " (loops ${String.format("%.2f", sampleResult.loopLengthSec)}s = ${(beatSec / sampleResult.loopLengthSec * 4).roundToInt()} beats)"
                } else ""
                Toast.makeText(
                    this@MainActivity,
                    "Sample added to Beats track. ${sampleResult.durationSec.roundToInt()}s$loopNote",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ===== Media library (browse device audio -> import / sample) =====

    private data class LibraryTrack(
        val title: String,
        val artist: String,
        val uri: android.net.Uri,
        val durationMs: Long
    )

    /** Preview player for library tracks (separate from timeline playback). */
    private var libraryPlayer: android.media.MediaPlayer? = null

    private val mediaAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showLibraryDialog()
            else Toast.makeText(this, "Media library permission denied", Toast.LENGTH_LONG).show()
        }

    private fun openLibrary() {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            showLibraryDialog()
        } else {
            mediaAudioPermissionLauncher.launch(perm)
        }
    }

    private fun showLibraryDialog() {
        editorScope.launch {
            val tracks = loadLibraryTracks()
            runOnUiThread {
                if (tracks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No music found in the device library", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = tracks.map { "${it.title} — ${it.artist}" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle("Media library (${tracks.size})")
                    .setItems(labels) { _, which -> onLibraryTrackChosen(tracks[which]) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private suspend fun loadLibraryTracks(): List<LibraryTrack> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val out = mutableListOf<LibraryTrack>()
        val proj = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.DURATION
        )
        try {
            contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                android.provider.MediaStore.Audio.Media.TITLE + " ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val artist = (c.getString(artistCol) ?: "Unknown").let { if (it == "<unknown>") "Unknown" else it }
                    out.add(LibraryTrack(c.getString(titleCol) ?: "Track", artist, uri, c.getLong(durCol)))
                }
            }
        } catch (e: Exception) {
            Log.w("Editor", "library query failed", e)
        }
        out
    }

    private fun onLibraryTrackChosen(track: LibraryTrack) {
        val secs = (track.durationMs / 1000) % 60
        val mins = track.durationMs / 60000
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(track.title)
            .setMessage("${track.artist} · ${mins}:%02d".format(secs))
            .setPositiveButton("Sample this") { _, _ -> sampleLibraryTrack(track) }
            .setNeutralButton("Preview") { _, _ -> toggleLibraryPreview(track) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleLibraryPreview(track: LibraryTrack) {
        val existing = libraryPlayer
        libraryPlayer = null
        if (existing != null) {
            runCatching { existing.stop() }
            runCatching { existing.release() }
            return
        }
        try {
            val mp = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MainActivity, track.uri)
                setOnCompletionListener {
                    runCatching { it.release() }
                    if (libraryPlayer === it) libraryPlayer = null
                }
                prepare()
                start()
            }
            libraryPlayer = mp
            Toast.makeText(this, "Previewing: ${track.title}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Preview failed: ${e.message?.take(80)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sampleLibraryTrack(track: LibraryTrack) {
        runCatching { libraryPlayer?.stop() }
        runCatching { libraryPlayer?.release() }
        libraryPlayer = null
        val project = ProjectManager.getCurrentProject() ?: return
        val importer = AudioImporter(this, editorScope)
        val progress = Toast.makeText(this, "Importing ${track.title}…", Toast.LENGTH_SHORT).apply { show() }
        editorScope.launch {
            val result = importer.importAudioFile(project, track.uri)
            if (result == null) {
                runOnUiThread {
                    progress.cancel()
                    Toast.makeText(this@MainActivity, "Import failed", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val (updatedProject, _, clip) = result
            ProjectManager.setCurrentProject(updatedProject)
            runOnUiThread {
                binding.trackTimelineView.setProject(ProjectManager.getCurrentProject())
                binding.trackTimelineView.postInvalidate()
                progress.cancel()
            }
            makeSampleFromClip(clip)
        }
    }

    // Editor state
    private var isPlaying = false
    private var isRecording = false
    private var playbackStartTime = 0L
    private var recordedSamples = FloatArray(0)
    private var recordingSampleRate = 48000

    private fun togglePlayback() {
        if (audioPlayer.getIsPlaying()) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    /** True when the given track is audible given mute/solo rules. */
    private fun isTrackAudible(track: Track, project: AudioProject): Boolean {
        if (track.muted) return false
        val anySolo = project.tracks.any { it.solo }
        if (anySolo && !track.solo) return false
        return true
    }

    private fun startPlayback() {
        val project = ProjectManager.getCurrentProject()
        // Load selected clip or first audible clip for playback
        val selectedClip = binding.trackTimelineView.currentSelectedClipId
            ?.let { binding.trackTimelineView.findClip(it) }
        val clip = when {
            selectedClip != null && project != null -> {
                val t = project.getTrack(selectedClip.trackId)
                if (t != null && isTrackAudible(t, project)) selectedClip else null
            }
            else -> null
        } ?: project?.tracks?.firstOrNull { isTrackAudible(it, project) }?.clips?.firstOrNull()
            ?: return
        
        if (audioPlayer.loadFile(java.io.File(clip.sourcePath))) {
            audioPlayer.seek(clip.startTime)
            audioPlayer.play()
            binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            binding.trackTimelineView.setPlayheadTime(clip.startTime)
        }
    }

    private fun pausePlayback() {
        audioPlayer.pause()
        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun stopPlayback() {
        audioPlayer.stop()
        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        binding.timelineTimeDisplay.text = "00:00.000 / 00:00.000"
        binding.trackTimelineView.clearPlayhead()
    }

    /** Toggle loop playback over the selected range (or selected clip). */
    private var loopActive = false
    private fun toggleLoop() {
        if (loopActive) {
            loopActive = false
            audioPlayer.clearLoop()
            binding.trackTimelineView.clearLoopRegion()
            binding.btnLoop.setColorFilter(null)
            Toast.makeText(this, "Loop off", Toast.LENGTH_SHORT).show()
            return
        }

        val project = ProjectManager.getCurrentProject() ?: return
        var start = -1.0
        var end = -1.0

        if (binding.trackTimelineView.hasRangeSelection) {
            val (rs, re) = binding.trackTimelineView.selectedRange
            start = rs
            end = re
        } else {
            currentSelectionClip()?.let { clip ->
                start = clip.startTime
                end = clip.endTime
            } ?: project.tracks.firstOrNull()?.clips?.firstOrNull()?.let { clip ->
                start = clip.startTime
                end = clip.endTime
            }
        }

        if (start < 0 || end <= start) {
            Toast.makeText(this, "Select a range or clip to loop", Toast.LENGTH_SHORT).show()
            return
        }

        loopActive = true
        audioPlayer.setLoop(start, end)
        binding.trackTimelineView.setLoopRegion(start, end)
        binding.btnLoop.setColorFilter(0xFFFFB300.toInt())
        // Jump into the loop
        stopPlayback()
        audioPlayer.seek(start)
        binding.trackTimelineView.setPlayheadTime(start)
        Toast.makeText(this, "Looping ${formatTime(start)} - ${formatTime(end)}", Toast.LENGTH_SHORT).show()
    }

    private fun rewind() {
        audioPlayer.seek(0.0)
    }

    private fun fastForward() {
        audioPlayer.seek(audioPlayer.getDurationSec())
    }

    private fun toggleRecording() {
        if (service == null) return
        
        if (service!!.isRecording()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (service == null) return
        
        val filePath = service!!.startRecording()
        if (filePath != null) {
            isRecording = true
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnRecord.setColorFilter(0xFFFFFFFF.toInt())
            recordingSampleRate = binding.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
            Log.d("Editor", "Started recording to $filePath")
        }
    }

    private fun stopRecording() {
        if (service == null) return
        
        val filePath = service!!.stopRecording()
        isRecording = false
        binding.btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        binding.btnRecord.clearColorFilter()
        
        if (filePath != null) {
            lastRecordingPath = java.io.File(filePath)
            // Create a clip from the recorded file and add to project
            val duration = service!!.getRecordingDurationMs() / 1000.0
            val updated = ProjectManager.updateCurrentProject { p ->
                val track = p.tracks.firstOrNull()
                    ?: p.addTrack(name = "Track 1", color = 0xFF00D4AA.toInt()).tracks.first()
                val clip = Clip(
                    sourcePath = filePath,
                    duration = duration,
                    startTime = track.duration,
                    trackId = track.id,
                    color = 0xFF00D4AA.toInt()
                )
                val updatedTrack = track.appendClip(clip)
                p.updateTrack(updatedTrack)
            }
            binding.trackTimelineView.setProject(updated)
            binding.trackTimelineView.postInvalidate()
        }
        Log.d("Editor", "Stopped recording: $filePath")
    }

    private fun formatTime(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        val ms = ((seconds * 1000) % 1000).toInt()
        return if (mins > 0) String.format("%d:%02d.%03d", mins, secs, ms)
        else String.format("%d.%03d", secs, ms)
    }

    // ---- Spectral tools (logic lives in SpectralCardController) ----

    /** Add the detected pitch as a note in the open MIDI clip (or create/route one). */
    private fun capturePitchToMidi() {
        val s = spectralCard.snapshot ?: run {
            Toast.makeText(this, "No live spectrum yet - start streaming", Toast.LENGTH_SHORT).show()
            return
        }
        val midiPitch = s.midi ?: run {
            Toast.makeText(this, "No pitched signal detected", Toast.LENGTH_SHORT).show()
            return
        }
        val project = ProjectManager.getCurrentProject() ?: return
        val tempo = project.tempo

        val existing = currentMidiClip()
        if (existing != null) {
            // Place at the playhead, clip-relative.
            val playhead = binding.trackTimelineView.currentPlayheadTime
            val relSec = (playhead - existing.startTime).coerceAtLeast(0.0)
            val tick = existing.tickAt(relSec, tempo)
                .coerceAtLeast(existing.endTick)
            binding.pianoRollView.addNote(MidiNote(startTick = tick, durationTicks = existing.ticksPerBeat / 2L, pitch = midiPitch, velocity = 100))
            Toast.makeText(this, "Note ${s.noteName} added to ${existing.name}", Toast.LENGTH_SHORT).show()
            return
        }

        // No open MIDI clip: put it in the Beats track (or first track).
        var track = project.tracks.firstOrNull { it.name.equals("Beats", ignoreCase = true) }
            ?: project.tracks.firstOrNull()
        var work = project
        if (track == null) {
            work = project.addTrack(name = "Beats", color = 0xFFFFD700.toInt())
            track = work.tracks.last()
        }
        val start = if (binding.trackTimelineView.currentPlayheadTime > 0) {
            binding.trackTimelineView.currentPlayheadTime
        } else track.duration
        val base = MidiClip(
            name = "Captured ${s.noteName}",
            startTime = start,
            trackId = track.id
        )
        val midi = base.copy(notes = listOf(MidiNote(startTick = 0, durationTicks = base.ticksPerBeat.toLong(), pitch = midiPitch, velocity = 100)))
        val updated = ProjectManager.updateCurrentProject {
            it.updateTrack(track!!.withMidiClips(track.midiClips + midi))
        }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        openMidiEditor(midi)
        Toast.makeText(this, "Captured ${s.noteName} as MIDI clip", Toast.LENGTH_SHORT).show()
    }

    // ---- Spectrogram selection → clip ----

    /** Create a timeline clip from the spectrogram selection of the last recording. */
    private fun createClipFromSpectrogramSelection(startSec: Double, endSec: Double) {
        val file = lastRecordingPath
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Record some audio first, then drag a span on the spectrogram", Toast.LENGTH_LONG).show()
            binding.mainSpectrogramView.clearSelection()
            return
        }
        val buf = com.audiology2.stream.audio.WavUtils.read(file)
        if (buf == null || buf.frames <= 0) {
            Toast.makeText(this, "Recorded file unreadable", Toast.LENGTH_SHORT).show()
            binding.mainSpectrogramView.clearSelection()
            return
        }
        val fileDur = buf.frames.toDouble() / buf.sampleRate

        // Selection is relative to the stream; clamp into the recording file.
        val srcStart = startSec.coerceAtLeast(0.0)
        if (srcStart >= fileDur) {
            Toast.makeText(this, "Selection is outside the recorded audio", Toast.LENGTH_SHORT).show()
            binding.mainSpectrogramView.clearSelection()
            return
        }
        val srcEnd = endSec.coerceIn(srcStart + 0.03, fileDur)
        if (srcEnd <= srcStart + 0.03) {
            binding.mainSpectrogramView.clearSelection()
            return
        }

        val updated = ProjectManager.updateCurrentProject { p ->
            val track = p.tracks.firstOrNull()
                ?: p.addTrack(name = "Track 1", color = 0xFF00D4AA.toInt()).tracks.first()
            val clip = Clip(
                sourcePath = file.absolutePath,
                sourceOffset = srcStart,
                duration = srcEnd - srcStart,
                startTime = track.duration,
                trackId = track.id,
                color = 0xFFFF4F00.toInt() // hunter orange: SRP selection accent
            )
            p.updateTrack(track.appendClip(clip))
        }
        binding.trackTimelineView.setProject(updated)
        binding.trackTimelineView.postInvalidate()
        binding.mainSpectrogramView.clearSelection()
        Toast.makeText(this, "Clip created from spectrogram selection (${String.format("%.2f", srcEnd - srcStart)}s)", Toast.LENGTH_SHORT).show()
    }

    // ---- Export/Import ----

    private fun showProjectsDialog() {
        refreshProjectsDialog()
    }

    private fun refreshProjectsDialog() {
        val projects = ProjectManager.getProjects()
        if (projects.isEmpty()) {
            Toast.makeText(this, "No saved projects yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = projects.map { p ->
            val d = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                .format(java.util.Date(p.modifiedAt))
            val star = if (p.id == ProjectManager.getCurrentProject()?.id) " ★" else ""
            "${p.name}$star  (${d})"
        }.toTypedArray()

        fun openP(i: Int) {
            val project = projects[i]
            ProjectManager.setCurrentProject(project)
            binding.trackTimelineView.setProject(project)
            binding.trackTimelineView.postInvalidate()
            stopPlayback()
            snapGrid.tempo = project.tempo
            snapGrid.timeSignatureNumerator = project.timeSignatureNumerator
            snapGrid.timeSignatureDenominator = project.timeSignatureDenominator
            binding.trackTimelineView.setSnapGrid(snapGrid)
            Toast.makeText(this, "Opened ${project.name}", Toast.LENGTH_SHORT).show()
            refreshProjectsDialog()
        }

        val selected = projects.indexOfFirst { it.id == ProjectManager.getCurrentProject()?.id }
        val builder = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Projects (${projects.size})")
            .setSingleChoiceItems(names, selected) { _, which -> openP(which) }
            .setPositiveButton("New", null)
            .setNegativeButton("Close", null)
        val listDialog = builder.create()
        listDialog.setOnShowListener {
            listDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                listDialog.dismiss()
                promptProjectName("New project name", "") { name ->
                    ProjectManager.createNewProject(name.ifBlank { "Session" })
                    binding.trackTimelineView.setProject(ProjectManager.getCurrentProject())
                    binding.trackTimelineView.postInvalidate()
                    refreshProjectsDialog()
                }
            }
        }
        listDialog.show()
    }

    private fun promptProjectName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "Project name"
            setPadding(24, 16, 24, 16)
        }
        val d = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onOk(input.text.toString().trim())
                d.dismiss()
            }
        }
        d.show()
    }

    private fun showExportDialog() {
        val project = ProjectManager.getCurrentProject() ?: return
        val formats = listOf(
            ExportFormat.WAV to getString(R.string.export_format_wav),
            ExportFormat.M4A to getString(R.string.export_format_m4a)
        )
        
        val adapter = ArrayAdapter<String>(this, android.R.layout.select_dialog_item, formats.map { it.second }.toTypedArray())
        
        AlertDialog.Builder(this)
            .setTitle("Export Project")
            .setAdapter(adapter) { _, which ->
                val format = formats[which].first
                val outputDir = getExternalFilesDir(null) ?: filesDir
                val outputFile = File(outputDir, "${project.name.replace(" ", "_")}_${System.currentTimeMillis()}.${format.extension}")
                
                val progressDialog = AlertDialog.Builder(this)
                    .setTitle("Exporting ${format.extension.uppercase()}...")
                    .setView(ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply { isIndeterminate = true })
                    .setCancelable(false)
                    .create()
                
                progressDialog.show()
                
                editorScope.launch {
                    // Render any MIDI clips to audio so exports include them.
                    val renderProject = midiSampler.renderProject(project)
                    val exporter = AudioExporter(editorScope)
                    val success = when (format) {
                        ExportFormat.WAV -> exporter.exportWav(renderProject, outputFile) { progress ->
                            runOnUiThread { progressDialog.setMessage("Exporting: ${(progress * 100).toInt()}%") }
                        }
                        ExportFormat.M4A -> exporter.exportM4a(renderProject, outputFile) { progress ->
                            runOnUiThread { progressDialog.setMessage("Exporting: ${(progress * 100).toInt()}%") }
                        }
                        else -> false
                    }
                    
                    runOnUiThread {
                        progressDialog.dismiss()
                        if (success) {
                            Toast.makeText(this@MainActivity, "Exported to ${outputFile.name}", Toast.LENGTH_LONG).show()
                            // Offer to share
                            val uri = FileProvider.getUriForFile(
                                this@MainActivity, "${packageName}.fileprovider", outputFile)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share audio"))
                        } else {
                            Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showImportDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        importActivityResult.launch(intent)
    }

    private val importActivityResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val clipData = result.data?.clipData
            val uris = if (clipData != null) {
                (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }.toList()
            } else {
                result.data?.data?.let { listOf(it) }
            }
            if (uris == null || uris.isEmpty()) return@registerForActivityResult
            
            val project = ProjectManager.getCurrentProject() ?: return@registerForActivityResult
            val importer = AudioImporter(this, editorScope)
            
            val progressDialog = AlertDialog.Builder(this)
                .setTitle("Importing Audio...")
                .setView(ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply { isIndeterminate = true })
                .setCancelable(false)
                .create()
            
            progressDialog.show()
            
            editorScope.launch {
                val (updatedProject, clips) = importer.importMultipleFiles(project, uris) { current, total ->
                    runOnUiThread { progressDialog.setMessage("Importing $current/$total") }
                }
                
                ProjectManager.setCurrentProject(updatedProject)
                runOnUiThread {
                    progressDialog.dismiss()
                    binding.trackTimelineView.setProject(ProjectManager.getCurrentProject())
                    binding.trackTimelineView.postInvalidate()
                    Toast.makeText(this@MainActivity, "Imported ${clips.size} file(s)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Ties each category header to its collapsible content panel. Panels start
     *  collapsed; tapping a header expands/collapses it and flips the chevron. */
    private fun setupExpandableSections() {
        val sections = listOf(
            Triple(binding.headerConnection, binding.contentConnection, binding.chevronConnection),
            Triple(binding.headerCapture, binding.contentCapture, binding.chevronCapture),
            Triple(binding.headerSensitivity, binding.contentSensitivity, binding.chevronSensitivity),
            Triple(binding.headerProcessing, binding.contentProcessing, binding.chevronProcessing),
            Triple(binding.headerPresets, binding.contentPresets, binding.chevronPresets),
            Triple(binding.headerDiagnostics, binding.contentDiagnostics, binding.chevronDiagnostics),
            Triple(binding.headerControls, binding.contentControls, binding.chevronControls),
            Triple(binding.headerSpectral, binding.contentSpectral, binding.chevronSpectral),
            Triple(binding.headerAssistant, binding.contentAssistant, binding.chevronAssistant),
            Triple(binding.headerEditor, binding.contentEditor, binding.chevronEditor)
        )
        sections.forEach { (header, content, chevron) ->
            header.setOnClickListener {
                val expanded = content.visibility == View.VISIBLE
                content.visibility = if (expanded) View.GONE else View.VISIBLE
                chevron.text = if (expanded) "\u25BC" else "\u25B2" // ▼ collapsed / ▲ expanded
            }
        }
    }

    // ---- Settings load/save ----

    private fun loadSettings() {
        lifecycleScope.launch {
            SettingsStore.settingsFlow.collectLatest { settings ->
                updatingViews = true
                applySettingsToViews(settings)
                updatingViews = false
            }
        }
    }

    private fun applySettingsToViews(s: AppSettings) {
        // Only update text fields if value actually changed to avoid cursor jumps
        val currentHost = binding.hostEdit.text.toString()
        if (currentHost != s.network.host) binding.hostEdit.setText(s.network.host)
        
        val currentPort = binding.portEdit.text.toString()
        if (currentPort != s.network.port.toString()) binding.portEdit.setText(s.network.port.toString())
        
        val currentSampleRate = binding.sampleRateEdit.text.toString()
        if (currentSampleRate != s.network.sampleRate.toString()) binding.sampleRateEdit.setText(s.network.sampleRate.toString())
        
        val currentPacketMs = binding.packetMsEdit.text.toString()
        if (currentPacketMs != s.network.packetMs.toString()) binding.packetMsEdit.setText(s.network.packetMs.toString())
        
        val currentJitterMs = binding.jitterMsEdit.text.toString()
        if (currentJitterMs != s.network.jitterMs.toString()) binding.jitterMsEdit.setText(s.network.jitterMs.toString())
        
        binding.reconnectSwitch.isChecked = s.network.autoReconnect
        binding.transportSpinner.setSelection(TransportType.entries.indexOf(s.network.transport).coerceAtLeast(0))
        binding.channelSpinner.setSelection(ChannelMode.entries.indexOf(s.network.channelMode).coerceAtLeast(0))

        binding.inputSourceSpinner.setSelection(InputSource.entries.indexOf(s.capture.inputSource).coerceAtLeast(0))
        
        val currentBufferMs = binding.bufferMsEdit.text.toString()
        if (currentBufferMs != s.capture.bufferMs.toString()) binding.bufferMsEdit.setText(s.capture.bufferMs.toString())
        
        binding.maxSensitivitySwitch.isChecked = s.capture.maxSensitivity
        binding.rawBypassSwitch.isChecked = s.capture.rawBypass

        val gainDb = s.capture.digitalGainDb
        binding.gainKnob.setValue(gainDb.toFloat())
        binding.gainLabel.text = getString(R.string.label_digital_gain) + ": ${gainDb.toInt()}dB"

        binding.agcSwitch.isChecked = s.dsp.agcEnabled
        binding.nsupSwitch.isChecked = s.dsp.nsEnabled
        binding.hpfSwitch.isChecked = s.dsp.highPassEnabled
        
        val currentHpfHz = binding.hpfHzEdit.text.toString()
        if (currentHpfHz != s.dsp.highPassHz.toString()) binding.hpfHzEdit.setText(s.dsp.highPassHz.toString())
        
        binding.lpfSwitch.isChecked = s.dsp.lowPassEnabled
        
        val currentLpfHz = binding.lpfHzEdit.text.toString()
        if (currentLpfHz != s.dsp.lowPassHz.toString()) binding.lpfHzEdit.setText(s.dsp.lowPassHz.toString())
        
        binding.notchSwitch.isChecked = s.dsp.notchEnabled
        
        val currentNotchHz = binding.notchHzEdit.text.toString()
        if (currentNotchHz != s.dsp.notchHz.toString()) binding.notchHzEdit.setText(s.dsp.notchHz.toString())
        
        val currentNotchQ = binding.notchQEdit.text.toString()
        if (currentNotchQ != s.dsp.notchQ.toString()) binding.notchQEdit.setText(s.dsp.notchQ.toString())
        
        binding.eqSwitch.isChecked = s.dsp.eqEnabled
        
        val currentEqHz = binding.eqHzEdit.text.toString()
        if (currentEqHz != s.dsp.eqHz.toString()) binding.eqHzEdit.setText(s.dsp.eqHz.toString())
        
        val currentEqGain = binding.eqGainEdit.text.toString()
        if (currentEqGain != s.dsp.eqGainDb.toString()) binding.eqGainEdit.setText(s.dsp.eqGainDb.toString())
        
        val currentEqBw = binding.eqBwEdit.text.toString()
        if (currentEqBw != s.dsp.eqBwOctaves.toString()) binding.eqBwEdit.setText(s.dsp.eqBwOctaves.toString())
        
        binding.compSwitch.isChecked = s.dsp.compressorEnabled
        
        val currentCompThresh = binding.compThreshEdit.text.toString()
        if (currentCompThresh != s.dsp.compThresholdDb.toString()) binding.compThreshEdit.setText(s.dsp.compThresholdDb.toString())
        
        val currentCompRatio = binding.compRatioEdit.text.toString()
        if (currentCompRatio != s.dsp.compRatio.toString()) binding.compRatioEdit.setText(s.dsp.compRatio.toString())
        
        val currentCompAttack = binding.compAttackEdit.text.toString()
        if (currentCompAttack != s.dsp.compAttackMs.toString()) binding.compAttackEdit.setText(s.dsp.compAttackMs.toString())
        
        val currentCompRelease = binding.compReleaseEdit.text.toString()
        if (currentCompRelease != s.dsp.compReleaseMs.toString()) binding.compReleaseEdit.setText(s.dsp.compReleaseMs.toString())
        
        binding.limiterSwitch.isChecked = s.dsp.limiterEnabled
        binding.abSwitch.isChecked = s.dsp.abBypass || s.capture.rawBypass

        binding.echoSwitch.isChecked = s.dsp.echoEnabled
        binding.echoDelayEdit.setText(s.dsp.echoDelayMs.toString())
        binding.echoFeedbackEdit.setText(s.dsp.echoFeedback.toString())
        binding.echoMixEdit.setText(s.dsp.echoMix.toString())
        binding.distSwitch.isChecked = s.dsp.distortionEnabled
        binding.distDriveEdit.setText(s.dsp.distortionDrive.toString())
        binding.tremoloSwitch.isChecked = s.dsp.tremoloEnabled
        binding.tremoloRateEdit.setText(s.dsp.tremoloRateHz.toString())
        binding.tremoloDepthEdit.setText(s.dsp.tremoloDepth.toString())

        // Preset spinner must be set last and silently.
        val pi = Presets.BUILT_IN.indexOfFirst { it.name == s.activePreset }
        if (pi >= 0) {
            updatingViews = true
            binding.presetSpinner.setSelection(pi)
            updatingViews = false
        }
    }

    private fun readSettingsFromViews(): AppSettings {
        val v = binding
        val host = v.hostEdit.text?.toString()?.trim().orEmpty()
        val port = v.portEdit.text.toString().toIntOrNull() ?: 0
        val sampleRate = v.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
        val packetMs = v.packetMsEdit.text.toString().toIntOrNull() ?: 30
        val jitterMs = v.jitterMsEdit.text.toString().toIntOrNull() ?: 60
        val bufferMs = v.bufferMsEdit.text.toString().toIntOrNull() ?: 20
        val gainDb = v.gainKnob.value.toDouble()
        val sIdx = TransportType.entries.getOrElse(v.transportSpinner.selectedItemPosition) { TransportType.UDP }
        val cIdx = ChannelMode.entries.getOrElse(v.channelSpinner.selectedItemPosition) { ChannelMode.MONO }
        return AppSettings(
            network = NetworkConfig(host, port, sIdx, sampleRate, cIdx, PcmFormat.PCM_16,
                packetMs, jitterMs, v.reconnectSwitch.isChecked),
            capture = CaptureConfig(
                inputSource = InputSource.entries.getOrElse(v.inputSourceSpinner.selectedItemPosition) { InputSource.DEFAULT },
                bufferMs = bufferMs,
                maxSensitivity = v.maxSensitivitySwitch.isChecked,
                rawBypass = v.rawBypassSwitch.isChecked,
                digitalGainDb = gainDb
            ),
            dsp = AudioConfig(
                inputGainDb = gainDb,
                agcEnabled = v.agcSwitch.isChecked,
                nsEnabled = v.nsupSwitch.isChecked,
                abBypass = v.abSwitch.isChecked || v.rawBypassSwitch.isChecked,
                highPassEnabled = v.hpfSwitch.isChecked,
                highPassHz = (v.hpfHzEdit.text.toString().toIntOrNull() ?: 80),
                lowPassEnabled = v.lpfSwitch.isChecked,
                lowPassHz = (v.lpfHzEdit.text.toString().toIntOrNull() ?: 8000),
                notchEnabled = v.notchSwitch.isChecked,
                notchHz = (v.notchHzEdit.text.toString().toDoubleOrNull() ?: 60.0),
                notchQ = (v.notchQEdit.text.toString().toDoubleOrNull() ?: 1.0),
                eqEnabled = v.eqSwitch.isChecked,
                eqHz = (v.eqHzEdit.text.toString().toIntOrNull() ?: 1000),
                eqGainDb = (v.eqGainEdit.text.toString().toDoubleOrNull() ?: 0.0),
                eqBwOctaves = (v.eqBwEdit.text.toString().toDoubleOrNull() ?: 1.0),
                compressorEnabled = v.compSwitch.isChecked,
                compThresholdDb = (v.compThreshEdit.text.toString().toDoubleOrNull() ?: -24.0),
                compRatio = (v.compRatioEdit.text.toString().toDoubleOrNull() ?: 4.0),
                compAttackMs = (v.compAttackEdit.text.toString().toDoubleOrNull() ?: 5.0),
                compReleaseMs = (v.compReleaseEdit.text.toString().toDoubleOrNull() ?: 50.0),
                limiterEnabled = v.limiterSwitch.isChecked,
                echoEnabled = v.echoSwitch.isChecked,
                echoDelayMs = (v.echoDelayEdit.text.toString().toDoubleOrNull() ?: 320.0),
                echoFeedback = (v.echoFeedbackEdit.text.toString().toDoubleOrNull() ?: 0.35),
                echoMix = (v.echoMixEdit.text.toString().toDoubleOrNull() ?: 0.25),
                distortionEnabled = v.distSwitch.isChecked,
                distortionDrive = (v.distDriveEdit.text.toString().toDoubleOrNull() ?: 10.0),
                tremoloEnabled = v.tremoloSwitch.isChecked,
                tremoloRateHz = (v.tremoloRateEdit.text.toString().toDoubleOrNull() ?: 5.0),
                tremoloDepth = (v.tremoloDepthEdit.text.toString().toDoubleOrNull() ?: 0.6)
            ),
            activePreset = v.presetSpinner.selectedItem?.toString().orEmpty()
        )
    }

    private fun updateSettingsFromViews() {
        val settings = readSettingsFromViews()
        lifecycleScope.launch { SettingsStore.saveSettings(settings) }
    }

    private fun applyPreset(s: AppSettings) {
        // Presets tune DSP/network behaviour. They must NEVER clobber the
        // receiver host/port, which is user infrastructure, not a preset.
        val current = readSettingsFromViews()
        val merged = s.copy(
            network = s.network.copy(
                host = current.network.host,
                port = current.network.port
            )
        )
        updatingViews = true
        applySettingsToViews(merged)
        updatingViews = false
        lifecycleScope.launch { SettingsStore.saveSettings(merged) }
        val pi = Presets.BUILT_IN.indexOfFirst { it.name == merged.activePreset }
        if (pi >= 0) binding.presetSpinner.setSelection(pi)
    }

    /** Save the current views as a NEW preset (never overwrites built-ins). */
    private fun saveCustomPreset() {
        val cur = readSettingsFromViews()
        val suggested = cur.activePreset.ifEmpty { "Custom ${System.currentTimeMillis() % 100000}" }
        val input = EditText(this).apply {
            setText(suggested)
            hint = "Preset name"
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Save preset as")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (Presets.builtInByName(name) != null) {
                            Toast.makeText(this, "'$name' is a built-in preset - choose another name", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            val custom = SettingsStore.currentPresets().filter { !it.builtIn }.toMutableList()
                            // Overwrite only a custom preset with the same name.
                            custom.removeAll { it.name == name }
                            custom.add(Preset(name = name, settings = cur, builtIn = false))
                            SettingsStore.savePresets(custom)
                            SettingsStore.saveSettings(cur.copy(activePreset = name))
                        }
                        binding.presetSpinner.setSelection(
                            Presets.names.indexOf(name).let { if (it >= 0) it else binding.presetSpinner.selectedItemPosition }
                        )
                        Toast.makeText(this, "Saved preset $name", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    }
                }
                d.show()
            }
    }

    private fun resetToDefaults() {
        Presets.builtInByName(Presets.names.first())?.settings?.let { applyPreset(it.copy(activePreset = "")) }
            ?: applyPreset(AppSettings())
    }

    private fun saveSettingsSync(s: AppSettings) {
        lifecycleScope.launch { SettingsStore.saveSettings(s) }
        applySettingsToViews(s)
    }

    // ---- Permissions ----

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        needed.add(android.Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionContract.launch(missing.toTypedArray())
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- Streaming ----

    private fun startStreaming() {
        if (!hasMicPermission()) {
            permissionContract.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            return
        }
        val s = readSettingsFromViews()
        val validation = Validators.validateAppSettings(s)
        if (!validation.isOk) {
            Toast.makeText(this, "Invalid config: " + validation.errors.joinToString(";"), Toast.LENGTH_LONG).show()
            setStateUi(ServiceState.ERROR, validation.errors.joinToString(";"))
            return
        }
        lifecycleScope.launch {
            SettingsStore.saveSettings(s)
            val intent = Intent(this@MainActivity, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_SETTINGS, json.encodeToString(AppSettings.serializer(), s))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopStreaming() {
        // Never use startForegroundService() to STOP: the service's ACTION_STOP
        // path does not call startForeground(), so Android kills the app with
        // ForegroundServiceDidNotStartInTimeException. Stop via the bound
        // instance, then stopService() to tear down the foreground service.
        val svc = service
        if (bound && svc != null) {
            runCatching { svc.stopStreaming() }
        }
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        runCatching { stopService(Intent(this, StreamService::class.java)) }
        com.audiology2.stream.service.PlaybackCaptureHolder.projection = null
        setStateUi(ServiceState.IDLE, null)
    }

    // ---- Callbacks from service ----

    override fun onCaptureStats(stats: CaptureStats) {
        runOnUiThread { applyCaptureStats(stats) }
    }

    override fun onNetworkStats(stats: NetworkStats) {
        runOnUiThread { applyNetworkStats(stats) }
    }

    override fun onServiceState(state: ServiceState) {
        runOnUiThread { setStateUi(state, null) }
    }

    private fun applyCaptureStats(s: CaptureStats) {
        binding.rmsText.text = getString(R.string.label_rms) + ": " + s.rmsDb.dbDisplay()
        binding.peakText.text = getString(R.string.label_peak) + ": " + s.peakDb.dbDisplay()
        binding.rmsBar.progress = dbToLinear(s.rmsDb)
        binding.peakBar.progress = dbToLinear(s.peakDb)
        binding.clippingText.text = getString(R.string.label_clipping) + ": " + (if (s.clipped) "CLIP!" else "ok")
        binding.clippingText.setTextColor(
            if (s.clipped) getColor(R.color.srp_danger) else getColor(R.color.srp_success)
        )
        val captureState = if (s.active) "Active" else if (s.running) "Ready" else "Stopped"
        binding.captureStateText.text = "capture: $captureState" +
            "  fmt=${s.pcmFormat} sr=${s.sampleRate}Hz ch=${s.channels} buf=${s.bufferMs}ms pkt=${s.effectivePacketMs}ms drops=${s.dropped}"
        binding.configText.text = getString(R.string.label_config) + ": ${s.pcmFormat}/${s.sampleRate}Hz/${s.channels}ch"
        if (s.error.isNotBlank()) setStatusBarError(s.error)
    }

    private fun applyNetworkStats(s: NetworkStats) {
        binding.packetsSentText.text = getString(R.string.label_packets_sent) + ": " + s.packetsSent
        // Loss is only knowable at the receiver (needs seq-gap feedback that the
        // protocol does not carry), so report it honestly instead of "-".
        binding.packetsLostText.text = getString(R.string.label_packets_lost) + ": n/a (receiver-side)"
        binding.lossPctText.text = getString(R.string.label_loss_pct) + ": n/a (receiver-side)"
        binding.sendRateText.text = getString(R.string.label_out_rate) + ": " + s.packetsSent + " sent"
        binding.queueDepthText.text = getString(R.string.label_buffer_occupancy) + ": " + s.queueDepth
        val txt = when (s.state) {
            StreamState.CONNECTING -> "CONNECTING"
            StreamState.CONNECTED -> "CONNECTED"
            StreamState.STREAMING -> "STREAMING"
            StreamState.DISCONNECTED -> "DISCONNECTED"
            StreamState.ERROR -> "ERROR"
        }
        binding.stateBadge.text = txt
        val color = when (s.state) {
            StreamState.STREAMING, StreamState.CONNECTED -> R.color.srp_success
            StreamState.CONNECTING -> R.color.srp_warning
            StreamState.ERROR, StreamState.DISCONNECTED -> R.color.srp_danger
        }
        binding.stateBadge.setTextColor(getColor(color))
        if (s.state == StreamState.ERROR && s.lastError.isNotBlank()) setStatusBarError(s.lastError)
    }

    private fun setStateUi(state: ServiceState, err: String?) {
        val txt = when (state) {
            ServiceState.CONNECTING -> "CONNECTING"
            ServiceState.CONNECTED, ServiceState.STREAMING -> "STREAMING"
            ServiceState.PAUSED -> "PAUSED"
            ServiceState.ERROR -> "ERROR"
            ServiceState.IDLE -> "DISCONNECTED"
        }
        binding.stateBadge.text = txt
        val color = when (state) {
            ServiceState.ERROR -> R.color.srp_danger
            ServiceState.CONNECTING -> R.color.srp_warning
            ServiceState.STREAMING, ServiceState.CONNECTED -> R.color.srp_success
            ServiceState.PAUSED -> R.color.srp_warning
            ServiceState.IDLE -> R.color.srp_text_muted
        }
        binding.stateBadge.setTextColor(getColor(color))
        if (state == ServiceState.ERROR && err != null && err.isNotBlank()) setStatusBarError(err)
    }

    private fun setStatusBarError(msg: String) {
        binding.remoteText.text = "Error: $msg"
        binding.remoteText.setTextColor(getColor(R.color.srp_danger))
    }

    private fun dbToLinear(db: Double): Int {
        val lin = if (db < -90.0) 0.0 else Math.pow(10.0, db / 20.0)
        return (lin.coerceIn(0.0, 1.0) * 100).toInt()
    }

    private fun refreshStatus() {
        service?.let {
            applyCaptureStats(it.latestCaptureStats())
            applyNetworkStats(it.latestNetworkStats())
            setStateUi(it.latestState(), null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { unbindService(connection); bound = false }
        recordingCallback?.let { service?.unregisterRecordingCallback(it) }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        runCatching { libraryPlayer?.stop() }
        runCatching { libraryPlayer?.release() }
        libraryPlayer = null
    }
}
