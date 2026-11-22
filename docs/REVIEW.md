# Review notes (v1.0 -> v1.1)

## Blockers: v1.0 would not have compiled or worked

| # | Problem | Effect | Fix |
|---|---------|--------|-----|
| 1 | `AudioPlaybackCaptureConfiguration(proj).addMatchingUsage(AudioPlaybackCaptureConfiguration.USAGE_MEDIA)` | Compile error: there is no public constructor or such constant | `AudioPlaybackCaptureConfiguration.Builder(proj).addMatchingUsage(AudioAttributes.USAGE_MEDIA)...build()` |
| 2 | `WifiManager.WIFI_MODE_WIFI_LOW_LATENCY` | Compile error: constant doesn't exist | `WIFI_MODE_FULL_LOW_LATENCY` + `WIFI_MODE_FULL_HIGH_PERF` (the one that matters with the screen off) |
| 3 | No `usesCleartextTraffic` | Android blocks plain HTTP since targetSdk 28: every Sonos call (port 1400) failed silently | `android:usesCleartextTraffic="true"` |
| 4 | Discovery called inside `LaunchedEffect` (main thread) | `NetworkOnMainThreadException`, swallowed by the catch: always "NO SONOS FOUND" | Runs on `Dispatchers.IO` |
| 5 | `"x-rincon-mp3radio://" + "http://ip..."` | Malformed `x-rincon-mp3radio://http://...`: Sonos can't open it | Scheme *replaces* `http` |
| 6 | Watchdog thread `interrupt()` during `Thread.sleep` | Uncaught `InterruptedException` crashed the app on every Stop, possibly before the phone volume was restored | Caught; teardown reordered |
| 7 | Encoder released while the capture thread was still inside `encode()` | `IllegalStateException` on a raw thread: crash on stop/switch | Stop recorder, join capture thread, then release codec |

## Correctness and robustness

- **Wrong IP handed to Sonos.** "First IPv4 of any interface" could be the cellular (`rmnet`) or VPN (`tun0`) address. The Wi-Fi address now comes from `ConnectivityManager`, and Sonos traffic is pinned to that network (falling back to the default route if pinning is refused).
- **Stereo pairs, surrounds, subs, grouped rooms.** SSDP answers from every box, so pairs showed up twice and secondary or grouped speakers rejected the stream (UPnP error 800). Discovery now reads ZoneGroupTopology: one entry per group, addressed to the coordinator, hidden members and Bridges excluded. It also returns as soon as the first speaker answers instead of always waiting 4 s.
- **Speaker switch killed the new session.** The old projection's `onStop` callback fired asynchronously after the new session started and called `stopSelf()`. Callbacks are now unregistered first, and switching no longer needs a new projection at all.
- **Tile auto-start ignored when the app was alive** (`singleTask` delivers to `onNewIntent`), and **re-triggered on rotation** (a second consent dialog). Both fixed; consent state survives rotation.
- **Stale saved IP after DHCP changes:** the saved speaker carries its UUID and is re-resolved against the live scan.
- **Phone left muted after a crash or force-stop.** The saved volume is persisted (`commit()`) and restored on next launch.
- **Port rebind race:** closing a `ServerSocket` with a blocked `accept()` frees the port asynchronously; restart could hit `EADDRINUSE`. Found by the new unit test; fixed with `SO_REUSEADDR` plus a short bind retry.
- **A stalled speaker blocked audio capture**: `broadcast()` wrote to sockets on the capture thread. Each listener now has its own bounded queue and writer; a listener more than ~6 s behind is dropped and reconnects.
- **SOAP ordering races:** each command spawned a thread, so a Stop could land after the next Play. Commands now run on one serial queue.
- **Watchdog fought the user:** it re-issued Play every 12 s even after you picked another source or paused in the Sonos app. It now checks what the speaker is doing first: it waits if the speaker is unreachable, leaves it alone if paused, and hands over (ending the session, no Stop sent) if another source is playing.
- **Volume desync:** panel keys used relative volume while the key provider tracked its own guess. Both now go through `SetRelativeVolume` and display the speaker's real resulting level.
- **`ForegroundServiceDidNotStartInTime`** risk on early-return paths: `startForeground()` is now called first on every start path.
- **XML unescape** replaced `&amp;` first (double-unescaping); fixed order.
- Request-header reading is capped (8 KB, 5 s timeout), connections are capped at 4, `HEAD` is handled, and the token is compared as an exact query parameter (`t=secretX` no longer passes a `contains()` check). The token is 96-bit `SecureRandom` instead of a truncated UUID.
- Wake lock: rolling 10-minute timeout refreshed by the watchdog instead of a fixed 6 h that could expire mid-session.

