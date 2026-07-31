"""可重算的一加 13 性能验收数据模型；不负责产生设备性能结论。"""

from __future__ import annotations

import hashlib
import math
import random
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any, Iterable


class RuntimeProbeStatus(str, Enum):
    VERIFIED = "VERIFIED"
    REJECTED = "REJECTED"
    UNAVAILABLE = "UNAVAILABLE"


class ColdState(str, Enum):
    DEVICE_COLD = "DEVICE_COLD"
    PROCESS_COLD = "PROCESS_COLD"
    OS_CACHE_WARM = "OS_CACHE_WARM"
    CONTEXT_WARM = "CONTEXT_WARM"


class Outcome(str, Enum):
    SUCCESS = "SUCCESS"
    TIMEOUT = "TIMEOUT"
    HANG = "HANG"
    CRASH = "CRASH"
    PERMANENT_LOADING = "PERMANENT_LOADING"
    SECOND_REQUEST_FAILED = "SECOND_REQUEST_FAILED"
    PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    ASSET_MISMATCH = "ASSET_MISMATCH"
    SEED_OR_DIMENSION_MISMATCH = "SEED_OR_DIMENSION_MISMATCH"
    QUALITY_FAILED = "QUALITY_FAILED"
    CANCELLED = "CANCELLED"


class ValidationLevel(str, Enum):
    """Evidence threshold for a target-device performance run.

    ``EXPLORATORY`` records candidate facts only.  It must never be confused
    with an admission decision for an automatic preset binding.
    """

    EXPLORATORY = "EXPLORATORY"
    TARGET_VALIDATED = "TARGET_VALIDATED"
    FINAL_VALIDATED = "FINAL_VALIDATED"


@dataclass(frozen=True)
class RuntimeProbe:
    status: RuntimeProbeStatus
    device_model: str | None = None
    soc: str | None = None
    abi: str | None = None
    qairt_version: str | None = None
    htp_target: str | None = None
    context_fingerprint: str | None = None
    loaded_library_fingerprints: dict[str, str] | None = None
    native_ready: bool | None = None
    rejection_reasons: tuple[str, ...] = ()

    @classmethod
    def from_json(cls, value: dict) -> "RuntimeProbe":
        return cls(
            status=RuntimeProbeStatus(value["status"]),
            device_model=value.get("deviceModel"),
            soc=value.get("soc"),
            abi=value.get("abi"),
            qairt_version=value.get("qairtVersion"),
            htp_target=value.get("htpTarget"),
            context_fingerprint=value.get("contextFingerprint"),
            loaded_library_fingerprints=value.get("loadedLibraryFingerprints"),
            native_ready=value.get("nativeReady"),
            rejection_reasons=tuple(value.get("rejectionReasons", ())),
        )


@dataclass(frozen=True)
class GroupKey:
    scenario_sha256: str
    preset_snapshot_sha256: str
    runtime_fingerprint: str
    cold_state: ColdState
    harness_version: str


@dataclass(frozen=True)
class Sample:
    run_id: str
    sequence: int
    group_key: GroupKey
    outcome: Outcome
    end_to_end_ms: float | None
    is_warmup: bool = False
    quality_passed: bool = False
    baseline_frozen: bool = False
    thermal_stable: bool = False
    quality_evidence: dict[str, Any] | None = None
    response_evidence: dict[str, Any] | None = None
    expected_model_asset_sha256: str | None = None
    stage_metrics: dict[str, float | str | None] | None = None
    resource_metrics: dict[str, float | str | None] | None = None

    @classmethod
    def from_json(cls, value: dict[str, Any]) -> "Sample":
        key = value["groupKey"]
        return cls(
            run_id=value["runId"],
            sequence=int(value["sequence"]),
            group_key=GroupKey(
                scenario_sha256=key["scenarioSha256"],
                preset_snapshot_sha256=key["presetSnapshotSha256"],
                runtime_fingerprint=key["runtimeFingerprint"],
                cold_state=ColdState(key["coldState"]),
                harness_version=key["harnessVersion"],
            ),
            outcome=Outcome(value["outcome"]),
            end_to_end_ms=value.get("endToEndMs"),
            is_warmup=bool(value.get("isWarmup", False)),
            quality_passed=bool(value.get("qualityPassed", False)),
            baseline_frozen=bool(value.get("baselineFrozen", False)),
            thermal_stable=bool(value.get("thermalStable", False)),
            quality_evidence=value.get("qualityEvidence"),
            response_evidence=value.get("responseEvidence"),
            expected_model_asset_sha256=value.get("expectedModelAssetSha256"),
            stage_metrics=value.get("stageMetrics"),
            resource_metrics=value.get("resourceMetrics"),
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "runId": self.run_id,
            "sequence": self.sequence,
            "groupKey": {
                "scenarioSha256": self.group_key.scenario_sha256,
                "presetSnapshotSha256": self.group_key.preset_snapshot_sha256,
                "runtimeFingerprint": self.group_key.runtime_fingerprint,
                "coldState": self.group_key.cold_state.value,
                "harnessVersion": self.group_key.harness_version,
            },
            "outcome": self.outcome.value,
            "endToEndMs": self.end_to_end_ms,
            "isWarmup": self.is_warmup,
            "qualityPassed": self.quality_passed,
            "baselineFrozen": self.baseline_frozen,
            "thermalStable": self.thermal_stable,
            "qualityEvidence": self.quality_evidence or {},
            "responseEvidence": self.response_evidence or {},
            "expectedModelAssetSha256": self.expected_model_asset_sha256,
            "stageMetrics": self.stage_metrics or {},
            "resourceMetrics": self.resource_metrics or {},
        }


