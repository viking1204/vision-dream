import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from localdream_perf_harness import validate_scenario
from localdream_perf_protocol import MCP_PROTOCOL_VERSION, HttpResult, ProtocolExecutionError, SseEvent, protocol_parity


class FixtureTransport:
    def __init__(self):
        self.calls = []
        self.initializations = 0
        self.creations = 0

    def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
        payload = json.loads((body or b"{}").decode())
        self.calls.append((method, path, payload, headers or {}))
        if payload.get("method") == "initialize":
            if payload["params"].get("protocolVersion") != MCP_PROTOCOL_VERSION:
                return HttpResult(200, {}, b'{"error":{"data":{"code":"UNSUPPORTED_PROTOCOL_VERSION"}}}')
            self.initializations += 1
            return HttpResult(
                200,
                {"mcp-session-id": f"session-{self.initializations}"},
                json.dumps({"result": {"protocolVersion": MCP_PROTOCOL_VERSION}}).encode(),
            )
        if payload.get("method") == "tools/call":
            tool = payload["params"]["name"]
            if tool == "generation.create":
                self.creations += 1
                return HttpResult(
                    200,
                    {},
                    json.dumps(
                        {"result": {"jobId": f"job-{self.creations}", "task": "working"}},
                    ).encode(),
                )
            if tool == "jobs.get":
                job_id = payload["params"]["arguments"]["jobId"]
                return HttpResult(
                    200,
                    {},
                    json.dumps(
                        {
                            "result": {
                                "jobId": job_id,
                                "task": "succeeded",
                                "image": f"/mcp/images/{job_id}/capability",
                                "content": [{"type": "resource_link", "uri": f"/mcp/images/{job_id}/capability", "mimeType": "image/png"}],
                            },
                        },
                    ).encode(),
                )
            if tool == "jobs.cancel":
                job_id = payload["params"]["arguments"]["jobId"]
                confirmation_id = payload["params"].get("confirmationId")
                if confirmation_id is None:
                    return HttpResult(
                        200,
                        {},
                        json.dumps({"error": {"data": {"code": "CONFIRMATION_REQUIRED"}}}).encode(),
                    )
                if confirmation_id != "approved-cancel" or getattr(self, "confirmation_consumed", False):
                    return HttpResult(
                        200,
                        {},
                        json.dumps({"error": {"data": {"code": "CONFIRMATION_INVALID"}}}).encode(),
                    )
                self.confirmation_consumed = True
                return HttpResult(200, {}, json.dumps({"result": {"jobId": job_id, "task": "cancelled"}}).encode())
        if method == "GET" and path.startswith("/mcp/images/"):
            return HttpResult(200, {"content-type": "image/png"}, b"png-bytes")
        return HttpResult(200, {}, b'{"data":[{"url":"/v1/images/files/image-1"}]}')

    def open_sse(self, path, headers, timeout_ms):
        self.calls.append(("GET", path, {}, headers))
        self.assert_sse_request(path, headers)
        return FixtureSseResponse([SseEvent(None, "ready", "{}"), SseEvent(10, "task", '{"jobId":"job-1","task":"working"}')])

    def assert_sse_request(self, path, headers):
        if path != "/mcp" or headers.get("Mcp-Session-Id") != "session-1":
            raise AssertionError("W7 must reconnect through the initialized session")


class FixtureSseResponse:
    status = 200
    headers = {"content-type": "text/event-stream"}

    def __init__(self, events):
        self._events = events
        self.closed = False

    def events(self):
        yield from self._events

    def close(self):
        self.closed = True


