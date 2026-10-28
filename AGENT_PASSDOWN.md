# AGENT PASSDOWN — Audiology2

**Written:** 2026-10-28 · **Next session:** resume from here; do NOT treat repo as unknown.

---

## 1. Session handoff (IMPORTANT — read first)

- **The user is running the sweep OUTSIDE this session** (free session, read+run only — no code changes).
- When the user returns with findings: incorporate them into `SWEEP_PLAN.md`, then implement together.
- **ASK the user: "You said you'd tell me about the API field in the Assistant."** They explicitly requested this reminder in the passdown. Do not skip it. (Placeholder: the Assistant card's API-key field behavior; they noticed something about it.)
- Local LLM inference on this box is **forbidden unless the user explicitly asks**: the N100 is permanently loaded (measured: 48 tokens took 135s at load 15.3; llama3 8B ≈ 4.9GB RAM on a 16GB box with AMP + their apps always resident). Default assistant provider = **OpenRouter free tier** (user already has key).

## 2. Repo state

- **HEAD:** `1f52ec0` — openrouter default. `master` on GitHub `jinvall/Audiology`.
- 12 commits this session (0341d26 → 1f52ec0): persistence, timeline editing, pitch/speed + FX, range selection + live FX + loop, project library, MIDI piano-roll/sequencer + sampler, spectrogram feed fix, spectral tools card, STFT/ISTFT spectral brush, assistant (text+voice+OpenRouter), TCP drain fix, recording integration tests.
- **Build:** `./gradlew :app:assembleDebug` GREEN.
- **Tests:** `./gradlew :app:testDebugUnitTest` — **57 tests, 0 failures** (kotlin-test pinned to 1.9.20 to match plugin — do not bump back).

## 3. Architecture decisions (do not undo)

| Area | Decision |
|---|---|
| Persistence | `ProjectManager` = sync in-memory cache + DataStore write-through (shared with SettingsStore). Room `ProjectDatabase.kt` exists but is DEAD — do not wire it; decide with user during sweep. |
| Data model | `AudioProject`/`Track`/`Clip` immutable-list, mutation helpers. `Clip` has pitchSemitones/speedRatio/stretchRatio. `Track` has midiClips. |
| DSP host | In-process only. NO network DSP host (latency/auth/offline-export reasons). `ProcessingChain` = live; `AudioEffects` = offline; `TimeStretch` = resample+SOLA; `SpectralTransform` = STFT/ISTFT masks. |
| TCP streaming | `StreamSender` MUST drain its socket (daemon thread) + `tcpNoDelay` — the fatal "massive delay" was receive-window collapse (Send-Q 36→57KB observed). `drainedBytes` counter exists, not yet surfaced in Diagnostics. |
| Spectrogram feed | Single callback registered in `onServiceConnected` (NOT onCreate — race was real). Strongest gradient stop = hunter orange `#FF4F00`. |
| Assistant | `assist/` package, OpenAI-compatible `/chat/completions`; strict JSON intent contract; key AES-GCM in Keystore; default = OpenRouter `openai/gpt-oss-120b:free`, base `https://openrouter.ai/api/v1`. |
| Files | SRP theme pack + all files: DO NOT erase/move. Dead code is flagged, not removed, until user decides. |

## 4. Known open items (flag → implement after sweep)

1. **Export mixer plays test tones, not real audio** (`AudioExporter.kt:306` TrackPlayer.readFrames). THE functional placeholder. Fix = decode `clip.sourcePath` window + apply pitch/stretch + mix.
2. `WavUtils` parses `audioFormat` (PCM vs IEEE-float) but discards it — auto-detect branch, not a config knob.
3. Dead files: `data/ProjectDatabase.kt`, `data/ProjectJsonHelper.kt` (both flagged, kept).
4. Unused members (flagged, kept): `AudioEffects.gate`, `AudioEffects.DEFAULT`, `ClipEditor.deleteGenerated`, `ProjectManager.editor`/`getProject(id)`, `recordChange`/`onChange` in UndoRedoManager, ~10 SeekBar locals in Effects dialog, `Clip.locked`/`Clip.solo`/`Track.height`/`Track.inputSource` (spec fields).
5. **MainActivity is ~2,200 lines (god file)** — split into PlaybackController/EditorDialogs/SettingsUi/ProjectActions/MidiPanel/StreamingUi. SpectralCard already extracted. Do BEFORE or WITH sweep fixes.
6. Recording mystery: the device recording (`recording_1793153866196.wav`, gitignored, at repo root) is 26.175s of **perfect digital silence** with a correct header (44100/16/mono). Code paths verified clean statically → sweep card: instrument capture (log `AudioRecord.getSampleRate()`, first-block RMS) to bisect device-input vs runtime bug.
7. Sweep also targets: jitter/buffer latency floor, Connections wire-format selectable (hardcoded PCM_16 at MainActivity readSettingsFromViews), helper surfacing `drainedBytes` in Diagnostics.

## 5. Environment facts (verified)

- Box = Intel N100, 4 cores, 16GB, `silver` @ 10.0.0.147. Always loaded (VS Code, 2× Chrome, 2 stream apps, RTSP, Inkscape ×2 typical).
- **AMP server** (`~/amp/server/amp.py`) = one receiver/host: AudioReceiver on **TCP/UDP 8090** (app stream target, default port), control 8091, web/viz 8093. Confirmed live: `pcm_clients:1`, `android_connected:true`, monitoring enabled. This is ONE host.
- **`~/audioscope` is a SEPARATE, active project** (own git repo, "Audio Microscope"): continuous acoustic monitoring / event detection / source separation / playback / evidence recording, Python 3.10+, consumes a **network audio stream** (or mic). It can be the *consuming* host for the app's stream and is NOT the same as `~/amp`. Do not conflate the two; when the user says "the audio makes a full loop" they may mean the phone → AMP → (audioscope consumes AMP's stream). Check `~/audioscope/README.md`/`TODO.md` before assuming which host matters for a given task. `~/amp` and `~/audioscope` are sibling projects, not aliases.
- **Ollama** installed with `phi3` (2.2GB) + `llama3` (4.7GB) pulled. Unusable as assistant default on this HW (measured). Unload with `ollama stop <model>`.
- No adb device attached to this box — user side-loads the debug APK (`app/build/outputs/apk/debug/`).
- `recording_*.wav` is gitignored (user artifacts).
- Unit tests read the repo-root recording artifact when present (gated, WavRecordingIntegrationTest).

## 6. Next-session start protocol

1. `git log --oneline -5`, `git status` — reconcile with this passdown (repo wins if newer).
2. Ask the user the **API field in the Assistant** question (section 1).
3. Collect sweep findings (user or free-you returns with `SWEEP_PLAN.md`-format report).
4. Implement in smallest-green-commit order; push after each logical unit.