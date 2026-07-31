import json
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from localdream_perf_harness import (
    canonical_digest,
    command_capture_baseline,
    command_capture_quality,
    command_run,
    command_run_w7,
    command_verify,
    fetch_runtime_probe,
    load_acceptance_evidence,
    _runtime_fingerprint,
    _group_key,
    _group_artifact_id,
    _w5_measurement_scenarios,
    _attach_group_resource_lifecycle,
    _collect_group_releases,
    _sample_from_execution,
    AcceptanceEvidence,
    AdbResourceSampler,
    finalize_thermal_stability,
    grouped_report,
    ProcessLifecycleController,
    resolve_bearer_token,
    validate_scenarios,
    write_artifacts,
)
from localdream_perf_protocol import HttpResult, ProtocolExecution, ProtocolExecutionError
from localdream_perf_models import ColdState, GroupKey, Outcome, RuntimeProbe, RuntimeProbeStatus, Sample, ValidationLevel, report, report_gate, statistics


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

    def test_v4_acceptance_scenarios_have_real_model_digests(self):
        scenarios = validate_scenarios(
            ROOT / "scenarios" / "v4",
            require_real_model_asset_sha256=True,
        )
        self.assertEqual(7, len(scenarios))
        self.assertTrue(all(len(item["model"]["assetSha256"]) == 64 for item in scenarios))

    def test_placeholder_model_digest_cannot_be_run_as_acceptance(self):
        with self.assertRaisesRegex(ValueError, "real lowercase SHA-256"):
            validate_scenarios(
                ROOT / "scenarios" / "v3",
                require_real_model_asset_sha256=True,
            )


