#!/usr/bin/env bash
# Runs the app's NoteSchema.kt SQL on real SQLite (python3 sqlite3). Box-only test, not part of the app.
set -euo pipefail
cd "$(dirname "$0")"
G=~/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar')
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.0.jar'):$STD:$(jar org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.0.21.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable '*.jar'):$(find "$G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name 'kotlinx-coroutines-core-jvm-*.jar' | grep -v sources | sort -V | tail -1):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
OUT=$(mktemp -d)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD" -jvm-target 17 \
  -d "$OUT" ../../app/src/main/java/com/anoop/dailywhisper/NoteSchema.kt Dump.kt 2>&1 | grep -v "^warning" || true
java -cp "$OUT:$STD" DumpKt > "$OUT/sql.txt"
python3 test.py "$OUT/sql.txt"
