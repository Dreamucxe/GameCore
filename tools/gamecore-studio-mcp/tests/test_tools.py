from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tests.fake_bridge import PACKAGE, FakeBridge
from gamecore_mcp import tools


class ToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.bridge = FakeBridge().start()
        self.temporary = tempfile.TemporaryDirectory()
        self.environment = patch.dict(
            os.environ,
            {
                "GAMECORE_STUDIO_URL": self.bridge.url,
                "GAMECORE_STUDIO_EXPORT_DIR": self.temporary.name,
            },
        )
        self.environment.start()

    def tearDown(self) -> None:
        self.environment.stop()
        self.bridge.stop()
        self.temporary.cleanup()

    def call(self, name: str, arguments: dict | None = None) -> dict:
        return tools.call_tool(name, arguments or {})

    def data(self, response: dict) -> dict:
        self.assertFalse(response["isError"], response)
        return json.loads(response["content"][0]["text"])

    def test_status_reads_the_live_app(self) -> None:
        data = self.data(self.call("studio_status"))
        self.assertEqual("com.gamecore.pluginmaker.debug", data["app"])
        self.assertEqual("com.example.mcp", data["draft"]["id"])
        self.assertEqual(1, len(self.bridge.requests))

    def test_set_draft_uses_the_draft_endpoint_and_token(self) -> None:
        draft = {"id": "com.example.mcp", "name": "Changed"}
        data = self.data(self.call("studio_set_draft", {"draft": draft}))
        self.assertEqual("com.example.mcp", data["draft"]["id"])
        request = self.bridge.requests[-1]
        self.assertEqual("PUT", request["method"])
        self.assertEqual("/v1/draft", request["path"])
        self.assertEqual(tools.StudioBridge().token, request["token"])

    def test_validate_does_not_require_a_draft(self) -> None:
        data = self.data(self.call("studio_validate_draft"))
        self.assertTrue(data["valid"])

    def test_export_writes_verified_bytes_into_the_export_directory(self) -> None:
        data = self.data(self.call("studio_export_plugin"))
        path = Path(data["path"])
        self.assertTrue(path.is_file())
        self.assertEqual(PACKAGE, path.read_bytes())
        self.assertEqual(hashlib.sha256(PACKAGE).hexdigest(), data["sha256"])
        self.assertEqual(path.parent, Path(self.temporary.name).resolve())
        self.assertFalse(list(path.parent.glob("*.tmp")))

    def test_export_filename_cannot_escape_the_directory(self) -> None:
        self.assertEqual("evil.gcplugin", tools._safe_name("../../evil.gcplugin"))
        self.assertEqual("plugin.gcplugin", tools._safe_name(".."))
        self.assertEqual("a_b.gcplugin", tools._safe_name("a b"))

    def test_inspect_reads_a_local_package_and_returns_the_import(self) -> None:
        package = Path(self.temporary.name) / "sample.gcplugin"
        package.write_bytes(PACKAGE)
        data = self.data(self.call("studio_inspect_package", {"path": str(package)}))
        self.assertEqual(str(package), data["path"])
        self.assertTrue(data["import"]["ok"])
        self.assertEqual(hashlib.sha256(PACKAGE).hexdigest(), data["inspection"]["sha256"])

    def test_inspect_rejects_a_missing_file(self) -> None:
        response = self.call("studio_inspect_package", {"path": "/nope/none.gcplugin"})
        self.assertTrue(response["isError"])

    def test_templates_and_apply(self) -> None:
        data = self.data(self.call("studio_list_templates"))
        self.assertEqual("minimal", data["templates"][0]["id"])
        applied = self.data(self.call("studio_apply_template", {"id": "minimal"}))
        self.assertEqual("com.example.mcp", applied["draft"]["id"])
        self.assertTrue(self.bridge.requests[-1]["body"])

    def test_capabilities_falls_back_when_the_bridge_is_absent(self) -> None:
        with patch.dict(os.environ, {"GAMECORE_STUDIO_URL": "http://127.0.0.1:1"}):
            data = self.data(self.call("studio_capabilities"))
        self.assertIn("sections", data)
        self.assertIn("unsupported", data)

    def test_setup_guide_works_without_the_bridge(self) -> None:
        with patch.dict(os.environ, {"GAMECORE_STUDIO_URL": "http://127.0.0.1:1"}):
            data = self.data(self.call("studio_setup_guide"))
        self.assertFalse(data["health"]["ok"])
        self.assertTrue(any("Open the Plugin Maker" in step for step in data["steps"]))
        self.assertIn("mcpServers", data["genericMcpConfig"])

    def test_unknown_tool_is_a_tool_error(self) -> None:
        response = self.call("not_a_tool")
        self.assertTrue(response["isError"])

    def test_unreachable_bridge_is_a_tool_error_with_a_useful_message(self) -> None:
        self.bridge.stop()
        response = self.call("studio_status")
        self.assertTrue(response["isError"])
        payload = json.loads(response["content"][0]["text"])
        self.assertEqual("bridge_unreachable", payload["details"]["error"])


if __name__ == "__main__":
    unittest.main()
