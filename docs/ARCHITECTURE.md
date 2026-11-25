# Architecture

How the repository is laid out and why. `AGENTS.md` has the rules; this is
the map.

## Layout

```
app/            RELAY (dev.relay)          own UI, own audio pipeline
cal/ dial/ gate/ list/ lore/ page/ reel/ tally/  the Field apps (dev.field.<name>)
field/          dev.field.ui               shared components, palette, formatting
build-logic/    convention plugins         field.app, field.android.application, field.library
gradle/libs.versions.toml                  every version number
scripts/        fetch-sherpa.sh            REEL's speech runtime and models (not in git)
docs/           this file, REVIEW.md, ROADMAP.md
.github/workflows/build.yml                tests + all APKs on every run
```

Dependencies point one way: an app depends on `field/`; `field/` depends
only on AndroidX. Apps never depend on each other.

## Inside a Field app

```
<app>/src/main/java/dev/field/<app>/
    <Logic>.kt        pure state and rules      -> unit tests in src/test
    <App>Store.kt     StateFlow + persistence
    MainActivity.kt   screen(s): collect state, send commands
    <X>Service.kt     only if work must outlive the screen
```

| App | Pure logic | Store | Service |
|---|---|---|---|
| CAL | `CalLogic.kt` | `CalendarRepo.kt` (system calendar provider) | none |
| DIAL | `Dial.kt` (`DialState`, `Dial`, `Playlist`) | `DialStore.kt` | `DialService.kt` (Media3 playback, media session) |
| GATE | `GateState.kt` | `GateStore.kt` | alarm + notification receivers in `GateStore.kt`; kit links in `GateLink.kt` |
| LIST | `Tasks.kt`, `TaskFile.kt` | `ListStore.kt` (lists as files in spaces) | `Reminders.kt` (alarm + receiver) |
| LORE | `Zim.kt` (archive reader), `Text.kt`, `Bm25.kt`, `Ask.kt` (retrieval, prompt), `Markdown.kt`, `Lore.kt` (catalogue, chats) | `LoreStore.kt` | none; `Llm.kt` wraps the model runtime, the system DownloadManager fetches the large files |
| PAGE | `Text.kt`, `Markdown.kt`, `Html.kt`, `Style.kt`, `PageState.kt`, `Sync.kt` (sync rules) | `PageStore.kt` (notes as files), `PageSync.kt` (sync runner; `Drime.kt` is the cloud client) | none |
| REEL | `Audio.kt` | `ReelStore.kt` | `RecorderService.kt`; `Transcriber.kt`, `ParakeetModel.kt`, `M4a.kt` |
| TALLY | `Calc.kt` | `LogStore` in `MainActivity.kt` | none |

## The shared library (`field/`)

- `Field.kt`: palette (`FieldColors`, light and dark), `fieldContent()`
  (theme entry point), and every component.
- `Fmt.kt`: number and clock formatting used in readouts.
- `Kit.kt`: the run-time links between the apps (focus sessions in GATE,
  text hand-offs). The library manifest lists the kit's packages so the
  apps may address each other.

The palette is snapshot state: switching `FieldColors.palette` redraws
everything that reads a tone, so components need no theme parameter.

## Build

Each module applies one convention plugin from `build-logic/` and states
only what is its own (namespace, application id, version, special
dependencies). SDK levels, Java target, Compose, R8, signing and the
`:field` dependency are defined once.

Release signing: with the `FIELD_KEYSTORE_*` secrets set, CI signs with a
stable key so new builds install over old ones; without them it uses the
debug key (installs, but not over a build signed elsewhere).

## Where state lives

| What | Where |
|---|---|
| Settings, small state | `SharedPreferences`, one JSON value per app |
| PAGE notes | plain `.md` / `.txt` files in a user-picked folder |
| REEL takes | audio files in app storage + an index |
| CAL events | the system calendar provider (not ours) |
| DIAL stations | JSON in preferences |
| LIST lists | one Markdown checklist per list, in app storage or user-picked folders (spaces) |
| LORE chats | one JSON file in app storage |
| LORE model and packs | large files in the app's external files folder (removed with the app); packs added from a file stay where they are |
| LORE notes index | rebuilt from the notes folder; a cache of the cut-up notes in app storage |

## How LORE answers

1. The question's words are tried as article titles in every archive on
   the shelf (`Ask.candidates`, `Zim.byTitle`: a binary search in the
   archive's own title list, so no index has to be built for 7 million
   articles). If none match, the model is asked to name articles.
2. The articles found are cut into passages and ranked against the
   question (`Bm25`); the notes index is searched the same way.
3. The best passages, within the model's context budget, go into the
   prompt as numbered sources (`Ask.prompt`), and are stored with the
   answer so they can be shown and opened.

`Zim.kt` is LORE's own reader for the ZIM format, written from the format
description: libzim and libkiwix are GPL and would not fit this
repository's licence.

## Testing

- Unit tests (JUnit 4, JVM): all pure logic. Run in CI on every build.
- LORE has three tests the JVM cannot fake: `ZimZstdTest` (real Zstandard),
  and `LiveTest`, which CI runs in its own step with the network: it asks
  Hugging Face and Kiwix whether every address in the catalogue exists and
  reads a real Kiwix archive end to end. `ModelLiveTest` runs two of the
  real models through `Llm.kt` on the build machine (LiteRT-LM's desktop
  build, Java 21); the Android build and phone GPUs are not covered.
- No instrumented tests are checked in. An emulator run in CI is possible
  and was used once to diagnose streams (see `docs/REVIEW.md`); making it a
  standing smoke test is on the roadmap.
