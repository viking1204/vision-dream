#!/usr/bin/env python3
"""验证不可变场景，并在设备端运行前执行强制 RuntimeProbe 门禁。"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, replace
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

from localdream_perf_executor import DeviceScenarioExecutor
from localdream_perf_models import ColdState, GroupKey, Outcome, RuntimeProbe, RuntimeProbeStatus, Sample, ValidationLevel, is_verified_target_probe, probe_as_dict, report
from localdream_perf_protocol import (
    ProtocolExecutionError,
    UrlLibTransport,
    _mcp_tool,
    initialize_mcp,
    protocol_parity,
)


REQUIRED_KEYS = {
    "schemaVersion", "scenarioId", "scenarioVersion", "workflow", "fixtures", "model",
    "request", "measurement", "timeoutMs", "sha256",
}
WORKFLOWS = {"GENERATE", "IMAGE_TO_IMAGE", "MODEL_SWITCH", "SUSTAINED", "UPSCALE_API", "PROTOCOL_PARITY"}
RUN_CONTEXT_KEYS = {"presetSnapshotSha256", "appBuild", "androidVersion", "network", "battery", "screen", "ambientTemperatureC"}
WARMUP_SAMPLES_PER_GROUP = 5
QUALITY_CAPTURE_KEYS = {"schemaVersion", "results"}


def canonical_digest(value: dict) -> str:
    payload = {key: content for key, content in value.items() if key != "sha256"}
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_scenario(path: Path, *, require_real_model_asset_sha256: bool = False) -> dict:
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
    if require_real_model_asset_sha256 and not _is_sha256(value["model"]["assetSha256"]):
        raise ValueError(f"{path}: acceptance scenarios require a real lowercase SHA-256 model asset summary")
    if not isinstance(value["request"], dict) or not value["request"]:
        raise ValueError(f"{path}: fixed request is required")
    if not isinstance(value["timeoutMs"], int) or value["timeoutMs"] <= 0:
        raise ValueError(f"{path}: timeoutMs must be positive")
    digest = canonical_digest(value)
    if value["sha256"] != digest:
        raise ValueError(f"{path}: sha256 mismatch")
    return value


def validate_scenarios(
    directory: Path,
    *,
    require_real_model_asset_sha256: bool = False,
) -> list[dict]:
    scenarios = [
        validate_scenario(path, require_real_model_asset_sha256=require_real_model_asset_sha256)
        for path in sorted(directory.glob("*.json"))
    ]
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
    result = grouped_report(probe, samples, run_id)
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


def command_capture_quality(args: argparse.Namespace) -> int:
    """Normalizes auditable quality measurements without accepting caller flags.

    The input is retained in the output so a later target-validation run can
    prove which per-output measurement produced its pass/fail decision.  This
    command deliberately computes ``passed`` itself; a hand-written boolean
    must never upgrade an image into a quality-qualified result.
    """
    scenarios = validate_scenarios(Path(args.scenario_dir), require_real_model_asset_sha256=True)
    known_scenarios = {
        item["sha256"]: item
        for item in (*scenarios, *_w5_measurement_scenarios(scenarios, next(item for item in scenarios if item["scenarioId"] == "W5")))
    }
    source = _load_json_object(Path(args.quality_input_file), "quality capture input")
    if set(source) != QUALITY_CAPTURE_KEYS or source["schemaVersion"] != 1 or not isinstance(source["results"], list):
        raise ValueError("quality capture input must exactly match schema v1")
    results: dict[str, dict] = {}
    for item in source["results"]:
        result = _captured_quality_result(item, known_scenarios)
        output_sha256 = result["outputSha256"]
        if output_sha256 in results:
            raise ValueError("quality capture input contains duplicate output SHA-256")
        results[output_sha256] = result
    if not results:
        raise ValueError("quality capture input requires at least one result")
    output = Path(args.output_file)
    _write_new_json(output, {"schemaVersion": 1, "results": results}, "quality evidence")
    print(json.dumps({"qualityEvidenceSha256": _file_sha256(output), "resultCount": len(results)}, sort_keys=True))
    return 0


def command_capture_baseline(args: argparse.Namespace) -> int:
    """Freezes B0 from a live authenticated target-runtime observation.

    A probe file proves only that a previous observation existed. It must not
    authorize a new B0 capture: authenticated ``/health``, ADB identity and
    the selected scenario model must agree at capture time.
    """
    scenario_dir = Path(args.scenario_dir)
    scenarios = validate_scenarios(scenario_dir, require_real_model_asset_sha256=True)
    selected = set(args.scenario_ids.split(",")) if args.scenario_ids else {f"W{number}" for number in range(1, 7)}
    by_id = _acceptance_measurement_scenarios(scenarios)
    if "W5" in selected:
        raise ValueError("B0 capture for W5 requires exactly one W5:W1 or W5:W2 variant")
    unknown = selected - set(by_id)
    if unknown:
        raise ValueError(f"unknown scenario ids: {','.join(sorted(unknown))}")
    selected_models = {by_id[scenario_id]["model"]["assetSha256"] for scenario_id in selected}
    if len(selected_models) != 1:
        raise ValueError("B0 capture requires scenarios with exactly one active model context")
    health_endpoint = _bound_health_endpoint(args.base_url, args.adb_serial)
    transport = UrlLibTransport(args.base_url, resolve_bearer_token(args))
    try:
        probe, installation = fetch_runtime_observation(transport, next(iter(selected_models)))
    except ProtocolExecutionError:
        print(json.dumps({
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": ["LIVE_RUNTIME_PROBE_REJECTED"],
        }, sort_keys=True))
        return 2
    device_identity = AdbResourceSampler(args.adb_serial, args.app_package).verify_target_identity(probe)
    if installation != {
        "appPackage": device_identity["appPackage"],
        "packagePathSha256": device_identity["packagePathSha256"],
    }:
        raise ValueError("authenticated health installation does not match the ADB target app instance")
    if not _is_sha256(args.preset_snapshot_sha256):
        raise ValueError("preset snapshot SHA-256 must be a lowercase SHA-256")
    quality = _load_json_object(Path(args.quality_evidence_file), "quality evidence")
    if set(quality) != {"schemaVersion", "results"} or quality["schemaVersion"] != 1 or not isinstance(quality["results"], dict):
        raise ValueError("quality evidence must exactly match schema v1")
    for output_sha256, result in quality["results"].items():
        _validate_quality_result_for_acceptance(output_sha256, result)
    runtime_fingerprint = _runtime_fingerprint(probe)
    entries = []
    for scenario_id in sorted(selected):
        scenario = by_id[scenario_id]
        references = {
            item.get("qualityReferenceSha256")
            for item in quality["results"].values()
            if isinstance(item, dict)
            and item.get("scenarioSha256") == scenario["sha256"]
            and item.get("mode") == scenario["measurement"]["qualityMode"]
            and item.get("passed") is True
        }
        if len(references) != 1 or not _is_sha256(next(iter(references), None)):
            raise ValueError(f"quality evidence needs exactly one passing reference for {scenario_id}")
        entries.append({
            "scenarioSha256": scenario["sha256"],
            "presetSnapshotSha256": args.preset_snapshot_sha256,
            "runtimeFingerprint": runtime_fingerprint,
            "coldState": scenario["measurement"]["coldState"],
            "absoluteTimeoutMs": scenario["timeoutMs"],
            "qualityReferenceSha256": next(iter(references)),
            "modelAssetSha256": scenario["model"]["assetSha256"],
        })
    baseline_id = f"b0-{hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()).hexdigest()[:16]}"
    output = Path(args.output_file)
    provenance = {
        "runtimeProbe": probe_as_dict(probe),
        "runtimeProbeSha256": _runtime_fingerprint(probe),
        "adbTarget": device_identity,
        "appPackage": args.app_package,
        "modelContextFingerprint": probe.context_fingerprint,
        "healthEndpoint": health_endpoint,
        "healthInstallation": installation,
    }
    _write_new_json(
        output,
        {"schemaVersion": 2, "baselineId": baseline_id, "entries": entries, "provenance": provenance},
        "B0 baseline",
    )
    print(json.dumps({"baselineId": baseline_id, "baselineSha256": _file_sha256(output), "entryCount": len(entries)}, sort_keys=True))
    return 0


def command_run(args: argparse.Namespace) -> int:
    scenario_dir = Path(args.scenario_dir)
    scenarios = validate_scenarios(scenario_dir, require_real_model_asset_sha256=True)
    preflight_probe = RuntimeProbe.from_json(json.loads(Path(args.runtime_probe_file).read_text(encoding="utf-8")))
    validation_level = ValidationLevel(getattr(args, "validation_level", ValidationLevel.FINAL_VALIDATED.value))
    run_id = args.run_id or str(uuid.uuid4())
    run_context = load_run_context(Path(args.run_context_file))
    if not is_verified_target_probe(preflight_probe):
        result = {
            "runId": run_id,
            "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13",
            "reasons": runtime_gate_reasons(preflight_probe),
            "sampleCount": 0,
        }
        write_artifacts(
            Path(args.output_dir), run_id, scenario_dir, preflight_probe, [], result,
            preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    selected = set(args.scenario_ids.split(",")) if args.scenario_ids else {f"W{number}" for number in range(1, 7)}
    unknown = selected - {item["scenarioId"] for item in scenarios}
    if unknown:
        raise ValueError(f"unknown scenario ids: {','.join(sorted(unknown))}")
    sampler = AdbResourceSampler(args.adb_serial, args.app_package)
    device_identity = sampler.verify_target_identity(preflight_probe)
    acceptance_evidence = (
        load_acceptance_evidence(args, device_identity)
        if validation_level != ValidationLevel.EXPLORATORY
        else None
    )
    write_manifest(
        Path(args.output_dir), run_id, scenario_dir, preflight_probe,
        preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
        acceptance_evidence=acceptance_evidence.manifest_reference if acceptance_evidence else None,
        validation_level=validation_level,
        device_identity=device_identity,
    )
    transport = UrlLibTransport(args.base_url, resolve_bearer_token(args))
    executor = DeviceScenarioExecutor(
        transport,
        scenarios,
        Path(args.fixture_dir),
    )
    lifecycle = ProcessLifecycleController(
        args.adb_serial,
        args.app_package,
        transport,
        start_timeout_seconds=getattr(args, "lifecycle_start_timeout_seconds", 60),
    )
    samples: list[Sample] = []
    warmed_group_keys: set[GroupKey] = set()
    warmup_counts_by_group: dict[GroupKey, int] = {}
    observed_probes: dict[str, RuntimeProbe] = {}
    probes_by_execution_id: dict[int, RuntimeProbe] = {}
    group_resources: dict[GroupKey, dict] = {}

    def capture_request_baseline(_scenario_id: str, _operation: str) -> dict:
        """Collect immediately before the physical HTTP request starts."""
        return sampler.collect_request_baseline(sequence)

    def observe_completed_execution(execution) -> None:
        request_scenario = next(item for item in scenarios if item["scenarioId"] == execution.scenario_id)
        sample_scenario = _measurement_scenario(scenarios, request_scenario, execution)
        # The post-operation RuntimeProbe must bind the model actually sent in
        # the request.  W5's measurement identity is distinct, but its W2
        # variant still requires the W2 context rather than W5's default model.
        observed_probe = fetch_runtime_probe(transport, request_scenario["model"]["assetSha256"])
        observed_probes[_runtime_fingerprint(observed_probe)] = observed_probe
        probes_by_execution_id[id(execution)] = observed_probe

    sequence = 0

    def record_execution_sample(execution, *, is_warmup: bool, lifecycle_evidence: dict | None = None) -> GroupKey:
        """Records one physical request with the RuntimeProbe observed after it.

        A warmup belongs to the GroupKey derived from its own completed
        operation.  If the runtime changes before the following measured
        request, the measured request deliberately has no matching warmups and
        the grouped report rejects it instead of silently reclassifying it.
        """
        nonlocal sequence
        execution_scenario = next(item for item in scenarios if item["scenarioId"] == execution.scenario_id)
        sample_scenario = _measurement_scenario(scenarios, execution_scenario, execution)
        observed_probe = probes_by_execution_id.pop(id(execution))
        fingerprint = _runtime_fingerprint(observed_probe)
        group_key = _group_key(sample_scenario, fingerprint, args.preset_snapshot_sha256)
        request_baseline = getattr(execution, "request_baseline", None)
        post_request = sampler.collect(sequence)
        lifecycle_metrics = group_resources.setdefault(group_key, {
            "baseline": request_baseline,
            "peakPssKb": None,
            "peakRssKb": None,
            "peakSwapPssKb": None,
            # The release operation must name the exact model that produced
            # this group.  A later A->B->A switch cannot silently unload A and
            # relabel it as evidence for B.
            # W4/W5 have derived measurement identities, but their unload is
            # for the physical W1/W2 request model, never the wrapper's
            # default selector.
            "expectedModelId": execution_scenario["model"].get("selector"),
        })
        _update_group_peak(lifecycle_metrics, request_baseline)
        _update_group_peak(lifecycle_metrics, post_request)
        samples.append(
            _sample_from_execution(
                run_id,
                sequence,
                sample_scenario,
                execution,
                fingerprint,
                args.preset_snapshot_sha256,
                acceptance_evidence,
                post_request,
                is_warmup=is_warmup,
                lifecycle_evidence=lifecycle_evidence,
            ),
        )
        sequence += 1
        if is_warmup:
            warmup_counts_by_group[group_key] = warmup_counts_by_group.get(group_key, 0) + 1
        return group_key

    # A GroupKey owns one contiguous physical batch and is released before the
    # next batch begins.  Deferring releases until the entire run lets a later
    # model switch relabel stale memory as another group's release evidence.
    releases: dict[GroupKey, dict] = {}
    release_sequence = 1_000_000

    def release_group_batch(group_keys: set[GroupKey]) -> None:
        nonlocal release_sequence
        for group_key in sorted(group_keys, key=_group_artifact_id):
            if group_key in releases:
                raise ProtocolExecutionError("a released GroupKey cannot be reused by a later resource batch")
            try:
                release = _collect_group_releases(
                    sampler, {group_key: group_resources[group_key]}, release_sequence,
                    _runtime_unloader(args, transport),
                )[group_key]
            except ProtocolExecutionError as error:
                release = {
                    "sequence": release_sequence,
                    "capturePhase": "POST_UNLOAD_RELEASE",
                    "resourceGroupId": _group_artifact_id(group_key),
                    "collectionError": str(error),
                }
                sampler.records.append(release)
            releases[group_key] = release
            release_sequence += 1

    def release_pending_groups() -> None:
        release_group_batch(set(group_resources).difference(releases))

    def run_w4_isolated_prefix(
        prefix_length: int,
        *,
        warmup_count: int,
        measurement_count: int,
    ) -> None:
        """Run one W4 terminal group using only its required A/B prefix.

        A, A->B and A->B->A are separate restart/replay batches.  Thus each
        terminal model is unloaded while it is still the active runtime, and
        the B or trailing-A release cannot be borrowed by another W4 group.
        """
        terminal_keys: set[GroupKey] = set()
        for is_warmup in [True] * warmup_count + [False] * measurement_count:
            lifecycle_evidence = lifecycle.restart_and_verify()
            executions = executor.execute_model_switch_prefix(
                prefix_length,
                after_execution=observe_completed_execution,
                before_execution=capture_request_baseline,
                observe_prefixes=True,
            )
            terminal = executions[-1]
            terminal_keys.add(record_execution_sample(
                terminal,
                is_warmup=is_warmup,
                lifecycle_evidence=lifecycle_evidence if prefix_length == 1 else None,
            ))
        release_group_batch(terminal_keys)

    started_ns = time.monotonic_ns()
    try:
        # Keep a normal scenario's warmups and formal samples contiguous.  It
        # yields exactly one model lifecycle per GroupKey rather than moving
        # through all scenarios first and releasing stale groups at run end.
        for scenario in scenarios:
            scenario_id = scenario["scenarioId"]
            if scenario_id not in selected or scenario_id in {"W4", "W5"}:
                continue
            batch_keys: set[GroupKey] = set()
            if _requires_warmup(scenario):
                for _ in range(WARMUP_SAMPLES_PER_GROUP):
                    warmup = _single_execution(
                        executor, scenario_id, observe_completed_execution, capture_request_baseline,
                    )
                    batch_keys.add(record_execution_sample(warmup, is_warmup=True))
            for _ in range(args.iterations):
                execution = _single_execution(
                    executor, scenario_id, observe_completed_execution, capture_request_baseline,
                )
                batch_keys.add(record_execution_sample(execution, is_warmup=False))
            release_group_batch(batch_keys)

        if "W4" in selected:
            # The first A is process-cold; B and trailing A are independently
            # warmed context groups.  Each group receives its own prefix replay
            # and immediate expected-model unload.
            run_w4_isolated_prefix(1, warmup_count=0, measurement_count=args.iterations)
            run_w4_isolated_prefix(2, warmup_count=WARMUP_SAMPLES_PER_GROUP, measurement_count=args.iterations)
            run_w4_isolated_prefix(3, warmup_count=WARMUP_SAMPLES_PER_GROUP, measurement_count=args.iterations)

        if "W5" in selected:
            sustained = next(item for item in scenarios if item["scenarioId"] == "W5")
            variants = sustained["fixtures"].get("variants")
            if variants != ["W1", "W2"]:
                raise ProtocolExecutionError("W5 must preserve the published W1/W2 sustained variants")
            for variant in variants:
                batch_keys: set[GroupKey] = set()
                for _ in range(WARMUP_SAMPLES_PER_GROUP):
                    warmup = executor.execute_sustained_variant(
                        variant, observe_completed_execution, capture_request_baseline,
                        measure_sustained=False,
                    )
                    batch_keys.add(record_execution_sample(warmup, is_warmup=True))
                executor.begin_sustained_measurement()
                for _ in range(args.iterations):
                    execution = executor.execute_sustained_variant(
                        variant, observe_completed_execution, capture_request_baseline,
                    )
                    batch_keys.add(record_execution_sample(execution, is_warmup=False))
                release_group_batch(batch_keys)
    except (ProtocolExecutionError, ValueError) as error:
        result = {"runId": run_id, "conclusion": "NOT_ACCEPTED_FOR_ONEPLUS13", "reasons": [str(error)], "sampleCount": len(samples)}
        release_pending_groups()
        samples = _attach_group_resource_lifecycle(samples, group_resources, releases)
        write_artifacts(
            Path(args.output_dir), run_id, scenario_dir, preflight_probe, samples, result,
            preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
            acceptance_evidence=acceptance_evidence.manifest_reference if acceptance_evidence else None,
            validation_level=validation_level,
            telemetry_records=sampler.records, device_identity=device_identity,
            observed_probes=observed_probes,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 2
    release_pending_groups()
    samples = _attach_group_resource_lifecycle(samples, group_resources, releases)
    if validation_level == ValidationLevel.FINAL_VALIDATED:
        samples = finalize_thermal_stability(
            samples,
            elapsed_ms=(time.monotonic_ns() - started_ns) / 1_000_000,
            required_minutes=args.thermal_duration_minutes,
        )
    result = grouped_report(
        preflight_probe,
        samples,
        run_id,
        validation_level=validation_level,
        observed_probes=observed_probes,
    )
    write_artifacts(
        Path(args.output_dir), run_id, scenario_dir, preflight_probe, samples, result,
        preset_snapshot_sha256=args.preset_snapshot_sha256, run_context=run_context,
        acceptance_evidence=acceptance_evidence.manifest_reference if acceptance_evidence else None,
        validation_level=validation_level,
        telemetry_records=sampler.records, device_identity=device_identity,
        observed_probes=observed_probes,
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    if result["conclusion"] in {"TARGET_VALIDATED", "ACCEPTED_FOR_ONEPLUS13"}:
        _write_qualification_candidate(
            Path(args.output_dir),
            run_id,
            scenarios,
            run_context,
            args.preset_snapshot_sha256,
            validation_level,
        )
    return 0 if result["conclusion"] in {"EXPLORATORY_COMPLETED", "TARGET_VALIDATED", "ACCEPTED_FOR_ONEPLUS13"} else 2


def command_run_w7(args: argparse.Namespace) -> int:
    scenario = validate_scenario(Path(args.scenario_file), require_real_model_asset_sha256=True)
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


def fetch_runtime_probe(transport, expected_model_asset_sha256: str) -> RuntimeProbe:
    return fetch_runtime_observation(transport, expected_model_asset_sha256)[0]


def fetch_runtime_observation(transport, expected_model_asset_sha256: str) -> tuple[RuntimeProbe, dict[str, str]]:
    """Reads post-operation runtime evidence from authenticated local health."""
    response = transport.request("GET", "/health", timeout_ms=5_000)
    if response.status != 200:
        raise ProtocolExecutionError(f"runtime probe health request returned HTTP {response.status}")
    try:
        health = json.loads(response.body.decode("utf-8"))
        probe_json = health["runtimeProbe"]
        installation = health["installation"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise ProtocolExecutionError("runtime probe health response is invalid") from error
    if not isinstance(probe_json, dict) or not isinstance(installation, dict):
        raise ProtocolExecutionError("runtime probe health response has no object probe")
    if set(installation) != {"appPackage", "packagePathSha256"} or not isinstance(installation["appPackage"], str) or not _is_sha256(installation["packagePathSha256"]):
        raise ProtocolExecutionError("runtime probe health response has an invalid installation binding")
    try:
        probe = RuntimeProbe.from_json(probe_json)
    except (KeyError, TypeError, ValueError) as error:
        raise ProtocolExecutionError("runtime probe health response has an invalid probe") from error
    _require_observed_probe_for_model(probe, expected_model_asset_sha256)
    return probe, installation


def _bound_health_endpoint(base_url: str, adb_serial: str) -> dict[str, str | int]:
    """Only admit health directly addressed to the Wi-Fi ADB target.

    A generic URL can point at a different PJZ110 with the same model and SoC.
    Target acceptance therefore uses the host portion of the explicitly chosen
    Wi-Fi ADB serial, in addition to the app-installation digest in /health.
    """
    endpoint = urlparse(base_url)
    serial_host, separator, _serial_port = adb_serial.rpartition(":")
    if endpoint.scheme != "http" or not endpoint.hostname or endpoint.path not in ("", "/") or endpoint.query or endpoint.fragment:
        raise ValueError("B0 capture base URL must be an http origin without path, query, or fragment")
    if not separator or not serial_host or endpoint.hostname != serial_host:
        raise ValueError("B0 capture base URL is not bound to the Wi-Fi ADB target")
    return {"baseUrl": f"http://{endpoint.netloc}", "adbHost": serial_host}


def _require_observed_probe_for_model(probe: RuntimeProbe, expected_model_asset_sha256: str) -> None:
    if not is_verified_target_probe(probe):
        raise ProtocolExecutionError(
            "post-operation RuntimeProbe is not a verified PJZ110 runtime: "
            + ",".join(runtime_gate_reasons(probe)),
        )
    if probe.context_fingerprint != expected_model_asset_sha256:
        raise ProtocolExecutionError(
            "post-operation RuntimeProbe context fingerprint does not match the executed model asset",
        )


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


@dataclass(frozen=True)
class AcceptanceEvidence:
    """The B0 and quality files that are frozen before a target-device run."""

    baselines: dict[tuple[str, str, str, str], dict]
    quality_results: dict[str, dict]
    manifest_reference: dict[str, str]

    def baseline_for(self, group_key: GroupKey) -> dict:
        key = (
            group_key.scenario_sha256,
            group_key.preset_snapshot_sha256,
            group_key.runtime_fingerprint,
            group_key.cold_state.value,
        )
        try:
            return self.baselines[key]
        except KeyError as error:
            raise ValueError("B0 baseline does not match this immutable scenario/runtime group") from error


def load_acceptance_evidence(args: argparse.Namespace, current_adb_target: dict) -> AcceptanceEvidence:
    """Rejects incomplete admission evidence before any device request is made."""
    baseline_path = Path(args.baseline_file)
    quality_path = Path(args.quality_evidence_file)
    baseline = _load_json_object(baseline_path, "B0 baseline")
    quality = _load_json_object(quality_path, "quality evidence")
    if set(baseline) != {"schemaVersion", "baselineId", "entries", "provenance"} or baseline["schemaVersion"] != 2:
        raise ValueError("B0 baseline must exactly match schema v2")
    if not isinstance(baseline["baselineId"], str) or not baseline["baselineId"] or not isinstance(baseline["entries"], list):
        raise ValueError("B0 baseline requires baselineId and entries")
    provenance = baseline["provenance"]
    if not isinstance(provenance, dict) or set(provenance) != {
        "runtimeProbe", "runtimeProbeSha256", "adbTarget", "appPackage", "modelContextFingerprint", "healthEndpoint", "healthInstallation",
    }:
        raise ValueError("B0 baseline requires live runtime and ADB provenance")
    if (
        not isinstance(provenance["runtimeProbe"], dict)
        or not _is_sha256(provenance["runtimeProbeSha256"])
        or not isinstance(provenance["adbTarget"], dict)
        or not isinstance(provenance["appPackage"], str)
        or not _is_sha256(provenance["modelContextFingerprint"])
        or not isinstance(provenance["healthEndpoint"], dict)
        or not isinstance(provenance["healthInstallation"], dict)
    ):
        raise ValueError("B0 baseline provenance is invalid")
    try:
        captured_probe = RuntimeProbe.from_json(provenance["runtimeProbe"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("B0 baseline provenance runtime probe is invalid") from error
    if (
        not is_verified_target_probe(captured_probe)
        or _runtime_fingerprint(captured_probe) != provenance["runtimeProbeSha256"]
        or captured_probe.context_fingerprint != provenance["modelContextFingerprint"]
        or provenance["adbTarget"].get("appPackage") != provenance["appPackage"]
        or provenance["adbTarget"].get("model") != captured_probe.device_model
        or str(provenance["adbTarget"].get("soc", "")).upper() != (captured_probe.soc or "").upper()
        or provenance["adbTarget"].get("abi") != captured_probe.abi
        or not isinstance(provenance["adbTarget"].get("hardwareSerial"), str)
        or not provenance["adbTarget"]["hardwareSerial"]
        or not _is_sha256(provenance["adbTarget"].get("packagePathSha256"))
        or provenance["healthInstallation"] != {"appPackage": provenance["appPackage"], "packagePathSha256": provenance["adbTarget"].get("packagePathSha256")}
        or provenance["adbTarget"] != current_adb_target
        or provenance["healthEndpoint"] != _bound_health_endpoint(args.base_url, args.adb_serial)
    ):
        raise ValueError("B0 baseline provenance does not bind the current verified target app instance")
    if set(quality) != {"schemaVersion", "results"} or quality["schemaVersion"] != 1 or not isinstance(quality["results"], dict):
        raise ValueError("quality evidence must exactly match schema v1")
    required = {
        "scenarioSha256", "presetSnapshotSha256", "runtimeFingerprint", "coldState",
        "absoluteTimeoutMs", "qualityReferenceSha256", "modelAssetSha256",
    }
    entries: dict[tuple[str, str, str, str], dict] = {}
    for entry in baseline["entries"]:
        if not isinstance(entry, dict) or set(entry) != required:
            raise ValueError("B0 baseline entry must exactly match schema v1")
        key = (entry["scenarioSha256"], entry["presetSnapshotSha256"], entry["runtimeFingerprint"], entry["coldState"])
        if (
            not all(_is_sha256(value) for value in key[:3])
            or entry["coldState"] not in {state.value for state in ColdState}
            or not isinstance(entry["absoluteTimeoutMs"], int) or entry["absoluteTimeoutMs"] <= 0
            or not _is_sha256(entry["qualityReferenceSha256"])
            or not _is_sha256(entry["modelAssetSha256"])
            or key in entries
            or entry["runtimeFingerprint"] != provenance["runtimeProbeSha256"]
            or entry["modelAssetSha256"] != provenance["modelContextFingerprint"]
        ):
            raise ValueError("B0 baseline entry has invalid or duplicate frozen facts")
        entries[key] = entry
    for output_sha256, result in quality["results"].items():
        _validate_quality_result_for_acceptance(output_sha256, result)
    return AcceptanceEvidence(
        baselines=entries,
        quality_results=quality["results"],
        manifest_reference={
            "baselineId": baseline["baselineId"],
            "baselineSha256": _file_sha256(baseline_path),
            "qualityEvidenceSha256": _file_sha256(quality_path),
        },
    )


class AdbResourceSampler:
    """Collects device thermals and process memory through a group lifecycle.

    OEMs may hide one of these counters.  That is kept as a collection error,
    never transformed into a passing thermal sample.
    """

    def __init__(self, serial: str, package_name: str):
        if not re.fullmatch(r"[A-Za-z0-9._:-]+", serial or ""):
            raise ValueError("ADB serial is invalid")
        if not re.fullmatch(r"[A-Za-z0-9_.]+", package_name or ""):
            raise ValueError("Android package name is invalid")
        self._serial = serial
        self._package_name = package_name
        self.records: list[dict] = []

    def collect(self, sequence: int) -> dict:
        try:
            battery = self._shell("dumpsys battery")
            thermal = self._shell("dumpsys thermalservice")
            memory = self._shell(f"dumpsys meminfo {self._package_name}")
            temperature = int(_required_match(battery, r"temperature:\s*(-?\d+)", "battery temperature")) / 10
            thermal_status = int(_required_match(thermal, r"Thermal Status:\s*(\d+)", "thermal status"))
            process_pss = int(_required_match(memory, r"TOTAL PSS:\s*([\d,]+)", "process PSS").replace(",", ""))
            process_rss = int(_required_match(memory, r"TOTAL RSS:\s*([\d,]+)", "process RSS").replace(",", ""))
            swap_pss = int(_required_match(memory, r"TOTAL SWAP PSS:\s*([\d,]+)", "process swap PSS").replace(",", ""))
            record = {
                "sequence": sequence,
                "capturedAt": datetime.now(timezone.utc).isoformat(),
                "batteryTemperatureC": temperature,
                "thermalStatus": thermal_status,
                "processPssKb": process_pss,
                "processRssKb": process_rss,
                "swapPssKb": swap_pss,
                "sourceSha256": hashlib.sha256((battery + thermal + memory).encode()).hexdigest(),
            }
        except (OSError, subprocess.SubprocessError, ValueError, ProtocolExecutionError) as error:
            record = {"sequence": sequence, "collectionError": str(error)}
        self.records.append(record)
        return record

    def collect_request_baseline(self, sequence: int) -> dict:
        """Records the device state immediately before one physical request."""
        record = self.collect(sequence)
        record["capturePhase"] = "REQUEST_BASELINE"
        return record

    def unload_and_collect(self, sequence: int, expected_model_id: str, runtime_unloader) -> dict:
        """Unload one model while keeping the authenticated API process alive.

        ``am force-stop`` makes PSS/RSS zero by killing the listener itself,
        which cannot prove model release, release recovery, or next-group
        reload.  The only admissible action is the existing expected-model MCP
        runtime transition, followed by authenticated-health and live-process
        evidence.
        """
        try:
            unload = runtime_unloader.unload(expected_model_id)
            record = self.collect(sequence)
            if "collectionError" in record:
                record.update({"capturePhase": "POST_UNLOAD_RELEASE", "expectedModelId": expected_model_id})
                return record
            if not self._pidof_after_unload():
                raise ValueError("OpenAI application process disappeared after runtime unload")
            record.update({
                "capturePhase": "POST_UNLOAD_RELEASE",
                "unloadAction": "MCP_RUNTIME_UNLOAD",
                "expectedModelId": expected_model_id,
                "runtimeUnloaded": unload["runtimeUnloaded"],
                "serviceAvailableAfterUnload": unload["serviceAvailableAfterUnload"],
                "processAliveAfterUnload": True,
                "unloadHealthSourceSha256": unload["healthSourceSha256"],
            })
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            record = {
                "sequence": sequence,
                "capturePhase": "POST_UNLOAD_RELEASE",
                "collectionError": str(error),
            }
        self.records.append(record)
        return record

    def verify_target_identity(self, probe: RuntimeProbe) -> dict:
        """Bind resource telemetry to the same PJZ110/SM8750 endpoint as probe."""
        model = self._shell("getprop ro.product.model").strip()
        # RuntimeProbe uses Android's SoC model identity. Qualcomm's board
        # platform property is a codename (for example "sun" on SM8750), so
        # comparing it with the public SoC model rejects the correct device.
        soc = self._shell("getprop ro.soc.model").strip()
        board_platform = self._shell("getprop ro.board.platform").strip()
        abi = self._shell("getprop ro.product.cpu.abi").strip()
        hardware_serial = self._shell("getprop ro.serialno").strip()
        package_path = self._shell(f"cmd package path {self._package_name}").strip()
        if (model, soc.upper(), abi) != (probe.device_model, (probe.soc or "").upper(), probe.abi):
            raise ValueError("ADB target identity does not match the VERIFIED RuntimeProbe")
        if not hardware_serial or not package_path.startswith("package:"):
            raise ValueError("ADB target identity does not expose the sampled application package")
        return {
            "serial": self._serial,
            "hardwareSerial": hardware_serial,
            "model": model,
            "soc": soc,
            "boardPlatform": board_platform,
            "abi": abi,
            "appPackage": self._package_name,
            "packagePathSha256": hashlib.sha256(package_path.encode()).hexdigest(),
        }

    def _shell(self, command: str) -> str:
        result = subprocess.run(
            ["adb", "-s", self._serial, "shell", command],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
        )
        return result.stdout

    def _pidof_after_unload(self) -> str:
        """Returns a remaining PID, or an empty string only for Android's absent form.

        ``pidof`` intentionally exits with status 1 when no matching process
        exists.  Treating that expected result as a subprocess failure turns a
        successful force-stop into a false resource-release failure.  Every
        other exit/output combination is ambiguous and must remain fail-closed.
        """
        result = subprocess.run(
            ["adb", "-s", self._serial, "shell", "pidof", self._package_name],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        pid = result.stdout.strip()
        if result.returncode == 1 and not pid:
            return ""
        if result.returncode == 0 and pid:
            return pid
        raise ValueError(
            "ADB pidof returned an ambiguous process state after explicit unload "
            f"(exit={result.returncode}, hasOutput={bool(pid)})"
        )


class McpRuntimeUnloader:
    """Runs the existing expected-model unload contract and verifies its result."""

    def __init__(self, api_transport, mcp_transport, timeout_ms: int = 15_000):
        self._api_transport = api_transport
        self._mcp_transport = mcp_transport
        self._timeout_ms = timeout_ms
        self._request_id = 50_000

    def unload(self, expected_model_id: str) -> dict:
        session_id = initialize_mcp(self._mcp_transport, self._timeout_ms)
        self._request_id += 1
        response = _mcp_tool(
            self._mcp_transport,
            session_id,
            self._request_id,
            "runtime.unload",
            {
                "runtimeId": expected_model_id,
                "dryRun": False,
                "idempotencyKey": f"perf-release:{expected_model_id}:{self._request_id}",
            },
            self._timeout_ms,
        )
        if response.get("runtimeId") != expected_model_id or response.get("unloadRequested") is not True:
            raise ProtocolExecutionError("runtime unload rejected the expected model")
        deadline = time.monotonic() + self._timeout_ms / 1_000
        last_status = None
        while time.monotonic() < deadline:
            health = self._api_transport.request("GET", "/health", timeout_ms=5_000)
            last_status = health.status
            if health.status == 200:
                try:
                    value = json.loads(health.body.decode("utf-8"))
                    is_unloaded = value["runtimeProbe"]["status"] == "UNAVAILABLE"
                    service_is_idle = value["active"] is False and value["queued"] == 0
                except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as error:
                    raise ProtocolExecutionError("runtime unload health response is invalid") from error
                if is_unloaded and service_is_idle:
                    return {
                        "runtimeUnloaded": True,
                        "serviceAvailableAfterUnload": True,
                        "healthSourceSha256": hashlib.sha256(health.body).hexdigest(),
                    }
            time.sleep(0.2)
        raise ProtocolExecutionError(
            f"runtime unload did not reach idle authenticated health (lastHealthStatus={last_status})",
        )


class UnavailableRuntimeUnloader:
    def unload(self, _expected_model_id: str) -> dict:
        raise ProtocolExecutionError("resource lifecycle requires MCP runtime.unload credentials")


def _runtime_unloader(args: argparse.Namespace, api_transport):
    """Constructs the model-only release path; missing credentials fail closed."""
    mcp_base_url = getattr(args, "mcp_base_url", None)
    mcp_token_file = getattr(args, "mcp_bearer_token_file", None)
    mcp_token_env = getattr(args, "mcp_bearer_token_env", None)
    if not mcp_base_url or not (mcp_token_file or mcp_token_env):
        return UnavailableRuntimeUnloader()
    token = resolve_secret_source(
        token_file=mcp_token_file,
        token_env=mcp_token_env,
        label="MCP bearer token",
    )
    return McpRuntimeUnloader(api_transport, UrlLibTransport(mcp_base_url, token))


class ProcessLifecycleController:
    """Proves a W4 process-cold boundary before its A→B→A requests.

    A scenario label is insufficient: the harness force-stops the exact app,
    observes that its process disappeared, starts the launcher activity, then
    requires both a new PID and the authenticated local health endpoint.
    Failure at any point prevents the first W4 request from being submitted.
    """

    def __init__(
        self,
        serial: str,
        package_name: str,
        transport,
        *,
        command_runner=subprocess.run,
        sleep=time.sleep,
        start_timeout_seconds: int = 60,
    ):
        if not re.fullmatch(r"[A-Za-z0-9._:-]+", serial or ""):
            raise ValueError("ADB serial is invalid")
        if not re.fullmatch(r"[A-Za-z0-9_.]+", package_name or ""):
            raise ValueError("Android package name is invalid")
        if not isinstance(start_timeout_seconds, int) or start_timeout_seconds <= 0:
            raise ValueError("lifecycle start timeout must be positive")
        self._serial = serial
        self._package_name = package_name
        self._transport = transport
        self._command_runner = command_runner
        self._sleep = sleep
        self._start_timeout_seconds = start_timeout_seconds

    def restart_and_verify(self) -> dict:
        pid_before = self._pid()
        self._adb_shell("am", "force-stop", self._package_name)
        if self._pid():
            raise ProtocolExecutionError("W4 process did not disappear after force-stop")
        self._adb_shell("monkey", "-p", self._package_name, "1")
        deadline = time.monotonic() + self._start_timeout_seconds
        last_health_status: int | None = None
        while time.monotonic() < deadline:
            pid_after = self._pid()
            if pid_after:
                health = self._transport.request("GET", "/health", timeout_ms=5_000)
                last_health_status = health.status
                if health.status == 200:
                    return {
                        "protocol": "PROCESS_COLD_V1",
                        "forceStopped": True,
                        "processAbsentAfterStop": True,
                        "pidBefore": pid_before,
                        "processPid": pid_after,
                        "healthStatus": health.status,
                    }
            self._sleep(1)
        raise ProtocolExecutionError(
            f"W4 process restart did not reach health=200 within {self._start_timeout_seconds}s"
            f" (lastHealthStatus={last_health_status})",
        )

    def _pid(self) -> str:
        result = self._command_runner(
            ["adb", "-s", self._serial, "shell", "pidof", self._package_name],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        pid = result.stdout.strip()
        if result.returncode == 1 and not pid:
            return ""
        if result.returncode == 0 and pid:
            return pid
        raise ProtocolExecutionError(
            "W4 lifecycle pidof returned an ambiguous process state "
            f"(exit={result.returncode}, hasOutput={bool(pid)})"
        )

    def _adb_shell(self, *command: str) -> str:
        result = self._command_runner(
            ["adb", "-s", self._serial, "shell", *command],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        if result.returncode != 0:
            raise ProtocolExecutionError(f"W4 lifecycle adb command failed: {' '.join(command)}")
        return result.stdout


def _required_match(value: str, pattern: str, label: str) -> str:
    match = re.search(pattern, value)
    if match is None:
        raise ValueError(f"ADB did not expose {label}")
    return match.group(1)


def _load_json_object(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is unavailable: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _write_new_json(path: Path, value: dict, label: str) -> None:
    """Prevents a capture command from silently replacing prior evidence."""
    if path.exists():
        raise ValueError(f"{label} output already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _captured_quality_result(value: object, known_scenarios: dict[str, dict]) -> dict:
    """Validates raw quality measurements and derives a fail-closed outcome."""
    if not isinstance(value, dict):
        raise ValueError("quality capture result must be an object")
    common = {"scenarioSha256", "outputSha256", "mode", "calculationVersion", "rawMeasurements"}
    scenario_sha256 = value.get("scenarioSha256")
    scenario = known_scenarios.get(scenario_sha256)
    if scenario is None or not _is_sha256(value.get("outputSha256")):
        raise ValueError("quality capture result does not bind a known scenario and output SHA-256")
    mode = scenario["measurement"]["qualityMode"]
    if value.get("mode") != mode or not isinstance(value.get("calculationVersion"), str) or not value["calculationVersion"].strip():
        raise ValueError("quality capture result mode or calculation version does not match the immutable scenario")
    raw = value.get("rawMeasurements")
    if not isinstance(raw, dict) or not raw:
        raise ValueError("quality capture result requires raw measurements")
    raw_json = json.dumps(raw, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    raw_sha256 = hashlib.sha256(raw_json.encode()).hexdigest()
    if mode == "BIT_EXACT":
        expected = common | {"referenceOutputSha256"}
        if set(value) != expected or not _is_sha256(value["referenceOutputSha256"]):
            raise ValueError("BIT_EXACT quality capture result must exactly match schema v1")
        reference = {
            "mode": mode,
            "calculationVersion": value["calculationVersion"],
            "referenceOutputSha256": value["referenceOutputSha256"],
        }
        passed = value["referenceOutputSha256"] == value["outputSha256"]
        result = {"referenceOutputSha256": value["referenceOutputSha256"]}
    elif mode == "GOLDEN_SET":
        expected = common | {
            "goldenSetSha256", "promptCount", "seedsPerPrompt", "ssim", "lpips",
            "clipScoreRegressionPct", "blindReviewPassed",
        }
        if set(value) != expected or not _is_sha256(value["goldenSetSha256"]):
            raise ValueError("GOLDEN_SET quality capture result must exactly match schema v1")
        numeric = ("ssim", "lpips", "clipScoreRegressionPct")
        if (
            not isinstance(value["promptCount"], int) or value["promptCount"] < 30
            or not isinstance(value["seedsPerPrompt"], int) or value["seedsPerPrompt"] < 4
            or any(not isinstance(value[key], (int, float)) or not float("-inf") < float(value[key]) < float("inf") for key in numeric)
        ):
            raise ValueError("GOLDEN_SET quality capture measurements are incomplete or non-finite")
        invalid_image = any(raw.get(key) not in (0, False) for key in ("nanCount", "infCount", "corruptImageCount", "blackImageCount"))
        layout_valid = raw.get("colorLayoutValid") is True
        reference = {
            "mode": mode,
            "calculationVersion": value["calculationVersion"],
            "goldenSetSha256": value["goldenSetSha256"],
        }
        passed = (
            value["ssim"] >= 0.98 and value["lpips"] <= 0.05 and value["clipScoreRegressionPct"] <= 1
            and value["blindReviewPassed"] is True and not invalid_image and layout_valid
        )
        result = {
            "goldenSetSha256": value["goldenSetSha256"], "promptCount": value["promptCount"],
            "seedsPerPrompt": value["seedsPerPrompt"], "ssim": value["ssim"], "lpips": value["lpips"],
            "clipScoreRegressionPct": value["clipScoreRegressionPct"], "blindReviewPassed": value["blindReviewPassed"],
        }
    else:
        raise ValueError("unsupported quality mode")
    return {
        "scenarioSha256": scenario_sha256,
        "outputSha256": value["outputSha256"],
        "mode": mode,
        "calculationVersion": value["calculationVersion"],
        "qualityReferenceSha256": hashlib.sha256(
            json.dumps(reference, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(),
        ).hexdigest(),
        "rawMeasurements": raw,
        "rawMeasurementsSha256": raw_sha256,
        "passed": passed,
    } | result


def _validate_quality_result_for_acceptance(output_sha256: object, result: object) -> None:
    """Requires run-time quality input to be an output- and scenario-bound capture."""
    required = {
        "scenarioSha256", "outputSha256", "mode", "calculationVersion", "qualityReferenceSha256",
        "rawMeasurements", "rawMeasurementsSha256", "passed",
    }
    if (
        not _is_sha256(output_sha256) or not isinstance(result, dict) or not required.issubset(result)
        or result["outputSha256"] != output_sha256 or not _is_sha256(result["scenarioSha256"])
        or result["mode"] not in {"BIT_EXACT", "GOLDEN_SET"}
        or not isinstance(result["calculationVersion"], str) or not result["calculationVersion"].strip()
        or not _is_sha256(result["qualityReferenceSha256"]) or not _is_sha256(result["rawMeasurementsSha256"])
        or not isinstance(result["rawMeasurements"], dict) or not isinstance(result["passed"], bool)
    ):
        raise ValueError("quality results must be generated, scenario-bound quality-v1 entries")
    raw_json = json.dumps(result["rawMeasurements"], ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    if hashlib.sha256(raw_json.encode()).hexdigest() != result["rawMeasurementsSha256"]:
        raise ValueError("quality raw measurements SHA-256 does not match the quality-v1 entry")


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(char in "0123456789abcdef" for char in value)


def _sample_from_execution(
    run_id: str,
    sequence: int,
    scenario: dict,
    execution,
    runtime_fingerprint: str,
    preset_snapshot_sha256: str,
    acceptance_evidence: AcceptanceEvidence | None,
    resource_metrics: dict,
    *,
    is_warmup: bool = False,
    lifecycle_evidence: dict | None = None,
) -> Sample:
    protocol = execution.protocol
    group_key = GroupKey(
        scenario_sha256=scenario["sha256"],
        preset_snapshot_sha256=preset_snapshot_sha256,
        runtime_fingerprint=runtime_fingerprint,
        cold_state=ColdState(scenario["measurement"]["coldState"]),
        harness_version="1",
    )
    if acceptance_evidence is None:
        # Exploration still records an immutable response and telemetry, but
        # cannot become a binding qualification without B0/quality evidence.
        baseline = None
        response_evidence = _response_evidence(scenario, protocol, scenario["model"]["assetSha256"])
        quality_evidence = {"mode": "UNQUALIFIED", "passed": False}
    else:
        baseline = acceptance_evidence.baseline_for(group_key)
        if baseline["absoluteTimeoutMs"] != scenario["timeoutMs"]:
            raise ValueError("B0 absolute timeout does not match the immutable scenario")
        if _is_sha256(scenario["model"]["assetSha256"]) and baseline["modelAssetSha256"] != scenario["model"]["assetSha256"]:
            raise ValueError("B0 model asset SHA-256 does not match the immutable scenario")
        response_evidence = _response_evidence(scenario, protocol, baseline["modelAssetSha256"])
        quality_evidence = _quality_evidence(
            scenario,
            response_evidence,
            baseline["qualityReferenceSha256"],
            acceptance_evidence.quality_results,
        )
    if execution.operation.startswith("W4.") and not _is_verified_process_cold_lifecycle(lifecycle_evidence):
        raise ValueError("W4 sample lacks verified PROCESS_COLD lifecycle evidence")
    lifecycle_metrics = {"processColdLifecycle": lifecycle_evidence} if lifecycle_evidence else {}
    return Sample(
        run_id=run_id,
        sequence=sequence,
        group_key=group_key,
        outcome=Outcome.SUCCESS if protocol.status == 200 else Outcome.PROTOCOL_MISMATCH,
        end_to_end_ms=protocol.elapsed_ms,
        is_warmup=is_warmup,
        quality_passed=quality_evidence["passed"],
        baseline_frozen=baseline is not None and protocol.elapsed_ms <= baseline["absoluteTimeoutMs"],
        thermal_stable=False,
        response_evidence=response_evidence,
        expected_model_asset_sha256=baseline["modelAssetSha256"] if baseline else scenario["model"]["assetSha256"],
        quality_evidence=quality_evidence,
        stage_metrics={
            # W5 has real W1/W2 request variants but remains a distinct
            # sustained measurement window in reports.  Never relabel it as
            # an ordinary baseline sample merely because the request payload
            # is shared with W1 or W2.
            "scenarioId": getattr(execution, "measurement_scenario_id", None) or scenario["scenarioId"],
            "variantId": getattr(execution, "variant_id", None),
            "endpoint": protocol.endpoint,
            "outputBytes": float(protocol.output_bytes),
            "endToEndMs": protocol.elapsed_ms,
        } | (
            protocol.evidence.get("vendorDiagnostics")
            if isinstance(protocol.evidence.get("vendorDiagnostics"), dict)
            else {}
        ) | (
            {"w5ThroughputPerSecond": getattr(execution, "sustained_throughput_per_second")}
            if getattr(execution, "sustained_throughput_per_second", None) is not None
            else {}
        ) | (
            {"w5WindowElapsedMs": getattr(execution, "sustained_window_elapsed_ms")}
            if getattr(execution, "sustained_window_elapsed_ms", None) is not None
            else {}
        ) | (
            {"w5WindowSampleCount": getattr(execution, "sustained_window_sample_count")}
            if getattr(execution, "sustained_window_sample_count", None) is not None
            else {}
        ),
        resource_metrics={"operation": execution.operation} | lifecycle_metrics | resource_metrics,
    )


def _update_group_peak(group_metrics: dict, record: object) -> None:
    """Accumulates only observed ADB counters; missing samples never become zero."""
    if not isinstance(record, dict) or "collectionError" in record:
        return
    for source_key, peak_key in (
        ("processPssKb", "peakPssKb"),
        ("processRssKb", "peakRssKb"),
        ("swapPssKb", "peakSwapPssKb"),
    ):
        value = record.get(source_key)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            previous = group_metrics.get(peak_key)
            group_metrics[peak_key] = value if previous is None else max(previous, value)


def _collect_group_releases(
    sampler: "AdbResourceSampler",
    group_resources: dict[GroupKey, dict],
    sequence: int,
    runtime_unloader,
) -> dict[GroupKey, dict]:
    """Collects a distinct explicit unload/release record for every GroupKey.

    A release is evidence for one statistical group, never a run-wide fact.  We
    invokes the expected-model runtime unload once per group.  A missing MCP
    lifecycle credential or a model mismatch produces a per-group error rather
    than an ADB force-stop substitute.
    """
    releases: dict[GroupKey, dict] = {}
    for index, group_key in enumerate(sorted(group_resources, key=_group_artifact_id)):
        expected_model_id = group_resources[group_key].get("expectedModelId")
        if not isinstance(expected_model_id, str) or not expected_model_id:
            release = {
                "sequence": sequence + index,
                "capturePhase": "POST_UNLOAD_RELEASE",
                "collectionError": "resource group has no expected model identity",
            }
        else:
            release = sampler.unload_and_collect(sequence + index, expected_model_id, runtime_unloader)
        # This is part of the raw telemetry record, not just an in-memory map:
        # group artifacts can therefore prove which force-stop produced their
        # release observation.
        release["resourceGroupId"] = _group_artifact_id(group_key)
        releases[group_key] = release
    return releases


def _attach_group_resource_lifecycle(
    samples: list[Sample],
    group_resources: dict[GroupKey, dict],
    releases: dict[GroupKey, dict],
) -> list[Sample]:
    """Publishes one request-before/peak/unload lifecycle for every GroupKey.

    Each GroupKey must own a separately collected explicit unload/release
    record.  Missing or invalid records deliberately leave the final gate
    without release metrics, so TARGET/FINAL validation fails closed.
    """
    finalized: list[Sample] = []
    for sample in samples:
        group = group_resources.get(sample.group_key, {})
        baseline = group.get("baseline")
        release = releases.get(sample.group_key)
        release_is_valid = (
            isinstance(release, dict)
            and release.get("capturePhase") == "POST_UNLOAD_RELEASE"
            and release.get("unloadAction") == "MCP_RUNTIME_UNLOAD"
            and release.get("runtimeUnloaded") is True
            and release.get("serviceAvailableAfterUnload") is True
            and release.get("processAliveAfterUnload") is True
            and release.get("resourceGroupId") == _group_artifact_id(sample.group_key)
            and "collectionError" not in release
        )
        metrics = dict(sample.resource_metrics or {})
        metrics.update({
            "groupPeakPssKb": group.get("peakPssKb"),
            "groupPeakRssKb": group.get("peakRssKb"),
            "groupPeakSwapPssKb": group.get("peakSwapPssKb"),
            "resourceLifecycle": "GROUP_BASELINE_PEAK_UNLOAD_V1",
        })
        if isinstance(baseline, dict) and "collectionError" not in baseline:
            metrics.update({
                "baselinePssKb": baseline.get("processPssKb"),
                "baselineRssKb": baseline.get("processRssKb"),
                "baselineSwapPssKb": baseline.get("swapPssKb"),
                "baselineSourceSha256": baseline.get("sourceSha256"),
            })
        if release_is_valid:
            metrics.update({
                "releasePssKb": release.get("processPssKb"),
                "releaseRssKb": release.get("processRssKb"),
                "releaseSwapPssKb": release.get("swapPssKb"),
                "releaseSourceSha256": release.get("sourceSha256"),
                "releaseTelemetrySequence": release.get("sequence"),
                "releaseProcessAlive": True,
            })
        baseline_pss = metrics.get("baselinePssKb")
        baseline_rss = metrics.get("baselineRssKb")
        release_pss = metrics.get("releasePssKb")
        release_rss = metrics.get("releaseRssKb")
        if all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in (baseline_pss, baseline_rss, release_pss, release_rss)):
            metrics["memoryLeakDetected"] = release_pss > baseline_pss or release_rss > baseline_rss
        finalized.append(replace(sample, resource_metrics=metrics))
    return finalized


def _group_key(scenario: dict, runtime_fingerprint: str, preset_snapshot_sha256: str) -> GroupKey:
    return GroupKey(
        scenario_sha256=scenario["sha256"],
        preset_snapshot_sha256=preset_snapshot_sha256,
        runtime_fingerprint=runtime_fingerprint,
        cold_state=ColdState(scenario["measurement"]["coldState"]),
        harness_version="1",
    )


def _w4_measurement_scenario(scenario: dict, operation: str) -> dict:
    """Preserves W4's actual baseline identity and per-step cold/warm state.

    W4 is a single A→B→A operation: the first A follows the proved process
    restart while B and the final A execute in that same warmed process.
    The immutable baseline SHA remains the GroupKey identity in all cases.
    """
    cold_state = (
        ColdState.PROCESS_COLD.value
        if operation == "W4.1.W1"
        else ColdState.CONTEXT_WARM.value
    )
    return scenario | {
        "measurement": scenario["measurement"] | {"coldState": cold_state},
    }


def _w5_measurement_scenarios(scenarios: list[dict], scenario: dict) -> tuple[dict, ...]:
    """Publishes W5 variant identities without rewriting the W5 scenario.

    A sustained W5 request reuses a W1/W2 payload, but its baseline, quality
    and statistics are about a distinct W5+variant measurement.  The derived
    immutable SHA binds the published W5 contract, variant and exact request
    scenario so neither variant can be absorbed by an ordinary W1/W2 group.
    """
    if scenario["scenarioId"] != "W5" or scenario["workflow"] != "SUSTAINED":
        raise ValueError("W5 measurement identity requires the published sustained scenario")
    variants = scenario["fixtures"].get("variants")
    if variants != ["W1", "W2"]:
        raise ValueError("W5 sustained variants must remain immutable W1/W2")
    by_id = {item["scenarioId"]: item for item in scenarios}
    return tuple(_w5_measurement_scenario(scenario, by_id[variant], variant) for variant in variants)


def _w5_measurement_scenario(sustained: dict, request_scenario: dict, variant_id: str) -> dict:
    if variant_id not in {"W1", "W2"} or request_scenario["scenarioId"] != variant_id:
        raise ValueError("W5 measurement variant does not match its published request scenario")
    identity = {
        "measurementScenarioSha256": sustained["sha256"],
        "variantId": variant_id,
        "requestScenarioSha256": request_scenario["sha256"],
    }
    measurement_sha256 = hashlib.sha256(
        json.dumps(identity, sort_keys=True, separators=(",", ":")).encode(),
    ).hexdigest()
    return request_scenario | {
        "scenarioId": f"W5:{variant_id}",
        "sha256": measurement_sha256,
        "measurement": sustained["measurement"],
        "measurementIdentity": identity,
    }


def _acceptance_measurement_scenarios(scenarios: list[dict]) -> dict[str, dict]:
    """Returns capture identities, including W5's independently frozen variants."""
    values = {item["scenarioId"]: item for item in scenarios if item["scenarioId"] != "W5"}
    sustained = next(item for item in scenarios if item["scenarioId"] == "W5")
    values.update({item["scenarioId"]: item for item in _w5_measurement_scenarios(scenarios, sustained)})
    return values


