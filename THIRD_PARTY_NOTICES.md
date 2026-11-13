# Third-party notices

Relay's own code is original. It uses the components below, all under
licenses that allow use, modification and redistribution, including in a
public repository and in distributed APKs.

## Shipped inside the APK

| Component | Version | License |
|-----------|---------|---------|
| Jetpack Compose (foundation, ui, runtime and their transitive AndroidX deps) | BOM 2024.12.01 | Apache License 2.0 |
| AndroidX Activity Compose | 1.9.3 | Apache License 2.0 |
| AndroidX Core KTX | 1.15.0 | Apache License 2.0 |
| AndroidX Lifecycle Runtime KTX | 2.8.7 | Apache License 2.0 |
| Kotlin standard library | 2.0.21 | Apache License 2.0 |
| kotlinx.coroutines (transitive) | via AndroidX | Apache License 2.0 |
| Speaker icon path data (`res/drawable/ic_relay.xml`) | Material Design Icons, "volume up" | Apache License 2.0, Google |

Copyright for each component remains with its authors (Google LLC / The
Android Open Source Project, JetBrains s.r.o. and contributors).

## Build and test only (not in the APK)

| Component | License |
|-----------|---------|
| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | Apache License 2.0, Gradle Inc. |
| Android Gradle Plugin, Kotlin Gradle plugins | Apache License 2.0 |
| JUnit 4.13.2 (unit tests) | Eclipse Public License 1.0 |
| Hamcrest Core 1.3 (via JUnit) | BSD 3-Clause |

## Apache License 2.0

The full text is at <https://www.apache.org/licenses/LICENSE-2.0>.
Redistributing the APK requires passing on that license and these notices;
this file does that for the source repository and its build artifacts.

## Trademarks and protocols

Sonos is a trademark of Sonos, Inc. Teenage Engineering is a trademark of
teenage engineering AB. Relay is an independent project, not affiliated
with or endorsed by either company. The names are used only to describe
compatibility and design inspiration; no logos or other brand assets are
included.

Relay talks to speakers through the UPnP/SOAP control interface they
expose on the local network (the same interface used by open-source
projects such as SoCo and Home Assistant's Sonos integration). No Sonos
code, SDK or firmware is included.
