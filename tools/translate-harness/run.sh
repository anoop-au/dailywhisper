#!/usr/bin/env bash
# Box tests for the app's translation code (pure Kotlin, the same files the app compiles), on the plain JVM with the
# desktop build of the same ONNX Runtime version.
#   tools/translate-harness/run.sh [--opus <models root>] [--hf]
#   --opus: real Opus-MT check; <root>/<pair>/{encoder.onnx,decoder.onnx,tokenizer.json,config.json} for the pairs in
#           opus-ref.json (en-de de-en en-hi hi-en ja-en en-es). Ids and translations must equal the Python reference.
#   --hf:   checks every catalog file at its pinned Hugging Face revision, and a real Range resume there.
set -euo pipefail
cd "$(dirname "$0")"
G=~/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar')
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.0.jar'):$STD:$(jar org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.0.21.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
# Same ONNX Runtime version as app/build.gradle.kts, desktop JVM build (same ai.onnxruntime API).
ORT_VER=$(grep -o 'onnxruntime-android:[0-9.]*' ../../app/build.gradle.kts | cut -d: -f2)
ORT=~/.cache/dailywhisper/onnxruntime-$ORT_VER.jar
[ -f "$ORT" ] || { mkdir -p "$(dirname "$ORT")"; curl -sfL -o "$ORT" "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$ORT_VER/onnxruntime-$ORT_VER.jar"; }
OUT=$(mktemp -d); WORK=$(mktemp -d)
SRC=../../app/src/main/java/com/anoop/dailywhisper
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD:$ORT" -jvm-target 17 \
  -d "$OUT" $SRC/TranslateConfig.kt $SRC/OpusMt.kt $SRC/OpusMtCatalog.kt $SRC/ResumableDownload.kt Harness.kt 2>&1 | grep -v "^warning" || true
# ML Kit's language codes, read from the AAR the app builds against.
AAR=$(find "$G/com.google.mlkit/translate" -name 'translate-*.aar' | head -1)
mkdir -p "$WORK/aar" && (cd "$WORK/aar" && unzip -q -o "$AAR" classes.jar && unzip -q -o classes.jar -d cls)
CODES=$(javap -constants -cp "$WORK/aar/cls" com.google.mlkit.nl.translate.TranslateLanguage | grep -o '"[a-z]*"' | tr -d '"' | tr '\n' ' ')
PORT=$((20000 + RANDOM % 20000))
python3 server.py $PORT "$WORK" > "$WORK/server.log" 2>&1 & SP=$!
trap 'kill $SP 2>/dev/null; rm -rf "$OUT" "$WORK"' EXIT
sleep 0.5
java -cp "$OUT:$STD:$ORT" HarnessKt $PORT "$WORK" "$CODES" "$@"