@dataclass(frozen=True)
class Statistics:
    count: int
    p50_ms: float
    p95_ms: float
    mad_ms: float
    ci95_low_ms: float
    ci95_high_ms: float


def statistics(samples: Iterable[Sample], run_id: str, bootstrap_rounds: int = 1_000) -> Statistics:
    values = sorted(
        sample.end_to_end_ms
        for sample in samples
        if sample.outcome == Outcome.SUCCESS and not sample.is_warmup and sample.end_to_end_ms is not None
    )
    if not values:
        raise ValueError("No successful non-warmup samples")
    median = percentile(values, 50)
    mad = percentile(sorted(abs(value - median) for value in values), 50)
    seed = int.from_bytes(hashlib.sha256(run_id.encode("utf-8")).digest()[:8], "big")
    generator = random.Random(seed)
    bootstrap = sorted(
        percentile([values[generator.randrange(len(values))] for _ in values], 50)
        for _ in range(bootstrap_rounds)
    )
    return Statistics(
        count=len(values),
        p50_ms=median,
        p95_ms=percentile(values, 95),
        mad_ms=mad,
        ci95_low_ms=percentile(bootstrap, 2.5),
        ci95_high_ms=percentile(bootstrap, 97.5),
    )


def percentile(values: list[float], percent: float) -> float:
    if not values:
        raise ValueError("Cannot calculate a percentile for an empty sequence")
    position = (len(values) - 1) * percent / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return values[lower]
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def report_gate(
    probe: RuntimeProbe,
    samples: Iterable[Sample],
    *,
    allow_cancelled: bool = False,
    validation_level: ValidationLevel = ValidationLevel.FINAL_VALIDATED,
) -> tuple[str, list[str]]:
    """Gates a run at its declared evidence threshold without upgrading it.

    Target runtime evidence and successful samples are mandatory for every
    level.  B0/quality are deliberately deferred until ``TARGET_VALIDATED``;
    the 100-run and thermal gates are deliberately deferred until
    ``FINAL_VALIDATED``.
    """
    reasons: list[str] = []
    sample_list = list(samples)
    if probe.status != RuntimeProbeStatus.VERIFIED:
        reasons.append(f"RuntimeProbe={probe.status.value}")
    if not is_verified_target_probe(probe):
        reasons.append("INCOMPLETE_RUNTIME_PROBE")
    measured_samples = [sample for sample in sample_list if not sample.is_warmup]
    if not measured_samples:
        reasons.append("NO_SAMPLES")
    keys = {sample.group_key for sample in sample_list}
    if len(keys) > 1:
        reasons.append("MIXED_GROUP_KEY")
    non_successes = [sample.outcome for sample in measured_samples if sample.outcome != Outcome.SUCCESS]
    if any(outcome != Outcome.CANCELLED for outcome in non_successes) or (non_successes and not allow_cancelled):
        reasons.append("RELIABILITY_FAILURE")
    if validation_level != ValidationLevel.EXPLORATORY:
        if any(not sample.quality_passed for sample in measured_samples):
            reasons.append("QUALITY_FAILURE")
        if any(not sample.baseline_frozen for sample in measured_samples):
            reasons.append("B0_NOT_FROZEN")
        if any(not _has_quality_and_response_evidence(sample) for sample in measured_samples):
            reasons.append("MISSING_QUALITY_OR_RESPONSE_EVIDENCE")
        reasons.extend(_final_metric_failures(measured_samples))
    if validation_level == ValidationLevel.FINAL_VALIDATED and any(
        not sample.thermal_stable for sample in measured_samples
    ):
        reasons.append("THERMAL_STABILITY_NOT_PROVEN")
    if measured_samples:
        state = measured_samples[0].group_key.cold_state
        warmup_count = sum(sample.is_warmup for sample in sample_list)
        if state in (ColdState.OS_CACHE_WARM, ColdState.CONTEXT_WARM) and warmup_count != 5:
            reasons.append(f"INVALID_WARMUP_COUNT:{warmup_count}!=5")
        if validation_level != ValidationLevel.EXPLORATORY:
            valid_count = sum(
                sample.outcome == Outcome.SUCCESS and not sample.is_warmup and sample.end_to_end_ms is not None
                for sample in measured_samples
            )
            required = 5 if state in (ColdState.DEVICE_COLD, ColdState.PROCESS_COLD) else 30
            if valid_count < required:
                reasons.append(f"INSUFFICIENT_SAMPLES:{valid_count}<{required}")
            if validation_level == ValidationLevel.FINAL_VALIDATED and valid_count < 100:
                reasons.append(f"INSUFFICIENT_RELIABILITY_SAMPLES:{valid_count}<100")
    if reasons:
        return "NOT_ACCEPTED_FOR_ONEPLUS13", reasons
    return (
        "EXPLORATORY_COMPLETED"
        if validation_level == ValidationLevel.EXPLORATORY
        else "TARGET_VALIDATED"
        if validation_level == ValidationLevel.TARGET_VALIDATED
        else "ACCEPTED_FOR_ONEPLUS13",
        reasons,
    )


