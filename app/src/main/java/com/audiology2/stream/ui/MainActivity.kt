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
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.audiology2.stream.R
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
    
    // Recording callback reference for unregistering
    private var recordingCallback: ((FloatArray, Int, Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force LTR before super.onCreate so window is configured before view inflation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        super.onCreate(savedInstanceState)
        SettingsStore.init(applicationContext)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = (binder as? StreamService.LocalBinder)
            service = b?.getService()
            bound = true
            service?.registerCallback(this@MainActivity)
            
            // Register for live waveform/spectrogram updates
            recordingCallback = { samples, framesRead, channels ->
                val sr = binding.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
                binding.mainWaveformView.setWaveform(samples, sr)
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
        binding.startBtn.setOnClickListener { startStreaming() }
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
            binding.compThreshEdit, binding.compRatioEdit, binding.compAttackEdit, binding.compReleaseEdit
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
        
        // Export/Import
        binding.btnExport.setOnClickListener { showExportDialog() }
        binding.btnImport.setOnClickListener { showImportDialog() }
        
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
            binding.mainSpectrogramView.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Initialize track timeline
        initTrackTimeline()
        
        // Setup FFT processor for real spectrogram
        setupFFTProcessor()
        
        // Setup snap grid
        setupSnapGrid()
    }

    private fun setupFFTProcessor() {
        // Connect FFT processor to timeline for real-time spectrogram
        if (service != null) {
            service?.registerRecordingCallback { samples, framesRead, channels ->
                val sr = binding.sampleRateEdit.text.toString().toIntOrNull() ?: 48000
                binding.mainWaveformView.setWaveform(samples, sr)
                
                // Process FFT for spectrogram
                val frames = fftProcessor.process(samples, framesRead, channels)
                frames?.let { spectrogramFrames ->
                    editorScope.launch(Dispatchers.Main) {
                        // Update spectrogram view with new frames
                        binding.mainSpectrogramView.updateSpectrogram(spectrogramFrames, sr, fftProcessor.fftSize)
                    }
                }
                
                binding.mainWaveformView.setWaveform(samples, sr)
                binding.mainSpectrogramView.postInvalidate()
            }
        }
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
            Log.d("Editor", "Selected track: ${track.name}")
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

    private fun startPlayback() {
        // Load selected clip or first clip for playback
        val clip = binding.trackTimelineView.currentSelectedClipId
            ?.let { binding.trackTimelineView.findClip(it) }
            ?: ProjectManager.getCurrentProject()?.tracks?.firstOrNull()?.clips?.firstOrNull()
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

    // ---- Export/Import ----
    
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
                    val exporter = AudioExporter(editorScope)
                    val success = when (format) {
                        ExportFormat.WAV -> exporter.exportWav(project, outputFile) { progress ->
                            runOnUiThread { progressDialog.setMessage("Exporting: ${(progress * 100).toInt()}%") }
                        }
                        ExportFormat.M4A -> exporter.exportM4a(project, outputFile) { progress ->
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
                limiterEnabled = v.limiterSwitch.isChecked
            ),
            activePreset = v.presetSpinner.selectedItem?.toString().orEmpty()
        )
    }

    private fun updateSettingsFromViews() {
        val settings = readSettingsFromViews()
        lifecycleScope.launch { SettingsStore.saveSettings(settings) }
    }

    private fun applyPreset(s: AppSettings) {
        updatingViews = true
        applySettingsToViews(s)
        updatingViews = false
        lifecycleScope.launch { SettingsStore.saveSettings(s) }
        val pi = Presets.BUILT_IN.indexOfFirst { it.name == s.activePreset }
        if (pi >= 0) binding.presetSpinner.setSelection(pi)
    }

    private fun saveCustomPreset() {
        val cur = readSettingsFromViews()
        val name = cur.activePreset.ifEmpty { "Custom ${System.currentTimeMillis() % 100000}" }
        val preset = Preset(name = name, settings = cur, builtIn = false)
        lifecycleScope.launch {
            val existing = SettingsStore.currentPresets().filter { !it.builtIn } .toMutableList()
            existing.removeAll { it.name == preset.name }
            existing.add(preset)
            SettingsStore.savePresets(existing)
            saveSettingsSync(cur.copy(activePreset = name))
        }
        Toast.makeText(this, "Saved preset $name", Toast.LENGTH_SHORT).show()
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
        val intent = Intent(this, StreamService::class.java).apply { action = StreamService.ACTION_STOP }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        if (bound) { unbindService(connection); bound = false }
        service = null
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
        binding.captureStateText.text = "capture: " + if (s.active) "Active" else if (s.running) "Ready" else "Stopped" +
            "  fmt=${s.pcmFormat} sr=${s.sampleRate}Hz ch=${s.channels} buf=${s.bufferMs}ms pkt=${s.effectivePacketMs}ms drops=${s.dropped}"
        binding.configText.text = getString(R.string.label_config) + ": ${s.pcmFormat}/${s.sampleRate}Hz/${s.channels}ch"
        if (s.error.isNotBlank()) setStatusBarError(s.error)
    }

    private fun applyNetworkStats(s: NetworkStats) {
        binding.packetsSentText.text = getString(R.string.label_packets_sent) + ": " + s.packetsSent
        binding.packetsLostText.text = getString(R.string.label_packets_lost) + ": - (receiver)"
        binding.lossPctText.text = getString(R.string.label_loss_pct) + ": - (receiver)"
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
    }
}
