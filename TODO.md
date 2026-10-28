# Audiology2 - TODO

> **Status (2026-10-28):** Feature-complete core DAW (streaming, DSP, multi-track, MIDI, spectral, assistant). 57/57 tests green, builds clean.
> **Immediate next phase: SWEEP** — user performs read+run-only sweep (see `SWEEP_PLAN.md`), returns findings, implementation follows in this session.
> **Passdown for continuity:** see `AGENT_PASSDOWN.md`.

## Phase NEXT: Sweep integration (arrives as findings)

- [ ] Incorporate `SWEEP_PLAN.md` findings card-by-card (Connections → Assistant → Editor order)
- [ ] Reminder to user: **tell assistant about the API field in the Assistant card** (explicitly requested)

## Known open implementation items (pre-sweep, confirmed)

### Functional placeholder (THE one)
- [ ] **`AudioExporter.kt:306` — export mixer plays test tones instead of decoding `clip.sourcePath`.** Fix = decode real audio window, apply pitch/stretch/gain/fades, mix. Affects WAV + M4A export.

### Correctness
- [ ] `WavUtils` parses `audioFormat` (PCM=1 vs IEEE-float=3) but discards it — auto-detect the decode branch (not a config knob)
- [ ] Recording pipeline produced 26s of digital silence in the field (device recording artifact) — instrument capture start (`AudioRecord.getSampleRate()`, first-block RMS) to bisect input vs runtime during sweep
- [ ] Surface `StreamSender.drainedBytes` in Diagnostics (TCP window-collapse watchdog)

### Architecture
- [ ] **MainActivity breakup** (~2,200-line god file): PlaybackController, EditorDialogs, SettingsUi, ProjectActions, MidiPanel, StreamingUi (SpectralCard already extracted)
- [ ] Decide fate of dead files: `data/ProjectDatabase.kt`, `data/ProjectJsonHelper.kt` (flagged, kept — user decides)

### Features queued
- [ ] Assistant provider preset dropdown (OpenRouter current default / Groq / Gemini / Local / Custom)
- [ ] Assistant Tier 3: audio-aware (send clip WAV to multimodal endpoint)
- [ ] Spectral brush v2: paint on the spectrogram (time×freq mask) + harmonic extraction → sample
- [ ] Connections: working PCM wire-format selector (currently hardcoded PCM_16)
- [ ] Main loop / loop-region state persistence across restart

## Carried from legacy TODO (still open)

- [ ] Master track with master gain/fader/meter
- [ ] Automation/envelope breakpoints (gain envelope per clip, editable)
- [ ] Clip fade drag handles on-clip (fields exist: fadeIn/fadeOut)
- [ ] Copy/paste clips, delete-with-ripple
- [ ] Track reorder by drag; color picker dialog; clip rename dialog
- [ ] Third-party VST3/LV2 hosting = longer-term native-FFI task; internal plugin interface + preset JSON first
- [ ] Reverb, chorus/flanger/phaser, reverse, DC-offset, normalize, stereo tools in the FX chain