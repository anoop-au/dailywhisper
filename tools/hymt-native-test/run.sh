#!/usr/bin/env bash
# Builds hymt_engine.cpp + the vendored llama.cpp and translates a sentence with the real Hy-MT model.
#   tools/hymt-native-test/run.sh host  <patched.gguf>   # x86-64 host build (generic STQ1_0 path)
#   tools/hymt-native-test/run.sh arm64 <patched.gguf>   # NDK arm64 static build under qemu-aarch64 (NEON STQ1_0 path)
# The model must already have its STQ type ids patched (the app does this after download; see GgufPatch).
set -euo pipefail
cd "$(dirname "$0")"
MODE=${1:-host}; MODEL=${2:?model path}
CM=$(ls -d ~/Android/Sdk/cmake/*/bin | tail -1)
NDK=$(ls -d ~/Android/Sdk/ndk/* | tail -1)
B=/tmp/hymt-test-$MODE
if [ "$MODE" = arm64 ]; then
  "$CM/cmake" -S . -B $B -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 >/dev/null
  RUN="qemu-aarch64 -cpu max"
else
  "$CM/cmake" -S . -B $B -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" -DCMAKE_BUILD_TYPE=Release >/dev/null
  RUN=""
fi
"$CM/cmake" --build $B -j"$(nproc)" --target hymt-test >/dev/null
P=$(mktemp)
printf '<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following segment into Hindi, without additional explanation.\n\nI need to buy milk and call my mother before the meeting tomorrow.<｜hy_Assistant｜>' > "$P"
$RUN $B/hymt-test "$MODEL" "${THREADS:-6}" "$P" "${MAX_NEW:-96}"
