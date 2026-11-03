#!/usr/bin/env python3
"""Entry point for the GameCore Studio MCP server.

Run it over stdio from an MCP client; it is not an HTTP daemon. Example:

    python3 tools/gamecore-studio-mcp/server.py

The only output on stdout is protocol JSON. Diagnostics go to stderr.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Allow `python3 server.py` from any working directory without installing a package.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from gamecore_mcp.protocol import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
