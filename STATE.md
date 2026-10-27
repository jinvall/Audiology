# Audiology2 - Project State

## Last Updated
2026-10-27

## Build Status
✅ **BUILD SUCCESSFUL** - Debug APK compiles and installs

## Project Overview
Audiology2 is an Android audio streaming and multi-track editor application. It captures microphone audio, applies real-time DSP processing, streams over UDP/TCP, and includes a full multi-track waveform/spectrogram editor.

## Current Features

### Core Streaming (Working)
- Microphone capture via `AudioRecord` with configurable buffer sizes
- Real-time DSP chain: AGC, Noise Suppression, High-pass/Low-pass/Notch filters, EQ, Compressor, Limiter
- UDP/TCP streaming with configurable packet size, jitter buffer, auto-reconnect
- Foreground service with wake lock for continuous streaming
- Live meters (RMS, Peak, Clipping detection, Packet stats)

### Editor / Timeline (Working)
- **WaveformView**: Zoomable/panable waveform with dB scale, selection, playback cursor
- **SpectrogramView**: Real-time FFT spectrogram using SRP spectral gradient (6-stop: #060119 → #2F076E → #370150 → #5633C7 → #134E1A → #65637E)
- **TrackTimelineView**: Multi-track timeline with:
  - Track headers (name, color, M/S/Arm buttons)
  - Clip rendering with waveform preview, fade in/out, resize handles
  - Time ruler with dynamic grid/labels
  - Zoom/pan gestures, selection, drag-to-move clips
  - Resize handles on clip edges
- **Transport controls**: Play/Stop/Record/Rewind/FF
- **Clip editing**: Split (at playhead), Duplicate, Delete
- **Track management**: Add/Delete tracks
- **SnapGrid**: Beat/measure/frame/second snapping
- **GainEnvelope**: Clip volume automation points

### Recording (Working)
- WAV file recording (8/16/24/32-bit PCM)
- Real-time recording to file via `WavWriter`
- Auto-creates Clip on Track when recording stops
- Live waveform/spectrogram updates during recording

### Persistence (Working)
- **Room Database**: Project/Track/Clip entities with foreign keys
- **TypeConverters**: CSV-based list serialization (avoids kotlinx.serialization kapt issues)
- **ProjectJsonHelper**: Manual JSON serialization without kotlinx.serialization (avoids kapt conflicts)
- **UndoRedoManager**: Full undo/redo stack with reflection-based property restoration

### DSP Chain (Working)
- `ProcessingChain`: Ordered effect processing
- `GainStage`: Digital gain (-60 to +60 dB)
- `AGC`: Automatic gain control
- `NoiseSuppressor`: Noise suppression
- `BiquadFilter`: High-pass, Low-pass, Notch, EQ, Band-pass
- `Compressor`: Threshold, ratio, attack, release
- `Limiter`: Hard/soft knee limiting

### UI Fixes (Working)
- **RTL cursor bug fixed**: Feedback loop in `applySettingsToViews()` was calling `setText()` on every keystroke → cursor reset to left
- **Fix**: Only call `setText()` when value actually changed
- **XML**: All `EditText` fields have proper RTL prevention attributes
- **Code**: `window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR` in `MainActivity.onCreate()`

### Permissions (Working)
- RECORD_AUDIO
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_MICROPHONE (Android 14+)
- WAKE_LOCK (for streaming service)
- INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE

## Known Issues / TODOs

### High Priority
- [ ] Project load/save UI integration (currently loads last project automatically)
- [ ] Audio playback seeking (playhead not perfectly synced)
- [ ] Multi-track mixdown/export (WAV/MP3 via MediaMuxer/FFmpeg)
- [ ] Grid snapping integration with drag/resize operations
- [ ] Undo/redo UI buttons

### Medium Priority
- [ ] Gain envelope visualization on clips
- [ ] Clip fade in/out handles
- [ ] Track reordering (drag to reorder)
- [ ] Master track with master gain/fader
- [ ] Tempo/time signature grid display
- [ ] Clip rename dialog
- [ ] Track color picker

### Low Priority
- [ ] Project settings persistence (sample rate, bit depth, tempo)
- [ ] Import audio files (MediaMetadataRetriever + MediaCodec decode)
- [ ] Export formats (MP3, OGG, FLAC via FFmpeg)
- [ ] Project templates
- [ ] Cloud sync / project sharing

### Bugs to Investigate
- [ ] Occasional audio glitches during heavy DSP load
- [ ] Spectrogram sometimes shows stale data after pause
- [ ] Track header M/S/Arm buttons need callback wiring
- [ ] Undo/redo for clip property changes (gain, pan, etc.)

## Architecture Notes

### Key Classes
| Class | Purpose |
|-------|---------|
| `MainActivity` | Main UI, editor initialization, service binding |
| `StreamService` | Foreground service, capture + DSP + streaming |
| `MicrophoneCapture` | AudioRecord capture thread |
| `StreamSender` | UDP/TCP packet sending |
| `FFTProcessor` | Cooley-Tukey FFT for spectrogram |
| `WavWriter` | WAV file writing |
| `AudioPlayer` | AudioTrack playback with seek |
| `TrackTimelineView` | Multi-track timeline canvas |
| `WaveformView` / `SpectrogramView` | Editor views |
| `ProjectDatabase` / `ProjectRepository` | Room persistence |

### Data Flow
```
Mic → MicrophoneCapture → DSP Chain → StreamSender → Network
                    ↓
              WavWriter (if recording)
                    ↓
              TrackTimelineView (live waveform/FFT)
```

### SRP Spectral Gradient
Used in `SpectrogramView` for intensity mapping:
```
#060119 (dark navy, lowest intensity)
#2F076E (violet)
#370150 (deep purple)
#5633C7 (blue-purple)
#134E1A (dark green)
#65637E (slate, highest intensity)
```

## Build Configuration
- **Gradle**: 8.6.1 with Kotlin 1.9.20
- **Room**: 2.6.1 with KAPT
- **Target SDK**: 34
- **Min SDK**: 21
- **Compile SDK**: 35

## Test Commands
```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK (signed)
./gradlew test                 # Run unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```