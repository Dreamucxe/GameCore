#!/bin/bash
# Type-checks the whole :app Kotlin source set without Gradle.
#
# Why this exists: a Gradle cycle on this host is ~21 minutes, almost all of it spent on work that has
# nothing to do with whether the Kotlin compiles — resource processing, KSP, dexing, packaging. This
# runs just the Kotlin front end and back end over every main source file, against the same classpath
# Gradle assembled on the last build, and takes a couple of minutes. It catches exactly the class of
# mistake that makes a 21-minute build fail at minute nineteen: a wrong parameter name, a missing
# import, a non-exhaustive `when`, a changed signature with a call site left behind.
#
# It is a pre-flight check, not a replacement for the build. It reuses the previous build's resolved
# dependency jars, generated sources and R.jar, so it cannot see a change to a resource, a manifest, a
# new dependency, or anything KSP would generate differently — and it does not run R8, which has broken
# this project before. Gradle stays the gate.
#
# Usage: ./tools/typecheck.sh [debug|release]      (default debug)
set -u

VARIANT=${1:-debug}
JAVA=/usr/lib/jvm/java-17-openjdk-arm64/bin/java
ROOT=/root/GameCore
OUT=/tmp/gctypecheck
ANDROID_JAR=/root/android-sdk/platforms/android-34/android.jar
CPFILE=/tmp/gcapp_cp.txt

# --- dependency classpath -------------------------------------------------------------------------
# Every jar Gradle has already resolved and transformed, cached on first run. Over-broad on purpose:
# naming the exact compile classpath would mean re-implementing Gradle's variant resolution, and an
# extra jar on a type-check classpath costs a little memory, while a missing one costs a false error.
if [ ! -s "$CPFILE" ]; then
  echo "==> building dependency classpath cache"
  { find /root/.gradle/caches/8.9/transforms -name "*.jar" 2>/dev/null
    find /root/.gradle/caches/modules-2 -name "*.jar" 2>/dev/null
  } | grep -v "sources\|javadoc" | sort -u | tr '\n' ':' | sed 's/:$//' > $CPFILE
fi

COMPOSE_PLUGIN=$(find /root/.gradle/caches/modules-2 -name "kotlin-compose-compiler-plugin-embeddable-2.0.21.jar" 2>/dev/null | head -1)
[ -z "$COMPOSE_PLUGIN" ] && { echo "compose compiler plugin not in the Gradle cache"; exit 1; }

R_JAR=$(ls $ROOT/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/$VARIANT/*/R.jar 2>/dev/null | head -1)
[ -z "$R_JAR" ] && { echo "no R.jar for $VARIANT — run a Gradle build once first"; exit 1; }

CP="$(cat $CPFILE):$ANDROID_JAR:$R_JAR"

# --- sources ---------------------------------------------------------------------------------------
# The Kotlin sources plus the generated Java. The .java files are passed as sources so the compiler can
# resolve the symbols in them (Hilt's generated superclasses, the members injectors, BuildConfig); it
# parses them for signatures and does not compile them, which is all that is needed here.
rm -rf $OUT && mkdir -p $OUT
SRC=$ROOT/app/src/main/java
GEN="$ROOT/app/build/generated/ksp/$VARIANT/java $ROOT/app/build/generated/source/buildConfig/$VARIANT"

ARGS=/tmp/gctypecheck_args.txt
{
  echo "-no-stdlib"
  echo "-nowarn"
  echo "-Xjvm-default=all"
  echo "-jvm-target"; echo "17"
  echo "-d"; echo "$OUT"
  echo "-cp"; echo "\"$CP\""
  # The Compose compiler plugin, same version Gradle uses. Without it the front end still type-checks
  # every file, but codegen dies trying to inline `CompositionLocal.current` — and more importantly the
  # Compose-specific diagnostics (a @Composable called from a non-composable function, an unstable
  # parameter) would be missing, which for a UI-heavy app is most of what there is to get wrong.
  echo "-Xplugin=$COMPOSE_PLUGIN"
  find $SRC -name "*.kt" | sed "s|^|$(echo)|"
  for g in $GEN; do [ -d "$g" ] && find $g -name "*.java"; done
} > $ARGS

echo "==> type-checking $(grep -c '\.kt$' $ARGS) Kotlin files ($VARIANT)"
$JAVA -Xmx3g -cp "$(cat /tmp/kcp.txt)" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @$ARGS 2>&1 \
  | grep -vE "^warning:|^info:" | grep -E "error:|exception|Exception" | head -40

CLASSES=$(find $OUT -name "*.class" 2>/dev/null | wc -l)
echo "==> $CLASSES classes emitted"
[ "$CLASSES" -gt 3000 ] && echo "TYPECHECK OK" || echo "TYPECHECK FAILED (too few classes emitted)"
