"""GameCore Studio MCP server.

A stdio Model Context Protocol server that edits the live draft in the debug build of the
GameCore Plugin Maker through its loopback bridge, validates with the studio's own rules, and
writes deterministic `.gcplugin` packages.

The package is pure standard library on purpose: the host this runs on is an Android/Termux
device, and an MCP server should not need a virtualenv, Node, or a network install to start.
"""

__version__ = "1.0.0"
