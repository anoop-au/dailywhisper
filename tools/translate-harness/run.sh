#!/usr/bin/env bash
# Box tests for TranslateConfig.kt (pure Kotlin, the same file the app compiles).
#   tools/translate-harness/run.sh
set -euo pipefail
cd "$(dirname "$0")"
G=~/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar')
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.0.jar'):$STD:$(jar org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.0.21.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
OUT=$(mktemp -d); WORK=$(mktemp -d)
trap 'rm -rf "$OUT" "$WORK"' EXIT
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD" -jvm-target 17 \
  -d "$OUT" ../../app/src/main/java/com/anoop/dailywhisper/TranslateConfig.kt Harness.kt 2>&1 | grep -v "^warning" || true
# ML Kit's language codes, read from the AAR the app builds against.
AAR=$(find "$G/com.google.mlkit/translate" -name 'translate-*.aar' | head -1)
mkdir -p "$WORK/aar" && (cd "$WORK/aar" && unzip -q -o "$AAR" classes.jar && unzip -q -o classes.jar -d cls)
CODES=$(javap -constants -cp "$WORK/aar/cls" com.google.mlkit.nl.translate.TranslateLanguage | grep -o '"[a-z]*"' | tr -d '"' | tr '\n' ' ')
java -cp "$OUT:$STD" HarnessKt "$CODES"
