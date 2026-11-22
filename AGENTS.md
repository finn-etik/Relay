# Working in this repository

Guidance for anyone changing this code, human or AI agent. `CLAUDE.md`
points here. Keep it short and true: if you change how the project works,
change this file in the same commit.

## What this is

A kit of small Android apps sharing one design language (the Field Design
Language), plus Relay. One Gradle build, one APK per app.

| Module | App | What it does | Accent |
|---|---|---|---|
| `app/` | RELAY | Streams the phone's audio to Sonos speakers. Own UI code, does not use `field/`. | orange |
| `cal/` | CAL | Calendar on the system calendar provider. | green |
| `dial/` | DIAL | Web radio (AndroidX Media3). | violet |
| `gate/` | GATE | Focus timer. | blue |
| `list/` | LIST | To-do lists as Markdown files in spaces (folders); shared through a synced folder. | magenta |
| `lore/` | LORE | Offline assistant: a small on-device language model (LiteRT-LM) that looks questions up in the notes folder and in Wikipedia / Wikibooks archives, and cites them. | cyan |
| `page/` | PAGE | Focused Markdown writing. | sage |
| `reel/` | REEL | Voice recorder with on-device transcription. | red |
| `tally/` | TALLY | Calculator. | amber |
| `field/` | (library) | Shared UI components and palette for all Field apps. | |
| `build-logic/` | (build) | Gradle convention plugins: the build setup the modules share. | |

More detail: `docs/ARCHITECTURE.md`. Why things are the way they are, and
every bug found so far: `docs/REVIEW.md`. What might come next:
`docs/ROADMAP.md`.

## Build, test, get an APK

```sh
./gradlew testDebugUnitTest     # unit tests, all modules
./gradlew assembleRelease       # all APKs (REEL first needs scripts/fetch-sherpa.sh)
./gradlew :dial:assembleDebug   # one app
```

Needs JDK 17 and the Android SDK (API 35). Without an SDK, use CI: the
`Build APKs` workflow runs the tests and builds every APK on any branch
(`gh workflow run build.yml --ref <branch>`; APKs are the `apks` artifact).
A push to `main` also publishes them to the `apks` branch.

## How an app is put together

Every Field app follows the same three layers. Keep to them.

1. **Pure logic**: plain Kotlin, no Android imports, one file or a few
   (`Calc.kt`, `GateState.kt`, `CalLogic.kt`, `Dial.kt`, `Text.kt`, ...).
   State is an immutable `data class`; a change is a function returning a
   new state. Time is passed in (`now: Long`), never read inside. Everything
   here has unit tests in `src/test`.
2. **Store**: one `object` (`GateStore`, `DialStore`, `PageStore`, ...)
   holding a `StateFlow` of that state and persisting it (JSON in
   `SharedPreferences`, or files). The only place that touches storage.
3. **Screen and services**: composables collect the store's state and send
   commands. Work that must outlive the screen (playback, recording,
   alarms) lives in a foreground `Service` that updates the same store.

Rules that follow from this:

- New behaviour goes into the pure layer first, with a test. If it cannot
  be unit-tested, ask whether it is in the right layer.
- Durations and clocks are derived from wall-clock instants, not from
  counters that tick, so they stay right while the screen is off.
- A screen never talks to a service's internals; both go through the store.

## UI rules (Field Design Language)

- Build screens from `field/` components (`FieldScreen`, `StatusHeader`,
  `RegisterPanel`, `FieldKey`, `FieldRow`, `FieldText`, ...). If a
  component is missing, add it to `field/`, not to one app.
- Colours come from `FieldColors` (`Body`, `Paper`, `Ink`, `Dim`, `Grid`,
  `Panel`, `OnPanel`) and `LocalAccent.current`. Never write a hex colour
  in an app. Text inside a `RegisterPanel` uses `OnPanel`, not `Paper`.
