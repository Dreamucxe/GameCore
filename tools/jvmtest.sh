#!/bin/bash
# Compiles and runs GameCore's pure-JVM unit tests without Gradle.
#
# The project's unit test classpath is deliberately tiny (junit + coroutines-test + org.json, no
# Robolectric or mocking framework), which means most of the plugin layer's tests are plain JVM tests
# over plain Kotlin. A full Gradle cycle on this host is ~21 minutes; this is ~40 seconds, so it is the
# loop to use while writing, with Gradle kept as the gate that also covers the Android half.
#
# Usage: ./tools/jvmtest.sh                      # everything pure under core/plugin + friends
#        ./tools/jvmtest.sh PluginOverlayCodec   # only test classes matching a substring
set -u

JAVA=/usr/lib/jvm/java-17-openjdk-arm64/bin/java
ROOT=/root/GameCore
OUT=/tmp/gcjvmtest
KCP=$(cat /tmp/kcp.txt)

G=/root/.gradle/caches/modules-2/files-2.1
STDLIB=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
JSON=$(find $G/org.json -name "json-20240303.jar" | head -1)
JUNIT=$(find $G/junit -name "junit-4.13.2.jar" | head -1)
HAMCREST=$(find $G/org.hamcrest -name "hamcrest-core-1.3.jar" | head -1)
COROUTINES=$(find $G/org.jetbrains.kotlinx -name "kotlinx-coroutines-core-jvm-1.8*.jar" | head -1)
[ -z "$COROUTINES" ] && COROUTINES=$(find $G/org.jetbrains.kotlinx -name "kotlinx-coroutines-core-jvm-*.jar" | sort -V | tail -1)
COTEST=$(find $G/org.jetbrains.kotlinx -name "kotlinx-coroutines-test-jvm-*.jar" | sort -V | tail -1)

CP="$STDLIB:$JSON:$JUNIT:$HAMCREST:$COROUTINES:$COTEST"

# The last Gradle build's output, used as a *backing* classpath so this runner does not have to
# rediscover the whole app's dependency graph to compile one package. Everything the plugin layer
# reaches that is not itself being recompiled — BuildConfig, AccessLevel, the display models — resolves
# from here. Freshly compiled classes are put ahead of it on every classpath below, so an edited file
# always wins over its stale class; a file that has never been built simply is not found, which fails
# loudly rather than silently testing yesterday's code.
STALE=""
for d in app/build/tmp/kotlin-classes/debug \
         app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes; do
  [ -d "$ROOT/$d" ] && STALE="$STALE:$ROOT/$d"
done
ANDROID=$(ls -d /root/android-sdk/platforms/android-34/android.jar 2>/dev/null)
[ -n "$ANDROID" ] && STALE="$STALE:$ANDROID"
# The full resolved dependency classpath, shared with tools/typecheck.sh, so a source file that touches
# android.* or Compose can be recompiled here rather than taken stale from the last build. It is far too
# long for a command line, hence the argfiles below.
[ -s /tmp/gcapp_cp.txt ] && STALE="$STALE:$(cat /tmp/gcapp_cp.txt)"
R_JAR=$(ls $ROOT/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/*/R.jar 2>/dev/null | head -1)
[ -n "$R_JAR" ] && STALE="$STALE:$R_JAR"
COMPOSE_PLUGIN=$(find /root/.gradle/caches/modules-2 -name "kotlin-compose-compiler-plugin-embeddable-2.0.21.jar" 2>/dev/null | head -1)

rm -rf $OUT && mkdir -p $OUT/main $OUT/test

# --- main sources: every pure file the plugin layer needs ------------------------------------------
# core/plugin is pure by design (nothing in it imports android.*, androidx.* or dagger) so the whole
# tree goes in. From core/common only TextSanitizer qualifies; Qualifiers and NotificationChannels are
# Hilt and Android respectively.
MAIN=$(find $ROOT/app/src/main/java/com/gamecore/core/plugin -name "*.kt")
MAIN="$MAIN $ROOT/app/src/main/java/com/gamecore/core/common/TextSanitizer.kt"
MAIN="$MAIN $ROOT/app/src/main/java/com/gamecore/core/overlay/OverlaySamplingGate.kt"
# OverlaySlot lives in OverlayWindows.kt and the slot-agreement test asserts on it, so it is recompiled
# rather than read from the last build — a stale copy would be an enum that predates the plugin slots,
# and the test would fail on code that is actually correct.
MAIN="$MAIN $ROOT/app/src/main/java/com/gamecore/core/overlay/OverlayWindows.kt"
# BrokerAction and ShizukuActionBroker are the two files here that are not self-contained — they speak
# in ShellCommand, WritableSetting and AccessLevel — but they compile anyway because those resolve from
# the backing classpath above. They are recompiled rather than taken stale, since the broker is where
# the permission-to-command mapping lives and a stale one would test a boundary that no longer exists.

echo "==> compiling main ($(echo $MAIN | wc -w) files)"
A1=/tmp/gcjvmtest_main_args.txt
{ echo "-no-stdlib"; echo "-nowarn"; echo "-jvm-target"; echo "17"
  echo "-d"; echo "$OUT/main"; echo "-cp"; echo "\"$CP$STALE\""
  echo "-Xplugin=$COMPOSE_PLUGIN"
  for f in $MAIN; do echo "$f"; done; } > $A1
$JAVA -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @$A1 2>&1 | grep -E "error:" | head -25
[ -z "$(ls -A $OUT/main)" ] && { echo "MAIN COMPILE FAILED"; exit 1; }

# --- test sources ----------------------------------------------------------------------------------
TESTS=$(find $ROOT/app/src/test/java/com/gamecore/core/plugin \
             $ROOT/app/src/test/java/com/gamecore/core/overlay \
             $ROOT/app/src/test/java/com/gamecore/data/plugin -name "*.kt" 2>/dev/null)
if [ $# -gt 0 ]; then
  TESTS=$(echo "$TESTS" | tr ' ' '\n' | grep -i "$1")
fi

# -Xfriend-paths is what makes `internal` members of main visible to the test source set, exactly as
# Gradle does for an associated compilation. Both halves of main are named: the freshly compiled output
# and the backing class dir, because an internal member the tests assert on (the overlay panel's layout
# arithmetic) may live in a file this runner did not recompile.
echo "==> compiling tests ($(echo $TESTS | wc -w) files)"
A2=/tmp/gcjvmtest_test_args.txt
{ echo "-no-stdlib"; echo "-nowarn"; echo "-jvm-target"; echo "17"
  echo "-d"; echo "$OUT/test"; echo "-cp"; echo "\"$OUT/main:$CP$STALE\""
  echo "-Xplugin=$COMPOSE_PLUGIN"
  echo "-Xfriend-paths=$OUT/main,$ROOT/app/build/tmp/kotlin-classes/debug"
  for f in $TESTS; do echo "$f"; done; } > $A2
$JAVA -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @$A2 2>&1 | grep -E "error:" | head -25
[ -z "$(ls -A $OUT/test)" ] && { echo "TEST COMPILE FAILED"; exit 1; }

# --- run -------------------------------------------------------------------------------------------
CLASSES=$(cd $OUT/test && find . -name "*Test.class" ! -name "*\$*" | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "==> running $(echo "$CLASSES" | wc -l) test classes"
echo "-cp" > /tmp/gcjvmtest_run_args.txt
echo "\"$OUT/test:$OUT/main:$CP$STALE\"" >> /tmp/gcjvmtest_run_args.txt
$JAVA @/tmp/gcjvmtest_run_args.txt org.junit.runner.JUnitCore $CLASSES 2>&1 | tail -40
