# Audiology2 - Project State

**Updated:** 2026-11-01 · **HEAD:** `6ddd2cf` (master, pushed)

## Build Status
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest` — **68 tests, 0 failures**
- kotlin-test pinned to 1.9.20 (must match the Kotlin plugin).

## What the app is
Android audio DAW: mic/playback capture → DSP → TCP/UDP stream (ASIO protocol) + a full multi-track editor, MIDI sequencer, Spectroid-style spectral analyzer + spectral clip editor, and a voice/text DAW assistant. Dark **SRP palette** (violet-black, green/cyan/violet accents, hunter-orange spectral peaks) with a **console** control set.

## Recent work (2026-11-01)

### Console UI
- `KnobView` (rotary dial, vertical-drag, tick ring, value arc), `VuMeterView`
  (peak-hold + clip LED), `LedSwitch` (LED toggle), `ConsoleMath` (unit-tested).
- Processing card rebuilt as a console: 13 knobs (FILTERS/EQ/COMP/SPACE-DRIVE),
  12 LED toggles; numeric fields hidden but retained as the settings source.
- Editor card opens by default; monitor = waveform (top) + spectrogram (bottom).

### Spectral build
- `SpectralTransform` STFT/ISTFT engine; clip brush.
- `SpectrumAnalyzerView`: live spectrum + **lateral interactive waterfall**
  (time X, freq Y) with region selection; also a **static clip mode**
  (`showFrames`) for the clip editor.
- Spectral ops on the selection: **gain, mix, invert, master, diff, isolate,
  denoise**; time-range targeting via the clip timeline selection.
- Clip selector (spinner) + clip spectrogram editor in the Spectral card.

### On-device UI sweep (Samsung SM-S127DL, Android 11) - findings + fixes
- **Blank monitor spectrogram** -> replaced the monitor view with the working
  `SpectrumAnalyzerView` (old `SpectrogramView` rendered blank); also made it
  accumulate frames. **Fixed.**
- **Status strip showed literal "host:port"** -> now shows the real
  `<host>:<port> <transport>`. **Fixed.**
- **Spectral partials all "0 dB"** -> `SpectralEngine.partials` computed db.
  **Fixed.**
- **Auto-record filled storage (3.1 GB, one 574 MB file)** -> 5-minute cap in
  `StreamService`. **Fixed.**
- **MIDI editor buttons wrapped vertically; piano roll was bright white** ->
  scrollable non-wrapping row + dark SRP piano roll. **Fixed.**
- **"Cannot round NaN value"** -> NaN/Inf guards in PcmEncoder, noteFromFreq,
  TimeStretch, SnapGrid. **Fixed.**
- **Transport was UDP** (receiver is TCP) -> user switched presets to TCP.

## Known issues / open
- Streaming still errors unless transport = TCP and host points at the receiver
  (AMP AudioReceiver, port 8090).
- `remoteText` error text appears transiently on send failure.
- Auto-record is bounded (5 min) but still starts on launch by default.
- `RotaryKnob` (existing gain dial) and `KnobView` (new) coexist — consolidate.
- Remaining Processing params without knobs: Comp attack/release, Notch Q,
  Echo feedback.
- Harmonic->sample (isolated partials onto the Beats track) not implemented.
- Auto-record + monitor start on launch; no opt-out toggle yet.

## Environment
- `silver` (N100, 16 GB) runs AMP; device over adb (`SM-S127DL`).
- Dead-but-kept files: `data/ProjectDatabase.kt`, `data/ProjectJsonHelper.kt`.