## Lighter / nicer

- Dropped Material 3: the UI uses Compose foundation `BasicText`, and R8 shrinking is on for release.
- State flows replace 1.5 s polling in the UI; the tile follows the session live while the shade is open.
- RESCAN key, error/status line, live Sonos volume readout, keys that invert when pressed, a scrollable speaker list.
- Launcher icon (adaptive + themed) and a window background matching the body (no black flash); dark status-bar icons forced so they stay visible in system dark mode.
- After a tile-initiated start, Relay moves itself to the back so you land in your player.
- `Locale.ROOT` readout formatting (no "0,5 MB" on phones set to a comma-decimal language).
- Gradle wrapper, unit tests, GitHub Actions APK build.

## Verified here

- `SonosControl`, `StreamServer` and `Adts` compiled with Kotlin 2.0.21 and passed 17 JUnit tests (fake Sonos SOAP endpoint, real sockets): SOAP envelopes, metadata fallback, connector fallback, topology parsing, token checks, HEAD, stalled-listener drop, port rebind, ADTS bits.
- Android-dependent files were syntax-checked only. The Android SDK and Google Maven were unreachable from the build sandbox, so the first full `./gradlew assembleRelease` (locally or via the included workflow) is the remaining check.

# v1.2: readability and publication cleanup

- `RelayService` (560 lines doing five jobs) split into focused classes:
  `CapturePipeline`, `PhoneVolume`, `SonosVolumeKeys`, `ReconnectWatchdog`,
  `Prefs`. The service now only wires a session together and tears it down.
- `ReconnectWatchdog` is plain JVM code and now has its own tests
  (replay after a drop or reboot, leave a paused speaker alone, hand over
  to another source). 21 tests in total.
- Consistent naming (`showMessage`, `startSession`/`endSession`), imports
  instead of inline fully-qualified names, one style for ignored exceptions.
- Removed a template packaging rule that stripped JNA's license files; Relay
  doesn't use JNA, so the rule did nothing.
- Added THIRD_PARTY_NOTICES.md, trademark disclaimer, code map in the
  README, and Gradle wrapper validation in CI.

# Field apps: review of the supplied sources

**Shared:** the four sources each pasted their own copy of the Field
components; they now share one library (`field/`) with the accent supplied
per app. LED and key modes are enums instead of strings (a typo used to fall
through to "slow blink" silently). All screens now draw edge-to-edge with
safe-area padding: targetSdk 35 forces edge-to-edge, so the originals would
have run under the status bar.

**TALLY**
- Compile error: the keypad passed `when (label) { "CLR" -> { clr() } ... }`
  as `onPress`, which runs the action during composition and passes `Unit`.
- `200+10%` gave 200.1, not 220: `term()` dropped the percent flag.
- `2e3` meant 2000 although `e` is a key for Euler's number; only uppercase
  `E` (as the display writes it) is now an exponent.
- `sin(180)` showed 1.22E-16 and `tan(90)` a huge number: trig noise is
  snapped to 0 and tan at 90° is DOMAIN.
- Log and input survive restarts and rotation.

**GATE**
- The readout never moved: the 1 s `tick` was written but never read, so
  nothing recomposed.
- The session only ended (and beeped) if the app stayed open on screen. It
  now ends from an exact system alarm, shows a lock-screen countdown, survives
  reboot, and logs persistently. The beep uses the alarm stream, so it is
  audible even while Relay has muted media.

**REEL**
- Compile error: `ClipboardManager` was used without an import.
- Same frozen-readout bug as GATE.
- Live transcription ran SpeechRecognizer on the microphone while
  MediaRecorder held it; most phones give one of them silence. That is the
  limitation the Parakeet build removes: one AudioRecord now feeds both the
  file and the recognizer.
- Recording stopped (went silent) in the background: it now runs in a
  microphone foreground service with HOLD/STOP in the notification.
