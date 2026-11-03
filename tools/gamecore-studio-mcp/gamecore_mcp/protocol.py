"""JSON-RPC 2.0 over newline-delimited stdio, as MCP specifies for a local server.

The protocol layer is deliberately separate from the tools: it knows how to frame a message,
answer a request, leave a notification unanswered, and turn an exception into a JSON-RPC error.
It knows nothing about plugins or HTTP.
"""

from __future__ import annotations

import json
import logging
import sys
from typing import Any, TextIO

from . import __version__, prompts, tools

LOG = logging.getLogger("gamecore_studio_mcp.protocol")

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "gamecore-studio"
SERVER_VERSION = __version__

# JSON-RPC 2.0 error codes.
PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603


class RpcError(Exception):
    """An error with a JSON-RPC code and an optional structured data payload."""

    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


def _write(stdout: TextIO, message: dict[str, Any]) -> None:
    stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    stdout.flush()


def _error(request_id: Any, code: int, message: str, data: Any = None) -> dict[str, Any]:
    error: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def _result(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def dispatch(method: str, params: Any) -> Any:
    """Run one request and return its result, or raise [RpcError]."""
    if method == "initialize":
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}, "prompts": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        }
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": tools.TOOLS}
    if method == "tools/call":
        if not isinstance(params, dict):
            raise RpcError(INVALID_PARAMS, "tools/call params must be an object.")
        name = params.get("name")
        arguments = params.get("arguments", {})
        if not isinstance(name, str) or not name:
            raise RpcError(INVALID_PARAMS, "tools/call requires a tool name.")
        if not isinstance(arguments, dict):
            raise RpcError(INVALID_PARAMS, "tools/call arguments must be an object.")
        return tools.call_tool(name, arguments)
    if method == "prompts/list":
        return prompts.prompt_list()
    if method == "prompts/get":
        if not isinstance(params, dict):
            raise RpcError(INVALID_PARAMS, "prompts/get params must be an object.")
        name = params.get("name")
        if not isinstance(name, str) or not name:
            raise RpcError(INVALID_PARAMS, "prompts/get requires a prompt name.")
        try:
            return prompts.prompt_get(name)
        except KeyError:
            raise RpcError(INVALID_PARAMS, f"Unknown prompt: {name}") from None
    if method in ("notifications/initialized", "notifications/cancelled"):
        return None
    raise RpcError(METHOD_NOT_FOUND, f"Unknown method: {method}")


def handle_message(message: Any) -> dict[str, Any] | None:
    """Handle one decoded message. Returns the response, or None for a notification."""
    if not isinstance(message, dict):
        return _error(None, INVALID_REQUEST, "A JSON-RPC message must be an object.")
    if message.get("jsonrpc") != "2.0":
        return _error(message.get("id"), INVALID_REQUEST, 'Only JSON-RPC 2.0 is supported.')

    is_notification = "id" not in message
    request_id = message.get("id")
    method = message.get("method")
    if not isinstance(method, str) or not method:
        if is_notification:
            return None
        return _error(request_id, INVALID_REQUEST, "A request needs a method.")

    try:
        result = dispatch(method, message.get("params"))
    except RpcError as error:
        if is_notification:
            return None
        return _error(request_id, error.code, error.message, error.data)
    except Exception as failure:  # noqa: BLE001 - the protocol boundary must not crash.
        LOG.exception("Unhandled tool failure for %s", method)
        if is_notification:
            return None
        return _error(request_id, INTERNAL_ERROR, f"Internal error: {failure.__class__.__name__}")

    if is_notification:
        return None
    return _result(request_id, result)


def handle_line(line: str) -> dict[str, Any] | list[Any] | None:
    """Decode one line of JSON and answer it. A batch is handled in order."""
    stripped = line.strip()
    if not stripped:
        return None
    try:
        message = json.loads(stripped)
    except json.JSONDecodeError as failure:
        return _error(None, PARSE_ERROR, "The line is not valid JSON.", {"detail": str(failure)})
    if isinstance(message, list):
        if not message:
            return _error(None, INVALID_REQUEST, "An empty batch is not a request.")
        responses = [response for item in message if (response := handle_message(item)) is not None]
        return responses or None
    return handle_message(message)


def serve(stdin: TextIO, stdout: TextIO) -> None:
    """Serve until stdin closes. Diagnostics go to stderr, never to the protocol stream."""
    LOG.info("GameCore Studio MCP %s ready", SERVER_VERSION)
    for line in stdin:
        response = handle_line(line)
        if response is not None:
            _write(stdout, response)


def main() -> int:
    logging.basicConfig(
        stream=sys.stderr,
        level=logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    # The protocol is UTF-8 regardless of the terminal locale on the device.
    sys.stdin.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    try:
        serve(sys.stdin, sys.stdout)
    except KeyboardInterrupt:
        return 130
    return 0
