#!/bin/sh
# Offline alternative when Gradle cannot run. All dependencies are provided by the caller.
set -eu
: "${KOTLIN_HOME:?Set KOTLIN_HOME to a Kotlin compiler installation}"
: "${JUNIT_JAR:?Set JUNIT_JAR to a JUnit 4.13 or later JAR}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to a Hamcrest Core 1.3 JAR}"
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_OUT="$TASK_ROOT/.verification/core"
mkdir -p "$TASK_OUT"
python3 - "$TASK_ROOT" "$TASK_OUT/sources.txt" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1]) / "connection-core" / "src"
Path(sys.argv[2]).write_text("\n".join('"' + str(p) + '"' for p in sorted(root.rglob("*.kt"))) + "\n")
PY
java -cp "$KOTLIN_HOME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -classpath "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$JUNIT_JAR:$HAMCREST_JAR" \
    -d "$TASK_OUT/classes" "@$TASK_OUT/sources.txt"
java -cp "$TASK_OUT/classes:$KOTLIN_HOME/lib/kotlin-stdlib.jar:$JUNIT_JAR:$HAMCREST_JAR" \
    org.junit.runner.JUnitCore es.tecnogesfausti.hatail.core.ConnectionMachineTest \
    es.tecnogesfausti.hatail.core.TailnetAddressPolicyTest
