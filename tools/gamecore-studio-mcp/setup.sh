#!/usr/bin/env bash
# Set up the GameCore Studio MCP server on this machine/device.
#
#   bash tools/gamecore-studio-mcp/setup.sh              # check and print the next steps
#   bash tools/gamecore-studio-mcp/setup.sh --install    # also offer the debug APK to Android
#   bash tools/gamecore-studio-mcp/setup.sh --register   # also register with OpenCode
#   bash tools/gamecore-studio-mcp/setup.sh --test       # run the Python contract tests
#   bash tools/gamecore-studio-mcp/setup.sh --build      # build the debug APK if it is missing
#
# The bridge exists only in the debug Plugin Maker. There is nothing to install on a release
# build, and this script never pretends otherwise.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SERVER="$HERE/server.py"
APK="${GAMECORE_STUDIO_APK:-/sdcard/Download/GameCore-PluginMaker-1.1.0-debug.apk}"
URL="${GAMECORE_STUDIO_URL:-http://127.0.0.1:47653}"

DO_INSTALL=0
DO_REGISTER=0
DO_TEST=0
DO_BUILD=0
for argument in "$@"; do
    case "$argument" in
        --install) DO_INSTALL=1 ;;
        --register) DO_REGISTER=1 ;;
        --test) DO_TEST=1 ;;
        --build) DO_BUILD=1 ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown option: $argument" >&2
            exit 2
            ;;
    esac
done

echo "GameCore Studio MCP"
echo "  repo:   $REPO"
echo "  server: $SERVER"
echo "  bridge: $URL"
echo

if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required." >&2
    exit 1
fi
python3 - <<'PY'
import sys
if sys.version_info < (3, 10):
    raise SystemExit("Python 3.10 or newer is required; found " + sys.version.split()[0])
print("  python: " + sys.version.split()[0])
PY

if [ "$DO_BUILD" = 1 ] && [ ! -f "$APK" ]; then
    echo
    echo "==> building the debug APK"
    ( cd "$REPO" && ./gradlew :plugin-maker:assembleDebug --console=plain )
    APK="$REPO/plugin-maker/build/outputs/apk/debug/plugin-maker-debug.apk"
fi

if [ -f "$APK" ]; then
    echo "  debug APK: $APK"
else
    echo "  debug APK: not found (build with --build or pass GAMECORE_STUDIO_APK)"
fi

if command -v pm >/dev/null 2>&1 && pm list packages 2>/dev/null | grep -q 'com.gamecore.pluginmaker.debug'; then
    echo "  installed: yes (com.gamecore.pluginmaker.debug)"
else
    echo "  installed: no debug package visible"
fi

echo
if python3 - "$URL" <<'PY'
import json
import sys
import urllib.error
import urllib.request

url = sys.argv[1].rstrip("/") + "/v1/health"
try:
    with urllib.request.urlopen(url, timeout=2) as response:
        body = json.loads(response.read())
    print("  bridge health: " + json.dumps(body))
except Exception as failure:
    print("  bridge health: not running (" + failure.__class__.__name__ + ")")
    sys.exit(1)
PY
then
    echo "  status: connected"
else
    echo "  status: app not running yet"
fi

if [ "$DO_TEST" = 1 ]; then
    echo
    echo "==> Python contract tests"
    ( cd "$HERE" && python3 -m unittest discover -s tests -t . )
fi

if [ "$DO_INSTALL" = 1 ]; then
    echo
    if [ ! -f "$APK" ]; then
        echo "Cannot install: no debug APK at $APK" >&2
        exit 1
    fi
    if command -v termux-open >/dev/null 2>&1; then
        echo "==> opening the APK installer; confirm the install on the phone"
        termux-open "$APK"
    else
        echo "Open this file on the Android device and confirm the install: $APK"
    fi
fi

if [ "$DO_REGISTER" = 1 ]; then
    echo
    if command -v opencode >/dev/null 2>&1; then
        echo "==> registering with OpenCode (project config)"
        opencode mcp add gamecore-studio -- python3 "$SERVER"
    else
        echo "OpenCode is not on PATH. Add this stdio server to your MCP client:"
        echo "  python3 $SERVER"
    fi
fi

cat <<EOF

Next steps
1. Install the debug APK and open the Plugin Maker (debug) once.
2. Its foreground service keeps the bridge reachable on 127.0.0.1:47653; the persistent notification means it is running.
3. Register the stdio server with your MCP client, for example:
     opencode mcp add gamecore-studio -- python3 $SERVER
4. Ask the agent to call studio_status. A reply proves it is connected.
5. Read docs/PLUGIN_STUDIO_MCP.md for the tool list and the capability boundary.

On a computer connected by USB instead of Termux, forward the device's loopback first:
     adb forward tcp:47653 tcp:47653

The release app has no bridge. Never expose port 47653 to a network; the debug token is a
convenience, not authentication.
EOF
