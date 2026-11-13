# Relay

A tiny Android app that moves whatever your phone is playing onto a Sonos speaker on your Wi-Fi. Your player app keeps playing as usual; the phone goes silent and the sound comes out of the Sonos.

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

## Licenses and trademarks

Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Relay is an independent project, not affiliated with or endorsed by Sonos, Inc. or teenage engineering AB. Sonos is a trademark of Sonos, Inc.
