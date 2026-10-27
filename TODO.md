# Audiology2 - TODO

## Phase 5: Editor Polish & Export (Next Sprint)

### Editor UX
- [ ] Add undo/redo toolbar buttons with descriptions
- [ ] Gain envelope visualization on clips (editable breakpoints)
- [ ] Clip fade in/out drag handles
- [ ] Track reordering (drag track headers to reorder)
- [ ] Master track with master gain/fader/meter
- [ ] Tempo/time signature grid display in timeline
- [ ] Clip rename dialog (long press or double tap)
- [ ] Track color picker dialog
- [ ] Zoom to selection button (already have zoom fit/selection)

### Snap Grid Integration
- [ ] Wire SnapGrid to drag operations in TrackTimelineView
- [ ] Snap clip start/end on drag
- [ ] Snap resize handles
- [ ] Visual grid lines in timeline background
- [ ] Snap mode selector in toolbar (Off/Seconds/Frames/Beats/Measures)

### Clip Editing
- [ ] Split at playhead (already have splitClip())
- [ ] Trim from clip edges (drag to resize)
- [ ] Copy/paste clips (with track selection)
- [ ] Delete with ripple option
- [ ] Gain envelope per clip (editable breakpoints)

### Track Management
- [ ] Track reordering (drag track headers)
- [ ] Track M/S/Arm button callbacks
- [ ] Track pan control
- [ ] Master track with master gain/fader/meter
- [ ] Track locking (prevent accidental edits)

## Phase 6: Export & Sharing

### Export Formats
- [ ] Multi-track mixdown to WAV (via MediaMuxer or manual mix)
- [ ] Export selected clips/range
- [ ] Export with/without DSP chain (bypass option)
- [ ] MP3 export (MediaCodec encoder)
- [ ] OGG/FLAC export (FFmpeg-kit integration)

### Import
- [ ] Import audio files (MediaMetadataRetriever + MediaCodec decode)
- [ ] Drag/drop or file picker
- [ ] Auto-create track + clip on import
- [ ] Support WAV, MP3, OGG, FLAC

### Project Sharing
- [ ] Project archive (zip with audio files + project.json)
- [ ] Cloud sync (Google Drive / Firebase)
- [ ] Project templates

## Phase 7: Advanced Features

### DSP Enhancements
- [ ] VST/AU plugin hosting (via JNI + Superpowered or native)
- [ ] Real-time spectrum analyzer overlay
- [ ] Phase correlation meter
- [ ] Loudness metering (LUFS)
- [ ] More filter types (all-pass, shelf, peak notch)

### Performance
- [ ] Profile and optimize FFT (consider RenderScript or native JNI)
- [ ] Reduce GC pressure in audio thread
- [ ] Optimize WaveformView decimation
- [ ] Cache spectrogram bitmaps more efficiently

### Accessibility
- [ ] TalkBack support for editor
- [ ] High contrast mode
- [ ] Larger touch targets for transport controls

## Bug Fixes

### Known Issues
- [ ] Occasional audio glitches during heavy DSP load
- [ ] Spectrogram sometimes shows stale data after pause
- [ ] Track header M/S/Arm buttons need callback wiring
- [ ] Undo/redo for clip property changes (gain, pan, etc.)
- [ ] Playhead not perfectly synced during playback
- [ ] Spectrogram sometimes shows stale data after pause
- [ ] Gain envelope not yet visualized on clips

### Audio Engine
- [ ] Investigate occasional audio glitches under heavy DSP
- [ ] Optimize buffer sizes for lower latency
- [ ] Handle sample rate conversion properly
- [ ] Better handling of audio focus changes

## Technical Debt

### Code Quality
- [ ] Add unit tests for DSP chain components
- [ ] Add integration tests for Room database
- [ ] Add UI tests for editor operations
- [ ] Document public APIs with KDoc
- [ ] Migrate from manual JSON to kotlinx.serialization when kapt issues resolved
- [ ] Replace reflection in UndoRedoManager with type-safe approach

### Architecture
- [ ] Extract audio engine to separate module
- [ ] Add dependency injection (Hilt/Koin)
- [ ] Implement proper error boundaries
- [ ] Add analytics/crash reporting

## Documentation
- [ ] User guide for editor
- [ ] Developer guide for DSP chain
- [ ] Architecture decision records (ADRs)
- [ ] Build/release process documentation

---

## Completed ✅

### Phase 1: RTL/Cursor Fix
- [x] Fixed feedback loop in `applySettingsToViews()`
- [x] All EditText fields have RTL prevention attributes
- [x] `window.decorView.layoutDirection = LTR` in onCreate()

### Phase 2: Recording
- [x] WavWriter for PCM WAV files
- [x] StreamService startRecording()/stopRecording()
- [x] Live waveform/spectrogram during recording
- [x] Auto Clip creation on stop

### Phase 3: Editor Foundation
- [x] TrackTimelineView with multi-track support
- [x] WaveformView with zoom/pan/selection
- [x] SpectrogramView with SRP gradient
- [x] RotaryKnob for gain
- [x] SnapGrid for beat/measure snapping
- [x] GainEnvelope for clip automation
- [x] Transport controls (play/stop/record/rewind/ff)

### Phase 4: Persistence
- [x] Room database (Project/Track/Clip entities)
- [x] CSV-based TypeConverters (no kotlinx.serialization)
- [x] Manual JSON serialization (ProjectJsonHelper)
- [x] UndoRedoManager with reflection-based restore
- [x] AudioPlayer with AudioTrack playback

---

## Git Workflow

```bash
# After making changes
git add .
git commit -m "feat: descriptive message"

# Push to remote
git push -u origin main
```

## Release Checklist
- [ ] Update versionCode/versionName in build.gradle.kts
- [ ] Update CHANGELOG.md
- [ ] Build release APK: `./gradlew assembleRelease`
- [ ] Test on physical device
- [ ] Sign with release keystore
- [ ] Upload to Play Console / distribute