def _measurement_scenario(scenarios: list[dict], request_scenario: dict, execution) -> dict:
    if getattr(execution, "measurement_scenario_id", None) == "W5":
        sustained = next(item for item in scenarios if item["scenarioId"] == "W5")
        return _w5_measurement_scenario(sustained, request_scenario, execution.variant_id)
    if execution.operation.startswith("W4."):
        return _w4_measurement_scenario(request_scenario, execution.operation)
    return request_scenario


def _requires_warmup(scenario: dict) -> bool:
    return ColdState(scenario["measurement"]["coldState"]) in {ColdState.OS_CACHE_WARM, ColdState.CONTEXT_WARM}


def _single_execution(executor: DeviceScenarioExecutor, scenario_id: str, after_execution=None, before_execution=None):
    executions = executor.execute(scenario_id, after_execution, before_execution)
    if len(executions) != 1 or executions[0].scenario_id != scenario_id:
        raise ProtocolExecutionError(f"warmup must execute exactly one published {scenario_id} request")
    return executions[0]


def _is_verified_process_cold_lifecycle(evidence: dict | None) -> bool:
    return bool(
        isinstance(evidence, dict)
        and evidence.get("protocol") == "PROCESS_COLD_V1"
        and evidence.get("forceStopped") is True
        and evidence.get("processAbsentAfterStop") is True
        and isinstance(evidence.get("processPid"), str)
        and bool(evidence["processPid"])
        and evidence.get("healthStatus") == 200
    )


