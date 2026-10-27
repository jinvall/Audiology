# Audiology2

**Professional Android Audio Streaming & Multi-Track Editor**

Audiology2 is a professional-grade Android application for real-time audio capture, DSP processing, network streaming, and multi-track waveform/spectrogram editing. Built for audio engineers, musicians, and researchers who need studio-quality tools on mobile.

---

## 🎯 Features

### 🎤 Real-Time Audio Streaming
- **Microphone Capture**: Configurable `AudioRecord` with buffer size, sample rate (8-48kHz), channel selection
- **Network Transport**: UDP/TCP with configurable packet size (5-100ms), jitter buffer (0-500ms), auto-reconnect
- **Foreground Service**: Wake lock + notification for uninterrupted background streaming
- **Live Meters**: RMS, Peak, Clipping detection, Packet sent/lost/rate statistics

### 🎚️ Real-Time DSP Chain
| Effect | Parameters |
|--------|------------|
| **Gain** | -60 to +60 dB (rotary knob UI) |
| **AGC** | Automatic gain control |
| **Noise Suppression** | Android built-in |
| **High-Pass Filter** | 20-20000 Hz, adjustable Q |
| **Low-Pass Filter** | 20-20000 Hz, adjustable Q |
| **Notch Filter** | Frequency + Q |
| **Parametric EQ** | Frequency, Gain (±24dB), Bandwidth (octaves) |
| **Compressor** | Threshold, Ratio (1:1-∞:1), Attack/Release (ms) |
| **Limiter** | Hard/soft knee, ceiling |

### 🎵 Multi-Track Editor
- **Waveform View**: Zoomable/panable, dB scale, selection, playback cursor
- **Spectrogram View**: Real-time FFT with **SRP Spectral Gradient** (6-stop: navy → violet → purple → blue-purple → green → slate)
- **Multi-Track Timeline**: Track headers (M/S/Arm), clip rendering with waveform preview, fade in/out, resize handles
- **Transport**: Play/Stop/Record/Rewind/FF with seek
- **Clip Editing**: Split at playhead, Duplicate, Delete, Trim (drag edges)
- **Track Management**: Add/Delete tracks, color coding
- **Snap Grid**: Off/Seconds/Frames/Beats/Measures/8th/16th/Triplet
- **Gain Envelope**: Per-clip volume automation with breakpoints
- **Undo/Redo**: Full history stack with reflection-based property restoration

### 📼 Recording & Playback
- **WAV Recording**: 8/16/24/32-bit PCM, real-time to file
- **Auto Clip Creation**: Stops recording → creates Clip on Track
- **AudioPlayer**: `AudioTrack` playback with seek, position callbacks, pause/resume

### 💾 Persistence
- **Room Database**: Project/Track/Clip entities with foreign keys & cascading deletes
- **TypeConverters**: CSV-based list serialization (no kotlinx.serialization kapt conflicts)
- **ProjectJsonHelper**: Manual JSON serialization (avoids kapt issues)
- **UndoRedoManager**: Full history stack with reflection-based property restoration

---

## 🛠 Tech Stack

| Component | Technology |
|-----------|------------|
| **Language** | Kotlin 1.9.20 |
| **Build** | Gradle 8.6.1 (Kotlin DSL) |
| **Architecture** | MVVM + Service + Custom Views |
| **Database** | Room 2.6.1 + KAPT |
| **Async** | Coroutines + Flow |
| **Audio** | `AudioRecord` / `AudioTrack` / `MediaCodec` |
| **Network** | UDP/TCP sockets (NIO) |
| **DSP** | Custom Cooley-Tukey FFT, Biquad filters |
| **UI** | Custom `View` canvas rendering, `ViewBinding` |
| **Min SDK** | 21 (Android 5.0) |
| **Target SDK** | 34 (Android 14) |

---

## 📦 Project Structure

```
app/src/main/java/com/audiology2/stream/
├── audio/              # Audio I/O, WAV writing, playback
├── config/             # Settings, presets, validation
├── data/               # Room database, entities, DAOs, repository
├── dsp/                # FFT, filters, gain, compressor, limiter
├── network/            # UDP/TCP sender, packetization
├── service/            # Foreground streaming service
├── ui/                 # Custom views, editor, MainActivity
│   ├── SpectrogramView.kt      # SRP gradient spectrogram
│   ├── WaveformView.kt         # Zoomable waveform
│   ├── TrackTimelineView.kt    # Multi-track canvas
│   ├── RotaryKnob.kt           # Studio gain knob
│   ├── SnapGrid.kt             # Beat/measure snapping
│   ├── GainEnvelope.kt         # Volume automation
│   ├── UndoRedoManager.kt      # History stack
│   └── AudioProject.kt         # Clip/Track/Project models
└── MainActivity.kt             # App entry, editor init
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog+ (2023.1.1+)
- JDK 17+
- Android SDK 34

### Build
```bash
# Debug APK
./gradlew assembleDebug

# Release APK (signed)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Install to device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Required Permissions (granted at runtime)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

---

## 🎨 SRP Spectral Gradient

The spectrogram uses a custom 6-stop gradient from the SRP theme pack:

| Stop | Hex | Color | Intensity |
|------|-----|-------|-----------|
| 0 | `#060119` | Dark Navy | Lowest |
| 1 | `#2F076E` | Violet | Low |
| 2 | `#370150` | Deep Purple | Mid-Low |
| 3 | `#5633C7` | Blue-Purple | Mid |
| 4 | `#134E1A` | Dark Green | High |
| 5 | `#65637E` | Slate | Highest |

Maps dB magnitude (-120 to 0 dB) → normalized 0-1 → interpolated color.

---

## 🔧 Configuration

### Stream Settings (UI: Connection section)
| Setting | Default | Range |
|---------|---------|-------|
| Host | (empty) | IPv4/hostname |
| Port | 59090 | 1-65535 |
| Transport | UDP | UDP/TCP |
| Sample Rate | 48000 | 8000-48000 |
| Channels | MONO | MONO/STEREO |
| Packet Duration | 30ms | 5-100ms |
| Jitter Buffer | 60ms | 0-500ms |
| Auto Reconnect | ON | ON/OFF |

### Capture Settings (UI: Microphone section)
| Setting | Default | Range |
|---------|---------|-------|
| Input Source | DEFAULT | VOICE_COMMUNICATION, VOICE_CALL, CAMCORDER, UNPROCESSED, VOICE_RECOGNITION |
| Buffer Size | 20ms | 5-100ms |
| Max Sensitivity | OFF | ON/OFF |
| Raw Bypass | OFF | ON/OFF |

---

## 🐛 Known Issues

- Occasional audio glitches under heavy DSP load
- Spectrogram occasionally shows stale data after pause
- Track M/S/Arm buttons need callback wiring
- Undo/redo for clip property changes (gain, pan) not fully wired
- Playhead sync during playback needs improvement

See [TODO.md](TODO.md) for full roadmap.

---

## 📄 License

This project is licensed under the terms of the LICENSE file in the root directory.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/jinvall/Audiology/issues)
- **Discussions**: [GitHub Discussions](https://github.com/jinvall/Audiology/discussions)

---

**Built with ❤️ for audio professionals who need studio tools on the go.**