- The takes list was lost on restart; transcripts could not be made for old
  takes; there was no way to get a recording out of the app (SHARE added).

**CAL + Calendula** (merged as CAL)
- Calendula ran on mock agendas and used Relay's orange accent.
- CAL stored events in its own SharedPreferences and wrote state during
  composition (`idCounter = ...` inside `remember`).
- Now on the phone's CalendarContract calendars, with recurring events,
  all-day events read in UTC (no off-by-one-day west of UTC), overlap lanes,
  and confirm-to-delete. Recurring and read-only events are not editable here.

# Field apps: dark mode and second review

**Shared (`field/`)**
- The palette is switchable (`FieldColors.palette`, light and dark) and apps
  start through `fieldContent(...)`, which follows the system setting and sets
  palette, system-bar icons and accent before the first frame. Blue and red
  have lifted variants for text on the dark body.
- The register card has its own tones (`Panel` / `OnPanel`): ink on the light
  body, a raised dark card at night instead of an inverted light one.
- `FieldScreen` promised a column "at most 420 dp wide" but applied
  `fillMaxWidth()` before `widthIn(max = 420.dp)`, which fixes the width
  first, so the cap never applied on tablets or in landscape. Order swapped.

**CAL**
- All-day entries were compared with the clock as UTC instants, so in UTC+1
  an all-day entry was "LIVE" from 01:00 to 01:00 the next day. They are now
  judged by calendar day and never blink.
- Editing a multi-day all-day entry saved it as a single day on whichever
  day it was tapped. The editor now keeps the first day and the span.
- Quick add takes a range for the length: `14:30-16:00 WORKSHOP`.

**GATE**
- Session length can be fine-tuned between the presets (−5 / −1 / +1 / +5,
  1 to 180 minutes).
- The header shows today's completed focus minutes; older log rows carry
  their date.

**REEL**
- TXT is off during any recording: a file job shares the live session's
  voice-activity detector and reset it mid-take.
- DEL is off for a take while it is being transcribed (the file was deleted
  under the decoder).
- The readout shows the playback position; take rows show when they were
  recorded.

**TALLY**
- An operator typed after an operator replaces it (`5+` then `×` is `5×`);
  `−` after × ÷ ^ stays a sign. A number takes one decimal point. A line
  cannot start with × ÷ + ^.
- `(−8)^0.5` reported RANGE; it is DOMAIN.
- ADVANCED PANEL and RAD MODE survive a restart. Tap the register to copy
  its value.

# DIAL joins the pack (from finn-etik/dial 1.1)

Moved into `dial/` as `dev.field.dial` (was `com.field.dial`), on the shared
`field/` components instead of its own copies, with the pack's release
build (R8, optional stable signing) and dark mode. minSdk is 26 like the
rest (was 24).

**Brought to the level of the other apps**
- Playback moved out of the screen into `DialService`, a foreground service
  of type mediaPlayback. In 1.1 the MediaPlayer lived in the composable:
  Android could stop the stream once the app was in the background, and a
  rotation was only survivable by blocking activity recreation.
- The service asks for audio focus (stops for another player, goes silent
  for a call or navigation prompt and returns), stops when headphones are
  unplugged, holds a CPU wake lock and a Wi-Fi lock only while a stream is
  wanted, and shows a notification with STOP.
- State is one pure `DialState` with unit-tested transitions, held in
  `DialStore` (the GATE pattern). Listening time is derived from wall-clock
  instants instead of a one-second counter, so it is right with the screen
  off and is no longer written to preferences every second.
- Launcher icon: 1.1 used a plain 48 dp vector as the icon, which launchers
  shrink onto a white plate. It is now an adaptive icon (with a themed
  monochrome layer) like the other apps, plus a status-bar glyph.

**Bugs**
- The screen drew under the status and navigation bars (targetSdk 35 forces
  edge-to-edge and the layout had no inset padding). `FieldScreen` handles it.
- TUNE while one station played and another was selected stopped playback
  instead of tuning the selected one.
- The retry counter was reset by the re-tune itself, so "RETRY 01" never
  counted up, and a dead stream was retried every 3 s forever. Retries now
  back off (3 s doubling to a minute), keep the count and keep the
  session's airtime.
- A stream that simply ended (no error) left the clock running on a silent
  player. It now counts as a drop.
- Callbacks from a player that had already been replaced or stopped could
  flip the state of the new one. They are ignored.
