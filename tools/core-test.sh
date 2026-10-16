#!/usr/bin/env bash
# Compiles and runs the :core unit tests WITHOUT Gradle or the Android SDK.
# Used when only a JDK + kotlinc are available (e.g. restricted build sandboxes).
# In a normal environment just run:  ./gradlew :core:test
#
# Requirements: JDK 17+, kotlinc on PATH (or KOTLINC env var), JUnit 4 jar
# (JUNIT_JAR env var; defaults to the copy bundled with Gradle 8.x).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-$(command -v kotlinc || echo /opt/kotlinc-2.1.21/kotlinc/bin/kotlinc)}"
KHOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
JUNIT_JAR="${JUNIT_JAR:-$(ls /opt/gradle*/lib/junit-4*.jar 2>/dev/null | head -1)}"
HAMCREST_JAR="${HAMCREST_JAR:-$(ls /opt/gradle*/lib/hamcrest-core-*.jar 2>/dev/null | head -1)}"
OUT="${OUT:-$(mktemp -d)}"
trap '[ -z "${KEEP_OUT:-}" ] && rm -rf "$OUT"' EXIT

echo "kotlinc: $("$KOTLINC" -version 2>&1 | tail -1)"
echo "Compiling core main..."
"$KOTLINC" -nowarn -jvm-target 17 -d "$OUT/main" $(find "$ROOT/core/src/main/kotlin" -name '*.kt') 2>&1 | grep -v '^warning' || true
[ -d "$OUT/main" ] || { echo "core main failed to compile"; exit 1; }

CP_TEST="$OUT/main:$KHOME/lib/kotlin-test.jar:$KHOME/lib/kotlin-test-junit.jar:$JUNIT_JAR:$HAMCREST_JAR"
echo "Compiling core tests..."
"$KOTLINC" -nowarn -jvm-target 17 -cp "$CP_TEST" -d "$OUT/test" $(find "$ROOT/core/src/test/kotlin" -name '*.kt') 2>&1 | grep -v '^warning' || true
[ -d "$OUT/test" ] || { echo "core tests failed to compile"; exit 1; }

CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "Running: $CLASSES"
java -cp "$CP_TEST:$OUT/test:$KHOME/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $CLASSES
