import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from localdream_perf_harness import canonical_digest, command_run, command_verify, validate_scenarios
from localdream_perf_models import ColdState, GroupKey, Outcome, RuntimeProbe, RuntimeProbeStatus, Sample, report, report_gate, statistics


class ScenarioContractTest(unittest.TestCase):
    def test_baselines_validate_and_w2b_is_not_present(self):
        scenarios = validate_scenarios(ROOT / "scenarios" / "v1")
        self.assertEqual({item["scenarioId"] for item in scenarios}, {"W1", "W2", "W3", "W4", "W5", "W6", "W7"})

    def test_tampering_is_rejected(self):
        source = ROOT / "scenarios" / "v1" / "W1.json"
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            value = json.loads(source.read_text())
            value["request"]["steps"] = 21
            (target / "W1.json").write_text(json.dumps(value))
            for item in (ROOT / "scenarios" / "v1").glob("W[2-7].json"):
                (target / item.name).write_text(item.read_text())
            with self.assertRaisesRegex(ValueError, "sha256 mismatch"):
                validate_scenarios(target)


class ReportGateTest(unittest.TestCase):
    def samples(self, status=RuntimeProbeStatus.VERIFIED, count=5, cold=ColdState.PROCESS_COLD, complete=False):
        key = GroupKey("scenario", "preset", "runtime", cold, "v1")
        probe = RuntimeProbe(
            status,
            device_model="PJZ110" if complete else None,
            soc="SM8750" if complete else None,
            abi="arm64-v8a" if complete else None,
            qairt_version="2.48.40" if complete else None,
            htp_target="v79" if complete else None,
            context_fingerprint="a" * 64 if complete else None,
            loaded_library_fingerprints={"libQnnHtpV79.so": "b" * 64} if complete else None,
            native_ready=True if complete else None,
        )
        evidence = {"model": "model", "seed": 1, "width": 1024, "height": 1024, "outputSha256": "c" * 64, "modelAssetSha256": "d" * 64}
        quality = {"mode": "GOLDEN_SET", "passed": True}
        return probe, [Sample("run", index, key, Outcome.SUCCESS, 100.0 + index, quality_passed=complete, baseline_frozen=complete, thermal_stable=complete, quality_evidence=quality if complete else None, response_evidence=evidence if complete else None, expected_model_asset_sha256="d" * 64 if complete else None) for index in range(count)]

    def test_unavailable_probe_cannot_pass(self):
        probe, samples = self.samples(RuntimeProbeStatus.UNAVAILABLE)
        conclusion, reasons = report_gate(probe, samples)
        self.assertEqual(conclusion, "NOT_ACCEPTED_FOR_ONEPLUS13")
        self.assertIn("RuntimeProbe=UNAVAILABLE", reasons)

    def test_mixed_group_and_failure_cannot_pass(self):
        probe, samples = self.samples()
        samples[0] = samples[0].__class__("run", 0, GroupKey("other", "preset", "runtime", ColdState.PROCESS_COLD, "v1"), Outcome.TIMEOUT, None)
        conclusion, reasons = report_gate(probe, samples)
        self.assertIn("MIXED_GROUP_KEY", reasons)
        self.assertIn("RELIABILITY_FAILURE", reasons)

    def test_warm_group_excludes_warmup_and_requires_thirty_samples(self):
        probe, samples = self.samples(count=34, cold=ColdState.CONTEXT_WARM, complete=True)
        samples = [sample.__class__(**(sample.__dict__ | {"is_warmup": sample.sequence < 5})) for sample in samples]
        self.assertEqual(report_gate(probe, samples)[0], "NOT_ACCEPTED_FOR_ONEPLUS13")
        samples.extend(Sample("run", number, samples[0].group_key, Outcome.SUCCESS, 140.0, quality_passed=True, baseline_frozen=True, thermal_stable=True, quality_evidence={"mode": "GOLDEN_SET", "passed": True}, response_evidence={"model": "model", "seed": 1, "width": 1024, "height": 1024, "outputSha256": "c" * 64, "modelAssetSha256": "d" * 64}, expected_model_asset_sha256="d" * 64) for number in range(34, 105))
        self.assertEqual(report_gate(probe, samples)[0], "ACCEPTED_FOR_ONEPLUS13")


class StatisticsTest(unittest.TestCase):
    def test_bootstrap_is_deterministic_and_warmups_are_excluded(self):
        key = GroupKey("scenario", "preset", "runtime", ColdState.CONTEXT_WARM, "v1")
        samples = [Sample("run-1", number, key, Outcome.SUCCESS, float(number), number < 5) for number in range(35)]
        first = statistics(samples, "run-1")
        second = statistics(samples, "run-1")
        self.assertEqual(first, second)
        self.assertEqual(first.count, 30)
        self.assertGreater(first.p95_ms, first.p50_ms)


class ArtifactProtocolTest(unittest.TestCase):
    def test_run_rejects_non_verified_probe_before_device_connection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            probe_path.write_text(json.dumps({"status": "UNAVAILABLE"}))

            exit_code = command_run(
                type("Args", (), {
                    "scenario_dir": ROOT / "scenarios" / "v1",
                    "runtime_probe_file": str(probe_path),
                    "base_url": "http://127.0.0.1:1",
                    "fixture_dir": str(root / "fixtures"),
                    "output_dir": str(root / "output"),
                    "preset_snapshot_sha256": "accepted-snapshot",
                    "bearer_token": None,
                    "scenario_ids": None,
                    "iterations": 1,
                    "run_id": "run-unavailable",
                })(),
            )

            self.assertEqual(2, exit_code)
            self.assertEqual(
                "NOT_ACCEPTED_FOR_ONEPLUS13",
                json.loads((root / "output" / "report.json").read_text())["conclusion"],
            )

    def test_rejected_runtime_writes_replayable_artifacts_without_accepting_device_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            probe_path.write_text(json.dumps({"status": "REJECTED", "rejectionReasons": ["DEVICE_MODEL_MISMATCH"]}))
            exit_code = command_verify(
                type("Args", (), {
                    "scenario_dir": ROOT / "scenarios" / "v1",
                    "runtime_probe_file": str(probe_path),
                    "samples_file": None,
                    "run_id": "run-rejected",
                    "output_dir": str(root / "output"),
                    "require_verified_runtime": True,
                })(),
            )
            self.assertEqual(2, exit_code)
            report_file = json.loads((root / "output" / "report.json").read_text())
            manifest = json.loads((root / "output" / "run-manifest.json").read_text())
            self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", report_file["conclusion"])
            self.assertEqual("REJECTED", manifest["runtimeProbe"]["status"])

    def test_report_requires_complete_probe_and_final_acceptance_evidence(self):
        key = GroupKey("scenario", "preset", "runtime", ColdState.PROCESS_COLD, "v1")
        samples = [Sample("run", index, key, Outcome.SUCCESS, 100.0 + index) for index in range(5)]
        result = report(RuntimeProbe(RuntimeProbeStatus.VERIFIED), samples, "run")
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", result["conclusion"])
        self.assertNotIn("statistics", result)
        self.assertIn("INCOMPLETE_RUNTIME_PROBE", result["reasons"])


if __name__ == "__main__":
    unittest.main()
