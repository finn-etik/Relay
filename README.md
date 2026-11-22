# Relay (and the Field apps)

Relay is a tiny Android app that moves whatever your phone is playing onto a Sonos speaker on your Wi-Fi. Your player app keeps playing as usual; the phone goes silent and the sound comes out of the Sonos.

- One screen, one foreground service. No Material library and no third-party code: SSDP discovery, Sonos SOAP control, AAC encoding and the HTTP stream are all hand-rolled.
- Sound plays **only on Sonos** while streaming: the phone's media volume is muted for the session and restored on stop, even after a crash.
- Teenage Engineering inspired UI: field-gray body, ink rules, signal-orange LEDs, monospace labels, keys that invert when pressed.
- **Quick Settings tile**: tap, approve the capture dialog, and you're dropped back into your player while Sonos takes over. Tap again to stop.
- **One entry per room**: discovery reads the household topology, so stereo pairs, surrounds, subs and Bridges never appear as separate or broken targets. Grouped rooms are listed under their group coordinator.
- **Switch speakers live**: tap another room while on air; the stream moves without another consent dialog.
- **Resilient**: if the speaker drops the stream (reboot, Wi-Fi blip) Relay re-issues Play. If you choose another source in the Sonos app, Relay hands the speaker over and ends the session instead of fighting you for it.
- **Volume keys control the Sonos** (remote MediaSession), plus on-screen keys with a live 000-100 readout synced to the speaker's real level.
- **Private by default**: the stream URL carries a random per-session token; anything else on the LAN gets a 403.
- **Works with mobile data or a VPN on**: Sonos traffic is pinned to the Wi-Fi network.

## Field apps in this repo

Besides Relay, the repo holds seven apps in the same Field Design Language,
sharing one component library (`field/`). Each app owns one signal colour.
All seven follow the phone's light / dark setting (PAGE can also be pinned
to either).