class AdbTargetIdentityTest(unittest.TestCase):
    def test_uses_soc_model_and_keeps_board_platform_as_evidence(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        values = {
            "getprop ro.product.model": "PJZ110\n",
            "getprop ro.soc.model": "SM8750\n",
            "getprop ro.board.platform": "sun\n",
            "getprop ro.product.cpu.abi": "arm64-v8a\n",
            "getprop ro.serialno": "3B15C4018L500000\n",
            "cmd package path io.github.ddq.visiondream": "package:/data/app/base.apk\n",
        }
        probe = RuntimeProbe(
            status=RuntimeProbeStatus.VERIFIED,
            device_model="PJZ110",
            soc="SM8750",
            abi="arm64-v8a",
        )
        with patch.object(sampler, "_shell", side_effect=values.__getitem__):
            identity = sampler.verify_target_identity(probe)

        self.assertEqual("SM8750", identity["soc"])
        self.assertEqual("sun", identity["boardPlatform"])

    def test_unload_release_keeps_service_process_alive_and_records_real_memory(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        unloader = type("Unloader", (), {"unload": lambda _self, model: {
            "runtimeUnloaded": model == "model-a", "serviceAvailableAfterUnload": True,
            "healthSourceSha256": "a" * 64,
        }})()
        with patch.object(sampler, "collect", return_value={"sequence": 7, "processPssKb": 100, "processRssKb": 200, "swapPssKb": 0}), patch.object(
            sampler, "_pidof_after_unload", return_value="123"
        ):
            release = sampler.unload_and_collect(7, "model-a", unloader)

        self.assertEqual("MCP_RUNTIME_UNLOAD", release["unloadAction"])
        self.assertTrue(release["processAliveAfterUnload"])
        self.assertEqual(100, release["processPssKb"])
        self.assertNotIn("collectionError", release)

    def test_unload_pidof_exit_one_without_output_proves_process_absent(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        result = type("Result", (), {"stdout": "", "returncode": 1})()

        with patch("localdream_perf_harness.subprocess.run", return_value=result):
            self.assertEqual("", sampler._pidof_after_unload())

    def test_unload_pidof_ambiguous_exit_or_output_fails_closed(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        for stdout, returncode in (("", 0), ("123", 1), ("", 2)):
            with self.subTest(stdout=stdout, returncode=returncode):
                result = type("Result", (), {"stdout": stdout, "returncode": returncode})()
                with patch("localdream_perf_harness.subprocess.run", return_value=result):
                    with self.assertRaisesRegex(ValueError, "ambiguous process state"):
                        sampler._pidof_after_unload()


class ResourceLifecycleTest(unittest.TestCase):
    def test_group_lifecycle_uses_request_baseline_peak_and_verified_unload_release(self):
        key = GroupKey("a" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1")
        samples = [
            Sample("run", 0, key, Outcome.SUCCESS, 100.0, resource_metrics={"processPssKb": 110, "processRssKb": 210}),
            Sample("run", 1, key, Outcome.SUCCESS, 100.0, resource_metrics={"processPssKb": 120, "processRssKb": 205}),
        ]
        baseline = {"processPssKb": 100, "processRssKb": 200, "swapPssKb": 0, "sourceSha256": "d" * 64}
        lifecycle = {key: {"baseline": baseline, "peakPssKb": 120, "peakRssKb": 210, "peakSwapPssKb": 0}}
        release = {
            "capturePhase": "POST_UNLOAD_RELEASE", "unloadAction": "MCP_RUNTIME_UNLOAD",
            "runtimeUnloaded": True, "serviceAvailableAfterUnload": True, "processAliveAfterUnload": True,
            "processPssKb": 80, "processRssKb": 160,
            "swapPssKb": 0, "sourceSha256": "e" * 64, "resourceGroupId": _group_artifact_id(key),
        }

        finalized = _attach_group_resource_lifecycle(samples, lifecycle, {key: release})

        for sample in finalized:
            self.assertEqual(100, sample.resource_metrics["baselinePssKb"])
            self.assertEqual(120, sample.resource_metrics["groupPeakPssKb"])
            self.assertEqual(210, sample.resource_metrics["groupPeakRssKb"])
            self.assertEqual(80, sample.resource_metrics["releasePssKb"])
            self.assertTrue(sample.resource_metrics["releaseProcessAlive"])
            self.assertFalse(sample.resource_metrics["memoryLeakDetected"])

    def test_group_lifecycle_does_not_invent_release_or_leak_result_when_unload_fails(self):
        key = GroupKey("a" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1")
        sample = Sample("run", 0, key, Outcome.SUCCESS, 100.0, resource_metrics={})

        finalized = _attach_group_resource_lifecycle(
            [sample],
            {key: {"baseline": {"processPssKb": 100, "processRssKb": 200, "swapPssKb": 0}}},
            {key: {"capturePhase": "POST_UNLOAD_RELEASE", "resourceGroupId": _group_artifact_id(key), "collectionError": "process remained"}},
        )

        self.assertNotIn("releasePssKb", finalized[0].resource_metrics)
        self.assertNotIn("memoryLeakDetected", finalized[0].resource_metrics)

    def test_multiple_group_keys_collect_and_archive_distinct_releases(self):
        key_a = GroupKey("a" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1")
        key_b = GroupKey("d" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1")

        class ReleaseSampler:
            def __init__(self):
                self.records = []

            def unload_and_collect(self, sequence, expected_model_id, _runtime_unloader):
                record = {
                    "sequence": sequence, "capturePhase": "POST_UNLOAD_RELEASE",
                    "unloadAction": "MCP_RUNTIME_UNLOAD", "runtimeUnloaded": True,
                    "serviceAvailableAfterUnload": True, "processAliveAfterUnload": True,
                    "processPssKb": 80, "processRssKb": 160, "swapPssKb": 0,
                    "expectedModelId": expected_model_id,
                    "sourceSha256": f"release-{sequence}",
                }
                self.records.append(record)
                return record

        lifecycle = {
            key_a: {"expectedModelId": "model-a", "baseline": {"processPssKb": 100, "processRssKb": 200, "swapPssKb": 0}},
            key_b: {"expectedModelId": "model-b", "baseline": {"processPssKb": 101, "processRssKb": 201, "swapPssKb": 0}},
        }
        sampler = ReleaseSampler()
        releases = _collect_group_releases(sampler, lifecycle, 20, object())
        self.assertEqual(2, len(releases))
        self.assertNotEqual(releases[key_a]["sourceSha256"], releases[key_b]["sourceSha256"])
        samples = [
            Sample("run", 0, key_a, Outcome.SUCCESS, 1.0, resource_metrics={}),
            Sample("run", 1, key_b, Outcome.SUCCESS, 1.0, resource_metrics={}),
        ]
        finalized = _attach_group_resource_lifecycle(samples, lifecycle, releases)
        self.assertNotEqual(
            finalized[0].resource_metrics["releaseTelemetrySequence"],
            finalized[1].resource_metrics["releaseTelemetrySequence"],
        )
        self.assertNotEqual(
            releases[key_a]["resourceGroupId"], releases[key_b]["resourceGroupId"],
        )
        self.assertNotIn(
            "releasePssKb",
            _attach_group_resource_lifecycle(samples, lifecycle, {key_a: releases[key_b]})[0].resource_metrics,
        )

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            probe = RuntimeProbe.from_json(probe_json_for_context("a" * 64))
            write_artifacts(
                output, "group-release-run", ROOT / "scenarios" / "v4", probe, finalized,
                {"runId": "group-release-run", "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13", "reasons": []},
                preset_snapshot_sha256="b" * 64,
                run_context=json.loads(write_run_context(Path(temporary)).read_text()),
                telemetry_records=sampler.records,
            )
            manifest = json.loads((output / "run-manifest.json").read_text())
            for artifact in manifest["groupArtifacts"]:
                telemetry = [
                    json.loads(line) for line in (output / artifact["artifactDirectory"] / "telemetry.jsonl").read_text().splitlines()
                ]
                self.assertEqual(1, len(telemetry))
                self.assertEqual(artifact["groupId"], telemetry[0]["resourceGroupId"])

    def test_rejects_a_different_soc_model_even_when_board_platform_matches(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        values = {
            "getprop ro.product.model": "PJZ110\n",
            "getprop ro.soc.model": "SM8650\n",
            "getprop ro.board.platform": "sun\n",
            "getprop ro.product.cpu.abi": "arm64-v8a\n",
            "getprop ro.serialno": "3B15C4018L500000\n",
            "cmd package path io.github.ddq.visiondream": "package:/data/app/base.apk\n",
        }
        probe = RuntimeProbe(
            status=RuntimeProbeStatus.VERIFIED,
            device_model="PJZ110",
            soc="SM8750",
            abi="arm64-v8a",
        )
        with patch.object(sampler, "_shell", side_effect=values.__getitem__):
            with self.assertRaisesRegex(ValueError, "identity does not match"):
                sampler.verify_target_identity(probe)


class AdbResourceSamplerTest(unittest.TestCase):
    def test_missing_swap_counter_is_a_collection_error_not_zero(self):
        sampler = AdbResourceSampler("3B15C4018L500000", "io.github.ddq.visiondream")
        values = {
            "dumpsys battery": "temperature: 328\n",
            "dumpsys thermalservice": "Thermal Status: 0\n",
            "dumpsys meminfo io.github.ddq.visiondream": "TOTAL PSS: 10\nTOTAL RSS: 20\n",
        }

        with patch.object(sampler, "_shell", side_effect=values.__getitem__):
            record = sampler.collect(1)

        self.assertIn("collectionError", record)
        self.assertNotIn("swapPssKb", record)


class CaptureEvidenceCommandTest(unittest.TestCase):
    def test_capture_quality_then_baseline_binds_the_verified_runtime_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
            quality_input = root / "quality-input.json"
            quality_input.write_text(json.dumps({
                "schemaVersion": 1,
                "results": [{
                    "scenarioSha256": scenario["sha256"], "outputSha256": "d" * 64,
                    "mode": "GOLDEN_SET", "calculationVersion": "golden-v1",
                    "goldenSetSha256": "e" * 64, "promptCount": 30, "seedsPerPrompt": 4,
                    "ssim": 0.99, "lpips": 0.04, "clipScoreRegressionPct": 0.5,
                    "blindReviewPassed": True,
                    "rawMeasurements": {
                        "nanCount": 0, "infCount": 0, "corruptImageCount": 0,
                        "blackImageCount": 0, "colorLayoutValid": True,
                    },
                }],
            }))
            quality_file = root / "quality-v1.json"
            self.assertEqual(0, command_capture_quality(type("Args", (), {
                "scenario_dir": ROOT / "scenarios" / "v4", "quality_input_file": quality_input,
                "output_file": quality_file,
            })()))
            quality = json.loads(quality_file.read_text())
            captured = quality["results"]["d" * 64]
            self.assertTrue(captured["passed"])
            self.assertEqual(scenario["sha256"], captured["scenarioSha256"])
            self.assertIn("rawMeasurementsSha256", captured)

            baseline_file = root / "baseline-v1.json"
            self.assertEqual(0, capture_baseline_with_live_target(
                scenario_dir=ROOT / "scenarios" / "v4",
                quality_file=quality_file,
                output_file=baseline_file,
                scenario_ids="W1",
                context_fingerprint=scenario["model"]["assetSha256"],
            ))
            baseline = json.loads(baseline_file.read_text())
            self.assertEqual(1, len(baseline["entries"]))
            self.assertEqual(captured["qualityReferenceSha256"], baseline["entries"][0]["qualityReferenceSha256"])
            self.assertEqual(baseline["provenance"]["runtimeProbeSha256"], baseline["entries"][0]["runtimeFingerprint"])
            self.assertEqual("io.github.ddq.visiondream", baseline["provenance"]["adbTarget"]["appPackage"])
            self.assertEqual("http://172.20.103.120:8080", baseline["provenance"]["healthEndpoint"]["baseUrl"])

            captured["rawMeasurements"]["nanCount"] = 1
            quality_file.write_text(json.dumps(quality))
            with self.assertRaisesRegex(ValueError, "raw measurements SHA-256"):
                capture_baseline_with_live_target(
                    scenario_dir=ROOT / "scenarios" / "v4",
                    quality_file=quality_file,
                    output_file=root / "tampered-baseline-v1.json",
                    scenario_ids="W1",
                    context_fingerprint=scenario["model"]["assetSha256"],
                )

    def test_capture_baseline_rejects_unverified_runtime_without_writing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "baseline-v1.json"
            self.assertEqual(2, capture_baseline_with_live_target(
                scenario_dir=ROOT / "scenarios" / "v4",
                quality_file=root / "missing.json",
                output_file=output,
                scenario_ids="W1",
                context_fingerprint=None,
            ))
            self.assertFalse(output.exists())

    def test_capture_baseline_rejects_live_model_context_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(2, capture_baseline_with_live_target(
                scenario_dir=ROOT / "scenarios" / "v4",
                quality_file=root / "missing.json",
                output_file=root / "baseline-v1.json",
                scenario_ids="W1",
                context_fingerprint="f" * 64,
            ))

    def test_capture_baseline_rejects_unbound_base_url_or_other_installation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
            output = root / "baseline-v1.json"
            with self.assertRaisesRegex(ValueError, "base URL is not bound"):
                capture_baseline_with_live_target(
                    scenario_dir=ROOT / "scenarios" / "v4", quality_file=root / "missing.json",
                    output_file=output, scenario_ids="W1", context_fingerprint=scenario["model"]["assetSha256"],
                    base_url="http://127.0.0.1:8080",
                )
            with self.assertRaisesRegex(ValueError, "installation does not match"):
                capture_baseline_with_live_target(
                    scenario_dir=ROOT / "scenarios" / "v4", quality_file=root / "missing.json",
                    output_file=output, scenario_ids="W1", context_fingerprint=scenario["model"]["assetSha256"],
                    installation_digest="b" * 64,
                )
            self.assertFalse(output.exists())

    def test_b0_consumer_rejects_changed_adb_installation_or_endpoint(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
            quality_file = root / "quality-v1.json"
            quality_file.write_text(json.dumps({"schemaVersion": 1, "results": {}}))
            baseline_file = root / "baseline-v2.json"
            baseline_file.write_text(json.dumps({
                "schemaVersion": 2, "baselineId": "b0", "entries": [],
                "provenance": {
                    "runtimeProbe": probe_json_for_context(scenario["model"]["assetSha256"]),
                    "runtimeProbeSha256": _runtime_fingerprint(verified_probe_for_context(scenario["model"]["assetSha256"])),
                    "adbTarget": target_identity_fixture(),
                    "appPackage": "io.github.ddq.visiondream",
                    "modelContextFingerprint": scenario["model"]["assetSha256"],
                    "healthEndpoint": {"baseUrl": "http://172.20.103.120:8080", "adbHost": "172.20.103.120"},
                    "healthInstallation": {"appPackage": "io.github.ddq.visiondream", "packagePathSha256": "a" * 64},
                },
            }))
            args = type("Args", (), {
                "baseline_file": baseline_file, "quality_evidence_file": quality_file,
                "base_url": "http://172.20.103.120:8080", "adb_serial": "172.20.103.120:5555",
            })()
            changed_installation = dict(target_identity_fixture(), packagePathSha256="b" * 64)
            with self.assertRaisesRegex(ValueError, "current verified target app instance"):
                load_acceptance_evidence(args, changed_installation)
            args.base_url = "http://127.0.0.1:8080"
            with self.assertRaisesRegex(ValueError, "base URL is not bound"):
                load_acceptance_evidence(args, target_identity_fixture())

    def test_capture_quality_rejects_non_finite_or_visually_invalid_measurements(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
            source = root / "quality-input.json"
            source.write_text(json.dumps({"schemaVersion": 1, "results": [{
                "scenarioSha256": scenario["sha256"], "outputSha256": "d" * 64,
                "mode": "GOLDEN_SET", "calculationVersion": "golden-v1", "goldenSetSha256": "e" * 64,
                "promptCount": 30, "seedsPerPrompt": 4, "ssim": 0.99, "lpips": 0.04,
                "clipScoreRegressionPct": 0.5, "blindReviewPassed": True,
                "rawMeasurements": {"nanCount": 1, "infCount": 0, "corruptImageCount": 0, "blackImageCount": 0, "colorLayoutValid": True},
            }]}))
            output = root / "quality-v1.json"
            self.assertEqual(0, command_capture_quality(type("Args", (), {
                "scenario_dir": ROOT / "scenarios" / "v4", "quality_input_file": source, "output_file": output,
            })()))
            self.assertFalse(json.loads(output.read_text())["results"]["d" * 64]["passed"])


class ReportGateTest(unittest.TestCase):
    @staticmethod
    def metrics(*, scenario_id="W1", pss=100, rss=200, swap=0, release_pss=80, release_rss=160):
        return (
            {"scenarioId": scenario_id, "unetMs": 80.0, "w5ThroughputPerSecond": 10.0},
            {
                "processPssKb": pss,
                "processRssKb": rss,
                "swapPssKb": swap,
                "baselinePssKb": 80,
                "baselineRssKb": 160,
                "releasePssKb": release_pss,
                "releaseRssKb": release_rss,
                "memoryLeakDetected": False,
            },
        )

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
        stage_metrics, resource_metrics = self.metrics()
        return probe, [Sample(
            "run", index, key, Outcome.SUCCESS, 100.0 + index,
            quality_passed=complete, baseline_frozen=complete, thermal_stable=complete,
            quality_evidence=quality if complete else None,
            response_evidence=evidence if complete else None,
            expected_model_asset_sha256="d" * 64 if complete else None,
            stage_metrics=stage_metrics,
            resource_metrics=resource_metrics,
        ) for index in range(count)]

    def test_unavailable_probe_cannot_pass(self):
        probe, samples = self.samples(RuntimeProbeStatus.UNAVAILABLE)
        conclusion, reasons = report_gate(probe, samples)
        self.assertEqual(conclusion, "NOT_ACCEPTED_FOR_ONEPLUS13")
        self.assertIn("RuntimeProbe=UNAVAILABLE", reasons)

    def test_exploration_does_not_promote_to_target_validation_without_b0_or_quality(self):
        """Candidate profiling must not be blocked by final-evidence gates or grant a binding qualification."""
        probe, samples = self.samples(complete=True)
        samples = [
            replace(
                sample,
                quality_passed=False,
                baseline_frozen=False,
                quality_evidence=None,
                response_evidence=None,
            )
            for sample in samples
        ]

        exploratory, exploratory_reasons = report_gate(
            probe,
            samples,
            validation_level=ValidationLevel.EXPLORATORY,
        )
        target, target_reasons = report_gate(
            probe,
            samples,
            validation_level=ValidationLevel.TARGET_VALIDATED,
        )

        self.assertEqual("EXPLORATORY_COMPLETED", exploratory)
        self.assertEqual([], exploratory_reasons)
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", target)
        self.assertIn("QUALITY_FAILURE", target_reasons)
        self.assertIn("B0_NOT_FROZEN", target_reasons)

    def test_exploration_accepts_one_measured_warm_sample_but_target_requires_statistics(self):
        """Exploration records a verified candidate without borrowing target sample thresholds."""
        probe, samples = self.samples(count=6, cold=ColdState.CONTEXT_WARM, complete=True)
        samples = [
            replace(
                sample,
                is_warmup=sample.sequence < 5,
                quality_passed=False,
                baseline_frozen=False,
                quality_evidence=None,
                response_evidence=None,
            )
            for sample in samples
        ]

        exploratory, exploratory_reasons = report_gate(
            probe,
            samples,
            validation_level=ValidationLevel.EXPLORATORY,
        )
        target, target_reasons = report_gate(
            probe,
            samples,
            validation_level=ValidationLevel.TARGET_VALIDATED,
        )

        self.assertEqual("EXPLORATORY_COMPLETED", exploratory)
        self.assertEqual([], exploratory_reasons)
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", target)
        self.assertIn("INSUFFICIENT_SAMPLES:1<30", target_reasons)

    def test_exploration_rejects_missing_measured_samples_or_failures(self):
        probe, samples = self.samples(count=5, cold=ColdState.CONTEXT_WARM, complete=True)
        warmups_only = [replace(sample, is_warmup=True) for sample in samples]
        conclusion, reasons = report_gate(
            probe,
            warmups_only,
            validation_level=ValidationLevel.EXPLORATORY,
        )
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("NO_SAMPLES", reasons)

        failed_samples = self.samples(count=6, cold=ColdState.CONTEXT_WARM, complete=True)[1]
        failed_samples = [
            replace(sample, is_warmup=sample.sequence < 5,
                    outcome=Outcome.TIMEOUT if sample.sequence == 5 else sample.outcome)
            for sample in failed_samples
        ]
        conclusion, reasons = report_gate(
            probe,
            failed_samples,
            validation_level=ValidationLevel.EXPLORATORY,
        )
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("RELIABILITY_FAILURE", reasons)

    def test_mixed_group_and_failure_cannot_pass(self):
        probe, samples = self.samples()
        samples[0] = samples[0].__class__("run", 0, GroupKey("other", "preset", "runtime", ColdState.PROCESS_COLD, "v1"), Outcome.TIMEOUT, None)
        conclusion, reasons = report_gate(probe, samples)
        self.assertIn("MIXED_GROUP_KEY", reasons)
        self.assertIn("RELIABILITY_FAILURE", reasons)

    def test_grouped_report_never_evaluates_distinct_group_keys_together(self):
        probe, samples = self.samples()
        samples[0] = samples[0].__class__(
            **(samples[0].__dict__ | {"group_key": GroupKey("other", "preset", "runtime", ColdState.PROCESS_COLD, "v1")}),
        )

        value = grouped_report(probe, samples, "multi-group")

        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", value["conclusion"])
        self.assertNotIn("MIXED_GROUP_KEY", value["reasons"])
        self.assertEqual(2, len(value["groupReports"]))
        self.assertTrue(all("MIXED_GROUP_KEY" not in item["reasons"] for item in value["groupReports"]))

    def test_warm_group_excludes_warmup_and_requires_thirty_samples(self):
        probe, samples = self.samples(count=34, cold=ColdState.CONTEXT_WARM, complete=True)
        samples = [sample.__class__(**(sample.__dict__ | {"is_warmup": sample.sequence < 5})) for sample in samples]
        self.assertEqual(report_gate(probe, samples)[0], "NOT_ACCEPTED_FOR_ONEPLUS13")
        stage_metrics, resource_metrics = self.metrics()
        samples.extend(Sample("run", number, samples[0].group_key, Outcome.SUCCESS, 140.0, quality_passed=True, baseline_frozen=True, thermal_stable=True, quality_evidence={"mode": "GOLDEN_SET", "passed": True}, response_evidence={"model": "model", "seed": 1, "width": 1024, "height": 1024, "outputSha256": "c" * 64, "modelAssetSha256": "d" * 64}, expected_model_asset_sha256="d" * 64, stage_metrics=stage_metrics, resource_metrics=resource_metrics) for number in range(34, 105))
        self.assertEqual(report_gate(probe, samples)[0], "ACCEPTED_FOR_ONEPLUS13")

    def test_target_and_final_reject_missing_primary_metrics(self):
        probe, samples = self.samples(count=5, complete=True)
        samples = [replace(sample, stage_metrics={"scenarioId": "W1"}, resource_metrics={}) for sample in samples]

        conclusion, reasons = report_gate(probe, samples, validation_level=ValidationLevel.TARGET_VALIDATED)

        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("MISSING_METRIC:unetMs", reasons)
        self.assertIn("MISSING_METRIC:processPssKb", reasons)
        self.assertIn("MISSING_METRIC:releaseRssKb", reasons)
        final_conclusion, final_reasons = report_gate(probe, samples, validation_level=ValidationLevel.FINAL_VALIDATED)
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", final_conclusion)
        self.assertIn("MISSING_METRIC:unetMs", final_reasons)

    def test_w5_requires_its_sustained_throughput_metric(self):
        probe, samples = self.samples(count=5, complete=True)
        samples = [replace(sample, stage_metrics={"scenarioId": "W5", "unetMs": 80.0}) for sample in samples]

        conclusion, reasons = report_gate(probe, samples, validation_level=ValidationLevel.TARGET_VALIDATED)

        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("MISSING_METRIC:w5ThroughputPerSecond", reasons)

    def test_target_rejects_sustained_swap_memory_leak_and_unrecovered_release(self):
        probe, samples = self.samples(count=5, complete=True)
        samples = [
            replace(
                sample,
                resource_metrics=self.metrics(
                    pss=100 + sample.sequence,
                    rss=200 + sample.sequence,
                    swap=1,
                    release_pss=81,
                    release_rss=161,
                )[1] | {"memoryLeakDetected": True},
            )
            for sample in samples
        ]

        conclusion, reasons = report_gate(probe, samples, validation_level=ValidationLevel.TARGET_VALIDATED)

        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("SUSTAINED_SWAP", reasons)
        self.assertIn("MEMORY_LEAK_DETECTED", reasons)
        self.assertIn("RESOURCE_RELEASE_NOT_RECOVERED", reasons)

    def test_report_publishes_primary_metrics_without_zero_filling(self):
        probe, samples = self.samples(count=5, complete=True)

        result = report(probe, samples, "metrics", validation_level=ValidationLevel.TARGET_VALIDATED)

        self.assertEqual(80.0, result["metrics"]["unetP50Ms"])
        self.assertEqual(100, result["metrics"]["peakPssKb"])
        self.assertEqual(200, result["metrics"]["peakRssKb"])
        self.assertEqual(0, result["metrics"]["peakSwapPssKb"])

    def test_warm_group_requires_exactly_five_recorded_warmups(self):
        probe, samples = self.samples(count=100, cold=ColdState.CONTEXT_WARM, complete=True)

        conclusion, reasons = report_gate(probe, samples)

        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", conclusion)
        self.assertIn("INVALID_WARMUP_COUNT:0!=5", reasons)


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
    def test_w5_variant_b0_lookup_rejects_a_w1_or_w2_baseline_identity(self):
        scenarios = validate_scenarios(ROOT / "scenarios" / "v4", require_real_model_asset_sha256=True)
        sustained = next(item for item in scenarios if item["scenarioId"] == "W5")
        w1, w2 = _w5_measurement_scenarios(scenarios, sustained)
        fingerprint = "f" * 64
        preset = "p" * 64
        key_w1 = _group_key(w1, fingerprint, preset)
        key_w2 = _group_key(w2, fingerprint, preset)
        evidence = AcceptanceEvidence(
            baselines={
                (key_w1.scenario_sha256, preset, fingerprint, key_w1.cold_state.value): {"variant": "W1"},
                (key_w2.scenario_sha256, preset, fingerprint, key_w2.cold_state.value): {"variant": "W2"},
            },
            quality_results={},
            manifest_reference={},
        )

        self.assertEqual("W1", evidence.baseline_for(key_w1)["variant"])
        self.assertEqual("W2", evidence.baseline_for(key_w2)["variant"])
        ordinary_w1 = next(item for item in scenarios if item["scenarioId"] == "W1")
        with self.assertRaisesRegex(ValueError, "B0 baseline does not match"):
            evidence.baseline_for(_group_key(ordinary_w1, fingerprint, preset))

    def test_post_operation_probe_requires_complete_matching_model_context(self):
        expected_context = "c" * 64

        class HealthTransport:
            def __init__(self, probe):
                self.probe = probe

            def request(self, method, path, **_kwargs):
                self.last_request = (method, path)
                return type("Response", (), {"status": 200, "body": json.dumps({
                    "runtimeProbe": self.probe,
                    "installation": {"appPackage": "io.github.ddq.visiondream", "packagePathSha256": "a" * 64},
                }).encode()})()

        transport = HealthTransport(probe_json_for_context(expected_context))
        observed = fetch_runtime_probe(transport, expected_context)

        self.assertEqual(("GET", "/health"), transport.last_request)
        self.assertEqual(expected_context, observed.context_fingerprint)
        with self.assertRaisesRegex(ProtocolExecutionError, "context fingerprint"):
            fetch_runtime_probe(HealthTransport(probe_json_for_context("d" * 64)), expected_context)
        with self.assertRaisesRegex(ProtocolExecutionError, "verified PJZ110"):
            rejected = probe_json_for_context(expected_context) | {"status": "REJECTED"}
            fetch_runtime_probe(HealthTransport(rejected), expected_context)

    def test_observed_probes_are_bound_to_group_artifacts_not_the_preflight_context(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            preflight = verified_probe_for_context("a" * 64)
            observed = {
                _runtime_fingerprint(verified_probe_for_context("c" * 64)): verified_probe_for_context("c" * 64),
                _runtime_fingerprint(verified_probe_for_context("d" * 64)): verified_probe_for_context("d" * 64),
            }
            samples = [
                Sample("observed", 0, GroupKey("a" * 64, "b" * 64, fingerprint, ColdState.CONTEXT_WARM, "1"), Outcome.SUCCESS, 1.0)
                for fingerprint in observed
            ]
            write_artifacts(
                root / "output", "observed", ROOT / "scenarios" / "v4", preflight, samples,
                {"runId": "observed", "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13", "reasons": []},
                preset_snapshot_sha256="b" * 64,
                run_context=json.loads(write_run_context(root).read_text()),
                observed_probes=observed,
            )

            manifest = json.loads((root / "output" / "run-manifest.json").read_text())
            self.assertNotIn("contextFingerprint", manifest)
            self.assertEqual("a" * 64, manifest["preflightRuntimeProbe"]["context_fingerprint"])
            self.assertEqual(set(observed), {item["runtimeFingerprint"] for item in manifest["observedRuntimeProbes"]})
            self.assertEqual(set(observed), {item["groupKey"]["runtimeFingerprint"] for item in manifest["groupArtifacts"]})

    def test_run_records_five_warmups_before_each_warm_group_measurement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            calls = []

            class FakeExecutor:
                def __init__(self, *_args):
                    pass

                def begin_sustained_measurement(self):
                    pass

                def execute(self, scenario_id, after_execution=None, before_execution=None, **_kwargs):
                    calls.append(scenario_id)
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {
                        "scenario_id": scenario_id,
                        "operation": scenario_id,
                        "protocol": protocol,
                        "physical_order": len(calls),
                    })()
                    if after_execution:
                        after_execution(execution)
                    return [execution]

            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", FakeExecutor,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", FakeSampler,
            ), patch(
                "localdream_perf_harness.load_acceptance_evidence", return_value=FakeAcceptanceEvidence(),
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=lambda _transport, context: verified_probe_for_context(context),
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ) as sample:
                self.assertEqual(2, command_run(run_args(root, probe_path, scenario_ids="W1")))

            self.assertEqual(["W1"] * 6, calls)
            self.assertEqual([True] * 5 + [False], [call.kwargs["is_warmup"] for call in sample.call_args_list])
            self.assertEqual(
                [(1, True), (2, True), (3, True), (4, True), (5, True), (6, False)],
                [(call.args[3].physical_order, call.kwargs["is_warmup"]) for call in sample.call_args_list],
            )

    def test_runtime_probe_change_between_warmup_and_measurement_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)

            class FakeExecutor:
                def __init__(self, *_args):
                    pass

                def begin_sustained_measurement(self):
                    pass

                def execute(self, scenario_id, after_execution=None, before_execution=None, **_kwargs):
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {"scenario_id": scenario_id, "operation": scenario_id, "protocol": protocol})()
                    if after_execution:
                        after_execution(execution)
                    return [execution]

            warmup_probe = verified_probe_for_context("a" * 64)
            measured_probe = verified_probe_for_context("b" * 64)
            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", FakeExecutor,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", FakeSampler,
            ), patch(
                "localdream_perf_harness.load_acceptance_evidence", return_value=FakeAcceptanceEvidence(),
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=[warmup_probe] * 5 + [measured_probe],
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ) as sample:
                self.assertEqual(2, command_run(run_args(root, probe_path, scenario_ids="W1")))

            self.assertEqual([True] * 5 + [False], [call.kwargs["is_warmup"] for call in sample.call_args_list])
            self.assertEqual(
                [_runtime_fingerprint(warmup_probe)] * 5,
                [call.args[4] for call in sample.call_args_list[:5]],
            )
            self.assertEqual(_runtime_fingerprint(measured_probe), sample.call_args_list[-1].args[4])
            report_value = json.loads((root / "output" / "report.json").read_text())
            self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", report_value["conclusion"])
            measured_group = next(
                item for item in report_value["groupReports"]
                if item["groupKey"]["runtimeFingerprint"] == _runtime_fingerprint(measured_probe)
            )
            measured_group_report = json.loads(
                (root / "output" / measured_group["artifactDirectory"] / "report.json").read_text(),
            )
            self.assertIn("INVALID_WARMUP_COUNT:0!=5", measured_group_report["reasons"])

    def test_regular_batches_unload_before_the_next_group_begins(self):
        """Release timing is causal: W1 unload precedes every W2 request."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            timeline = []

            class TimelineExecutor:
                def __init__(self, *_args):
                    pass

                def execute(self, scenario_id, after_execution=None, before_execution=None, **_kwargs):
                    timeline.append(f"request:{scenario_id}")
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {
                        "scenario_id": scenario_id, "operation": scenario_id, "protocol": protocol,
                    })()
                    if after_execution:
                        after_execution(execution)
                    return [execution]

            class TimelineSampler(FakeSampler):
                def unload_and_collect(self, sequence, expected_model_id, _unloader):
                    timeline.append(f"release:{expected_model_id}")
                    record = {
                        "sequence": sequence, "capturePhase": "POST_UNLOAD_RELEASE",
                        "unloadAction": "MCP_RUNTIME_UNLOAD", "runtimeUnloaded": True,
                        "serviceAvailableAfterUnload": True, "processAliveAfterUnload": True,
                        "processPssKb": 80, "processRssKb": 160, "swapPssKb": 0,
                        "sourceSha256": f"release-{sequence}",
                    }
                    self.records.append(record)
                    return record

            scenarios = {
                item["scenarioId"]: item for item in validate_scenarios(ROOT / "scenarios" / "v4")
            }
            args = run_args(root, probe_path, scenario_ids="W1,W2")
            args.validation_level = ValidationLevel.EXPLORATORY.value
            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", TimelineExecutor,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", TimelineSampler,
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=lambda _transport, context: verified_probe_for_context(context),
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ):
                self.assertEqual(0, command_run(args))

            self.assertEqual(
                ["request:W1"] * 6
                + [f"release:{scenarios['W1']['model']['selector']}"]
                + ["request:W2"] * 6
                + [f"release:{scenarios['W2']['model']['selector']}"],
                timeline,
            )

    def test_w4_requires_a_verified_process_cold_lifecycle_before_first_request(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            events = []

            class FakeExecutor:
                def __init__(self, *_args):
                    pass

                def execute(self, scenario_id, after_execution=None, before_execution=None):
                    events.append(("execute", scenario_id))
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    executions = [
                        type("Execution", (), {"scenario_id": baseline, "operation": f"W4.{index}.{baseline}", "protocol": protocol})()
                        for index, baseline in enumerate(("W1", "W2", "W1"), start=1)
                    ]
                    if after_execution:
                        for execution in executions:
                            after_execution(execution)
                    return executions

                def execute_model_switch_prefix(
                    self,
                    prefix_length,
                    *,
                    after_execution=None,
                    before_execution=None,
                    observe_prefixes=False,
                ):
                    events.append(("prefix", prefix_length))
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    executions = [
                        type("Execution", (), {"scenario_id": baseline, "operation": f"W4.{index}.{baseline}", "protocol": protocol})()
                        for index, baseline in enumerate(("W1", "W2", "W1")[:prefix_length], start=1)
                    ]
                    if after_execution:
                        for execution in executions if observe_prefixes else executions[-1:]:
                            after_execution(execution)
                    return executions

                def execute_sustained_variant(
                    self,
                    variant_id,
                    after_execution=None,
                    before_execution=None,
                    **_kwargs,
                ):
                    calls.append(f"W5:{variant_id}")
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {
                        "scenario_id": variant_id,
                        "operation": f"W5.{variant_id}",
                        "protocol": protocol,
                        "measurement_scenario_id": "W5",
                        "variant_id": variant_id,
                        "sustained_throughput_per_second": 1.0,
                        "sustained_window_elapsed_ms": 1_000.0,
                        "sustained_window_sample_count": 1,
                    })()
                    if after_execution:
                        after_execution(execution)
                    return execution

            class FakeLifecycle:
                def __init__(self, *_args, **_kwargs):
                    pass

                def restart_and_verify(self):
                    events.append(("lifecycle", "verified"))
                    return {"forceStopped": True, "processAbsentAfterStop": True, "healthStatus": 200, "processPid": "123"}

            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", FakeExecutor,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", FakeSampler,
            ), patch(
                "localdream_perf_harness.ProcessLifecycleController", FakeLifecycle,
            ), patch(
                "localdream_perf_harness.load_acceptance_evidence", return_value=FakeAcceptanceEvidence(),
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=lambda _transport, context: verified_probe_for_context(context),
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ) as sample:
                self.assertEqual(2, command_run(run_args(root, probe_path, scenario_ids="W4")))

            self.assertEqual(
                [("lifecycle", "verified"), ("prefix", 1)]
                + [item for _ in range(6) for item in (("lifecycle", "verified"), ("prefix", 2))]
                + [item for _ in range(6) for item in (("lifecycle", "verified"), ("prefix", 3))],
                events,
            )
            self.assertEqual(13, sample.call_count)
            self.assertTrue(sample.call_args_list[0].kwargs["lifecycle_evidence"]["processAbsentAfterStop"])
            self.assertTrue(all(call.kwargs["lifecycle_evidence"] is None for call in sample.call_args_list[1:]))
            self.assertEqual(
                ["W1"] + ["W2"] * 6 + ["W1"] * 6,
                [call.args[2]["scenarioId"] for call in sample.call_args_list],
            )
            self.assertEqual(
                ["PROCESS_COLD"] + ["CONTEXT_WARM"] * 12,
                [call.args[2]["measurement"]["coldState"] for call in sample.call_args_list],
            )
            self.assertEqual(
                [False] + [True] * 5 + [False] + [True] * 5 + [False],
                [call.kwargs["is_warmup"] for call in sample.call_args_list],
            )
            raw_samples = [
                json.loads(line)
                for line in (root / "output" / "raw-samples.jsonl").read_text().splitlines()
            ]
            warmup_counts = {}
            for raw_sample in raw_samples:
                key = raw_sample["groupKey"]
                if key["coldState"] == "CONTEXT_WARM" and raw_sample["isWarmup"]:
                    warmup_counts[key["scenarioSha256"]] = warmup_counts.get(key["scenarioSha256"], 0) + 1
            self.assertEqual([5, 5], sorted(warmup_counts.values()))

    def test_w5_warms_and_reports_each_variant_with_its_own_measurement_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            calls = []

            class FakeExecutor:
                def __init__(self, *_args):
                    pass

                def begin_sustained_measurement(self):
                    pass

                def execute_sustained_variant(
                    self,
                    variant_id,
                    after_execution=None,
                    before_execution=None,
                    **_kwargs,
                ):
                    calls.append(f"W5:{variant_id}")
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {
                        "scenario_id": variant_id,
                        "operation": f"W5.{variant_id}",
                        "protocol": protocol,
                        "measurement_scenario_id": "W5",
                        "variant_id": variant_id,
                        "sustained_throughput_per_second": 1.0,
                        "sustained_window_elapsed_ms": 1_000.0,
                        "sustained_window_sample_count": 1,
                    })()
                    if after_execution:
                        after_execution(execution)
                    return execution

            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", FakeExecutor,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", FakeSampler,
            ), patch(
                "localdream_perf_harness.load_acceptance_evidence", return_value=FakeAcceptanceEvidence(),
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=lambda _transport, context: verified_probe_for_context(context),
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ) as sample:
                args = run_args(root, probe_path, scenario_ids="W5")
                args.iterations = 2
                self.assertEqual(2, command_run(args))

            self.assertEqual(
                ["W5:W1"] * 7 + ["W5:W2"] * 7,
                calls,
            )
            self.assertEqual(
                [True] * 5 + [False] * 2 + [True] * 5 + [False] * 2,
                [call.kwargs["is_warmup"] for call in sample.call_args_list],
            )
            recorded_scenarios = [call.args[2] for call in sample.call_args_list]
            self.assertEqual(
                {"W5:W1", "W5:W2"},
                {scenario["scenarioId"] for scenario in recorded_scenarios},
            )
            self.assertEqual(2, len({scenario["sha256"] for scenario in recorded_scenarios}))
            self.assertTrue(all(scenario["sha256"] not in {
                json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())["sha256"],
                json.loads((ROOT / "scenarios" / "v4" / "W2.json").read_text())["sha256"],
            } for scenario in recorded_scenarios))

    def test_process_lifecycle_records_force_stop_pid_transition_and_health(self):
        commands = iter([("111", 0), ("", 1), ("222", 0)])

        def runner(command, **_kwargs):
            if command[-2] == "pidof":
                stdout, returncode = next(commands)
            else:
                stdout, returncode = "", 0
            return type("Result", (), {"stdout": stdout, "returncode": returncode})()

        class HealthyTransport:
            def request(self, method, path, **_kwargs):
                assert (method, path) == ("GET", "/health")
                return type("Result", (), {"status": 200})()

        controller = ProcessLifecycleController(
            "172.20.103.120:5555",
            "io.github.xororz.localdream",
            HealthyTransport(),
            command_runner=runner,
            sleep=lambda _seconds: None,
            start_timeout_seconds=1,
        )
        evidence = controller.restart_and_verify()
        self.assertEqual("111", evidence["pidBefore"])
        self.assertEqual("222", evidence["processPid"])
        self.assertTrue(evidence["forceStopped"])
        self.assertTrue(evidence["processAbsentAfterStop"])
        self.assertEqual(200, evidence["healthStatus"])

    def test_process_lifecycle_rejects_ambiguous_pidof_result(self):
        def runner(_command, **_kwargs):
            return type("Result", (), {"stdout": "", "returncode": 0})()

        controller = ProcessLifecycleController(
            "172.20.103.120:5555",
            "io.github.xororz.localdream",
            object(),
            command_runner=runner,
        )

        with self.assertRaisesRegex(ProtocolExecutionError, "ambiguous process state"):
            controller._pid()

    def test_mixed_groups_write_isolated_reports_and_raw_artifacts(self):
        """A multi-scenario run must never publish one mixed GroupKey report."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            probe = RuntimeProbe.from_json(json.loads(probe_path.read_text()))
            sample_w1 = Sample(
                "grouped-run",
                0,
                GroupKey("a" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1"),
                Outcome.SUCCESS,
                100.0,
            )
            sample_w2 = Sample(
                "grouped-run",
                1,
                GroupKey("d" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1"),
                Outcome.SUCCESS,
                110.0,
            )

            write_artifacts(
                root / "output",
                "grouped-run",
                ROOT / "scenarios" / "v3",
                probe,
                [sample_w1, sample_w2],
                {"runId": "grouped-run", "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13", "reasons": []},
                preset_snapshot_sha256="b" * 64,
                run_context=json.loads(write_run_context(root).read_text()),
                telemetry_records=[{"sequence": 0}, {"sequence": 1}],
            )

            output = root / "output"
            manifest = json.loads((output / "run-manifest.json").read_text())
            self.assertEqual(2, len(manifest["groupArtifacts"]))
            root_report = json.loads((output / "report.json").read_text())
            self.assertNotIn("MIXED_GROUP_KEY", root_report["reasons"])
            for group in manifest["groupArtifacts"]:
                group_dir = output / group["artifactDirectory"]
                report_value = json.loads((group_dir / "report.json").read_text())
                raw_samples = [json.loads(line) for line in (group_dir / "raw-samples.jsonl").read_text().splitlines()]
                telemetry = [json.loads(line) for line in (group_dir / "telemetry.jsonl").read_text().splitlines()]
                self.assertEqual(1, report_value["sampleCount"])
                self.assertEqual(1, len(raw_samples))
                self.assertEqual([raw_samples[0]["sequence"]], [item["sequence"] for item in telemetry])

    def test_sample_requires_downloaded_output_b0_quality_and_resource_evidence(self):
        scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
        fingerprint = "a" * 64
        snapshot = "b" * 64
        baseline = {
            "scenarioSha256": scenario["sha256"], "presetSnapshotSha256": snapshot,
            "runtimeFingerprint": fingerprint, "coldState": "CONTEXT_WARM",
            "absoluteTimeoutMs": 120000, "qualityReferenceSha256": "c" * 64,
            "modelAssetSha256": scenario["model"]["assetSha256"],
        }
        output = "e" * 64
        acceptance = AcceptanceEvidence(
            {(scenario["sha256"], snapshot, fingerprint, "CONTEXT_WARM"): baseline},
            {output: {
                "scenarioSha256": scenario["sha256"], "outputSha256": output,
                "mode": "GOLDEN_SET", "qualityReferenceSha256": "c" * 64,
                "goldenSetSha256": "f" * 64, "promptCount": 30, "seedsPerPrompt": 4,
                "ssim": 0.98, "lpips": 0.05, "clipScoreRegressionPct": 1.0, "blindReviewPassed": True,
            }},
            {},
        )
        execution = type("Execution", (), {
            "operation": "W1",
            "protocol": ProtocolExecution(
                "/v1/images/generations", 100.0, 200, "/assets/image", 42,
                {"downloaded": True, "downloadContentType": "image/png", "downloadMagic": "PNG",
                 "downloadSha256": output, "downloadBytes": 42, "downloadWidth": 1024, "downloadHeight": 1024},
            ),
        })()
        sample = _sample_from_execution("run", 0, scenario, execution, fingerprint, snapshot, acceptance, {
            "batteryTemperatureC": 35.0, "thermalStatus": 0, "processPssKb": 10,
            "swapPssKb": 0, "sourceSha256": "a" * 64,
        })
        self.assertTrue(sample.quality_passed)
        self.assertTrue(sample.baseline_frozen)
        self.assertEqual(scenario["model"]["assetSha256"], sample.expected_model_asset_sha256)
        self.assertFalse(sample.thermal_stable)

        mismatched_baseline = baseline | {"modelAssetSha256": "a" * 64}
        with self.assertRaisesRegex(ValueError, "model asset SHA-256"):
            _sample_from_execution(
                "run",
                1,
                scenario,
                execution,
                fingerprint,
                snapshot,
                AcceptanceEvidence(
                    {(scenario["sha256"], snapshot, fingerprint, "CONTEXT_WARM"): mismatched_baseline},
                    acceptance.quality_results,
                    {},
                ),
                {},
            )

        with self.assertRaisesRegex(ValueError, "PROCESS_COLD lifecycle"):
            _sample_from_execution(
                "run", 2, scenario,
                type("Execution", (), {"operation": "W4.1.W1", "protocol": execution.protocol})(),
                fingerprint, snapshot, acceptance, {},
            )

    def test_thermal_gate_requires_duration_and_complete_adb_metrics(self):
        key = GroupKey("a" * 64, "b" * 64, "c" * 64, ColdState.CONTEXT_WARM, "1")
        samples = [Sample("run", index, key, Outcome.SUCCESS, 100.0 + index, resource_metrics={
            "batteryTemperatureC": 35.0, "thermalStatus": 0, "processPssKb": 10,
            "swapPssKb": 0, "sourceSha256": "d" * 64,
        }) for index in range(4)]
        self.assertFalse(finalize_thermal_stability(samples, elapsed_ms=29 * 60_000, required_minutes=30)[0].thermal_stable)
        stable = finalize_thermal_stability(samples, elapsed_ms=30 * 60_000, required_minutes=30)
        self.assertTrue(all(sample.thermal_stable for sample in stable))

    def test_run_rejects_non_verified_probe_before_device_connection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            probe_path.write_text(json.dumps({"status": "UNAVAILABLE"}))

            exit_code = command_run(
                type("Args", (), {
                    "scenario_dir": ROOT / "scenarios" / "v4",
                    "runtime_probe_file": str(probe_path),
                    "base_url": "http://127.0.0.1:1",
                    "fixture_dir": str(root / "fixtures"),
                    "output_dir": str(root / "output"),
                    "preset_snapshot_sha256": "accepted-snapshot",
                    "run_context_file": str(write_run_context(root)),
                    "bearer_token": None,
                    "bearer_token_file": None,
                    "bearer_token_env": None,
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
            self.assertEqual("REJECTED", manifest["preflightRuntimeProbe"]["status"])
            self.assertFalse(manifest["replayable"])
            self.assertIn("presetSnapshotSha256", manifest["missingReplayFacts"])

    def test_run_writes_complete_manifest_before_the_first_device_request(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            output_dir = root / "output"

            def assert_manifest_then_interrupt(_scenario_id, _after_execution=None, _before_execution=None):
                manifest = json.loads((output_dir / "run-manifest.json").read_text())
                self.assertTrue(manifest["replayable"])
                self.assertEqual(3, manifest["manifestVersion"])
                self.assertEqual("a" * 64, manifest["presetSnapshotSha256"])
                self.assertEqual(7, len(manifest["scenarioContracts"]))
                raise ProtocolExecutionError("interrupted after manifest")

            args = type("Args", (), {
                "scenario_dir": ROOT / "scenarios" / "v4", "runtime_probe_file": str(probe_path),
                "base_url": "http://172.20.103.120:8080", "fixture_dir": str(root / "fixtures"),
                "output_dir": str(output_dir), "preset_snapshot_sha256": "a" * 64,
                "run_context_file": str(write_run_context(root)), "bearer_token": None,
                "bearer_token_file": None, "bearer_token_env": "LOCALDREAM_API_KEY",
                "scenario_ids": "W1", "iterations": 1, "run_id": "manifest-first",
                "baseline_file": str(write_baseline(root, probe_path)),
                "quality_evidence_file": str(write_quality_evidence(root)),
                "adb_serial": "172.20.103.120:5555", "app_package": "io.github.xororz.localdream",
                "thermal_duration_minutes": 30,
            })()
            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor.execute", side_effect=assert_manifest_then_interrupt,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler.verify_target_identity",
                return_value={
                    "serial": "172.20.103.120:5555", "hardwareSerial": "3B15C4018L500000",
                    "model": "PJZ110", "soc": "SM8750", "boardPlatform": "sun", "abi": "arm64-v8a",
                    "appPackage": "io.github.ddq.visiondream", "packagePathSha256": "a" * 64,
                },
            ):
                self.assertEqual(2, command_run(args))

    def test_run_persists_partial_samples_and_telemetry_after_transport_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)

            class OneSampleThenDisconnect:
                def __init__(self, *_args):
                    self.calls = 0

                def execute(self, scenario_id, after_execution=None, before_execution=None):
                    self.calls += 1
                    if self.calls == 2:
                        raise ProtocolExecutionError(
                            "transport failure for POST /v1/images/generations (RemoteDisconnected)",
                        )
                    protocol = ProtocolExecution("/v1/images/generations", 100.0, 200, "/asset", 1, {})
                    execution = type("Execution", (), {
                        "scenario_id": scenario_id,
                        "operation": scenario_id,
                        "protocol": protocol,
                    })()
                    if after_execution:
                        after_execution(execution)
                    return [execution]

            args = run_args(root, probe_path, scenario_ids="W1")
            args.validation_level = ValidationLevel.EXPLORATORY.value
            with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "token"}, clear=True), patch(
                "localdream_perf_harness.DeviceScenarioExecutor", OneSampleThenDisconnect,
            ), patch(
                "localdream_perf_harness.AdbResourceSampler", FakeSampler,
            ), patch(
                "localdream_perf_harness.fetch_runtime_probe",
                side_effect=lambda _transport, context: verified_probe_for_context(context),
            ), patch(
                "localdream_perf_harness._sample_from_execution", side_effect=sample_from_mock_execution,
            ):
                self.assertEqual(2, command_run(args))

            report_value = json.loads((root / "output" / "report.json").read_text())
            manifest = json.loads((root / "output" / "run-manifest.json").read_text())
            self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", report_value["conclusion"])
            self.assertEqual(1, report_value["sampleCount"])
            self.assertIn("RemoteDisconnected", report_value["reasons"][0])
            self.assertTrue(manifest["observedRuntimeProbes"])
            self.assertTrue(manifest["groupArtifacts"])

    def test_report_requires_complete_probe_and_final_acceptance_evidence(self):
        key = GroupKey("scenario", "preset", "runtime", ColdState.PROCESS_COLD, "v1")
        samples = [Sample("run", index, key, Outcome.SUCCESS, 100.0 + index) for index in range(5)]
        result = report(RuntimeProbe(RuntimeProbeStatus.VERIFIED), samples, "run")
        self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", result["conclusion"])
        self.assertNotIn("statistics", result)
        self.assertIn("INCOMPLETE_RUNTIME_PROBE", result["reasons"])

    def test_bearer_token_uses_file_or_environment_never_argv(self):
        with tempfile.TemporaryDirectory() as temporary:
            secret_file = Path(temporary) / "token"
            secret_file.write_text("file-token\n")
            self.assertEqual(
                "file-token",
                resolve_bearer_token(type("Args", (), {
                    "bearer_token": None,
                    "bearer_token_file": str(secret_file),
                    "bearer_token_env": None,
                })()),
            )
        with patch.dict("os.environ", {"LOCALDREAM_API_KEY": "env-token"}, clear=True):
            self.assertEqual(
                "env-token",
                resolve_bearer_token(type("Args", (), {
                    "bearer_token": None,
                    "bearer_token_file": None,
                    "bearer_token_env": "LOCALDREAM_API_KEY",
                })()),
            )
        with self.assertRaisesRegex(ValueError, "not supported"):
            resolve_bearer_token(type("Args", (), {
                "bearer_token": "argv-secret",
                "bearer_token_file": None,
                "bearer_token_env": None,
            })())

    def test_w7_runner_uses_independent_secret_files_and_writes_a_redacted_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            v1_token = root / "v1-token"
            mcp_token = root / "mcp-token"
            v1_token.write_text("v1-secret")
            mcp_token.write_text("mcp-secret")
            probe_path = root / "probe.json"
            write_verified_probe(probe_path)
            execution = ProtocolExecution("/endpoint", 12.5, 200, "/image", 0, {"jobId": "job-1"})
            parity_result = {
                "v1": execution,
                "mcp": {
                    "generation": execution,
                    "progressEvents": [{"step": 1}],
                    "progressReplay": [{"step": 1}],
                    "taskEvent": {"task": "succeeded"},
                    "taskReplay": {"task": "succeeded"},
                    "cancelReplay": {"task": "cancelled"},
                    "download": b"png",
                },
                "parity": {"tool": True, "download": True},
            }
            args = type("Args", (), {
                "scenario_file": ROOT / "scenarios" / "v4" / "W7.json",
                "v1_base_url": "http://127.0.0.1:8809",
                "mcp_base_url": "http://127.0.0.1:8810",
                "output_dir": root / "output",
                "runtime_probe_file": probe_path,
                "run_context_file": write_run_context(root),
                "v1_bearer_token_file": v1_token,
                "v1_bearer_token_env": None,
                "mcp_bearer_token_file": mcp_token,
                "mcp_bearer_token_env": None,
                "run_id": "w7-run",
            })()
            with patch("localdream_perf_harness.protocol_parity", return_value=parity_result) as parity:
                self.assertEqual(0, command_run_w7(args))

            self.assertEqual("v1-secret", parity.call_args.args[0].bearer_token)
            self.assertEqual("mcp-secret", parity.call_args.args[1].bearer_token)
            self.assertEqual("w7-run", parity.call_args.kwargs["mutation_namespace"])
            report_value = json.loads((root / "output" / "w7-report.json").read_text())
            self.assertEqual("W7_PROTOCOL_PARITY_PASSED", report_value["conclusion"])
            self.assertNotIn("secret", json.dumps(report_value))
            self.assertEqual("VERIFIED", json.loads((root / "output" / "run-manifest.json").read_text())["preflightRuntimeProbe"]["status"])

    def test_w7_rejects_non_verified_or_incomplete_runtime_before_contacting_device(self):
        for status in ("UNAVAILABLE", "REJECTED", "VERIFIED"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                probe_path = root / "probe.json"
                probe_path.write_text(json.dumps({"status": status}))
                args = type("Args", (), {
                    "scenario_file": ROOT / "scenarios" / "v4" / "W7.json", "runtime_probe_file": probe_path,
                    "run_context_file": write_run_context(root), "v1_base_url": "http://127.0.0.1:1",
                    "mcp_base_url": "http://127.0.0.1:1", "output_dir": root / "output",
                    "v1_bearer_token_file": None, "v1_bearer_token_env": "LOCALDREAM_V1_TOKEN",
                    "mcp_bearer_token_file": None, "mcp_bearer_token_env": "LOCALDREAM_MCP_TOKEN", "run_id": f"w7-{status.lower()}",
                })()
                with patch("localdream_perf_harness.protocol_parity") as parity:
                    self.assertEqual(2, command_run_w7(args))
                parity.assert_not_called()
                report = json.loads((root / "output" / "w7-report.json").read_text())
                self.assertEqual("NOT_ACCEPTED_FOR_ONEPLUS13", report["conclusion"])
                self.assertEqual(status, json.loads((root / "output" / "run-manifest.json").read_text())["preflightRuntimeProbe"]["status"])


class FakeSampler:
    def __init__(self, *_args):
        self.records = []

    def verify_target_identity(self, _probe):
        return {"serial": "172.20.103.120:5555", "model": "PJZ110", "soc": "SM8750", "abi": "arm64-v8a"}

    def collect(self, sequence):
        record = {"sequence": sequence, "collectionError": "not collected by protocol fixture"}
        self.records.append(record)
        return record

    def collect_request_baseline(self, sequence):
        record = self.collect(sequence)
        record["capturePhase"] = "REQUEST_BASELINE"
        return record

    def unload_and_collect(self, sequence, *_args):
        record = {"sequence": sequence, "collectionError": "not collected by protocol fixture"}
        self.records.append(record)
        return record


class FakeAcceptanceEvidence:
    manifest_reference = {"baselineId": "fixture"}


def sample_from_mock_execution(
    run_id,
    sequence,
    scenario,
    _execution,
    runtime_fingerprint,
    preset_snapshot_sha256,
    _acceptance_evidence,
    _resource_metrics,
    *,
    is_warmup=False,
    lifecycle_evidence=None,
):
    return Sample(
        run_id,
        sequence,
        GroupKey(
            scenario["sha256"],
            preset_snapshot_sha256,
            runtime_fingerprint,
            ColdState(scenario["measurement"]["coldState"]),
            "1",
        ),
        Outcome.SUCCESS,
        100.0,
        is_warmup=is_warmup,
        resource_metrics={"processColdLifecycle": lifecycle_evidence} if lifecycle_evidence else {},
    )


def run_args(root: Path, probe_path: Path, *, scenario_ids: str):
    return type("Args", (), {
        "scenario_dir": ROOT / "scenarios" / "v4",
        "runtime_probe_file": str(probe_path),
        "base_url": "http://172.20.103.120:8080",
        "fixture_dir": str(root / "fixtures"),
        "output_dir": str(root / "output"),
        "preset_snapshot_sha256": "a" * 64,
        "run_context_file": str(write_run_context(root)),
        "bearer_token": None,
        "bearer_token_file": None,
        "bearer_token_env": "LOCALDREAM_API_KEY",
        "scenario_ids": scenario_ids,
        "iterations": 1,
        "run_id": "protocol-fixture",
        "baseline_file": str(root / "baseline.json"),
        "quality_evidence_file": str(root / "quality.json"),
        "adb_serial": "172.20.103.120:5555",
        "app_package": "io.github.xororz.localdream",
        "thermal_duration_minutes": 30,
    })()


def write_run_context(root: Path) -> Path:
    path = root / "run-context.json"
    path.write_text(json.dumps({
        "presetSnapshotSha256": "a" * 64,
        "appBuild": "debug-1",
        "androidVersion": "15",
        "network": {"type": "wifi"},
        "battery": {"percent": 80},
        "screen": {"brightness": 50},
        "ambientTemperatureC": 25.0,
    }))
    return path


def write_verified_probe(path: Path) -> None:
    path.write_text(json.dumps(probe_json_for_context("a" * 64)))


def capture_baseline_with_live_target(
    *,
    scenario_dir: Path,
    quality_file: Path,
    output_file: Path,
    scenario_ids: str,
    context_fingerprint: str | None,
    base_url: str = "http://172.20.103.120:8080",
    installation_digest: str = "a" * 64,
) -> int:
    probe_json = (
        probe_json_for_context(context_fingerprint)
        if context_fingerprint is not None
        else {"status": "UNAVAILABLE"}
    )
    target_identity = target_identity_fixture()

    args = type("Args", (), {
        "scenario_dir": scenario_dir,
        "base_url": base_url,
        "adb_serial": "172.20.103.120:5555",
        "app_package": "io.github.ddq.visiondream",
        "bearer_token": None,
        "bearer_token_file": "unused-by-mock",
        "bearer_token_env": None,
        "preset_snapshot_sha256": "a" * 64,
        "quality_evidence_file": quality_file,
        "output_file": output_file,
        "scenario_ids": scenario_ids,
    })()
    with (
        patch("localdream_perf_harness.resolve_bearer_token", return_value="secret"),
        patch("localdream_perf_harness.UrlLibTransport") as transport_class,
        patch.object(AdbResourceSampler, "verify_target_identity", return_value=target_identity),
    ):
        transport_class.return_value.request.return_value = HttpResult(
            status=200,
            headers={},
            body=json.dumps({"runtimeProbe": probe_json, "installation": {
                "appPackage": "io.github.ddq.visiondream", "packagePathSha256": installation_digest,
            }}).encode(),
        )
        return command_capture_baseline(args)


def target_identity_fixture() -> dict:
    return {
        "serial": "172.20.103.120:5555",
        "hardwareSerial": "3B15C4018L500000",
        "model": "PJZ110",
        "soc": "SM8750",
        "boardPlatform": "sun",
        "abi": "arm64-v8a",
        "appPackage": "io.github.ddq.visiondream",
        "packagePathSha256": "a" * 64,
    }


def verified_probe_for_context(context_fingerprint: str) -> RuntimeProbe:
    return RuntimeProbe.from_json(probe_json_for_context(context_fingerprint))


def probe_json_for_context(context_fingerprint: str) -> dict:
    return {
        "status": "VERIFIED",
        "deviceModel": "PJZ110",
        "soc": "SM8750",
        "abi": "arm64-v8a",
        "qairtVersion": "2.48.40",
        "htpTarget": "v79",
        "contextFingerprint": context_fingerprint,
        "loadedLibraryFingerprints": {
            "libQnnHtp.so": "a" * 64,
            "libQnnHtpV79Stub.so": "b" * 64,
        },
        "nativeReady": True,
    }


def write_baseline(root: Path, probe_path: Path) -> Path:
    scenario = json.loads((ROOT / "scenarios" / "v4" / "W1.json").read_text())
    probe = verified_probe_for_context(scenario["model"]["assetSha256"])
    path = root / "baseline.json"
    path.write_text(json.dumps({
        "schemaVersion": 2,
        "baselineId": "b0-test",
        "provenance": {
            "runtimeProbe": probe_json_for_context(probe.context_fingerprint),
            "runtimeProbeSha256": _runtime_fingerprint(probe),
            "adbTarget": {
                "serial": "172.20.103.120:5555",
                "hardwareSerial": "3B15C4018L500000",
                "model": "PJZ110",
                "soc": "SM8750",
                "boardPlatform": "sun",
                "abi": "arm64-v8a",
                "appPackage": "io.github.ddq.visiondream",
                "packagePathSha256": "a" * 64,
            },
            "appPackage": "io.github.ddq.visiondream",
            "modelContextFingerprint": probe.context_fingerprint,
            "healthEndpoint": {"baseUrl": "http://172.20.103.120:8080", "adbHost": "172.20.103.120"},
            "healthInstallation": {
                "appPackage": "io.github.ddq.visiondream", "packagePathSha256": "a" * 64,
            },
        },
        "entries": [{
            "scenarioSha256": scenario["sha256"],
            "presetSnapshotSha256": "a" * 64,
            "runtimeFingerprint": _runtime_fingerprint(probe),
            "coldState": "CONTEXT_WARM",
            "absoluteTimeoutMs": 120000,
            "qualityReferenceSha256": "c" * 64,
            "modelAssetSha256": scenario["model"]["assetSha256"],
        }],
    }))
    return path


def write_quality_evidence(root: Path) -> Path:
    path = root / "quality.json"
    path.write_text(json.dumps({"schemaVersion": 1, "results": {}}))
    return path

if __name__ == "__main__":
    unittest.main()
