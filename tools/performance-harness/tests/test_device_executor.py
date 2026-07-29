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
from localdream_perf_harness import canonical_digest, validate_scenarios
from localdream_perf_protocol import HttpResult, ProtocolExecutionError


class RecordingTransport:
    def __init__(self):
        self.calls = []

    def request(self, method, path, body=None, headers=None, timeout_ms=30_000):
        self.calls.append((method, path, body or b"", headers or {}))
        if method == "GET":
            return HttpResult(200, {"content-type": "image/png"}, _png_bytes(8, 6))
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
        self.assertEqual(
            ["euler_a", "euler_a", "euler", "euler_a", "euler_a", "euler"],
            [payload["scheduler"] for payload in payloads],
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
        self.assertIn(b'euler_a', edit_call[2])
        self.assertIn(self.fixture, edit_call[2])
        upscale_call = next(call for call in transport.calls if call[1] == "/v1/images/upscales")
        self.assertIn(b'name="output_format"\r\n\r\npng', upscale_call[2])
        self.assertEqual("image/png", upscale.protocol.evidence["downloadContentType"])
        self.assertEqual("PNG", upscale.protocol.evidence["downloadMagic"])
        self.assertEqual(8, upscale.protocol.evidence["downloadWidth"])
        self.assertEqual(6, upscale.protocol.evidence["downloadHeight"])
        self.assertEqual(
            hashlib.sha256(_png_bytes(8, 6)).hexdigest(),
            upscale.protocol.evidence["downloadSha256"],
        )
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

    def test_w6_rejects_a_non_png_published_contract_before_request(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture_dir = Path(directory)
            (fixture_dir / "W6.png").write_bytes(self.fixture)
            scenarios = self._scenario_set_with_real_fixture_digests()
            next(item for item in scenarios if item["scenarioId"] == "W6")["request"]["format"] = "JPEG"
            transport = RecordingTransport()
            executor = DeviceScenarioExecutor(transport, scenarios, fixture_dir)

            with self.assertRaisesRegex(ProtocolExecutionError, "must declare PNG"):
                executor.execute("W6")

        self.assertEqual([], transport.calls)

    def test_published_v2_replays_and_v3_changes_only_w6_upscaler_contract(self):
        scenarios = validate_scenarios(ROOT / "scenarios" / "v2")
        scenarios_v3 = validate_scenarios(ROOT / "scenarios" / "v3")
        fixture = ROOT / "fixtures" / "v2" / "oneplus13-reference-1024.png"
        image = fixture.read_bytes()
        w6 = next(item for item in scenarios if item["scenarioId"] == "W6")

        self.assertEqual(b"\x89PNG\r\n\x1a\n", image[:8])
        self.assertEqual((1024, 1024), tuple(int.from_bytes(image[offset : offset + 4], "big") for offset in (16, 20)))
        self.assertGreater(len(image), 100_000)
        self.assertEqual(
            hashlib.sha256(image).hexdigest(),
            next(item for item in scenarios if item["scenarioId"] == "W3")["fixtures"]["imageSha256"],
        )
        self.assertEqual(
            hashlib.sha256(image).hexdigest(),
            w6["fixtures"]["imageSha256"],
        )
        self.assertEqual(2, w6["scenarioVersion"])
        self.assertEqual("upscaler", w6["model"]["selector"])
        self.assertEqual("upscaler-baseline", w6["model"]["assetSha256"])
        self.assertEqual("71ea50e7f3c4977066e6244cbadee2c2a7f863c01daaec2b442ab0113f544943", w6["sha256"])
        self.assertEqual(w6["sha256"], canonical_digest(w6))
        w6_v3 = next(item for item in scenarios_v3 if item["scenarioId"] == "W6")
        self.assertEqual(3, w6_v3["scenarioVersion"])
        self.assertEqual("upscaler_realistic", w6_v3["model"]["selector"])
        self.assertEqual(
            "7800f35efd8965e045f2f85925a6aa1f6fd95da0b00950036dd94caa9f3b19e9",
            w6_v3["model"]["assetSha256"],
        )

        transport = RecordingTransport()
        executor_v2 = DeviceScenarioExecutor(transport, scenarios, fixture.parent)
        executor_v3 = DeviceScenarioExecutor(transport, scenarios_v3, fixture.parent)
        self.assertEqual("/v1/images/edits", executor_v2.execute("W3")[0].protocol.endpoint)
        historical_w6 = executor_v2.execute("W6")[0]
        self.assertEqual("/v1/images/upscales", executor_v3.execute("W6")[0].protocol.endpoint)
        self.assertEqual("/v1/images/upscales", historical_w6.protocol.endpoint)
        self.assertTrue(historical_w6.protocol.evidence["downloaded"])
        self.assertTrue(any(image in call[2] for call in transport.calls if call[0] == "POST"))


def _png_bytes(width: int, height: int) -> bytes:
    """Minimal PNG sufficient for transport-contract dimension verification."""
    return (
        b"\x89PNG\r\n\x1a\n"
        + b"\x00\x00\x00\rIHDR"
        + width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + b"\x08\x02\x00\x00\x00"
    )


if __name__ == "__main__":
    unittest.main()
