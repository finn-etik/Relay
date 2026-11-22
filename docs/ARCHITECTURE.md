# Architecture

How the repository is laid out and why. `AGENTS.md` has the rules; this is
the map.

## Layout

```
app/            RELAY (dev.relay)          own UI, own audio pipeline
cal/ dial/ gate/ page/ reel/ tally/        the Field apps (dev.field.<name>)
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
| GATE | `GateState.kt` | `GateStore.kt` | alarm + notification receivers in `GateStore.kt` |
| PAGE | `Text.kt`, `Markdown.kt`, `Html.kt`, `Style.kt`, `PageState.kt` | `PageStore.kt` (notes as files) | none |
| REEL | `Audio.kt` | `ReelStore.kt` | `RecorderService.kt`; `Transcriber.kt`, `ParakeetModel.kt`, `M4a.kt` |
| TALLY | `Calc.kt` | `LogStore` in `MainActivity.kt` | none |

## The shared library (`field/`)

- `Field.kt`: palette (`FieldColors`, light and dark), `fieldContent()`
  (theme entry point), and every component.
- `Fmt.kt`: number and clock formatting used in readouts.

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

## Testing

- Unit tests (JUnit 4, JVM): all pure logic. Run in CI on every build.
- No instrumented tests are checked in. An emulator run in CI is possible
  and was used once to diagnose streams (see `docs/REVIEW.md`); making it a
  standing smoke test is on the roadmap.
