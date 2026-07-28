#!/usr/bin/env python3
"""验证不可变场景，并在设备端运行前执行强制 RuntimeProbe 门禁。"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import sys
import uuid
from pathlib import Path

from localdream_perf_executor import DeviceScenarioExecutor
from localdream_perf_models import ColdState, GroupKey, Outcome, RuntimeProbe, RuntimeProbeStatus, Sample, probe_as_dict, report
from localdream_perf_protocol import ProtocolExecutionError, UrlLibTransport


REQUIRED_KEYS = {
    "schemaVersion", "scenarioId", "scenarioVersion", "workflow", "fixtures", "model",
    "request", "measurement", "timeoutMs", "sha256",
}
WORKFLOWS = {"GENERATE", "IMAGE_TO_IMAGE", "MODEL_SWITCH", "SUSTAINED", "UPSCALE_API", "PROTOCOL_PARITY"}


def canonical_digest(value: dict) -> str:
    payload = {key: content for key, content in value.items() if key != "sha256"}
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_scenario(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"{path}: invalid JSON: {error}") from error
    if not isinstance(value, dict) or set(value) != REQUIRED_KEYS:
        raise ValueError(f"{path}: scenario keys must exactly match v1 contract")
    if value["schemaVersion"] != 1 or value["workflow"] not in WORKFLOWS:
        raise ValueError(f"{path}: unsupported schema or workflow")
    if not isinstance(value["fixtures"], dict) or not value["fixtures"]:
        raise ValueError(f"{path}: fixtures are required")
    if not isinstance(value["model"], dict) or not value["model"].get("assetSha256"):
        raise ValueError(f"{path}: model asset summary is required")
    if not isinstance(value["request"], dict) or not value["request"]:
        raise ValueError(f"{path}: fixed request is required")
    if not isinstance(value["timeoutMs"], int) or value["timeoutMs"] <= 0:
        raise ValueError(f"{path}: timeoutMs must be positive")
    digest = canonical_digest(value)
    if value["sha256"] != digest:
        raise ValueError(f"{path}: sha256 mismatch")
    return value


def validate_scenarios(directory: Path) -> list[dict]:
    scenarios = [validate_scenario(path) for path in sorted(directory.glob("*.json"))]
    ids = [scenario["scenarioId"] for scenario in scenarios]
    required = {f"W{number}" for number in range(1, 8)}
    if not required.issubset(ids) or len(ids) != len(set(ids)):
        raise ValueError("scenario directory must contain exactly one W1-W7 baseline")
    w2b = [scenario for scenario in scenarios if scenario["scenarioId"] == "W2b"]
    if w2b and any(scenario["scenarioVersion"] == 1 for scenario in w2b):
        raise ValueError("W2b must be a separately published variant, not a W2 baseline replacement")
    return scenarios


def command_validate(args: argparse.Namespace) -> int:
    scenarios = validate_scenarios(Path(args.scenario_dir))
    print(json.dumps({"validated": len(scenarios), "scenarioIds": [item["scenarioId"] for item in scenarios]}))
    return 0


def command_verify(args: argparse.Namespace) -> int:
    validate_scenarios(Path(args.scenario_dir))
    probe = (
        RuntimeProbe.from_json(json.loads(Path(args.runtime_probe_file).read_text(encoding="utf-8")))
        if args.runtime_probe_file
        else RuntimeProbe(RuntimeProbeStatus.UNAVAILABLE)
    )
    samples = load_samples(args.samples_file)
    run_id = args.run_id or str(uuid.uuid4())
    result = report(probe, samples, run_id)
    if args.require_verified_runtime and probe.status != RuntimeProbeStatus.VERIFIED:
        result = {
            "runId": run_id,
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": [f"RuntimeProbe={probe.status.value}"],
            "sampleCount": len(samples),
        }
    if args.output_dir:
        write_artifacts(Path(args.output_dir), run_id, Path(args.scenario_dir), probe, samples, result)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["conclusion"] == "ACCEPTED_FOR_ONEPLUS13" else 2


def command_run(args: argparse.Namespace) -> int:
    scenario_dir = Path(args.scenario_dir)
    scenarios = validate_scenarios(scenario_dir)
    probe = RuntimeProbe.from_json(json.loads(Path(args.runtime_probe_file).read_text(encoding="utf-8")))
    run_id = args.run_id or str(uuid.uuid4())
    if probe.status != RuntimeProbeStatus.VERIFIED:
        result = {
            "runId": run_id,
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": [f"RuntimeProbe={probe.status.value}"],
            "sampleCount": 0,
        }
        write_artifacts(Path(args.output_dir), run_id, scenario_dir, probe, [], result)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    selected = set(args.scenario_ids.split(",")) if args.scenario_ids else {f"W{number}" for number in range(1, 7)}
    unknown = selected - {item["scenarioId"] for item in scenarios}
    if unknown:
        raise ValueError(f"unknown scenario ids: {','.join(sorted(unknown))}")
    executor = DeviceScenarioExecutor(
        UrlLibTransport(args.base_url, args.bearer_token),
        scenarios,
        Path(args.fixture_dir),
    )
    samples: list[Sample] = []
    fingerprint = _runtime_fingerprint(probe)
    sequence = 0
    try:
        for _ in range(args.iterations):
            for scenario in scenarios:
                if scenario["scenarioId"] not in selected:
                    continue
                for execution in executor.execute(scenario["scenarioId"]):
                    samples.append(
                        _sample_from_execution(
                            run_id,
                            sequence,
                            next(item for item in scenarios if item["scenarioId"] == execution.scenario_id),
                            execution,
                            fingerprint,
                            args.preset_snapshot_sha256,
                        ),
                    )
                    sequence += 1
    except ProtocolExecutionError as error:
        result = {"runId": run_id, "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13", "reasons": [str(error)], "sampleCount": len(samples)}
        write_artifacts(Path(args.output_dir), run_id, scenario_dir, probe, samples, result)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    result = report(probe, samples, run_id)
    write_artifacts(Path(args.output_dir), run_id, scenario_dir, probe, samples, result)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["conclusion"] == "ACCEPTED_FOR_ONEPLUS13" else 2


def _runtime_fingerprint(probe: RuntimeProbe) -> str:
    return hashlib.sha256(json.dumps(probe_as_dict(probe), sort_keys=True, default=list).encode("utf-8")).hexdigest()


def _sample_from_execution(
    run_id: str,
    sequence: int,
    scenario: dict,
    execution,
    runtime_fingerprint: str,
    preset_snapshot_sha256: str,
) -> Sample:
    protocol = execution.protocol
    return Sample(
        run_id=run_id,
        sequence=sequence,
        group_key=GroupKey(
            scenario_sha256=scenario["sha256"],
            preset_snapshot_sha256=preset_snapshot_sha256,
            runtime_fingerprint=runtime_fingerprint,
            cold_state=ColdState(scenario["measurement"]["coldState"]),
            harness_version="1",
        ),
        outcome=Outcome.SUCCESS if protocol.status == 200 else Outcome.PROTOCOL_MISMATCH,
        end_to_end_ms=protocol.elapsed_ms,
        # An HTTP 200 only proves transport success.  Target acceptance needs a
        # device-produced response/quality bundle and B0/thermal evidence, so
        # none of these values may silently default to passing here.
        quality_passed=False,
        baseline_frozen=False,
        thermal_stable=False,
        response_evidence=protocol.evidence.get("responseEvidence"),
        expected_model_asset_sha256=scenario["model"]["assetSha256"],
        quality_evidence=protocol.evidence.get("qualityEvidence"),
        stage_metrics={"endpoint": protocol.endpoint, "outputBytes": float(protocol.output_bytes)},
        resource_metrics={"operation": execution.operation},
    )


def load_samples(path: str | None) -> list[Sample]:
    if not path:
        return []
    payload = Path(path).read_text(encoding="utf-8")
    values = json.loads(payload)
    if not isinstance(values, list):
        raise ValueError("samples file must be a JSON array")
    return [Sample.from_json(value) for value in values]




def write_artifacts(
    output_dir: Path,
    run_id: str,
    scenario_dir: Path,
    probe: RuntimeProbe,
    samples: list[Sample],
    result: dict,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = validate_scenarios(scenario_dir)
    manifest = {
        "runId": run_id,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "harnessVersion": "1",
        "scenarioDigests": {item["scenarioId"]: item["sha256"] for item in scenarios},
        "runtimeProbe": probe_as_dict(probe),
    }
    (output_dir / "run-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output_dir / "raw-samples.jsonl").write_text(
        "".join(json.dumps(sample.to_json(), ensure_ascii=False, sort_keys=True) + "\n" for sample in samples),
        encoding="utf-8",
    )
    (output_dir / "report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-scenarios")
    validate.add_argument("--scenario-dir", required=True)
    validate.set_defaults(func=command_validate)
    verify = commands.add_parser("verify")
    verify.add_argument("--scenario-dir", required=True)
    verify.add_argument("--runtime-probe-file")
    verify.add_argument("--samples-file")
    verify.add_argument("--run-id")
    verify.add_argument("--require-verified-runtime", action="store_true")
    verify.add_argument("--output-dir")
    verify.set_defaults(func=command_verify)
    run = commands.add_parser("run")
    run.add_argument("--scenario-dir", required=True)
    run.add_argument("--runtime-probe-file", required=True)
    run.add_argument("--base-url", required=True)
    run.add_argument("--fixture-dir", required=True)
    run.add_argument("--output-dir", required=True)
    run.add_argument("--preset-snapshot-sha256", required=True)
    run.add_argument("--bearer-token")
    run.add_argument("--scenario-ids")
    run.add_argument("--iterations", type=int, default=1)
    run.add_argument("--run-id")
    run.set_defaults(func=command_run)
    try:
        args = parser.parse_args()
        return args.func(args)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