class ProtocolParityTest(unittest.TestCase):
    def setUp(self):
        self.scenario = validate_scenario(ROOT / "scenarios" / "v1" / "W7.json")

    def test_w7_uses_the_same_fixed_inputs_for_v1_and_mcp(self):
        v1 = FixtureTransport()
        mcp = FixtureTransport()
        result = protocol_parity(v1, mcp, self.scenario, confirmation_id_supplier=lambda: "approved-cancel")

        self.assertEqual("/v1/images/generations", v1.calls[0][1])
        self.assertEqual("initialize", mcp.calls[0][2]["method"])
        creation = next(call for call in mcp.calls if call[2].get("params", {}).get("name") == "generation.create")
        self.assertEqual("tools/call", creation[2]["method"])
        self.assertEqual("generation.create", creation[2]["params"]["name"])
        self.assertEqual(self.scenario["fixtures"]["prompt"], v1.calls[0][2]["prompt"])
        self.assertEqual(self.scenario["fixtures"]["prompt"], creation[2]["params"]["arguments"]["prompt"])
        self.assertEqual(self.scenario["fixtures"]["seed"], creation[2]["params"]["arguments"]["seed"])
        self.assertEqual(self.scenario["request"]["width"], creation[2]["params"]["arguments"]["width"])
        self.assertEqual(self.scenario["request"]["height"], creation[2]["params"]["arguments"]["height"])
        self.assertEqual(self.scenario["request"]["scheduler"], creation[2]["params"]["arguments"]["scheduler"])
        self.assertEqual(self.scenario["request"]["steps"], creation[2]["params"]["arguments"]["steps"])
        self.assertEqual(self.scenario["request"]["cfg"], creation[2]["params"]["arguments"]["cfg"])
        self.assertTrue(result["parity"]["sharedModel"])
        self.assertTrue(result["parity"]["fixtureParity"])

    def test_w7_exercises_lifecycle_reconnect_capability_download_and_confirmed_cancel(self):
        v1 = FixtureTransport()
        mcp = FixtureTransport()

        result = protocol_parity(v1, mcp, self.scenario, confirmation_id_supplier=lambda: "approved-cancel")

        tools = [call[2]["params"]["name"] for call in mcp.calls if call[2].get("method") == "tools/call"]
        self.assertEqual(["generation.create", "jobs.get", "generation.create", "jobs.cancel", "jobs.cancel", "jobs.cancel", "jobs.get"], tools)
        self.assertEqual(2, mcp.initializations)
        cancels = [call for call in mcp.calls if call[2].get("params", {}).get("name") == "jobs.cancel"]
        required, cancel, replay = cancels
        reconnect = [call for call in mcp.calls if call[2].get("params", {}).get("name") == "jobs.get"][-1]
        self.assertNotIn("confirmationId", required[2]["params"])
        self.assertEqual("approved-cancel", cancel[2]["params"]["confirmationId"])
        self.assertEqual("approved-cancel", replay[2]["params"]["confirmationId"])
        self.assertEqual("session-2", reconnect[3]["Mcp-Session-Id"])
        stream_calls = [call for call in mcp.calls if call[0] == "GET" and call[1] == "/mcp"]
        self.assertEqual(2, len(stream_calls))
        self.assertEqual({"Mcp-Session-Id": "session-1"}, stream_calls[0][3])
        self.assertEqual({"Mcp-Session-Id": "session-1", "Last-Event-ID": "9"}, stream_calls[1][3])
        self.assertIn(("GET", "/mcp/images/job-1/capability", {}, {}), mcp.calls)
        self.assertEqual(b"png-bytes", result["mcp"]["download"])
        self.assertTrue(all(result["parity"].values()))

    def test_w7_rejects_legacy_image_field_without_standard_resource_link(self):
        class LegacyImageOnly(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                result = super().request(method, path, body, headers, timeout_ms)
                payload = json.loads((body or b"{}").decode())
                if payload.get("params", {}).get("name") == "jobs.get":
                    value = json.loads(result.body)
                    value["result"].pop("content")
                    return HttpResult(result.status, result.headers, json.dumps(value).encode())
                return result

        with self.assertRaisesRegex(ProtocolExecutionError, "resource_link"):
            protocol_parity(FixtureTransport(), LegacyImageOnly(), self.scenario, confirmation_id_supplier=lambda: "approved-cancel")

    def test_w7_requires_cancel_confirmation_and_task_event(self):
        with self.assertRaisesRegex(ProtocolExecutionError, "confirmation"):
            protocol_parity(FixtureTransport(), FixtureTransport(), self.scenario)

        class MissingTaskEvent(FixtureTransport):
            def open_sse(self, path, headers, timeout_ms):
                return FixtureSseResponse([SseEvent(None, "ready", "{}")])

        with self.assertRaisesRegex(ProtocolExecutionError, "task event"):
            protocol_parity(FixtureTransport(), MissingTaskEvent(), self.scenario, confirmation_id_supplier=lambda: "approved-cancel")

    def test_w7_rejects_an_unsupported_mcp_protocol_version(self):
        class OldVersion(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                if json.loads((body or b"{}").decode()).get("method") == "initialize":
                    return HttpResult(200, {"mcp-session-id": "old-session"}, b'{"result":{"protocolVersion":"2025-03-26"}}')
                return super().request(method, path, body, headers, timeout_ms)

        with self.assertRaisesRegex(ProtocolExecutionError, "unsupported protocol version"):
            protocol_parity(FixtureTransport(), OldVersion(), self.scenario, confirmation_id_supplier=lambda: "approved-cancel")

    def test_w7_accepts_case_insensitive_http_response_headers(self):
        class CanonicalHttpHeaders(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                result = super().request(method, path, body, headers, timeout_ms)
                normalized = {
                    ("Mcp-Session-Id" if key == "mcp-session-id" else "Content-Type" if key == "content-type" else key): value
                    for key, value in result.headers.items()
                }
                return HttpResult(result.status, normalized, result.body)

        result = protocol_parity(
            FixtureTransport(),
            CanonicalHttpHeaders(),
            self.scenario,
            confirmation_id_supplier=lambda: "approved-cancel",
        )

        self.assertTrue(result["parity"]["taskEvent"])

    def test_w7_rejects_mcp_without_a_real_job_id(self):
        class MissingJob(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                result = super().request(method, path, body, headers, timeout_ms)
                if json.loads((body or b"{}").decode()).get("method") == "tools/call":
                    return HttpResult(200, {}, b'{"result":{"task":"working"}}')
                return result

        with self.assertRaisesRegex(ProtocolExecutionError, "jobId"):
            protocol_parity(FixtureTransport(), MissingJob(), self.scenario)


if __name__ == "__main__":
    unittest.main()
