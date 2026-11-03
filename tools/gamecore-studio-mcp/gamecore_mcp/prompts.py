"""MCP prompts that make the server self-describing.

A prompt is returned as a normal MCP message. The setup prompt is intentionally written as
instructions to the agent, not as marketing copy: it names the one debug APK, the one command and
the checks that prove the connection is real.
"""

from __future__ import annotations

from typing import Any

from .tools import DEBUG_APK, SERVER_PATH

SETUP_PROMPT = f"""You are connecting to the GameCore Plugin Studio MCP server.

Goal: edit the live draft in the debug GameCore Plugin Maker, validate it with the studio's own
rules, and export a deterministic .gcplugin. The release app deliberately has no bridge.

Do this in order:

1. Confirm the debug app is installed and open. The APK is:
   {DEBUG_APK}
   The app package is com.gamecore.pluginmaker.debug. If it is not installed, ask the user to tap
   the APK installer; do not pretend a background install succeeded.
2. Open the app once. It starts a foreground service with a persistent "Plugin Studio bridge is
   running" notification; that service keeps 127.0.0.1:47653 reachable while the user works in a
   terminal. If the notification is gone, ask the user to reopen the app.
3. Register this server with the MCP client using:
   opencode mcp add gamecore-studio -- python3 {SERVER_PATH}
   For another client, use the equivalent stdio server command:
   python3 {SERVER_PATH}
4. Call studio_status. A successful result proves the agent is talking to the live app.
5. Call studio_get_draft to read the current draft. Call studio_set_draft with the same shape to
   change it; the editor updates immediately and autosaves.
6. Call studio_validate_draft before exporting. Fix every returned field.
7. Call studio_export_plugin. It writes the package under the studio-mcp-exports folder and returns
   the path, size and SHA-256. Inspect it with studio_inspect_package if needed.

Hard boundaries:
- The bridge only edits drafts and returns package bytes. It cannot install, grant permissions,
  run rules, execute code, or change GameCore.
- Do not claim a package is installed or a plugin is enabled just because it exported.
- Do not add touchable overlays, interactive screen controls, plugin messaging, branching or
  scheduled workflows, network access, shell commands or executable code. The studio does not
  represent them and the host does not run them.
- Keep the work local. Do not expose port 47653 to a network or another device.

When finished, report: the draft id, validation result, export path, package SHA-256, and which
capabilities the plugin actually uses.
"""


def prompt_list() -> dict[str, Any]:
    return {
        "prompts": [
            {
                "name": "setup_plugin_studio",
                "title": "Set up the GameCore Plugin Studio MCP",
                "description": (
                    "Step-by-step instructions for installing the debug APK, starting the bridge, "
                    "registering this server and proving the connection."
                ),
                "arguments": [],
            }
        ]
    }


def prompt_get(name: str) -> dict[str, Any]:
    if name != "setup_plugin_studio":
        raise KeyError(name)
    return {
        "description": "Connect an agent to the debug GameCore Plugin Maker and author a plugin.",
        "messages": [
            {
                "role": "user",
                "content": {"type": "text", "text": SETUP_PROMPT},
            }
        ],
    }
