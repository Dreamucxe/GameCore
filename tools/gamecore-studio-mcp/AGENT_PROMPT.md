# Give this prompt to your agent

Copy the block below into a coding agent that can run shell commands in this repository. It is
written to make the agent verify the connection instead of assuming it, and to stop honestly when
the debug app is not installed.

```text
Connect to the GameCore Plugin Studio MCP in this repository and prove it works.

Repository: /root/GameCore-4.0
MCP server: tools/gamecore-studio-mcp/server.py
Debug app: com.gamecore.pluginmaker.debug (the release app has no bridge)
Bridge: 127.0.0.1:47653

Steps:
1. Run `bash tools/gamecore-studio-mcp/setup.sh --test` and report the result.
2. If the debug APK is missing, build it with `./gradlew :plugin-maker:assembleDebug`, copy
   plugin-maker/build/outputs/apk/debug/plugin-maker-debug.apk to
   /sdcard/Download/GameCore-PluginMaker-1.1.0-debug.apk, and ask the user to tap the installer.
   Do not claim a background install succeeded.
3. Register the server for OpenCode with:
   `opencode mcp add gamecore-studio -- python3 /root/GameCore-4.0/tools/gamecore-studio-mcp/server.py`
   If the tools do not appear, ask the user to run /reload. For another MCP client, use the same
   `python3 .../server.py` stdio command.
4. Ask the user to open the Plugin Maker (debug) once. It starts a foreground service with a
   persistent notification, so the bridge stays alive while they work in the terminal. Then call
   `studio_status`. If it reports bridge_unreachable, ask the user to reopen the app; do not
   fabricate data.
5. Call `studio_setup_guide`, then `studio_get_draft`.
6. Create a small test plugin: apply the `amoled` template, set a distinctive name and a valid
   package id, call `studio_validate_draft`, then `studio_export_plugin`.
7. Verify the export: call `studio_inspect_package` on the returned path and confirm its SHA-256
   matches. Report the path, size, hash, draft id and validation result.
8. Leave the package in the export folder. Do not install it, grant permissions, or claim it runs.

Constraints:
- Only draft editing, validation, export and inspection. No installs, permissions, rule execution,
  network exposure, shell execution, touchable overlays, interactive screen controls, plugin
  messaging, or scheduled/branching workflows.
- Keep the bridge on loopback. On a PC instead of Termux, use `adb forward tcp:47653 tcp:47653`.
- Never commit or archive keystore.properties or release.keystore.
```

A shorter version for an agent that is already connected:

```text
Use the GameCore Studio MCP. Call studio_status, then studio_setup_guide. Read the live draft,
make a small valid change, validate it, export it, inspect the exported package, and report the
path and SHA-256. If the bridge is unreachable, say so and stop.
```
