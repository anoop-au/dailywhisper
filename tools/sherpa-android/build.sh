#!/usr/bin/env bash
# Builds libsherpa-onnx-jni.so (arm64-v8a) from source, linked against the SAME ONNX Runtime 1.28.0 the app already
# ships (from the onnxruntime-android AAR in the Gradle cache), so the APK carries one runtime, not two.
# Output: app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so. Only speech-to-text (streaming + offline) and the
# voice-activity detector are compiled in; TTS, diarization, keyword spotting, punctuation, denoisers etc. are left out.
# Usage: source ~/android-env.sh && tools/sherpa-android/build.sh [workdir]
set -euo pipefail
SHERPA_COMMIT=935e5ded3d949a8b557ac44ef4dad03a7d2038e7   # k2-fsa/sherpa-onnx master, 29 Oct 2026 (Apache-2.0)
ORT_VERSION=1.28.0
NDK_VERSION=27.2.12479018
CMAKE_VERSION=3.22.1
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${1:-/tmp/sherpa-android-build}
SDK=${ANDROID_HOME:?source ~/android-env.sh first}
NDK=$SDK/ndk/$NDK_VERSION
export PATH=$SDK/cmake/$CMAKE_VERSION/bin:$PATH
mkdir -p "$WORK" && cd "$WORK"

# 1. The app's own ONNX Runtime (headers + libonnxruntime.so) from the AAR Gradle already downloaded.
AAR=$(find ~/.gradle/caches/modules-2 -name "onnxruntime-android-$ORT_VERSION.aar" | head -1)
[ -n "$AAR" ] || { echo "onnxruntime-android-$ORT_VERSION.aar not in the Gradle cache: run ./gradlew assembleRelease once"; exit 1; }
rm -rf ort && mkdir ort && (cd ort && unzip -q "$AAR" 'jni/arm64-v8a/*' 'headers/*')
export SHERPA_ONNXRUNTIME_LIB_DIR=$WORK/ort/jni/arm64-v8a
export SHERPA_ONNXRUNTIME_INCLUDE_DIR=$WORK/ort/headers

# 2. Sherpa-ONNX source at a pinned commit.
if [ ! -d sherpa-onnx/.git ]; then git clone -q https://github.com/k2-fsa/sherpa-onnx.git; fi
cd sherpa-onnx
git cat-file -e "$SHERPA_COMMIT" 2>/dev/null || git fetch -q origin "$SHERPA_COMMIT"
git checkout -q -f "$SHERPA_COMMIT"
# Keep only the JNI files for recognition + VAD; --gc-sections drops the unused core code (about 0.8 MB smaller).
python3 - <<'PY'
p='sherpa-onnx/jni/CMakeLists.txt'; s=open(p).read()
for f in ['audio-tagging','keyword-spotter','offline-diacritization','offline-punctuation','offline-speech-denoiser',
          'online-speech-denoiser','online-punctuation','speaker-embedding-extractor','speaker-embedding-manager',
          'speech-denoiser','spoken-language-identification','wave-reader','wave-writer']:
    s=s.replace('  %s.cc\n' % f,'')
s=s.replace('LINK_FLAGS "-Wl,--version-script','LINK_FLAGS "-Wl,--gc-sections -Wl,--version-script')
open(p,'w').write(s)
PY

# 3. Configure + build (Ninja; libc++ linked statically so no libc++_shared.so is needed).
rm -rf build && mkdir build && cd build
cmake -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-30 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_ENABLE_C_API=OFF \
  -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_RKNN=OFF -DSHERPA_ONNX_ENABLE_QNN=OFF \
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=ON -DCMAKE_INSTALL_PREFIX=./install .. > cmake.log
ninja -j"$(nproc)" > ninja.log
ninja install/strip >> ninja.log

# 4. Safety check: must link to the app's 1.28.0 runtime by symbol version, nothing else.
NM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm
SO=install/lib/libsherpa-onnx-jni.so
if ! $NM -D --undefined-only $SO | grep -q "OrtGetApiBase@VERS_$ORT_VERSION"; then
  echo "ERROR: not linked against ONNX Runtime $ORT_VERSION"; $NM -D --undefined-only $SO | grep Ort; exit 1
fi
mkdir -p "$REPO/app/src/main/jniLibs/arm64-v8a"
cp $SO "$REPO/app/src/main/jniLibs/arm64-v8a/"
# The Kotlin API (package com.k2fsa.sherpa.onnx, bound to the JNI names) is vendored in app/src/main/java/com/k2fsa.
ls -la "$REPO/app/src/main/jniLibs/arm64-v8a/"
