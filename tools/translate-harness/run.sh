#!/usr/bin/env bash
# Box tests for TranslateConfig.kt + ResumableDownload.kt (pure Kotlin, same files the app compiles).
#   tools/translate-harness/run.sh [--hf] [reference.gguf]
#   --hf: also does a real 3 MB ranged download from Hugging Face (pinned revision) and compares bytes with
#         reference.gguf if given (the unmodified downloaded model).
set -euo pipefail
cd "$(dirname "$0")"
G=~/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar')
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.0.jar'):$STD:$(jar org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.0.21.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
OUT=$(mktemp -d); WORK=$(mktemp -d)
SRC=../../app/src/main/java/com/anoop/dailywhisper
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD" -jvm-target 17 \
  -d "$OUT" $SRC/TranslateConfig.kt $SRC/ResumableDownload.kt Harness.kt 2>&1 | grep -v "^warning" || true
# ML Kit's language codes, read from the AAR the app builds against.
AAR=$(find "$G/com.google.mlkit/translate" -name 'translate-*.aar' | head -1)
mkdir -p "$WORK/aar" && (cd "$WORK/aar" && unzip -q -o "$AAR" classes.jar && unzip -q -o classes.jar -d cls)
CODES=$(javap -constants -cp "$WORK/aar/cls" com.google.mlkit.nl.translate.TranslateLanguage | grep -o '"[a-z]*"' | tr -d '"' | tr '\n' ' ')
PORT=$((20000 + RANDOM % 20000))
python3 server.py $PORT "$WORK" > "$WORK/server.log" 2>&1 & SP=$!
trap 'kill $SP 2>/dev/null; rm -rf "$OUT" "$WORK"' EXIT
sleep 0.5
HF=""; REF=""
for a in "$@"; do if [ "$a" = --hf ]; then HF=--hf; else REF=$a; fi; done
java -cp "$OUT:$STD" HarnessKt $PORT "$WORK" "$CODES" "$REF" $HF