def _response_evidence(scenario: dict, protocol, model_asset_sha256: str) -> dict:
    evidence = protocol.evidence
    if (
        evidence.get("downloaded") is not True
        or evidence.get("downloadContentType") != "image/png"
        or evidence.get("downloadMagic") != "PNG"
        or not _is_sha256(evidence.get("downloadSha256"))
        or not isinstance(evidence.get("downloadWidth"), int) or evidence["downloadWidth"] <= 0
        or not isinstance(evidence.get("downloadHeight"), int) or evidence["downloadHeight"] <= 0
    ):
        raise ValueError("device response is incomplete: expected downloaded PNG integrity evidence")
    request = scenario["request"]
    if "width" in request and (evidence["downloadWidth"], evidence["downloadHeight"]) != (request["width"], request["height"]):
        raise ValueError("device output dimensions do not match the frozen scenario")
    result = {
        "workflow": scenario["workflow"],
        "model": scenario["model"]["selector"],
        "modelAssetSha256": model_asset_sha256,
        "outputSha256": evidence["downloadSha256"],
        "outputBytes": evidence["downloadBytes"],
        "contentType": evidence["downloadContentType"],
        "width": evidence["downloadWidth"],
        "height": evidence["downloadHeight"],
    }
    if "seed" in scenario["fixtures"]:
        result["seed"] = scenario["fixtures"]["seed"]
    if "imageSha256" in scenario["fixtures"]:
        result["inputSha256"] = scenario["fixtures"]["imageSha256"]
    return result


