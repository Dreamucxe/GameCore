"""HTTP client for the debug-only bridge inside the Plugin Maker.

The bridge is the app's own code path: it validates with `PluginDraft.problems()` and writes with
`PluginComposer`, so this client never reimplements either. It only moves JSON and bytes.
"""

from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request
from typing import Any

DEFAULT_URL = "http://127.0.0.1:47653"
DEFAULT_TOKEN = "gamecore-studio-debug-v1"
TOKEN_HEADER = "X-GameCore-Studio-Token"
DEFAULT_TIMEOUT = 8.0


class BridgeError(Exception):
    """The bridge refused a request, or could not be reached at all."""

    def __init__(self, status: int, payload: dict[str, Any]) -> None:
        self.status = status
        self.payload = payload
        message = payload.get("message") or payload.get("error") or "The studio bridge failed."
        super().__init__(str(message))


class StudioBridge:
    """A thin, blocking client. The MCP server is single-threaded, so this is enough."""

    def __init__(
        self,
        base_url: str | None = None,
        token: str | None = None,
        timeout: float | None = None,
    ) -> None:
        self.base_url = (base_url or os.environ.get("GAMECORE_STUDIO_URL") or DEFAULT_URL).rstrip("/")
        self.token = token or os.environ.get("GAMECORE_STUDIO_TOKEN") or DEFAULT_TOKEN
        self.timeout = timeout or float(os.environ.get("GAMECORE_STUDIO_TIMEOUT", DEFAULT_TIMEOUT))
        # Never route 127.0.0.1 through an HTTP proxy that the terminal may have configured.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def health(self) -> dict[str, Any]:
        return self._request("GET", "/v1/health", authenticated=False)

    def status(self) -> dict[str, Any]:
        return self._request("GET", "/v1/status")

    def get_draft(self) -> dict[str, Any]:
        return self._request("GET", "/v1/draft")

    def set_draft(self, draft: dict[str, Any]) -> dict[str, Any]:
        return self._request("PUT", "/v1/draft", {"draft": draft})

    def validate(self, draft: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._request("POST", "/v1/validate", {"draft": draft} if draft is not None else {})

    def export(self, draft: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._request("POST", "/v1/export", {"draft": draft} if draft is not None else {})

    def inspect(self, package: bytes) -> dict[str, Any]:
        return self._request(
            "POST",
            "/v1/inspect",
            {"packageBase64": base64.b64encode(package).decode("ascii")},
        )

    def templates(self) -> dict[str, Any]:
        return self._request("GET", "/v1/templates")

    def apply_template(self, template_id: str) -> dict[str, Any]:
        return self._request("POST", "/v1/templates", {"id": template_id})

    def capabilities(self) -> dict[str, Any]:
        return self._request("GET", "/v1/capabilities")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        authenticated: bool = True,
    ) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        if authenticated:
            headers[TOKEN_HEADER] = self.token
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                body = response.read()
                return json.loads(body.decode("utf-8")) if body else {}
        except urllib.error.HTTPError as failure:
            body = failure.read()
            try:
                parsed = json.loads(body.decode("utf-8")) if body else {}
            except (json.JSONDecodeError, UnicodeDecodeError):
                parsed = {"error": "bridge_http_error", "message": body.decode("utf-8", "replace")}
            if not isinstance(parsed, dict):
                parsed = {"error": "bridge_http_error", "message": str(parsed)}
            raise BridgeError(failure.code, parsed) from failure
        except urllib.error.URLError as failure:
            raise BridgeError(
                0,
                {
                    "error": "bridge_unreachable",
                    "message": (
                        f"Could not reach the studio bridge at {self.base_url}. "
                        "Install and open the debug build of the Plugin Maker; the release build "
                        "deliberately has no bridge."
                    ),
                    "detail": str(failure.reason),
                },
            ) from failure
        except TimeoutError as failure:
            raise BridgeError(
                0,
                {
                    "error": "bridge_timeout",
                    "message": f"The studio bridge did not answer within {self.timeout:g}s.",
                },
            ) from failure
