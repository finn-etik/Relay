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
repo secrets fixes updates for all eight apps permanently.

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

**5. More links between the apps.**
Built: REEL transcript to PAGE, CAL entry to a GATE session, PAGE sprints on
GATE's timer, LIST task to GATE and PAGE, text shared into PAGE and LIST.
Still open:
- CAL shows LIST tasks on their due date, and a LIST task can be planned as
  a CAL entry.
- A link shared to DIAL becomes a station.
- GATE's log names what the session was for (the CAL entry or LIST task).

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

**LIST**
- Repeating tasks.
- A DONE action on the reminder notification.
- Pick any date and time for due and remind (today there are presets).
- Reorder tasks by hand; move a task to another list.
- Live sync through a small server, if the synced-folder model proves too
  slow for two people.

**RELAY**
- Dark mode (comes with item 8 above).

**All apps**
- Move the text fields to Compose's state-based `BasicTextField`: the
  value-based one (used by `FieldInput` and PAGE's editor, with its
  `VisualTransformation`) is deprecated since Compose 1.13. It still
  works; the change touches PAGE's editor deeply and wants a phone to
  test on.

**LORE**
- Speak the question: REEL's speech recognition as an input key.
- Search by meaning as well as by words (a small embedding model) for
  questions that share no word with the article that answers them.
- Kit links in: ASK LORE from a PAGE selection or a REEL transcript
  (summarise, explain); the plumbing exists, LORE accepts shared text.
- A plain reader for the packs (browse Wikipedia by title) without asking
  the model anything.
- A provider in PAGE that tells LORE which folder the notes are in, so the
  folder does not have to be picked twice.

## New apps for the kit

Chosen for three things: an instrument-like purpose that suits the
language, heavy reuse of what exists, and no dependence on accounts or
Play services.

**TICK: clock.** Alarms, world clock, stopwatch, countdown. The most-used
utility the kit lacks, and GATE already has exact alarms, the boot receiver
and the notification pattern.

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

Suggested order: TICK (highest daily use, lowest risk), then SPOT or GAUGE.