| App | What it is | Accent |
|-----|------------|--------|
| **CAL** (`cal/`) | Calendar on the phone's real calendars (Google, DAVx5, local). Month grid, week strip, 24 h day timeline with a NOW line and LIVE entries, add / edit / delete, quick add (`14:30 DENTIST`). Creates an on-device "FIELD" calendar if the phone has none. | green |
| **DIAL** (`dial/`) | Web radio on AndroidX Media3 (MP3, AAC, FLAC, HLS; `.pls` / `.m3u` links are followed), showing the programme or track the stream announces. Saved stations, tap twice to tune; search the [Radio Browser](https://www.radio-browser.info/) catalog and tap a result to save it. Keeps playing with the screen off or another app in front (foreground service with a media session: play / pause, stop, next and previous station from the lock screen, the notification and headset or Bluetooth buttons); yields to calls and other players, pauses when headphones are unplugged. Volume and mute, sleep timer, airtime and total listening time, auto-reconnect that backs off from 3 s to a minute. | violet |
| **GATE** (`gate/`) | Focus timer. Presets 5/10/25/50, hold/resume, log. Ends on time with the app closed (exact alarm), lock-screen countdown. | blue |
| **LIST** (`list/`) | To-do lists, personal and shared. Lists are Markdown checklists grouped into spaces (folders); a space whose folder a sync app keeps identical on two phones is shared, and conflict copies are merged without losing tasks. Due dates, reminders, steps, important, MY DAY and PLANNED views. Quick add reads `call Eva fri !`. | magenta |
| **PAGE** (`page/`) | Focused writing in Markdown. Focus mode (sentence or paragraph), typewriter scrolling, ZEN (keys hidden), light and dark paper. STYLE check marks filler words, adverbs, passive voice and long sentences (English, on-device). Daily word goal with streak (or goal off: a plain counter), timed sprints. Notes are plain .md / .txt files, autosaved into a folder you pick, so other apps and sync tools can open and move them. Import, share, export .md / .html / .txt, PDF through the print dialog. No permissions. | sage |
| **REEL** (`reel/`) | Voice recorder (AAC .m4a) with on-device transcription by NVIDIA Parakeet TDT 0.6B v3: 25 European languages, detected automatically. Live while recording, or afterwards for any take. One-time ~670 MB model download inside the app (Wi-Fi by default), checksum-verified, then fully offline. | red |
| **TALLY** (`tally/`) | Calculator with scientific panel, DEG/RAD, percent convention, log. | amber |

Every push to `main` builds all eight APKs; they appear under the run's
artifacts and on the `apks` branch. To build locally, run
`scripts/fetch-sherpa.sh` once first (REEL's speech runtime is not committed).

## How it works

```
Your player app (any app that allows audio capture)
        |  plays normally; phone media volume set to 0
        v
Relay foreground service
        |  AudioPlaybackCapture (MediaProjection consent)
        v
44.1 kHz 16-bit stereo PCM -> AAC-LC 256 kbit/s (platform MediaCodec)
        |
        v
ADTS over HTTP from the phone: http://<wifi-ip>:8079/stream.aac?t=<token>
        |
        v
Sonos (SetAVTransportURI x-rincon-mp3radio://... + Play)
```

Capture is taken at unity gain on Android's capture path, independent of the speaker volume, which is why the phone can be muted while Sonos still receives full-level audio.

## Code map

All code is in `app/src/main/java/dev/relay/`.

| File | What it does |
|------|--------------|
| `MainActivity.kt` | Permissions, the capture-consent dialog, starting/switching sessions |
| `RelayUi.kt` | The whole UI (Compose foundation, no Material) |
| `RelayTile.kt` | Quick Settings tile |
| `RelayService.kt` | Foreground service: wires one session together and tears it down |
| `CapturePipeline.kt` | Playback capture -> AAC encoder -> stream server |
| `AacEncoder.kt`, `Adts.kt` | MediaCodec AAC-LC encoding and ADTS framing |
| `StreamServer.kt` | Tiny HTTP server for the live stream, token-protected |
| `SonosControl.kt` | Sonos UPnP/SOAP commands and topology parsing |
| `SonosDiscovery.kt` | SSDP discovery |
| `ReconnectWatchdog.kt` | Reconnects a dropped speaker; steps aside if the user picks another source |
| `SonosVolumeKeys.kt` | Phone volume keys -> Sonos volume |
| `PhoneVolume.kt` | Mutes the phone and always restores it |
| `Net.kt` | Finds the Wi-Fi address and pins Sonos traffic to Wi-Fi |
| `RelayState.kt`, `Prefs.kt`, `SonosSpeaker.kt` | Shared state, settings, data type |

Unit tests for the protocol and networking parts are in `app/src/test/`.

## How the apps work together

The apps hand work to each other when both are installed (`field/Kit.kt`):

- **One timer.** GATE is the kit's clock. A PAGE sprint is a GATE session;
  CAL starts one as long as a calendar entry (FOCUS); LIST starts one for a
  task. A session begun anywhere counts words in PAGE.
- **Text travels.** A REEL transcript becomes a PAGE note (PAGE key). Text
  shared to PAGE becomes a note; text shared to LIST becomes a task, its
  further lines the steps. A LIST task opens as a PAGE note.

## Working on the code

- [AGENTS.md](AGENTS.md): how the apps are built and the rules to follow (for people and AI agents alike).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the map of modules, layers and the build.
- [docs/REVIEW.md](docs/REVIEW.md): what was found and fixed, and why.
- [docs/ROADMAP.md](docs/ROADMAP.md): proposed features and new apps.

## Build

The project ships with the Gradle wrapper. You need JDK 17 and the Android SDK (Android Studio provides both).

**Android Studio:** open the `Relay` folder, let Gradle sync, plug in the phone with USB debugging, press Run.

**Command line:**

```bash
./gradlew assembleRelease          # minified (a couple of MB), signed with your debug key
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # networking/protocol unit tests
```

**No Android tooling at all? Let GitHub build it.** Push the folder to a (private) GitHub repo. The included workflow (`.github/workflows/build.yml`) runs the tests and builds the APK on every push: open Actions, then the latest run, then download `relay-apk`. Each CI run signs with a fresh debug key, so to install a newer CI build over an older one, uninstall first (or add your own keystore).

**GrapheneOS:** nothing special. It's a plain sideloaded APK with no Google dependencies. Make sure Relay's Network permission is on.

## First run

1. Open Relay. Your speakers appear by room name (tap RESCAN if you changed networks).
2. Tap a room, grant the microphone permission (the capture API requires it), then approve the capture dialog. The phone goes silent and the Sonos plays whatever your player plays.
3. Add the tile: Quick Settings, edit, drag "Relay to Sonos" into place.
4. From then on: tap the tile, approve, done. Tap it again to stop; your phone volume comes back exactly as it was.

## Battery design

- **Radio first:** AAC 256 kbit/s (~32 KB/s) instead of 1.4 Mbit/s raw PCM; the Wi-Fi radio is the biggest power draw.
- **Encode on demand:** the encoder only runs while a speaker is connected; otherwise captured PCM is discarded.
- **Hardware encoder:** MediaCodec uses the platform AAC encoder.
- **No polling:** UI and tile observe state flows; nothing runs when nothing changes. The only timers are the 1 Hz session readout (only while visible) and a 5 s watchdog that does nothing while the stream is healthy.
- **Scoped locks:** a wake lock with a rolling 10-minute timeout (it can never leak), a multicast lock only during discovery, and an opt-in Wi-Fi lock (off by default; enable it if you hear dropouts).

## Known limits

- **Consent every session.** Android requires the capture dialog each time; it can't be removed.
- **Apps can opt out of capture.** Spotify and most DRM apps block it; local players and most indie apps don't. Silent Sonos while your player shows playback means the app opted out.
- **Latency 1-4 s.** Sonos buffers HTTP streams like radio. Fine for music, not for video.
- **Lossy.** AAC-LC 256k is near-transparent. For more headroom set `BIT_RATE` to 320_000 in `CapturePipeline.kt`.
- **Volume-key routing** follows Android's active-session rules; if your player grabs it back, use the on-screen keys. Relay's media session may also receive headset play/pause presses while streaming.
- **Same subnet required.** Guest Wi-Fi with client isolation blocks it. A VPN in "block connections without VPN" lockdown mode blocks it too.
- **Port 8079** must be free on the phone.

## License

Relay is licensed under the [Apache License 2.0](LICENSE). Copyright 2026 finn-etik.

Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Relay is an independent project, not affiliated with or endorsed by Sonos, Inc. or teenage engineering AB. Sonos is a trademark of Sonos, Inc.
