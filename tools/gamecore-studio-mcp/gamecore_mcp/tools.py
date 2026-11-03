"""The tools an agent can call.

Every tool either reads the live studio through [StudioBridge] or writes a file the bridge
returned. Nothing here reimplements a studio rule: validation, composition and import all happen
inside the app's own debug build, so an agent cannot drift from what the editor would do.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Callable

from . import capabilities as capability_fallback
from .bridge import BridgeError, StudioBridge

DEFAULT_EXPORT_DIR = "/sdcard/Download/GameCore Project/studio-mcp-exports"
MAX_PACKAGE_BYTES = 4 * 1024 * 1024
REPO_ROOT = Path(__file__).resolve().parents[3]
DEBUG_APK = Path(
    os.environ.get(
        "GAMECORE_STUDIO_APK",
        "/sdcard/Download/GameCore-PluginMaker-1.1.0-debug.apk",
    )
)
SERVER_PATH = REPO_ROOT / "tools" / "gamecore-studio-mcp" / "server.py"

_OBJECT = {"type": "object", "additionalProperties": True}


def _tool(
    name: str,
    description: str,
    properties: dict[str, Any] | None = None,
    required: list[str] | None = None,
    read_only: bool = False,
) -> dict[str, Any]:
    schema: dict[str, Any] = {
        "type": "object",
        "properties": properties or {},
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": {"readOnlyHint": read_only, "destructiveHint": False},
    }


TOOLS: list[dict[str, Any]] = [
    _tool(
        "studio_setup_guide",
        (
            "Return the exact setup steps for connecting this MCP server to the debug Plugin Maker, "
            "including the APK path, the server command and an MCP configuration example. Works even "
            "when the studio is not running."
        ),
        read_only=True,
    ),
    _tool(
        "studio_status",
        "Read the debug bridge status, app package/version, and the id/name/validity of the live draft.",
        read_only=True,
    ),
    _tool(
        "studio_get_draft",
        "Read the complete live draft as the studio's own storage JSON, plus its validation problems.",
        read_only=True,
    ),
    _tool(
        "studio_set_draft",
        (
            "Replace the live draft. The editor updates immediately and autosaves it. The body is the "
            "same JSON returned by studio_get_draft; invalid but well-formed drafts are accepted for editing."
        ),
        properties={"draft": _OBJECT},
        required=["draft"],
    ),
    _tool(
        "studio_validate_draft",
        (
            "Run the studio's real validation rules against the live draft or a candidate draft. "
            "Returns every {field, message} problem; it does not write or export anything."
        ),
        properties={"draft": _OBJECT},
        read_only=True,
    ),
    _tool(
        "studio_export_plugin",
        (
            "Compose a deterministic .gcplugin with the studio's own writer, verify its SHA-256, and "
            "write it to the export directory. Refuses a draft with validation problems."
        ),
        properties={"draft": _OBJECT},
    ),
    _tool(
        "studio_list_templates",
        "List the eight built-in theme templates and their palette values.",
        read_only=True,
    ),
    _tool(
        "studio_apply_template",
        (
            "Apply a built-in theme template to the live draft. Like the editor, this replaces the theme "
            "and keeps the package identity, permissions and other sections."
        ),
        properties={"id": {"type": "string", "description": "Template id from studio_list_templates."}},
        required=["id"],
    ),
    _tool(
        "studio_inspect_package",
        (
            "Inspect a local .gcplugin with the studio's container reader and loss-aware importer. "
            "Returns entries, size, SHA-256, issues, import warnings and the decoded draft when possible."
        ),
        properties={"path": {"type": "string", "description": "Path to a .gcplugin file."}},
        required=["path"],
        read_only=True,
    ),
    _tool(
        "studio_capabilities",
        (
            "Return the runtime-backed capability boundary: supported sections, component vocabulary, "
            "limits and deliberately unsupported features. Falls back to the documented copy offline."
        ),
        read_only=True,
    ),
]


def _text(data: Any) -> dict[str, Any]:
    return {
        "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False, indent=2)}],
        "isError": False,
    }


def _failure(message: str, details: Any = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"error": message}
    if details is not None:
        payload["details"] = details
    return {
        "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=2)}],
        "isError": True,
    }


def _safe_name(raw: str) -> str:
    """Keep the package's own name but never let it traverse out of the export directory."""
    name = os.path.basename(raw or "").strip()
    if not name:
        name = "plugin.gcplugin"
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    if not name.strip(".") or name in {".", ".."}:
        name = "plugin"
    if not name.lower().endswith(".gcplugin"):
        name += ".gcplugin"
    if name in {".gcplugin", "..", "."}:
        name = "plugin.gcplugin"
    return name


