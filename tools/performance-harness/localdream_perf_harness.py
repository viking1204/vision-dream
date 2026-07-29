#!/usr/bin/env python3
"""验证不可变场景，并在设备端运行前执行强制 RuntimeProbe 门禁。"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import sys
import time
import uuid
from pathlib import Path

from localdream_perf_executor import DeviceScenarioExecutor
from localdream_perf_models import ColdState, GroupKey, Outcome, RuntimeProbe, RuntimeProbeStatus, Sample, is_verified_target_probe, probe_as_dict, report
from localdream_perf_protocol import ProtocolExecutionError, UrlLibTransport, protocol_parity


REQUIRED_KEYS = {
    "schemaVersion", "scenarioId", "scenarioVersion", "workflow", "fixtures", "model",
    "request", "measurement", "timeoutMs", "sha256",
}
WORKFLOWS = {"GENERATE", "IMAGE_TO_IMAGE", "MODEL_SWITCH", "SUSTAINED", "UPSCALE_API", "PROTOCOL_PARITY"}
RUN_CONTEXT_KEYS = {"presetSnapshotSha256", "appBuild", "androidVersion", "network", "battery", "screen", "ambientTemperatureC"}


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
    run_context = load_run_context(Path(args.run_context_file))
    if not is_verified_target_probe(probe):
        result = {
            "runId": run_id,
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": runtime_gate_reasons(probe),
            "sampleCount": 0,
        }
        write_artifacts(
            Path(args.output_dir), run_id, scenario_dir, probe, [], result,
            preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    selected = set(args.scenario_ids.split(",")) if args.scenario_ids else {f"W{number}" for number in range(1, 7)}
    unknown = selected - {item["scenarioId"] for item in scenarios}
    if unknown:
        raise ValueError(f"unknown scenario ids: {','.join(sorted(unknown))}")
    write_manifest(
        Path(args.output_dir), run_id, scenario_dir, probe,
        preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
    )
    executor = DeviceScenarioExecutor(
        UrlLibTransport(args.base_url, resolve_bearer_token(args)),
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
        write_artifacts(
            Path(args.output_dir), run_id, scenario_dir, probe, samples, result,
            preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    result = report(probe, samples, run_id)
    write_artifacts(
        Path(args.output_dir), run_id, scenario_dir, probe, samples, result,
        preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["conclusion"] == "ACCEPTED_FOR_ONEPLUS13" else 2


def command_run_w7(args: argparse.Namespace) -> int:
    scenario = validate_scenario(Path(args.scenario_file))
    if scenario["scenarioId"] != "W7" or scenario["workflow"] != "PROTOCOL_PARITY":
        raise ValueError("run-w7 requires the immutable W7 PROTOCOL_PARITY scenario")
    run_id = args.run_id or str(uuid.uuid4())
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    probe = RuntimeProbe.from_json(json.loads(Path(args.runtime_probe_file).read_text(encoding="utf-8")))
    run_context = load_run_context(Path(args.run_context_file))
    if not is_verified_target_probe(probe):
        report_value = {
            "runId": run_id,
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": runtime_gate_reasons(probe),
            "scenarioSha256": scenario["sha256"],
        }
        write_artifacts(
            output_dir, run_id, Path(args.scenario_file).parent, probe, [], report_value,
            preset_snapshot_sha256=run_context["presetSnapshotSha256"], run_context=run_context,
        )
        (output_dir / "w7-report.json").write_text(
            json.dumps(report_value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(report_value, ensure_ascii=False, sort_keys=True))
        return 2
    write_manifest(
        output_dir, run_id, Path(args.scenario_file).parent, probe,
        preset_snapshot_sha256=run_context["presetSnapshotSha256"], run_context=run_context,
    )
    v1_token = resolve_secret_source(
        args.v1_bearer_token_file,
        args.v1_bearer_token_env,
        "OpenAI bearer token",
    )
    mcp_token = resolve_secret_source(
        args.mcp_bearer_token_file,
        args.mcp_bearer_token_env,
        "MCP bearer token",
    )
    try:
        result = protocol_parity(
            UrlLibTransport(args.v1_base_url, v1_token),
            UrlLibTransport(args.mcp_base_url, mcp_token),
            scenario,
            mutation_namespace=run_id,
        )
        parity = result["parity"]
        report_value = {
            "runId": run_id,
            "conclusion": "W7_PROTOCOL_PARITY_PASSED" if all(parity.values()) else "W7_PROTOCOL_PARITY_FAILED",
            "scenarioSha256": scenario["sha256"],
            "parity": parity,
            "v1": execution_summary(result["v1"]),
            "mcp": {
                "generation": execution_summary(result["mcp"]["generation"]),
                "progressEventCount": len(result["mcp"]["progressEvents"]),
                "progressReplayCount": len(result["mcp"]["progressReplay"]),
                "taskEvent": result["mcp"]["taskEvent"],
                "taskReplay": result["mcp"]["taskReplay"],
                "cancelReplay": result["mcp"]["cancelReplay"],
                "downloadBytes": len(result["mcp"]["download"]),
            },
        }
    except ProtocolExecutionError as error:
        report_value = {
            "runId": run_id,
            "conclusion": "W7_PROTOCOL_PARITY_FAILED",
            "scenarioSha256": scenario["sha256"],
            "reason": str(error),
        }
    (output_dir / "w7-report.json").write_text(
        json.dumps(report_value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    write_artifacts(
        output_dir, run_id, Path(args.scenario_file).parent, probe, [], report_value,
        preset_snapshot_sha256=run_context["presetSnapshotSha256"], run_context=run_context,
    )
    print(json.dumps(report_value, ensure_ascii=False, sort_keys=True))
    return 0 if report_value["conclusion"] == "W7_PROTOCOL_PARITY_PASSED" else 2


def execution_summary(execution) -> dict:
    return {
        "endpoint": execution.endpoint,
        "elapsedMs": execution.elapsed_ms,
        "status": execution.status,
        "hasOutputUrl": bool(execution.output_url),
        "outputBytes": execution.output_bytes,
        "evidence": execution.evidence,
    }


def resolve_bearer_token(args: argparse.Namespace) -> str:
    """Reads a secret from a file or named environment variable, never argv."""
    if getattr(args, "bearer_token", None):
        raise ValueError("--bearer-token is not supported; use --bearer-token-file or --bearer-token-env")
    return resolve_secret_source(
        getattr(args, "bearer_token_file", None),
        getattr(args, "bearer_token_env", None),
        "bearer token",
    )


def resolve_secret_source(token_file: str | None, token_env: str | None, label: str) -> str:
    if bool(token_file) == bool(token_env):
        raise ValueError(f"exactly one file or environment source is required for {label}")
    if token_file:
        path = Path(token_file)
        if not path.is_file():
            raise ValueError(f"{label} file is unavailable")
        token = path.read_text(encoding="utf-8").strip()
    else:
        if not str(token_env).isidentifier() or not str(token_env).isupper():
            raise ValueError(f"{label} environment variable name is invalid")
        token = os.environ.get(str(token_env), "").strip()
    if not token:
        raise ValueError(f"{label} is empty")
    return token


def _runtime_fingerprint(probe: RuntimeProbe) -> str:
    return hashlib.sha256(json.dumps(probe_as_dict(probe), sort_keys=True, default=list).encode("utf-8")).hexdigest()


def runtime_gate_reasons(probe: RuntimeProbe) -> list[str]:
    reasons = [] if probe.status == RuntimeProbeStatus.VERIFIED else [f"RuntimeProbe={probe.status.value}"]
    if not is_verified_target_probe(probe):
        reasons.append("INCOMPLETE_RUNTIME_PROBE")
    return reasons


def load_run_context(path: Path) -> dict:
    try:
        context = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"run context is unavailable: {error}") from error
    if not isinstance(context, dict) or set(context) != RUN_CONTEXT_KEYS:
        raise ValueError("run context must exactly match the replay contract")
    required_strings = ("presetSnapshotSha256", "appBuild", "androidVersion")
    if not all(isinstance(context[key], str) and context[key].strip() for key in required_strings):
        raise ValueError("run context requires preset snapshot, app build and Android version")
    required_objects = ("network", "battery", "screen")
    if not all(isinstance(context[key], dict) and context[key] for key in required_objects):
        raise ValueError("run context requires network, battery and screen facts")
    if not isinstance(context["ambientTemperatureC"], (int, float)):
        raise ValueError("run context requires ambient temperature")
    return context


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
    *,
    preset_snapshot_sha256: str | None = None,
    run_context: dict | None = None,
) -> None:
    write_manifest(
        output_dir, run_id, scenario_dir, probe,
        preset_snapshot_sha256=preset_snapshot_sha256, run_context=run_context,
    )
    (output_dir / "raw-samples.jsonl").write_text(
        "".join(json.dumps(sample.to_json(), ensure_ascii=False, sort_keys=True) + "\n" for sample in samples),
        encoding="utf-8",
    )
    (output_dir / "report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_manifest(
    output_dir: Path,
    run_id: str,
    scenario_dir: Path,
    probe: RuntimeProbe,
    *,
    preset_snapshot_sha256: str | None = None,
    run_context: dict | None = None,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = validate_scenarios(scenario_dir)
    context = run_context or {}
    scenario_contracts = {
        item["scenarioId"]: {
            "scenarioVersion": item["scenarioVersion"],
            "fixtures": item["fixtures"],
            "modelMetadata": item["model"],
            "coldState": item["measurement"].get("coldState"),
        }
        for item in scenarios
    }
    required_context_facts = ("presetSnapshotSha256", "appBuild", "androidVersion", "network", "battery", "screen", "ambientTemperatureC")
    missing_replay_facts = [
        key for key in required_context_facts
        if not (preset_snapshot_sha256 if key == "presetSnapshotSha256" else context.get(key))
    ]
    manifest = {
        "manifestVersion": 2,
        "runId": run_id,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "harnessVersion": "1",
        "scenarioDigests": {item["scenarioId"]: item["sha256"] for item in scenarios},
        "scenarioContracts": scenario_contracts,
        "presetSnapshotSha256": preset_snapshot_sha256,
        "appBuild": context.get("appBuild"),
        "androidVersion": context.get("androidVersion"),
        "network": context.get("network"),
        "battery": context.get("battery"),
        "screen": context.get("screen"),
        "ambientTemperatureC": context.get("ambientTemperatureC"),
        "contextFingerprint": probe.context_fingerprint,
        "runtimeProbe": probe_as_dict(probe),
        "replayable": not missing_replay_facts,
        "missingReplayFacts": missing_replay_facts,
    }
    (output_dir / "run-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
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
    run.add_argument("--run-context-file", required=True)
    run.add_argument("--bearer-token", help=argparse.SUPPRESS)
    secret_source = run.add_mutually_exclusive_group(required=True)
    secret_source.add_argument("--bearer-token-file")
    secret_source.add_argument("--bearer-token-env")
    run.add_argument("--scenario-ids")
    run.add_argument("--iterations", type=int, default=1)
    run.add_argument("--run-id")
    run.set_defaults(func=command_run)
    run_w7 = commands.add_parser("run-w7")
    run_w7.add_argument("--scenario-file", required=True)
    run_w7.add_argument("--v1-base-url", required=True)
    run_w7.add_argument("--mcp-base-url", required=True)
    run_w7.add_argument("--output-dir", required=True)
    run_w7.add_argument("--runtime-probe-file", required=True)
    run_w7.add_argument("--run-context-file", required=True)
    v1_secret_source = run_w7.add_mutually_exclusive_group(required=True)
    v1_secret_source.add_argument("--v1-bearer-token-file")
    v1_secret_source.add_argument("--v1-bearer-token-env")
    mcp_secret_source = run_w7.add_mutually_exclusive_group(required=True)
    mcp_secret_source.add_argument("--mcp-bearer-token-file")
    mcp_secret_source.add_argument("--mcp-bearer-token-env")
    run_w7.add_argument("--run-id")
    run_w7.set_defaults(func=command_run_w7)
    try:
        args = parser.parse_args()
        return args.func(args)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
