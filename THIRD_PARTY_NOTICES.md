# Third-party notices

Relay's own code is original and licensed under the Apache License 2.0
(see LICENSE). It uses the components below, all under
licenses that allow use, modification and redistribution, including in a
public repository and in distributed APKs.

## Shipped inside the APK

| Component | Version | License |
|-----------|---------|---------|
| Jetpack Compose (foundation, ui, runtime and their transitive AndroidX deps) | BOM 2024.12.01 | Apache License 2.0 |
| AndroidX Activity Compose | 1.9.3 | Apache License 2.0 |
| AndroidX Core KTX | 1.15.0 | Apache License 2.0 |
| AndroidX Lifecycle Runtime KTX | 2.8.7 | Apache License 2.0 |
| Kotlin standard library | 2.4.0 | Apache License 2.0 |
| kotlinx.coroutines (transitive) | via AndroidX | Apache License 2.0 |
| Speaker icon path data (`res/drawable/ic_relay.xml`) | Material Design Icons, "volume up" | Apache License 2.0, Google |

Copyright for each component remains with its authors (Google LLC / The
Android Open Source Project, JetBrains s.r.o. and contributors).

## Field apps (CAL, DIAL, GATE, LIST, LORE, PAGE, REEL, TALLY)

The Field apps use the same AndroidX / Compose / Kotlin components as above
(Apache License 2.0). REEL additionally ships:

| Component | License |
|-----------|---------|
| sherpa-onnx 1.13.7 runtime (`reel/libs/sherpa-onnx.aar`, fetched at build time) | Apache License 2.0, Xiaomi Corporation and contributors |
| ONNX Runtime (inside the sherpa-onnx AAR) | MIT, Microsoft Corporation |
| Silero VAD model (`assets/silero_vad.onnx`) | MIT, Silero Team |

REEL downloads, at the user's request and not inside the APK:

| Model | License |
|-------|---------|
| NVIDIA Parakeet TDT 0.6B v3, int8 ONNX export by the sherpa-onnx project (`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`) | CC-BY-4.0, NVIDIA Corporation. Attribution is shown in REEL's model card. |

LORE additionally ships:

| Component | License |
|-----------|---------|
| LiteRT-LM 0.19.1 (`com.google.ai.edge.litertlm:litertlm-android`), the on-device language-model runtime, with its native libraries | Apache License 2.0, Google LLC |
| Gson and kotlinx.coroutines (dependencies of LiteRT-LM) | Apache License 2.0 |
| zstd-jni 1.5.6-9 (`com.github.luben:zstd-jni`), JNI bindings | BSD 2-Clause, Luben Karavelov |
| Zstandard (inside zstd-jni) | BSD 3-Clause, Meta Platforms, Inc. |

LORE reads ZIM archives with its own code, written from the openZIM format
description; it contains no libzim or libkiwix code.

LORE downloads, at the user's request and not inside the APK:

| What | From | License |
|------|------|---------|
| Gemma 4 E2B and E4B, LiteRT-LM builds (`litert-community/gemma-4-E2B-it-litert-lm`, `gemma-4-E4B-it-litert-lm`) | Hugging Face | Apache License 2.0, Google LLC |
| Qwen3 0.6B, LiteRT-LM build (`litert-community/Qwen3-0.6B-int4`) | Hugging Face | Apache License 2.0, Alibaba Cloud |
| English Wikipedia and Wikibooks as ZIM archives | [Kiwix](https://kiwix.org/) (`download.kiwix.org`) | Text: CC BY-SA 4.0, the Wikipedia and Wikibooks contributors. LORE shows the article a passage comes from with every answer that uses one. |

Wikipedia and Wikibooks are trademarks of the Wikimedia Foundation; Kiwix is
an independent project. LORE is affiliated with neither.

DIAL additionally ships:

| Component | License |
|-----------|---------|
| AndroidX Media3 1.4.1 (`media3-exoplayer`, `media3-exoplayer-hls` and their Media3 dependencies) | Apache License 2.0, The Android Open Source Project |

DIAL reads, at runtime and not inside the APK:

| Source | Data license | Used for |
|--------|--------------|----------|
| [Radio Browser](https://www.radio-browser.info/) API (`*.api.radio-browser.info`) | Public domain (CC0) | Station search: names, stream URLs, metadata |

The streams themselves (e.g. SomaFM, Radio Paradise) belong to their
broadcasters; DIAL only plays their public stream URLs.

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
