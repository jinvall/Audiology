# SWEEP PLAN — Audiology2 (read + run only)

**Who:** user + free assistant session · **Mode:** READ + RUN ONLY. NO code edits, NO file moves, NO deletions.
**Output:** findings report — for each finding: card, exact repro steps, observed vs expected, logs/screenshot, severity (Blocker/Major/Minor/Nit).

---

## How to run

1. Build + install: `./gradlew :app:assembleDebug` → sideload `app/build/outputs/apk/debug/` (no adb on box; device accepted in this session's env if present).
2. Start with a **fresh session/project** for baseline, then repeat with a saved project.
3. Order: go card-by-card TOP OF SCREEN → BOTTOM. Do not jump around.

## Card 1 — Connections
- [ ] Host/port fields: enter host `10.0.0.147`, port `8090` → Start. Confirm a receiver shows the connection. **Two sibling hosts exist — identify which one is listening:** `~/amp` (AudioReceiver, `ss -tn | grep 8090`, `/health` shows `pcm_clients`/`android_connected`) vs `~/audioscope` (separate project, may consume the stream). Note which host the phone lands on.
- [ ] **Audio loop:** user reports the audio makes a full loop with live monitoring. Verify: phone → receiver → monitor graph active. If audioscope is the consumer, confirm it sees/records the stream (its own tools), else it's AMP.
- [ ] Wire format: does Connections expose a working **PCM format selector**? (Currently hardcoded PCM_16 in code — flag if the spinner is cosmetic.)
- [ ] Sample rate change: SR 44100 ↔ 48000 — does DSP + WSOLA timing survive? Measure latency floor (capture 20ms + packet 30ms expected).
- [ ] Transport UDP ↔ TCP toggle: switch mid-stream — clean reconnect? `drainedBytes` not shown in Diagnostics (flag), Send-Q on server should stay ~0 with new build.
- [ ] Jitter/packetMs extremes: 5ms and 100ms — drop counters sane?
- [ ] **Latency**: with the TCP-drain build (post `0a736e9`), latency should NOT grow over 60s of streaming. Record start/end values.

## Card 2 — Capture & Sensitivity
- [ ] Record 10s → stop → clip appears on Track 1 in the timeline. Then **inspect the WAV** (pull a copy): is it real audio or digital silence? (Known suspect: prior recording was 26s of perfect zeros — see passdown §4.6.)
- [ ] Max Sensitivity toggle: quiet inputs stay audible; no clipping explosion on loud.
- [ ] Input source spinner: DEFAULT vs other — does switching actually change `AudioRecord` source?
- [ ] Buffer size edits (bufferMs): lower = lower latency; does it stay stable or crackle?

## Card 3 — Processing (DSP)
- [ ] A/B bypass switch: raw vs processed audibly different.
- [ ] Each filter: HP/LP/notch/EQ — sweep params, confirm audible + metered change.
- [ ] Compressor/limiter: threshold sweep on loud input — peak clamping visible on meters.
- [ ] **Live FX (Echo/Drive/Tremolo)**: toggle while streaming — signal present in monitor waveform? Params (delay/feedback/mix, drive, rate/depth) edit live or on next start?
- [ ] Extreme params (delay 3.9s, feedback 0.95, drive 30) — no crash, no stuck filter state. Reset to defaults restores clean audio.

## Card 4 — Presets
- [ ] Save custom preset → defaults change → load preset → fields restore (incl. DSP + live FX).
- [ ] Built-in presets all load without error.
- [ ] Preset survives app restart (DataStore).

## Card 5 — Diagnostics
- [ ] Meters move during capture; packet counters increment; dropped increases only on network failure.
- [ ] `drainedBytes` for TCP: FLAG — not yet surfaced (expected gap).
- [ ] Config text matches effective settings.

## Card 6 — Transport / Playback (editor)
- [ ] Play selected clip, play first-audible-clip (mute/solo honored), pause/stop/rewind/FF.
- [ ] Loop toggle: range select → loop; playhead wraps; **loop button state** survives restart? (Flag if not.)
- [ ] Import WAV/MP3 → clip appears; import during playback doesn't crash.
- [ ] **Export WAV/M4A — KNOWN BUG TARGET**: exports currently mix TEST TONES not real clip audio (`AudioExporter.kt:306`). Verify and confirm blob is tone-like, not your import. This is the #1 implementation item on return.
- [ ] Project switch (Projects dialog): open another project, timeline reloads, undo history cleared?

## Card 7 — Assistant
- [ ] Key field: enter key → Save → `••••••••` shown; app restart keeps key (encrypted).
- [ ] **THE USER HAS A NOTE ABOUT THE API FIELD — must tell the dev assistant on return.**
- [ ] Send "set tempo to 112" → intent executes, reply shown. OpenRouter base default (`https://openrouter.ai/api/v1`, `openai/gpt-oss-120b:free`).
- [ ] No-key path: graceful "no API key" message (no crash).
- [ ] Mic button: recognizes speech on-device → text lands in input → auto-send.
- [ ] Bad base URL → friendly network error in transcript (no ANR).
- [ ] 50-req/day OpenRouter quota: note 429 behavior — transcript shows the error, app recovers.

## Card 8 — Spectral Tools
- [ ] Live readout updates while streaming (pitch/tuner/peak/F0/centroid/partials).
- [ ] Tuner bar tracks pitch; Capture→MIDI creates a note in the piano roll.
- [ ] Spectral Edit on a clip: set band 0–1kHz gain −60 → apply → waveform audibly loses low end and clip is replaced. Undo works.
- [ ] Spectrogram: drag selection → toast + clip created from last recording; selection clears.
- [ ] **Registration race check**: cold-start the app, start streaming — does the spectrogram NOW show live data (post `d7660f0`)? (It did NOT before — that was the bug.)

## Card 9 — Editor / Timeline
- [ ] Add/delete/split/duplicate/delete clip — all persist across restart.
- [ ] Drag clip move + resize + range selection + two-finger pan; snap modes (Off/sec/frames/beats).
- [ ] Track props dialog (tap header): rename, gain, pan, mute/solo/arm — timeline + export reflect.
- [ ] Pitch/Speed dialog: preview plays processed; apply swaps source; range-edit splits head/mid/tail correctly (tail start shifts when duration changes).
- [ ] FX dialog: enable each effect → apply; range apply same behavior.
- [ ] Make Sample → Beats track gets a trimmed/normalized one-shot at next measure.
- [ ] MIDI: Add MIDI → piano roll; place notes; Play renders WAV (needs samples on the track); Drums mode maps rows; delete note; velocity long-press. MIDI clip draws purple on timeline; tap opens editor.
- [ ] Timeline playhead while playing a clip; time display format.

## Cross-cutting (do once, anywhere)
- [ ] **Restart persistence sweep**: do a few edits (track, clip, pitch, MIDI note, effects) → force-stop app → relaunch → everything still there? DataStore write-through confirmed.
- [ ] MainActivity god-file: note ANY crash stack pointing into `MainActivity.kt` lines (report line numbers — that's the breakout evidence).
- [ ] Rotation/resume: orientation change mid-stream or mid-recording — no duplicate callbacks, no leak of the recording callback.
- [ ] Permission denial: deny mic → app warns, doesn't crash. Deny POST_NOTIFICATIONS → still streams.
- [ ] Logcat: after 2 min streaming + 2 edits, count `W/E` lines — report any repeated warnings (candidates: ProcessingChain stage params, unused-expression warnings).

## Findings report format (per item)

```markdown
### CARD / AREA — [Severity: Blocker|Major|Minor|Nit]
- Steps: ...
- Observed: ...
- Expected: ...
- Evidence: (log line / screenshot / ss output)
- Suggested fix (optional; do not implement)
```

Deliver the full report to the main session on return.