def _quality_evidence(scenario: dict, response: dict, reference_sha256: str, results: dict[str, dict]) -> dict:
    output_sha256 = response["outputSha256"]
    source = results.get(output_sha256)
    if not isinstance(source, dict):
        raise ValueError("quality evidence is missing for the downloaded output SHA-256")
    mode = scenario["measurement"]["qualityMode"]
    if (
        source.get("scenarioSha256") != scenario["sha256"]
        or source.get("outputSha256") != output_sha256
        or source.get("mode") != mode
        or source.get("qualityReferenceSha256") != reference_sha256
    ):
        raise ValueError("quality evidence does not match the frozen scenario/B0 reference")
    if mode == "BIT_EXACT":
        passed = source.get("referenceOutputSha256") == output_sha256
    elif mode == "GOLDEN_SET":
        passed = (
            _is_sha256(source.get("goldenSetSha256"))
            and isinstance(source.get("promptCount"), int) and source["promptCount"] >= 30
            and isinstance(source.get("seedsPerPrompt"), int) and source["seedsPerPrompt"] >= 4
            and isinstance(source.get("ssim"), (int, float)) and source["ssim"] >= 0.98
            and isinstance(source.get("lpips"), (int, float)) and source["lpips"] <= 0.05
            and isinstance(source.get("clipScoreRegressionPct"), (int, float)) and source["clipScoreRegressionPct"] <= 1
            and source.get("blindReviewPassed") is True
        )
    else:
        raise ValueError("unsupported quality mode")
    return source | {"mode": mode, "passed": passed}


