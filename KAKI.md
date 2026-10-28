# KAKI — Notes & Discussion (audiology2)

Working notes between user and assistant. Latest first.

## 2026-10-28 — Session 1 close

- **Sweep handoff:** user is doing the read+run-only sweep in a free session (`SWEEP_PLAN.md`), returns findings, we implement together in this session.
- **⏰ PENDING user note:** user explicitly asked to be reminded to tell the assistant **about the API field in the Assistant card**. Ask at the start of the next session.
- **Local LLM is dead on this box (measured):** N100/16GB always loaded; phi3 did 48 tokens in 135s at load 15.3. Do not run local inference unless user explicitly asks. Models stay parked on disk (phi3 2.2GB, llama3 4.7GB); unload with `ollama stop <model>`.
- **Assistant default = OpenRouter free** (`openai/gpt-oss-120b:free`); user already has the key stored encrypted. Groq free tier is the better-quota fallback (1,000/day vs OpenRouter's 50/day).
- **TCP "massive delay" root cause found + fixed:** phone's `StreamSender` never drained its receive side → server replies collapsed the TCP window (Send-Q 36→57KB observed live). Fix: `tcpNoDelay` + daemon drain thread (`0a736e9`). Always keep the drain.
- **Hosts are NOT a singleton.** `~/amp` = AMP server (AudioReceiver :8090, :8093 web/viz) — ONE receiver. `~/audioscope` = a SEPARATE active project (Audio Microscope: event detection/source separation/evidence, Python 3.10+, consumes a network audio stream or mic). The phone's audio loop may be phone → AMP → audioscope. Check `~/audioscope/README.md`/`TODO.md` before assuming which host matters. Do not conflate them.
- **Recording mystery:** user's device recording was 26s of *perfect digital zeros* with a valid header (44100/16/mono). Writer/reader verified correct locally (57 tests incl. bit-exact range reads on the real artifact). Sweep item: instrument capture start (actual `AudioRecord.getSampleRate()`, first-block RMS) to bisect device-input vs runtime bug.
- **Rules locked:** never erase/move files (SRP pack + dead files stay); flag-and-report pattern for unused code; smallest green commits; push each logical unit.
- Fun fact: even mid-thrash, phi3 returned the exact expected `{"intent":"set_tempo","args":{"bpm":96}}` JSON — the tool contract is solid on any brain that can type.

## Decisions recorded this session (see also AGENT_PASSDOWN.md §3)

- Persistence: DataStore write-through (not Room — Room file stays but un-wired, user decides).
- DSP: in-process only; no network DSP host.
- MIDI: custom Canvas piano-roll/step view (no third-party libs).
- Spectral: STFT/ISTFT engine + rectangular mask brush; strongest gradient stop = hunter orange #FF4F00.
- Assistant: strict-JSON intent contract (provider-agnostic), encrypted key, OpenRouter default.