- Search: an error status from one mirror was reported as "no streams
  found" without trying the others; "unreachable" is now told apart from
  "nothing found". Typed stations are checked for duplicates like search
  results were.

**Small features**
- Sleep timer (15 / 30 / 60 minutes).
- Reorder stations (UP / DOWN on the selected one).
- Search results already in the list are marked SAVED.

**Media session (lock screen and headset control)**
- `DialService` publishes a framework `MediaSession` and a media-style
  notification: play / pause, stop, next and previous station work from the
  lock screen, the notification shade and headset or Bluetooth buttons.
- A live stream cannot be paused in place: PAUSE drops the connection and
  PLAY reconnects to the same station. The paused service waits ten minutes
  for PLAY, then shuts down.
- Losing audio focus to another player, or unplugging headphones, now
  pauses (resumable) instead of stopping.
- Next / previous walk the saved station list and wrap round.

**Not done**
- A headset button does not start DIAL cold: once the service has shut down
  (STOP, or ten minutes paused) playback starts from the app again.

# DIAL: Sveriges Radio did not play

Measured on an Android 14 emulator in CI, the same streams through both
players:

| Stream | Android MediaPlayer | Media3 ExoPlayer |
|---|---|---|
| SR AAC (`live1.sr.se/p1-aac-320`, `-128`, `-32`, P3, the `topsy` AAC link) | never finishes preparing | plays |
| SR MP3 (`live1.sr.se/p1-mp3-96`, `srapi/132.mp3`) | plays | plays |
| SR FLAC (`live1.sr.se/p2-flac`) | error | plays |
| SomaFM, Radio Paradise (MP3) | plays | plays |

The servers were checked first and behave correctly (clean ADTS AAC, 48 kHz,
redirect from `live1` to `edge1/2`, metadata only when asked for). The
catalog search returns the AAC links for Sveriges Radio, which is why "all
of SR" appeared dead. And because the old player never reported an error
for them, DIAL sat in CONNECT forever instead of retrying.

- DIAL now plays through Media3 ExoPlayer, which also takes over audio
  focus, the unplugged-headphones pause and the wake / Wi-Fi locks that were
  hand-written before.
- The stream's own title (SR sends the programme name, music stations the
  track) is shown in the register, the notification and on the lock screen.
- `.pls` and `.m3u` links, which is what Sveriges Radio's own "direktlänkar"
  page hands out, are opened and the stream inside is played. A link that
  turns out not to be audio is tried once as a playlist.

# CAL timeline and app name

- The hour grid and the NOW line were full-width boxes shifted right by an
  offset, so they ran past the card's right edge. They are inset with
  padding now.
- Hour labels wrapped onto two lines with a larger system font. They are
  single-line and the column is wider.
- Relay's launcher name is RELAY.

# Kit links and LIST

**Links (`field/Kit.kt`).** The apps hand off at run time, never in the
build. GATE exposes a receiver (start / stop) and a read-only provider (the
running session); CAL, LIST and PAGE use them. PAGE and LIST accept shared
text. The links carry no permission on purpose: a signature permission
would make installs fail whenever two of the apps were signed with
different keys, which is the normal case for CI debug builds. What is
exposed is a focus timer and its countdown.

**PAGE sprints.** With GATE installed a sprint is a GATE session that PAGE
follows once a second (`Goals.followFocus`): it joins a session started
anywhere, moves its end when GATE is held and resumed, logs the words at
the end and drops the sprint if GATE is stopped early. Without GATE, PAGE
times the sprint itself as before.

**LIST.** Lists are Markdown checklists (`TaskFile`), one file per list,
grouped into spaces (app storage, or folders the user picks). Sharing is a
folder that a sync app keeps identical on two phones. Because files then
change behind the app's back:
- every edit re-reads the file, applies one change by task id, writes back;
- conflict copies left by the sync tool are merged into the original on the
  next read (`TaskFile.merge`: nothing is dropped, ticked wins);
- tasks carry a short `id:` so both of the above can match them.
MY DAY picks are kept on the phone, not in the file, so one partner's day
does not appear in the other's. Reminders use one alarm for the next
reminder plus an hourly look-in when a shared space is connected.

Not verified on a device: the folder picker with a real sync app, reminder
timing, and the links between installed apps.