def _export_dir() -> Path:
    directory = Path(
        os.environ.get("GAMECORE_STUDIO_EXPORT_DIR", DEFAULT_EXPORT_DIR)
    ).expanduser()
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _setup_guide() -> dict[str, Any]:
    health: dict[str, Any]
    try:
        health = StudioBridge().health()
    except BridgeError as failure:
        health = {"ok": False, **failure.payload}

    config = {
        "mcpServers": {
            "gamecore-studio": {
                "command": "python3",
                "args": [str(SERVER_PATH)],
            }
        }
    }
    opencode_command = f"opencode mcp add gamecore-studio -- python3 {SERVER_PATH}"
    return {
        "whatThisIs": (
            "A local MCP server for the debug build of the GameCore Plugin Maker. It edits the live "
            "draft, validates with the studio's rules, and writes deterministic .gcplugin files."
        ),
        "health": health,
        "steps": [
            "Install the debug APK on the Android device: " + str(DEBUG_APK),
            "Open the Plugin Maker (debug) once; it starts a foreground service with a persistent notification and binds 127.0.0.1:47653.",
            "Register this server with the MCP client: " + opencode_command,
            "For other clients, use the mcpServers JSON in this result with the same command and args.",
            "Call studio_status. A successful reply means the agent is connected to the live studio.",
            "Use studio_set_draft / studio_validate_draft / studio_export_plugin to author and test.",
        ],
        "genericMcpConfig": config,
        "opencodeCommand": opencode_command,
        "debugApk": str(DEBUG_APK),
        "serverPath": str(SERVER_PATH),
        "exportDirectory": str(_export_dir()),
        "notes": [
            "The bridge exists only in debug builds. The release APK has no loopback server.",
            "The bridge runs in a debug-only foreground service. Keep its notification visible; if the OS stops the service, reopen the debug app.",
            "The bridge is bound to 127.0.0.1 and uses a fixed debug token; it is a local convenience, not a security boundary.",
            "The bridge can edit and export a draft. It cannot install a plugin, grant a permission, run a rule or change GameCore.",
            "The MCP client and the app must be on the same device, or reachable through an explicit adb forward.",
        ],
    }


def _status(_: dict[str, Any]) -> dict[str, Any]:
    return StudioBridge().status()


def _get_draft(_: dict[str, Any]) -> dict[str, Any]:
    return StudioBridge().get_draft()


def _set_draft(arguments: dict[str, Any]) -> dict[str, Any]:
    draft = arguments.get("draft")
    if not isinstance(draft, dict):
        raise ValueError("draft must be an object.")
    return StudioBridge().set_draft(draft)


def _validate(arguments: dict[str, Any]) -> dict[str, Any]:
    draft = arguments.get("draft")
    if draft is not None and not isinstance(draft, dict):
        raise ValueError("draft must be an object when present.")
    return StudioBridge().validate(draft)


def _export(arguments: dict[str, Any]) -> dict[str, Any]:
    draft = arguments.get("draft")
    if draft is not None and not isinstance(draft, dict):
        raise ValueError("draft must be an object when present.")
    result = StudioBridge().export(draft)
    encoded = result.get("base64")
    if not isinstance(encoded, str):
        raise ValueError("The bridge did not return package bytes.")
    try:
        package = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as failure:
        raise ValueError(f"The bridge returned invalid base64: {failure}") from failure
    expected_size = result.get("size")
    if isinstance(expected_size, int) and expected_size != len(package):
        raise ValueError("The bridge's package size does not match its bytes.")
    expected_sha = result.get("sha256")
    actual_sha = hashlib.sha256(package).hexdigest()
    if expected_sha != actual_sha:
        raise ValueError("The bridge's SHA-256 does not match its bytes.")

    directory = _export_dir().resolve()
    destination = (directory / _safe_name(str(result.get("fileName", "")))).resolve()
    if destination.parent != directory:
        raise ValueError("The export path escapes the export directory.")
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_bytes(package)
    os.replace(temporary, destination)
    return {
        "path": str(destination),
        "sha256": actual_sha,
        "size": len(package),
        "entries": result.get("entries", []),
        "draftId": result.get("draftId"),
        "versionName": result.get("versionName"),
    }


def _templates(_: dict[str, Any]) -> dict[str, Any]:
    return StudioBridge().templates()


def _apply_template(arguments: dict[str, Any]) -> dict[str, Any]:
    template_id = arguments.get("id")
    if not isinstance(template_id, str) or not template_id.strip():
        raise ValueError("id must be a non-empty template id.")
    return StudioBridge().apply_template(template_id.strip())


def _inspect(arguments: dict[str, Any]) -> dict[str, Any]:
    raw = arguments.get("path")
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("path must be a non-empty file path.")
    path = Path(raw).expanduser()
    if not path.is_file():
        raise ValueError(f"No such .gcplugin file: {path}")
    size = path.stat().st_size
    if size > MAX_PACKAGE_BYTES:
        raise ValueError(f"The package is {size} bytes; the studio importer accepts at most {MAX_PACKAGE_BYTES}.")
    result = StudioBridge().inspect(path.read_bytes())
    result["path"] = str(path)
    return result


def _capabilities(_: dict[str, Any]) -> dict[str, Any]:
    try:
        return StudioBridge().capabilities()
    except BridgeError:
        return capability_fallback.CAPABILITIES


_HANDLERS: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {
    "studio_setup_guide": lambda _: _setup_guide(),
    "studio_status": _status,
    "studio_get_draft": _get_draft,
    "studio_set_draft": _set_draft,
    "studio_validate_draft": _validate,
    "studio_export_plugin": _export,
    "studio_list_templates": _templates,
    "studio_apply_template": _apply_template,
    "studio_inspect_package": _inspect,
    "studio_capabilities": _capabilities,
}


def call_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """Dispatch one `tools/call` request. Tool failures are content, not protocol errors."""
    handler = _HANDLERS.get(name)
    if handler is None:
        return _failure(f"Unknown tool: {name}")
    try:
        return _text(handler(arguments))
    except BridgeError as failure:
        return _failure(str(failure), {"status": failure.status, **failure.payload})
    except (ValueError, OSError) as failure:
        return _failure(str(failure))
    except Exception as failure:  # noqa: BLE001 - a tool error must not kill the server.
        return _failure(f"{failure.__class__.__name__}: {failure}")
