# GameCore Studio MCP

A local MCP server for the debug build of the GameCore Plugin Maker. It edits the live draft,
validates with the studio's own rules, composes deterministic `.gcplugin` packages and inspects
them with the studio importer.

```bash
bash tools/gamecore-studio-mcp/setup.sh --test
python3 tools/gamecore-studio-mcp/server.py                 # stdio, for an MCP client
opencode mcp add gamecore-studio -- python3 "$PWD/tools/gamecore-studio-mcp/server.py"
```

Read [docs/PLUGIN_STUDIO_MCP.md](../../docs/PLUGIN_STUDIO_MCP.md) for the architecture tree, every
tool and route, the six studio sections, setup on Termux and on a USB-connected computer, tests
and the security boundary. [AGENT_PROMPT.md](AGENT_PROMPT.md) is the copy-paste prompt that lets a
coding agent set the connection up and prove it.

The bridge exists only in the debug APK (`com.gamecore.pluginmaker.debug`). The release app has no
listener. Nothing here installs a plugin, grants a permission or executes plugin code.
