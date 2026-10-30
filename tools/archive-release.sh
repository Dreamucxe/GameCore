#!/usr/bin/env bash
# Archive one GameCore release to /sdcard/Download: the signed APK, the source
# tree it was built from, and checksums for both.
#
# Usage:  tools/archive-release.sh <version-label> [apk-path]
#
# Example:
#   tools/archive-release.sh 3.9.0 app/build/outputs/apk/release/app-release.apk
#
# The source zip never carries the signing material: `keystore.properties` and
# `release.keystore` are excluded, along with build output and the machine's
# local SDK path.

set -euo pipefail

VERSION="${1:?usage: archive-release.sh <version-label> [apk-path]}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${2:-$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk}"
DEST="${GAMECORE_DOWNLOAD_DIR:-/sdcard/Download}"

[ -f "$APK" ] || { echo "no such APK: $APK" >&2; exit 1; }

APK_OUT="$DEST/GameCore-$VERSION.apk"
SRC_OUT="$DEST/GameCore-$VERSION-source.zip"

mkdir -p "$DEST"

echo "==> copying $APK"
cp -f "$APK" "$APK_OUT"

echo "==> zipping the source tree"
rm -f "$SRC_OUT"
python3 - "$PROJECT_ROOT" "$SRC_OUT" <<'PY'
import os, sys, zipfile

root, out = sys.argv[1], sys.argv[2]
EXCLUDED_DIRS = {".git", ".gradle", ".kotlin", "build"}
EXCLUDED_FILES = {"local.properties", "keystore.properties", "release.keystore"}

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for base, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS)
        for name in sorted(files):
            if name in EXCLUDED_FILES:
                continue
            path = os.path.join(base, name)
            rel = os.path.relpath(path, root)
            zf.write(path, os.path.join("GameCore", rel))
print(f"zipped {out}")
PY

echo "==> checksums"
cd "$DEST"
sha256sum "GameCore-$VERSION.apk" > "GameCore-$VERSION.apk.sha256"
sha256sum "GameCore-$VERSION-source.zip" > "GameCore-$VERSION-source.zip.sha256"
sha256sum "GameCore-$VERSION.apk" "GameCore-$VERSION-source.zip" > SHA256SUMS.txt

echo
echo "archived to $DEST:"
ls -lh "GameCore-$VERSION.apk" "GameCore-$VERSION-source.zip"
