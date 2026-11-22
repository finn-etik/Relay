# Roadmap

Proposals, not commitments. Nothing here is built. Each item says what it
is and why it is worth doing. Order within a section is by value for effort.

## For every app

**1. On-device smoke tests in CI.**
Every bug reported from a phone so far (CAL's overflowing timeline, wrapped
hour labels, the light panel in dark mode, DIAL's silent SR streams) passed
unit tests and a release build, because none of those run a screen or a
real stream. CI can boot an emulator (it was done once to diagnose DIAL).
A standing job that opens each app in light, dark and large-font and saves
screenshots would catch this class before it reaches a phone.

**2. One signing key, set up once.**
Without the `FIELD_KEYSTORE_*` secrets each build is signed with a throwaway
debug key, so a new APK may refuse to install over the old one and
uninstalling loses the app's data. Creating one keystore and adding four
repo secrets fixes updates for all seven apps permanently.

**3. Backup and restore.**
Each app writes its data (stations, focus log, register tape, settings,
take index) to one JSON file in a folder you choose, and reads it back.
Sideloaded apps have no cloud backup; a phone change or a signing-key
change currently means starting over. PAGE's folder already shows the
pattern.

**4. Quick Settings tiles and home-screen widgets.**
Relay already has a tile. The same for GATE (start / stop), REEL (record),
DIAL (play / pause), plus small widgets: today's agenda (CAL), countdown
(GATE), now playing (DIAL). These are instruments: the most-used action
should not need the app opened. Tiles are framework-only and cheap;
widgets are a larger step.

**5. The apps as a kit, not seven islands.**
- REEL transcript -> "SEND TO PAGE" (a note file in PAGE's folder).
- CAL entry -> "FOCUS" starts a GATE session of that length.
- PAGE sprint and GATE session share one timer rather than two.
- Share targets: text shared to PAGE becomes a note; a link shared to DIAL
  becomes a station.
This is what makes a suite worth more than its parts, and it is mostly
intents between apps we already control.

**6. Accessibility pass on `field/`.**
The components are drawn from primitives, so they carry little semantic
information: keys may not announce as buttons, LEDs carry state by colour
alone, toggles lack on / off state for TalkBack. Fixing it once in `field/`
fixes all apps. It is also what "professional" means for an app other
people might use.

**7. A manual LIGHT / DARK / AUTO switch everywhere.**
PAGE has one; the others only follow the system. One shared settings row in
`field/` gives every app the same control.

**8. RELAY onto the shared library.**
Relay draws its own copy of the design language, so it has no dark mode and
every design fix has to be made twice. Moving its screen onto `field/` ends
that. Needs a real Sonos setup to verify, hence not done blind.

**9. Text as resources.**
All labels are hard-coded English strings. Moving them to `strings.xml`
makes Swedish (or any) translation possible and makes the wording
reviewable in one place.

**10. Lint and formatting as CI checks.**
Android Lint and a Kotlin formatter (ktlint) as a required step, so style
and common mistakes are caught by the machine rather than by review. Left
out so far because turning them on needs one pass to fix what they find.

## Per app

**CAL**
- Reminders: a notification before an entry starts. A calendar that cannot
  remind is half a calendar.
- Week view between DAY and MON.
- Editing one occurrence of a repeating entry (today the series is all or
  nothing).
- Search across entries.

**DIAL**
- Favourites row and "recent", so the daily two or three stations are one
  tap. Reordering exists, but the list grows.
- Quality choice when a broadcaster offers several (SR has 32 / 128 / 320
  AAC, MP3 and FLAC): low on mobile data, high on Wi-Fi.
- A built-in Sveriges Radio list from SR's open API (all 50-odd channels by
  name), rather than hoping the catalog search finds the right link.
- Cold start from a headset button (the last gap in outside control).

**GATE**
- Work / break cycles (25 / 5 with a long break), not just single sessions.
- Do Not Disturb while a session runs.
- Week and month totals; the data is already logged.

**PAGE**
- Outline: a list of headings to jump between in long documents.
- Find and replace.
- Snapshots: keep versions of a note and restore one.
- Hardware-keyboard shortcuts (bold, heading, focus toggle) for tablets.
- Style check in Swedish, or switchable per note.

**REEL**
- Name a take; today they are numbers.
- Search inside transcripts.
- Markers while recording ("this bit matters") that show in the transcript.
- Trim the start and end.

**TALLY**
- Unit and currency conversion.
- Share or export the register tape.
- Memory keys (M+, MR) for multi-step sums.

**RELAY**
- Dark mode (comes with item 8 above).

## New apps for the kit

Chosen for three things: an instrument-like purpose that suits the
language, heavy reuse of what exists, and no dependence on accounts or
Play services.

**TICK: clock.** Alarms, world clock, stopwatch, countdown. The most-used
utility the kit lacks, and GATE already has exact alarms, the boot receiver
and the notification pattern.

**LIST: tasks.** Checklists as plain Markdown files (`- [ ]`), in the same
folder model as PAGE, so PAGE can open them and other apps can sync them.
Completes the CAL / GATE / PAGE planning loop: what, when, do, write.

**SPOT: field log.** Tap-to-count tallies with time and optional location,
saved as CSV: bird counts, inventory, traffic surveys, lap counting. A
counter is the purest "field instrument" there is, and nothing in the kit
records observations yet.

**GAUGE: sensors.** Compass, level, barometer and altitude, light meter,
sound level. Each is a readout with an LED, which is exactly what the
design language draws best. Framework sensors only, no network, small.

**VANE: weather.** Forecast as an instrument panel (temperature, wind,
rain in the next hours) from an open service such as SMHI or MET Norway,
no key or account. Pairs with CAL (weather on the day) and SPOT.

**TRAIL: track recorder.** Records a walk or ride as a GPX file, with
distance, time and pace. Reuses REEL's foreground-service recording pattern
and PAGE's plain-files-in-a-folder model.

**POD: podcasts.** DIAL's player and service with a feed list instead of a
station list; includes Sveriges Radio's programme archive through the same
open API. The second half of "radio".

Suggested order: TICK (highest daily use, lowest risk), then LIST, then
SPOT or GAUGE.
