#!/usr/bin/env bash
# Compiles the app's WebSocketClient.kt + TranscriptParser.kt + speech-slot sources (Deepgram, Sarvam AI, fallback) with Harness.kt on the plain JVM and runs them
# against server.py. Test-only tools (org.json jar, python websockets venv) live in /workspace/wstest, not in the app.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../app/src/main/java/com/anoop/dailywhisper
T=/workspace/wstest
G=~/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.0.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.0.21.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains/annotations 'annotations-13.0.jar')"
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.2.0.jar')
mkdir -p "$T/out"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD:$T/json.jar" -jvm-target 17 \
  -d "$T/out" "$SRC/WebSocketClient.kt" "$SRC/TranscriptParser.kt" "$SRC/LiveDictationConfig.kt" "$SRC/Residency.kt" \
  "$SRC/SarvamConfig.kt" "$SRC/SpeechSlots.kt" "$SRC/SttConnect.kt" Harness.kt 2>&1 | grep -v "^warning" || true
PORT=8765
"$T/venv/bin/python" server.py $PORT > "$T/server.log" 2>&1 &
SP=$!; trap 'kill $SP 2>/dev/null' EXIT
for i in $(seq 1 50); do grep -q ready "$T/server.log" 2>/dev/null && break; sleep 0.1; done
java -cp "$T/out:$STD:$T/json.jar" HarnessKt $PORT "$@"
