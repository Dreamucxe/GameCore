"""A tiny stand-in for the app's loopback bridge.

The real bridge is Kotlin and runs inside the debug APK. These tests need to prove the Python side
speaks the same contract, so this server implements only the routes the client uses and records
every request for assertions. It is not a second implementation of validation or packaging.
"""

from __future__ import annotations

import base64
import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from gamecore_mcp.bridge import DEFAULT_TOKEN, TOKEN_HEADER

PACKAGE = b"PK\x03\x04fake-gcplugin-bytes"


class FakeBridge:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.server.fake = self  # type: ignore[attr-defined]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        host, port = self.server.server_address
        return f"http://{host}:{port}"

    def start(self) -> "FakeBridge":
        self.thread.start()
        return self

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args: Any) -> None:  # noqa: D401 - silence test output.
        return

    def do_GET(self) -> None:
        self._handle()

    def do_PUT(self) -> None:
        self._handle()

    def do_POST(self) -> None:
        self._handle()

    def _handle(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        fake: FakeBridge = self.server.fake  # type: ignore[attr-defined]
        token = self.headers.get(TOKEN_HEADER)
        fake.requests.append(
            {"method": self.command, "path": self.path, "token": token, "body": raw}
        )
        if self.path == "/v1/health":
            self._send(200, {"ok": True, "protocol": 1, "bridge": "fake"})
            return
        if token != DEFAULT_TOKEN:
            self._send(401, {"error": "unauthorized", "message": "token"})
            return
        if self.path == "/v1/status":
            self._send(
                200,
                {
                    "protocol": 1,
                    "app": "com.gamecore.pluginmaker.debug",
                    "version": "1.1.0-debug",
                    "draft": {
                        "id": "com.example.mcp",
                        "name": "MCP Test",
                        "version": 1,
                        "versionName": "1.0.0",
                        "valid": True,
                        "problemCount": 0,
                    },
                },
            )
        elif self.path == "/v1/draft" and self.command in ("GET", "PUT", "POST"):
            body = json.loads(raw) if raw else {}
            self._send(200, {"draft": body.get("draft", {"id": "com.example.mcp"}), "validation": {"valid": True, "problems": []}})
        elif self.path == "/v1/validate":
            self._send(200, {"valid": True, "problems": [], "draftId": "com.example.mcp"})
        elif self.path == "/v1/export":
            self._send(
                200,
                {
                    "fileName": "com.example.mcp-1.0.0.gcplugin",
                    "sha256": hashlib.sha256(PACKAGE).hexdigest(),
                    "size": len(PACKAGE),
                    "entries": ["manifest.json", "theme.json"],
                    "base64": base64.b64encode(PACKAGE).decode("ascii"),
                    "draftId": "com.example.mcp",
                    "versionName": "1.0.0",
                },
            )
        elif self.path == "/v1/inspect":
            body = json.loads(raw)
            decoded = base64.b64decode(body["packageBase64"])
            self._send(
                200,
                {
                    "inspection": {
                        "entries": [{"name": "manifest.json", "bytes": len(decoded)}],
                        "totalBytes": len(decoded),
                        "sha256": hashlib.sha256(decoded).hexdigest(),
                        "issues": [],
                        "manifestJson": "{}",
                        "themeJson": "{}",
                    },
                    "import": {"ok": True, "draft": {"id": "com.example.mcp"}, "warnings": []},
                },
            )
        elif self.path == "/v1/templates" and self.command == "GET":
            self._send(200, {"templates": [{"id": "minimal", "name": "Minimal"}]})
        elif self.path == "/v1/templates" and self.command == "POST":
            self._send(200, {"draft": {"id": "com.example.mcp"}, "validation": {"valid": True, "problems": []}})
        elif self.path == "/v1/capabilities":
            self._send(200, {"sections": [{"id": "theme"}], "unsupported": []})
        else:
            self._send(404, {"error": "not_found", "message": self.path})

    def _send(self, status: int, body: dict[str, Any]) -> None:
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True