- Every app starts with `fieldContent(accent, accentOnDark) { ... }`, which
  follows the system light / dark setting. Check both.
- Labels are upper case, monospace, terse. One accent per app, used only
  for what is live, active or urgent.
- Inset with `padding`, not `offset`, when a child must stay inside its
  parent (an offset full-width box overflows; this was a real bug).
- Text that must stay on one line says so (`maxLines = 1`, and
  `softWrap = false` for fixed labels): users run larger system fonts.

### Screen layout: keep it calm

These came out of a review of every screen; they are the difference between
"works" and "easy to use". Follow them for new screens.

- **Opening something must not move the page.** The details of a tapped
  thing (a calendar entry, a take, a station, a task) and any editor go in
  `FieldScreen(sheet = ...)`, drawn over the bottom of the screen. Never
  insert a panel above the list the user is looking at.
- **The main action comes first.** Order a screen: status, the one or two
  things people open the app to do, the list of their things, then
  everything else. Settings never sit above the main action.
- **Fold away what is set once.** Settings, management and rarely used
  tools go in a `FieldFold`, closed by default, with the current state in
  its caption so it seldom needs opening.
- **Destructive keys are quiet.** DELETE is the last key in a sheet or
  fold, in the plain (Paper) variant, never `KeyVariant.Ink`, and it asks
  twice (the label changes to "TAP AGAIN TO DELETE ..."). It is never next
  to a key used often.
- **One scroll.** Nothing scrolls vertically inside the page's own scroll;
  a finger on a nested scroller cannot move the page. Show the part that
  matters at full height instead (CAL's day view shows only relevant hours).
- **What a field produced leaves with it.** Search results are cleared when
  the search text is, and get an explicit CLEAR key.
- **Captions say state, not instructions,** wherever there is state to say.

## Links between apps

Apps never depend on each other in Gradle. They hand off at run time through
`field/Kit.kt` (start / stop / read a GATE focus session; send text to
another kit app). A new link goes there, works when the other app is
missing (say so on screen), and carries no custom permission: the APKs may
be signed with different keys.

## Dependencies

AndroidX and Kotlin only, no other third-party libraries, and nothing that
needs Google Play services. Versions live in `gradle/libs.versions.toml`.
A new dependency also gets a line in `THIRD_PARTY_NOTICES.md` and a comment
at its declaration saying why it earns its place.

The exceptions, each because Android has nothing built in: sherpa-onnx
(REEL, speech recognition), LiteRT-LM (LORE, runs the language model) and
zstd-jni (LORE, decompresses Kiwix archives). No GPL code: LORE reads ZIM
archives with its own reader (`Zim.kt`) rather than libzim / libkiwix.

## Adding a Field app

1. New module directory with `build.gradle.kts` applying `id("field.app")`
   (copy `tally/build.gradle.kts`; it is twelve lines).
2. Add it to `settings.gradle.kts` and to the `Collect` loop in
   `.github/workflows/build.yml`.
3. Manifest, `res/` (launcher icon as an adaptive icon, `values` and
   `values-night` colours and launch theme): copy from `tally/`.
4. Pure logic + tests, store, screen, as above.
5. Add a row to the tables in `README.md` and this file, and pick an
   accent no other app uses.

## Style

- Kotlin official style, `.editorconfig` settings, lines up to 160.
- Comments explain why, or what is not obvious from the code. KDoc on
  every public type and on any function whose name does not say it all.
- No dead code, no commented-out code, no TODOs without an owner; open
  ideas go to `docs/ROADMAP.md`.
- Commit messages: imperative summary line, then what changed and why.

## Honesty about verification

Unit tests and a release build prove the pure logic and that everything
compiles. They do not prove a screen looks right or a service behaves on a
real phone. Say which of the two you have. When a claim depends on real
devices or real servers (a stream that will not play, a layout that
breaks), measure it (CI can run an Android emulator) before changing code.
