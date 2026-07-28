import hashlib
import json
import sys
import tempfile
from unittest.mock import patch
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from localdream_perf_executor import DeviceScenarioExecutor
from localdream_perf_harness import validate_scenarios
from localdream_perf_protocol import HttpResult, ProtocolExecutionError


class RecordingTransport:
    def __init__(self):
        self.calls = []

    def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
        self.calls.append((method, path, body or b"", headers or {}))
        if method == "GET":
            return HttpResult(200, {"content-type": "image/png"}, b"downloaded-png")
        if path == "/v1/images/upscales":
            return HttpResult(200, {}, b'{"data":[{"url":"/v1/images/files/upscaled"}]}')
        return HttpResult(200, {}, b'{"data":[{"url":"/v1/images/files/generated"}]}')


class DeviceScenarioExecutorTest(unittest.TestCase):
    def setUp(self):
        self.scenarios = validate_scenarios(ROOT / "scenarios" / "v1")
        self.fixture = b"fixture-png"
        self.fixture_sha = hashlib.sha256(self.fixture).hexdigest()

    def _scenario_set_with_real_fixture_digests(self):
        values = []
        for scenario in self.scenarios:
            copied = json.loads(json.dumps(scenario))
            if copied["scenarioId"] in {"W3", "W6"}:
                copied["fixtures"]["imageSha256"] = self.fixture_sha
            values.append(copied)
        return values

    def test_w1_w4_and_w5_submit_only_published_baseline_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            transport = RecordingTransport()
            executor = DeviceScenarioExecutor(transport, self._scenario_set_with_real_fixture_digests(), Path(directory))

            self.assertEqual(1, len(executor.execute("W1")))
            self.assertEqual(["W4", "W4", "W4"], [item.scenario_id for item in executor.execute("W4")])
            self.assertEqual(["W1", "W2"], [item.scenario_id for item in executor.execute("W5")])

        payloads = [json.loads(call[2].decode()) for call in transport.calls]
        self.assertEqual(
            [
                "novaAsianXL_illustriousV70",
                "novaAsianXL_illustriousV70",
                "novaAsianXL_illustriousV70DMD2",
                "novaAsianXL_illustriousV70",
                "novaAsianXL_illustriousV70",
                "novaAsianXL_illustriousV70DMD2",
            ],
            [payload["model"] for payload in payloads],
        )

    def test_w3_and_w6_require_digest_matched_fixture_and_verify_download(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture_dir = Path(directory)
            for scenario_id in ("W3", "W6"):
                (fixture_dir / f"{scenario_id}.png").write_bytes(self.fixture)
            transport = RecordingTransport()
            executor = DeviceScenarioExecutor(transport, self._scenario_set_with_real_fixture_digests(), fixture_dir)

            with patch("localdream_perf_executor.time.monotonic_ns", side_effect=[0, 2_000_000, 3_000_000, 4_000_000, 6_000_000, 10_000_000]):
                edit = executor.execute("W3")[0]
                upscale = executor.execute("W6")[0]

        self.assertEqual("/v1/images/edits", edit.protocol.endpoint)
        self.assertEqual("/v1/images/upscales", upscale.protocol.endpoint)
        self.assertEqual(2.0, edit.protocol.elapsed_ms)
        self.assertEqual(7.0, upscale.protocol.elapsed_ms)
        self.assertTrue(upscale.protocol.evidence["downloaded"])
        self.assertTrue(upscale.protocol.evidence["endToEndIncludesDownload"])
        edit_call = next(call for call in transport.calls if call[1] == "/v1/images/edits")
        self.assertIn(b'name="denoise_strength"', edit_call[2])
        self.assertIn(self.fixture, edit_call[2])
        self.assertIn(("GET", "/v1/images/files/upscaled", b"", {}), transport.calls)

    def test_placeholder_or_mismatched_fixture_is_rejected_before_request(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture_dir = Path(directory)
            (fixture_dir / "W3.png").write_bytes(self.fixture)
            transport = RecordingTransport()
            executor = DeviceScenarioExecutor(transport, self.scenarios, fixture_dir)

            with self.assertRaisesRegex(ProtocolExecutionError, "not a frozen SHA-256"):
                executor.execute("W3")

        self.assertEqual([], transport.calls)

    def test_published_v2_fixture_is_a_real_1024_png_with_a_frozen_digest(self):
        scenarios = validate_scenarios(ROOT / "scenarios" / "v2")
        fixture = ROOT / "fixtures" / "v2" / "oneplus13-reference-1024.png"
        image = fixture.read_bytes()

        self.assertEqual(b"\x89PNG\r\n\x1a\n", image[:8])
        self.assertEqual((1024, 1024), tuple(int.from_bytes(image[offset : offset + 4], "big") for offset in (16, 20)))
        self.assertGreater(len(image), 100_000)
        self.assertEqual(
            hashlib.sha256(image).hexdigest(),
            next(item for item in scenarios if item["scenarioId"] == "W3")["fixtures"]["imageSha256"],
        )
        self.assertEqual(
            hashlib.sha256(image).hexdigest(),
            next(item for item in scenarios if item["scenarioId"] == "W6")["fixtures"]["imageSha256"],
        )

        transport = RecordingTransport()
        executor = DeviceScenarioExecutor(transport, scenarios, fixture.parent)
        self.assertEqual("/v1/images/edits", executor.execute("W3")[0].protocol.endpoint)
        self.assertEqual("/v1/images/upscales", executor.execute("W6")[0].protocol.endpoint)
        self.assertTrue(any(image in call[2] for call in transport.calls if call[0] == "POST"))


if __name__ == "__main__":
    unittest.main()
