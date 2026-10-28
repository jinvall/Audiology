# Audiology2 - Project State

**Updated:** 2026-10-28 · **HEAD:** `1f52ec0` (master, pushed)

## Build Status

- ✅ `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- ✅ `./gradlew :app:testDebugUnitTest` — **57 tests, 0 failures**
- kotlin-test pinned to `1.9.20` (must match Kotlin plugin; do not bump)

## What this app is now

Android audio DAW: real-time capture → DSP → TCP/UDP stream (ASIO protocol) + full multi-track editor, MIDI sequencer, spectral analysis/editing, and a voice/text DAW assistant.

## Feature status

### Streaming (working, latency fix landed)
- Capture via `AudioRecord` (buffer 5–100ms configurable); DSP chain GAIN→AGC→NS→HP/LP/Notch→EQ→**Echo/Drive/Tremolo (live)**→Comp→Limiter
- TCP fixed: `tcpNoDelay` + inbound socket **drain thread** (killed the window-collapse "massive delay"; `drainedBytes` counter not yet in UI)
- UDP fallback; auto-reconnect; foreground service + wake lock; meters (RMS/Peak/clip/packets)

### Editor / Timeline (working)
- Multi-track timeline: headers, clip drag/resize (snap-aware), range selection, two-finger pan, loop region overlay + loop transport, MIDI clip blocks (purple)
- Clip ops: add/delete track, split/duplicate/delete clip, **track properties** (name/volume/pan/mute/solo/arm/monitor)
- **Pitch/Speed** (resample + SOLA time-stretch, pitch-shift cascade) and **FX dialogs** with preview + apply; **range editing** splits head/mid/tail
- **Make Sample**: silence-trim + normalize + BPM-synced loop + placement on Beats track
- Project persistence via DataStore (write-through), Projects dialog (new/open, starred current)

### MIDI (working)
- `MidiNote`/`MidiClip` on tracks; **piano-roll + drum/step modes** (tap-place, drag move/transpose, edge-resize, velocity long-press, pin-zoom)
- `MidiSampler` renders clips from the track's sample pool (pitch-shifted melodic, drum one-shots), tempo-synced; **MIDI included in WAV/M4A export**

### Spectral (working)
- **Spectral Tools card**: live pitch/tuner notes+cents, peak (parabolic interp), F0 (HPS), centroid, partials; **Capture→MIDI note**; copy peak
- **Spectral clip editor**: STFT/ISTFT (COLA Hann, 75% overlap) rectangular time×freq gain mask, per-channel resynthesis, destructive with undo
- Spectrogram: live feed registered on service connect (race fixed), **hunter-orange strongest-stop SRP gradient**, drag-selection → clip from last recording

### Assistant (working, free tier)
- `assist/` package: strict-JSON tool intents over OpenAI-compatible `/chat/completions`; 10 tools wired to real DAW functions; **default = OpenRouter free** (`https://openrouter.ai/api/v1`, `openai/gpt-oss-120b:free`)
- Voice: on-device `SpeechRecognizer` (no key); API key AES-GCM in Android Keystore
- Verified NOT viable on this host: local Ollama (N100 ≈ 3–8 tok/s; measured 0.5 tok/s under load) — kept as offline preset only

## Known issues (see AGENT_PASSDOWN.md / SWEEP_PLAN.md)

1. **Export mixer still renders test tones, not clip audio** (`AudioExporter.kt:306`) — the one real placeholder; next implementation priority
2. Field recording produced 26s of digital silence (header perfect, samples zero) — capture instrumentation is a sweep item
3. `WavUtils` reads-but-discards `audioFormat` (PCM vs float) — auto-detect branch planned
4. Dead-but-kept files: `data/ProjectDatabase.kt`, `data/ProjectJsonHelper.kt` (flagged)
5. MainActivity (~2,200 lines) pending breakout into controllers

## Sweep notice

The user runs a read+run-only sweep in a free session per `SWEEP_PLAN.md`; findings return here for joint implementation.