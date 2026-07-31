import json
import sys
import unittest
from http.client import RemoteDisconnected
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from localdream_perf_harness import validate_scenario
from localdream_perf_protocol import (
    MCP_PROTOCOL_VERSION,
    HttpResult,
    ProtocolExecutionError,
    SseEvent,
    UrlLibTransport,
    _UrlLibSseResponse,
    execute_v1_generation,
    protocol_parity,
    scheduler_api_id,
)


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
                arguments = payload["params"]["arguments"]
                if not arguments.get("idempotencyKey", "").startswith("w7:W7:1:"):
                    return HttpResult(200, {}, b'{"error":{"data":{"code":"INVALID_PARAMS"}}}')
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
                image_path = "/assets/history:1"
                return HttpResult(
                    200,
                    {},
                    json.dumps(
                        {
                            "result": {
                                "jobId": job_id,
                                "task": "succeeded",
                                "image": image_path,
                                "content": [{"type": "resource_link", "uri": image_path, "mimeType": "image/png"}],
                            },
                        },
                    ).encode(),
                )
            if tool == "jobs.cancel":
                arguments = payload["params"]["arguments"]
                job_id = arguments["jobId"]
                if arguments.get("dryRun") is not False or not arguments.get("idempotencyKey"):
                    return HttpResult(200, {}, b'{"error":{"data":{"code":"INVALID_PARAMS"}}}')
                if getattr(self, "cancel_arguments", arguments) != arguments:
                    return HttpResult(200, {}, b'{"error":{"data":{"code":"IDEMPOTENCY_KEY_CONFLICT"}}}')
                self.cancel_arguments = arguments
                return HttpResult(200, {}, json.dumps({"result": {"jobId": job_id, "task": "cancelled"}}).encode())
        if method == "GET" and path.startswith("/assets/"):
            return HttpResult(200, {"content-type": "image/png"}, _png_bytes(1024, 1024))
        return HttpResult(200, {}, b'{"data":[{"url":"/v1/images/files/image-1"}]}')

    def open_sse(self, path, headers, timeout_ms):
        self.calls.append(("GET", path, {}, headers))
        self.assert_sse_request(path, headers)
        return FixtureSseResponse(
            [SseEvent(None, "ready", "{}")]
            + [SseEvent(1, "task", '{"jobId":"job-1","task":"working"}')]
            + [
                SseEvent(
                    step + 1,
                    "progress",
                    f'{{"jobId":"job-1","task":"working","step":{step},"totalSteps":20}}',
                )
                for step in range(1, 21)
            ]
            + [SseEvent(22, "task", '{"jobId":"job-1","task":"completed"}')],
        )

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
    def test_urllib_request_normalizes_connection_reset_without_reflecting_secret(self):
        transport = UrlLibTransport("http://127.0.0.1:1", "secret-token")
        with patch(
            "localdream_perf_protocol.urlopen",
            side_effect=RemoteDisconnected("Bearer secret-token disconnected"),
        ):
            with self.assertRaisesRegex(ProtocolExecutionError, "RemoteDisconnected") as error:
                transport.request("GET", "/health")

        self.assertNotIn("secret-token", str(error.exception))
        self.assertNotIn("disconnected", str(error.exception))

    def test_urllib_sse_open_and_read_normalize_recoverable_disconnects(self):
        transport = UrlLibTransport("http://127.0.0.1:1", "secret-token")
        with patch(
            "localdream_perf_protocol.urlopen",
            side_effect=ConnectionResetError("Bearer secret-token reset"),
        ):
            with self.assertRaisesRegex(ProtocolExecutionError, "ConnectionResetError") as error:
                transport.open_sse("/mcp", {}, 1_000)
        self.assertNotIn("secret-token", str(error.exception))

        class TimeoutLines:
            status = 200
            headers = {}

            def __iter__(self):
                raise TimeoutError("Bearer secret-token timeout")

            def close(self):
                pass

        with self.assertRaisesRegex(ProtocolExecutionError, "TimeoutError") as error:
            list(_UrlLibSseResponse(TimeoutLines()).events())
        self.assertNotIn("secret-token", str(error.exception))

    def setUp(self):
        self.scenario = validate_scenario(ROOT / "scenarios" / "v1" / "W7.json")

    def test_w7_uses_the_same_fixed_inputs_for_v1_and_mcp(self):
        v1 = FixtureTransport()
        mcp = FixtureTransport()
        result = protocol_parity(v1, mcp, self.scenario)

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
        expected_scheduler = scheduler_api_id(self.scenario["request"]["scheduler"])
        self.assertEqual(expected_scheduler, v1.calls[0][2]["scheduler"])
        self.assertEqual(expected_scheduler, creation[2]["params"]["arguments"]["scheduler"])
        self.assertEqual(self.scenario["request"]["steps"], creation[2]["params"]["arguments"]["steps"])
        self.assertEqual(self.scenario["request"]["cfg"], creation[2]["params"]["arguments"]["cfg"])
        self.assertEqual("w7:W7:1:fixture:primary-generation", creation[2]["params"]["arguments"]["idempotencyKey"])
        self.assertTrue(result["parity"]["sharedModel"])
        self.assertTrue(result["parity"]["fixtureParity"])

    def test_scheduler_display_names_translate_to_the_openai_api_ids(self):
        self.assertEqual("euler_a", scheduler_api_id("Euler A"))
        self.assertEqual("euler", scheduler_api_id("Euler"))
        with self.assertRaisesRegex(ProtocolExecutionError, "unsupported scheduler label"):
            scheduler_api_id("Euler ancestral")

    def test_w7_exercises_lifecycle_reconnect_stable_asset_download_and_token_authorized_cancel(self):
        v1 = FixtureTransport()
        mcp = FixtureTransport()

        result = protocol_parity(v1, mcp, self.scenario)

        tools = [call[2]["params"]["name"] for call in mcp.calls if call[2].get("method") == "tools/call"]
        self.assertEqual(["generation.create", "jobs.get", "generation.create", "jobs.cancel", "jobs.cancel", "jobs.get"], tools)
        creations = [call for call in mcp.calls if call[2].get("params", {}).get("name") == "generation.create"]
        self.assertEqual(self.scenario["request"]["steps"], creations[0][2]["params"]["arguments"]["steps"])
        self.assertEqual(50, creations[1][2]["params"]["arguments"]["steps"])
        self.assertEqual(2, mcp.initializations)
        cancels = [call for call in mcp.calls if call[2].get("params", {}).get("name") == "jobs.cancel"]
        cancel, replay = cancels
        reconnect = [call for call in mcp.calls if call[2].get("params", {}).get("name") == "jobs.get"][-1]
        self.assertEqual(cancel[2]["params"]["arguments"], replay[2]["params"]["arguments"])
        self.assertNotIn("confirmationId", cancel[2]["params"])
        self.assertNotIn("confirmationId", replay[2]["params"])
        self.assertFalse(cancel[2]["params"]["arguments"]["dryRun"])
        self.assertEqual("w7:W7:1:fixture:cancel:job-2", cancel[2]["params"]["arguments"]["idempotencyKey"])
        self.assertEqual({"jobId", "dryRun", "idempotencyKey"}, set(cancel[2]["params"]["arguments"]))
        self.assertEqual("session-2", reconnect[3]["Mcp-Session-Id"])
        stream_calls = [call for call in mcp.calls if call[0] == "GET" and call[1] == "/mcp"]
        self.assertEqual(2, len(stream_calls))
        self.assertEqual({"Mcp-Session-Id": "session-1"}, stream_calls[0][3])
        self.assertEqual({"Mcp-Session-Id": "session-1", "Last-Event-ID": "1"}, stream_calls[1][3])
        self.assertIn(("GET", "/assets/history:1", {}, {}), mcp.calls)
        self.assertEqual(_png_bytes(1024, 1024), result["mcp"]["download"])
        self.assertEqual("image/png", result["mcp"]["downloadEvidence"]["contentType"])
        self.assertEqual(1024, result["mcp"]["downloadEvidence"]["width"])
        self.assertEqual(20, len(result["mcp"]["progressEvents"]))
        self.assertEqual({"eventId": 2, "jobId": "job-1", "step": 1, "totalSteps": 20}, result["mcp"]["progressEvents"][0])
        self.assertEqual({"eventId": 21, "jobId": "job-1", "step": 20, "totalSteps": 20}, result["mcp"]["progressEvents"][-1])
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
            protocol_parity(FixtureTransport(), LegacyImageOnly(), self.scenario)

    def test_w7_requires_completed_task_event(self):
        class MissingTaskEvent(FixtureTransport):
            def open_sse(self, path, headers, timeout_ms):
                return FixtureSseResponse(
                    [
                        SseEvent(step, "progress", f'{{"jobId":"job-1","step":{step},"totalSteps":20}}')
                        for step in range(1, 21)
                    ],
                )

        with self.assertRaisesRegex(ProtocolExecutionError, "task event"):
            protocol_parity(FixtureTransport(), MissingTaskEvent(), self.scenario)

    def test_w7_rejects_a_task_only_stream_without_diffusion_progress(self):
        class MissingProgressEvent(FixtureTransport):
            def open_sse(self, path, headers, timeout_ms):
                return FixtureSseResponse([SseEvent(10, "task", '{"jobId":"job-1","task":"working"}')])

        with self.assertRaisesRegex(ProtocolExecutionError, "diffusion-step progress"):
            protocol_parity(FixtureTransport(), MissingProgressEvent(), self.scenario)

    def test_w7_rejects_progress_with_invalid_step_contract(self):
        class InvalidProgressEvent(FixtureTransport):
            def open_sse(self, path, headers, timeout_ms):
                return FixtureSseResponse([SseEvent(9, "progress", '{"jobId":"job-1","step":21,"totalSteps":20}')])

        with self.assertRaisesRegex(ProtocolExecutionError, "does not match"):
            protocol_parity(FixtureTransport(), InvalidProgressEvent(), self.scenario)

    def test_w7_rejects_partial_diffusion_step_progress(self):
        class PartialProgressEvent(FixtureTransport):
            def open_sse(self, path, headers, timeout_ms):
                return FixtureSseResponse([SseEvent(1, "progress", '{"jobId":"job-1","step":1,"totalSteps":20}')])

        with self.assertRaisesRegex(ProtocolExecutionError, "not fully observed"):
            protocol_parity(FixtureTransport(), PartialProgressEvent(), self.scenario)

    def test_w7_rejects_an_unsupported_mcp_protocol_version(self):
        class OldVersion(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                if json.loads((body or b"{}").decode()).get("method") == "initialize":
                    return HttpResult(200, {"mcp-session-id": "old-session"}, b'{"result":{"protocolVersion":"2025-03-26"}}')
                return super().request(method, path, body, headers, timeout_ms)

        with self.assertRaisesRegex(ProtocolExecutionError, "unsupported protocol version"):
            protocol_parity(FixtureTransport(), OldVersion(), self.scenario)

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

    def test_w7_rejects_non_png_asset_download(self):
        class HtmlAsset(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                if method == "GET" and path.startswith("/assets/"):
                    return HttpResult(200, {"content-type": "text/html"}, b"<html>expired</html>")
                return super().request(method, path, body, headers, timeout_ms)

        with self.assertRaisesRegex(ProtocolExecutionError, "not a PNG"):
            protocol_parity(FixtureTransport(), HtmlAsset(), self.scenario)

    def test_v1_generation_preserves_only_native_vendor_unet_diagnostics(self):
        class NativeDiagnosticTransport(FixtureTransport):
            def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
                if path == "/v1/images/generations":
                    return HttpResult(
                        200,
                        {},
                        b'{"data":[{"url":"/v1/images/files/image-1"}],"vendor_diagnostics":{"unet_ms":321}}',
                    )
                return super().request(method, path, body, headers, timeout_ms)

        execution = execute_v1_generation(NativeDiagnosticTransport(), self.scenario)

        self.assertEqual({"unetMs": 321.0}, execution.evidence["vendorDiagnostics"])


def _png_bytes(width: int, height: int) -> bytes:
    return (
        b"\x89PNG\r\n\x1a\n"
        + b"\x00\x00\x00\rIHDR"
        + width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + b"\x08\x02\x00\x00\x00"
    )


if __name__ == "__main__":
    unittest.main()