def _final_metric_failures(samples: list[Sample]) -> list[str]:
    """Returns the metrics that must block target and final reports.

    Exploration can record a platform capability gap, but a candidate cannot
    become a target-validated/default preset unless every required metric is
    present. Missing Android counters are never silently converted to zero.
    """
    missing: set[str] = set()
    swap_values: list[int] = []
    leak_detected = False
    for sample in samples:
        stage = sample.stage_metrics if isinstance(sample.stage_metrics, dict) else {}
        resources = sample.resource_metrics if isinstance(sample.resource_metrics, dict) else {}
        if not _positive_number(stage.get("unetMs")):
            missing.add("unetMs")
        if not _positive_number(sample.end_to_end_ms):
            missing.add("endToEndMs")
        scenario_id = stage.get("scenarioId")
        if not isinstance(scenario_id, str) or not scenario_id:
            missing.add("scenarioId")
        elif scenario_id == "W5" and not _positive_number(stage.get("w5ThroughputPerSecond")):
            missing.add("w5ThroughputPerSecond")
        for key in ("processPssKb", "processRssKb", "swapPssKb", "baselinePssKb", "baselineRssKb", "releasePssKb", "releaseRssKb"):
            if not _non_negative_int(resources.get(key)):
                missing.add(key)
        if not isinstance(resources.get("memoryLeakDetected"), bool):
            missing.add("memoryLeakDetected")
        elif resources["memoryLeakDetected"]:
            leak_detected = True
        if _non_negative_int(resources.get("swapPssKb")):
            swap_values.append(resources["swapPssKb"])
        if _non_negative_int(resources.get("baselinePssKb")) and _non_negative_int(resources.get("releasePssKb")) and resources["releasePssKb"] > resources["baselinePssKb"]:
            missing.add("RESOURCE_RELEASE_NOT_RECOVERED")
        if _non_negative_int(resources.get("baselineRssKb")) and _non_negative_int(resources.get("releaseRssKb")) and resources["releaseRssKb"] > resources["baselineRssKb"]:
            missing.add("RESOURCE_RELEASE_NOT_RECOVERED")
    reasons = [f"MISSING_METRIC:{name}" for name in sorted(missing) if not name.startswith("RESOURCE_")]
    if "RESOURCE_RELEASE_NOT_RECOVERED" in missing:
        reasons.append("RESOURCE_RELEASE_NOT_RECOVERED")
    if _has_consecutive_positive_values(swap_values):
        reasons.append("SUSTAINED_SWAP")
    if leak_detected:
        reasons.append("MEMORY_LEAK_DETECTED")
    return reasons


def _positive_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def _non_negative_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _has_consecutive_positive_values(values: list[int]) -> bool:
    return any(first > 0 and second > 0 for first, second in zip(values, values[1:]))


def is_verified_target_probe(probe: RuntimeProbe) -> bool:
    """A host-provided status alone is never target-device runtime evidence."""
    fingerprints = probe.loaded_library_fingerprints
    return (
        probe.status == RuntimeProbeStatus.VERIFIED
        and probe.device_model == "PJZ110"
        and (probe.soc or "").upper() == "SM8750"
        and probe.abi == "arm64-v8a"
        and probe.qairt_version == "2.48.40"
        and (probe.htp_target or "").lower() == "v79"
        and _sha256(probe.context_fingerprint)
        and probe.native_ready is True
        and isinstance(fingerprints, dict)
        and bool(fingerprints)
        and all(isinstance(name, str) and name and _sha256(digest) for name, digest in fingerprints.items())
        and not probe.rejection_reasons
    )


