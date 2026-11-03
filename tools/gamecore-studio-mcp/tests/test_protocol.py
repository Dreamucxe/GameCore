from __future__ import annotations

import io
import json
import unittest

from gamecore_mcp.protocol import handle_line, serve


class ProtocolTest(unittest.TestCase):
    def test_initialize_advertises_tools_and_prompts(self) -> None:
        response = handle_line(
            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        )
        self.assertEqual(1, response["id"])
        self.assertEqual("2024-11-05", response["result"]["protocolVersion"])
        self.assertIn("tools", response["result"]["capabilities"])
        self.assertIn("prompts", response["result"]["capabilities"])

    def test_tools_list_names_the_setup_tool(self) -> None:
        response = handle_line(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}))
        names = [tool["name"] for tool in response["result"]["tools"]]
        self.assertIn("studio_setup_guide", names)
        self.assertIn("studio_export_plugin", names)

    def test_prompts_list_and_get_the_setup_prompt(self) -> None:
        listed = handle_line(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "prompts/list"}))
        self.assertEqual("setup_plugin_studio", listed["result"]["prompts"][0]["name"])
        fetched = handle_line(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 4,
                    "method": "prompts/get",
                    "params": {"name": "setup_plugin_studio"},
                }
            )
        )
        text = fetched["result"]["messages"][0]["content"]["text"]
        self.assertIn("127.0.0.1:47653", text)
        self.assertIn("studio_status", text)

    def test_notifications_get_no_response(self) -> None:
        response = handle_line(
            json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"})
        )
        self.assertIsNone(response)

    def test_parse_error_is_reported(self) -> None:
        response = handle_line("{not json")
        self.assertEqual(-32700, response["error"]["code"])

    def test_invalid_jsonrpc_version_is_reported(self) -> None:
        response = handle_line(json.dumps({"jsonrpc": "1.0", "id": 5, "method": "ping"}))
        self.assertEqual(-32600, response["error"]["code"])

    def test_unknown_method_is_reported(self) -> None:
        response = handle_line(json.dumps({"jsonrpc": "2.0", "id": 6, "method": "nope"}))
        self.assertEqual(-32601, response["error"]["code"])

    def test_unknown_tool_is_content_not_a_protocol_error(self) -> None:
        response = handle_line(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 7,
                    "method": "tools/call",
                    "params": {"name": "nope", "arguments": {}},
                }
            )
        )
        self.assertTrue(response["result"]["isError"])

    def test_batch_answers_requests_and_omits_notifications(self) -> None:
        batch = [
            {"jsonrpc": "2.0", "id": 8, "method": "ping"},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 9, "method": "tools/list"},
        ]
        responses = handle_line(json.dumps(batch))
        self.assertEqual(2, len(responses))
        self.assertEqual(8, responses[0]["id"])
        self.assertEqual(9, responses[1]["id"])

    def test_serve_writes_one_response_per_request(self) -> None:
        source = io.StringIO(
            "\n".join(
                [
                    json.dumps({"jsonrpc": "2.0", "id": 10, "method": "initialize", "params": {}}),
                    json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}),
                    json.dumps({"jsonrpc": "2.0", "id": 11, "method": "ping"}),
                ]
            )
            + "\n"
        )
        output = io.StringIO()
        serve(source, output)
        lines = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(2, len(lines))
        self.assertEqual(10, lines[0]["id"])
        self.assertEqual(11, lines[1]["id"])


if __name__ == "__main__":
    unittest.main()
