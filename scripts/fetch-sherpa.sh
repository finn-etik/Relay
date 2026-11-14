#!/usr/bin/env bash
# Fetches REEL's speech-recognition inputs that are not committed to git:
#   reel/libs/sherpa-onnx.aar          sherpa-onnx runtime (Apache-2.0, includes onnxruntime, MIT)
#   reel/src/main/assets/silero_vad.onnx   Silero VAD (MIT)
#   reel/src/main/assets/parakeet.json     pinned file list + SHA-256 for the
#                                          in-app Parakeet v3 download (CC-BY-4.0)
# Run once after cloning, before building. CI runs it on every build.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="1.13.7"
REL="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p reel/libs reel/src/main/assets
curl -fsSL --retry 3 -o reel/libs/sherpa-onnx.aar "$REL/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
curl -fsSL --retry 3 -o reel/src/main/assets/silero_vad.onnx "$REL/asr-models/silero_vad.onnx"
python3 scripts/parakeet_manifest.py reel/src/main/assets/parakeet.json
ls -la reel/libs reel/src/main/assets