def finalize_thermal_stability(samples: list[Sample], *, elapsed_ms: float, required_minutes: int) -> list[Sample]:
    """Marks samples thermally stable only after a completed 30/60 minute run."""
    if required_minutes not in (30, 60):
        raise ValueError("thermal duration must be exactly 30 or 60 minutes")
    duration_passed = elapsed_ms >= required_minutes * 60_000
    by_group: dict[GroupKey, list[Sample]] = {}
    for sample in samples:
        by_group.setdefault(sample.group_key, []).append(sample)
    finalized: dict[int, tuple[bool, float]] = {}
    for group_samples in by_group.values():
        successes = [item for item in group_samples if item.outcome == Outcome.SUCCESS and not item.is_warmup and item.end_to_end_ms]
        quartile = max(1, len(successes) // 4)
        if len(successes) < 4:
            drop_pct = float("inf")
        else:
            first = sum(1000 / item.end_to_end_ms for item in successes[:quartile]) / quartile
            last = sum(1000 / item.end_to_end_ms for item in successes[-quartile:]) / quartile
            drop_pct = (first - last) / first * 100 if first else float("inf")
        for item in group_samples:
            metrics = item.resource_metrics or {}
            metrics_complete = (
                "collectionError" not in metrics
                and isinstance(metrics.get("batteryTemperatureC"), (int, float))
                and isinstance(metrics.get("thermalStatus"), int)
                and isinstance(metrics.get("processPssKb"), int)
                and isinstance(metrics.get("swapPssKb"), int)
                and _is_sha256(metrics.get("sourceSha256"))
            )
            stable = duration_passed and metrics_complete and metrics["thermalStatus"] < 3 and drop_pct <= 10
            finalized[item.sequence] = (stable, drop_pct)
    return [
        replace(
            sample,
            thermal_stable=finalized[sample.sequence][0],
            resource_metrics=(sample.resource_metrics or {}) | {
                "observedDurationMinutes": elapsed_ms / 60_000,
                "lastQuarterThroughputDropPct": finalized[sample.sequence][1],
            },
        )
        for sample in samples
    ]


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
    acceptance_evidence: dict[str, str] | None = None,
    validation_level: ValidationLevel = ValidationLevel.FINAL_VALIDATED,
    telemetry_records: list[dict] | None = None,
    device_identity: dict | None = None,
    observed_probes: dict[str, RuntimeProbe] | None = None,
) -> None:
    group_artifacts = _write_group_artifacts(
        output_dir, probe, samples, run_id, telemetry_records, validation_level, observed_probes,
    )
    write_manifest(
        output_dir, run_id, scenario_dir, probe,
        preset_snapshot_sha256=preset_snapshot_sha256, run_context=run_context,
        acceptance_evidence=acceptance_evidence, validation_level=validation_level,
        device_identity=device_identity,
        group_artifacts=group_artifacts,
        observed_probes=observed_probes,
    )
    (output_dir / "raw-samples.jsonl").write_text(
        "".join(json.dumps(sample.to_json(), ensure_ascii=False, sort_keys=True) + "\n" for sample in samples),
        encoding="utf-8",
    )
    root_result = result | {"groupReports": group_artifacts}
    (output_dir / "report.json").write_text(
        json.dumps(root_result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if telemetry_records is not None:
        (output_dir / "telemetry.jsonl").write_text(
            "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in telemetry_records),
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
    acceptance_evidence: dict[str, str] | None = None,
    validation_level: ValidationLevel = ValidationLevel.FINAL_VALIDATED,
    device_identity: dict | None = None,
    group_artifacts: list[dict] | None = None,
    observed_probes: dict[str, RuntimeProbe] | None = None,
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
        "manifestVersion": 3,
        "runId": run_id,
        "validationLevel": validation_level.value,
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
        "preflightRuntimeProbe": probe_as_dict(probe),
        "observedRuntimeProbes": [
            {
                "runtimeFingerprint": fingerprint,
                "runtimeProbe": probe_as_dict(observed_probe),
            }
            for fingerprint, observed_probe in sorted((observed_probes or {}).items())
        ],
        "acceptanceEvidence": acceptance_evidence or {},
        "adbTarget": device_identity,
        "groupArtifacts": group_artifacts or [],
        "replayable": not missing_replay_facts,
        "missingReplayFacts": missing_replay_facts,
    }
    (output_dir / "run-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def grouped_report(
    probe: RuntimeProbe,
    samples: list[Sample],
    run_id: str,
    *,
    validation_level: ValidationLevel = ValidationLevel.FINAL_VALIDATED,
    observed_probes: dict[str, RuntimeProbe] | None = None,
) -> dict:
    """Summarizes a run without ever evaluating different GroupKeys together."""
    groups = _samples_by_group(samples)
    reports = [
        _group_report(_probe_for_group(probe, group_key, observed_probes), group_samples, run_id, group_key, validation_level)
        for group_key, group_samples in groups
    ]
    expected = (
        "EXPLORATORY_COMPLETED"
        if validation_level == ValidationLevel.EXPLORATORY
        else "TARGET_VALIDATED"
        if validation_level == ValidationLevel.TARGET_VALIDATED
        else "ACCEPTED_FOR_ONEPLUS13"
    )
    accepted = bool(reports) and all(item["conclusion"] == expected for item in reports)
    return {
        "runId": run_id,
        "validationLevel": validation_level.value,
        "conclusion": expected if accepted else "NOT_ACCEPTED_FOR_ONEPLUS13",
        "reasons": [] if accepted else ["GROUP_REPORT_NOT_ACCEPTED"],
        "sampleCount": len(samples),
        "groupReports": reports,
    }


def _write_group_artifacts(
    output_dir: Path,
    probe: RuntimeProbe,
    samples: list[Sample],
    run_id: str,
    telemetry_records: list[dict] | None,
    validation_level: ValidationLevel,
    observed_probes: dict[str, RuntimeProbe] | None,
) -> list[dict]:
    artifacts: list[dict] = []
    for group_key, group_samples in _samples_by_group(samples):
        group_id = _group_artifact_id(group_key)
        relative_dir = Path("groups") / group_id
        group_dir = output_dir / relative_dir
        group_dir.mkdir(parents=True, exist_ok=True)
        group_probe = _probe_for_group(probe, group_key, observed_probes)
        group_report = _group_report(group_probe, group_samples, run_id, group_key, validation_level)
        (group_dir / "raw-samples.jsonl").write_text(
            "".join(json.dumps(sample.to_json(), ensure_ascii=False, sort_keys=True) + "\n" for sample in group_samples),
            encoding="utf-8",
        )
        (group_dir / "report.json").write_text(
            json.dumps(group_report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        if telemetry_records is not None:
            sequences = {sample.sequence for sample in group_samples}
            sequences.update(
                sample.resource_metrics.get("releaseTelemetrySequence")
                for sample in group_samples
                if isinstance(sample.resource_metrics, dict)
                and isinstance(sample.resource_metrics.get("releaseTelemetrySequence"), int)
            )
            (group_dir / "telemetry.jsonl").write_text(
                "".join(
                    json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n"
                    for item in telemetry_records
                    if item.get("sequence") in sequences
                ),
                encoding="utf-8",
            )
        artifacts.append({
            "groupId": group_id,
            "groupKey": _group_key_json(group_key),
            "artifactDirectory": relative_dir.as_posix(),
            "sampleCount": len(group_samples),
            "conclusion": group_report["conclusion"],
            "runtimeProbe": probe_as_dict(group_probe),
        })
    return artifacts


def _samples_by_group(samples: list[Sample]) -> list[tuple[GroupKey, list[Sample]]]:
    groups: dict[GroupKey, list[Sample]] = {}
    for sample in samples:
        groups.setdefault(sample.group_key, []).append(sample)
    return sorted(groups.items(), key=lambda item: _group_artifact_id(item[0]))


def _group_report(
    probe: RuntimeProbe,
    samples: list[Sample],
    run_id: str,
    group_key: GroupKey,
    validation_level: ValidationLevel,
) -> dict:
    group_id = _group_artifact_id(group_key)
    return report(
        probe,
        samples,
        f"{run_id}:{group_id}",
        validation_level=validation_level,
    ) | {"groupKey": _group_key_json(group_key)}


def _probe_for_group(
    preflight_probe: RuntimeProbe,
    group_key: GroupKey,
    observed_probes: dict[str, RuntimeProbe] | None,
) -> RuntimeProbe:
    """Uses the observed probe for new runs while preserving v2 artifact readers."""
    if observed_probes is None:
        return preflight_probe
    return observed_probes.get(group_key.runtime_fingerprint, RuntimeProbe(RuntimeProbeStatus.UNAVAILABLE))


def _group_key_json(group_key: GroupKey) -> dict:
    return {
        "scenarioSha256": group_key.scenario_sha256,
        "presetSnapshotSha256": group_key.preset_snapshot_sha256,
        "runtimeFingerprint": group_key.runtime_fingerprint,
        "coldState": group_key.cold_state.value,
        "harnessVersion": group_key.harness_version,
    }


def _group_artifact_id(group_key: GroupKey) -> str:
    encoded = json.dumps(_group_key_json(group_key), sort_keys=True, separators=(",", ":")).encode("utf-8")
    return f"group-{hashlib.sha256(encoded).hexdigest()[:16]}"


def _write_qualification_candidate(
    output_dir: Path,
    run_id: str,
    scenarios: list[dict],
    run_context: dict,
    preset_snapshot_sha256: str,
    validation_level: ValidationLevel,
) -> None:
    """Produces auditable candidates; Android remains the sole qualification writer.

    The artifact is intentionally not an import side effect.  A later Android
    consumer must validate its manifest digest against its current preset,
    model, runtime and build before it writes Room qualification state.
    """
    manifest_path = output_dir / "run-manifest.json"
    scenario_set_sha256 = hashlib.sha256(
        json.dumps(
            {item["scenarioId"]: item["sha256"] for item in scenarios},
            sort_keys=True,
            separators=(",", ":"),
        ).encode(),
    ).hexdigest()
    model_by_scenario = {item["sha256"]: item["model"] for item in scenarios}
    candidates = []
    for group in json.loads((output_dir / "report.json").read_text(encoding="utf-8"))["groupReports"]:
        group_key = group["groupKey"]
        model = model_by_scenario[group_key["scenarioSha256"]]
        candidates.append({
            "runId": run_id,
            "qualificationLevel": validation_level.value,
            "presetSnapshotSha256": preset_snapshot_sha256,
            "modelId": model["selector"],
            "modelAssetSha256": model["assetSha256"],
            "scenarioSetSha256": scenario_set_sha256,
            "runtimeFingerprint": group_key["runtimeFingerprint"],
            "appBuild": run_context["appBuild"],
            "evidenceManifestSha256": _file_sha256(manifest_path),
            "groupKey": group_key,
        })
    _write_new_json(
        output_dir / "qualification-candidates.json",
        {"schemaVersion": 1, "candidates": candidates},
        "qualification candidates",
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
    capture_quality = commands.add_parser("capture-quality")
    capture_quality.add_argument("--scenario-dir", required=True)
    capture_quality.add_argument("--quality-input-file", required=True, help="raw BIT_EXACT or GOLDEN_SET measurement input")
    capture_quality.add_argument("--output-file", required=True, help="new quality-v1.json; existing evidence is never replaced")
    capture_quality.set_defaults(func=command_capture_quality)
    capture_baseline = commands.add_parser("capture-baseline")
    capture_baseline.add_argument("--scenario-dir", required=True)
    capture_baseline.add_argument("--base-url", required=True)
    capture_baseline.add_argument("--adb-serial", required=True)
    capture_baseline.add_argument("--app-package", required=True)
    capture_baseline.add_argument("--preset-snapshot-sha256", required=True)
    capture_baseline.add_argument("--quality-evidence-file", required=True)
    capture_baseline.add_argument("--output-file", required=True, help="new baseline-v1.json; existing evidence is never replaced")
    capture_baseline.add_argument("--scenario-ids", help="comma-separated W1-W6; defaults to all six")
    capture_baseline.add_argument("--bearer-token", help=argparse.SUPPRESS)
    capture_baseline_secret_source = capture_baseline.add_mutually_exclusive_group(required=True)
    capture_baseline_secret_source.add_argument("--bearer-token-file")
    capture_baseline_secret_source.add_argument("--bearer-token-env")
    capture_baseline.set_defaults(func=command_capture_baseline)
    run = commands.add_parser("run")
    run.add_argument("--scenario-dir", required=True)
    run.add_argument("--runtime-probe-file", required=True)
    run.add_argument("--base-url", required=True)
    run.add_argument("--mcp-base-url", help="MCP endpoint for expected-model runtime release")
    run.add_argument("--fixture-dir", required=True)
    run.add_argument("--output-dir", required=True)
    run.add_argument("--preset-snapshot-sha256", required=True)
    run.add_argument("--run-context-file", required=True)
    run.add_argument("--validation-level", choices=[level.value for level in ValidationLevel], default=ValidationLevel.FINAL_VALIDATED.value)
    run.add_argument("--baseline-file", help="frozen B0 contract for the exact runtime group; required above exploration")
    run.add_argument("--quality-evidence-file", help="quality results indexed by downloaded output SHA-256; required above exploration")
    run.add_argument("--adb-serial", required=True, help="target-device serial used for resource sampling")
    run.add_argument("--app-package", required=True, help="application package sampled through adb")
    run.add_argument("--thermal-duration-minutes", type=int, choices=(30, 60), help="required only for FINAL_VALIDATED")
    run.add_argument("--bearer-token", help=argparse.SUPPRESS)
    secret_source = run.add_mutually_exclusive_group(required=True)
    secret_source.add_argument("--bearer-token-file")
    secret_source.add_argument("--bearer-token-env")
    mcp_secret_source = run.add_mutually_exclusive_group()
    mcp_secret_source.add_argument("--mcp-bearer-token-file")
    mcp_secret_source.add_argument("--mcp-bearer-token-env")
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