def _has_quality_and_response_evidence(sample: Sample) -> bool:
    response = sample.response_evidence
    quality = sample.quality_evidence
    if not isinstance(response, dict) or not isinstance(quality, dict):
        return False
    width = response.get("width")
    height = response.get("height")
    workflow = response.get("workflow")
    identity_is_complete = (
        isinstance(response.get("model"), str)
        and response["model"]
        and isinstance(width, int) and width > 0
        and isinstance(height, int) and height > 0
        and _sha256(response.get("outputSha256"))
        and _sha256(response.get("modelAssetSha256"))
        and response.get("modelAssetSha256") == sample.expected_model_asset_sha256
    )
    request_identity_is_complete = (
        isinstance(response.get("seed"), int)
        if workflow in {"GENERATE", "IMAGE_TO_IMAGE", None}
        else _sha256(response.get("inputSha256"))
        if workflow == "UPSCALE_API"
        else False
    )
    return (
        identity_is_complete
        and request_identity_is_complete
        and quality.get("mode") in {"BIT_EXACT", "GOLDEN_SET"}
        and quality.get("passed") is True
    )


def _sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(character in "0123456789abcdef" for character in value)


def probe_as_dict(probe: RuntimeProbe) -> dict:
    return asdict(probe) | {"status": probe.status.value}


def report(
    probe: RuntimeProbe,
    samples: Iterable[Sample],
    run_id: str,
    *,
    validation_level: ValidationLevel = ValidationLevel.FINAL_VALIDATED,
) -> dict[str, Any]:
    """Returns a deterministic report; only a VERIFIED probe can pass it."""
    sample_list = list(samples)
    conclusion, reasons = report_gate(probe, sample_list, validation_level=validation_level)
    payload: dict[str, Any] = {
        "runId": run_id,
        "validationLevel": validation_level.value,
        "conclusion": conclusion,
        "reasons": reasons,
        "sampleCount": len(sample_list),
        "metrics": _metric_summary(sample_list),
    }
    if conclusion != "NOT_ACCEPTED_FOR_ONEPLUS13":
        payload["statistics"] = asdict(statistics(sample_list, run_id))
    return payload


def _metric_summary(samples: list[Sample]) -> dict[str, Any]:
    """Publishes the primary evidence alongside the conclusion, never as zero-fill."""
    measured = [sample for sample in samples if not sample.is_warmup]
    stages = [sample.stage_metrics for sample in measured if isinstance(sample.stage_metrics, dict)]
    resources = [sample.resource_metrics for sample in measured if isinstance(sample.resource_metrics, dict)]
    unet = [stage["unetMs"] for stage in stages if _positive_number(stage.get("unetMs"))]
    w5_windows = [
        (stage.get("w5WindowSampleCount"), stage["w5ThroughputPerSecond"])
        for stage in stages
        if stage.get("scenarioId") == "W5"
        and isinstance(stage.get("w5WindowSampleCount"), int)
        and stage["w5WindowSampleCount"] > 0
        and _positive_number(stage.get("w5ThroughputPerSecond"))
    ]
    # A W5 report describes the longest observed per-variant continuous
    # window, not the first single request that happened to start it.
    w5_window = max(w5_windows, default=None, key=lambda item: item[0])
    pss = [resource["processPssKb"] for resource in resources if _non_negative_int(resource.get("processPssKb"))]
    rss = [resource["processRssKb"] for resource in resources if _non_negative_int(resource.get("processRssKb"))]
    swap = [resource["swapPssKb"] for resource in resources if _non_negative_int(resource.get("swapPssKb"))]
    releases = [
        resource["releasePssKb"] <= resource["baselinePssKb"] and resource["releaseRssKb"] <= resource["baselineRssKb"]
        for resource in resources
        if _non_negative_int(resource.get("releasePssKb")) and _non_negative_int(resource.get("baselinePssKb"))
        and _non_negative_int(resource.get("releaseRssKb")) and _non_negative_int(resource.get("baselineRssKb"))
    ]
    return {
        "unetP50Ms": percentile(sorted(unet), 50) if unet else None,
        "w5SustainedThroughputPerSecond": w5_window[1] if w5_window else None,
        "w5SustainedWindowSampleCount": w5_window[0] if w5_window else None,
        "peakPssKb": max(pss) if pss else None,
        "peakRssKb": max(rss) if rss else None,
        "peakSwapPssKb": max(swap) if swap else None,
        "memoryLeakDetected": any(resource.get("memoryLeakDetected") is True for resource in resources) if resources else None,
        "resourceReleaseRecovered": all(releases) if releases and len(releases) == len(measured) else None,
    }
