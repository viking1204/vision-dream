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
) -> tuple[str, list[str]]:
    """Returns a publishable conclusion only when all local, non-device gates hold."""
    reasons: list[str] = []
    sample_list = list(samples)
    if probe.status != RuntimeProbeStatus.VERIFIED:
        reasons.append(f"RuntimeProbe={probe.status.value}")
    if not is_verified_target_probe(probe):
        reasons.append("INCOMPLETE_RUNTIME_PROBE")
    if not sample_list:
        reasons.append("NO_SAMPLES")
    keys = {sample.group_key for sample in sample_list}
    if len(keys) > 1:
        reasons.append("MIXED_GROUP_KEY")
    non_successes = [sample.outcome for sample in sample_list if sample.outcome != Outcome.SUCCESS]
    if any(outcome != Outcome.CANCELLED for outcome in non_successes) or (non_successes and not allow_cancelled):
        reasons.append("RELIABILITY_FAILURE")
    if any(not sample.quality_passed for sample in sample_list):
        reasons.append("QUALITY_FAILURE")
    if any(not sample.baseline_frozen for sample in sample_list):
        reasons.append("B0_NOT_FROZEN")
    if any(not sample.thermal_stable for sample in sample_list):
        reasons.append("THERMAL_STABILITY_NOT_PROVEN")
    if any(not _has_quality_and_response_evidence(sample) for sample in sample_list):
        reasons.append("MISSING_QUALITY_OR_RESPONSE_EVIDENCE")
    if sample_list:
        state = sample_list[0].group_key.cold_state
        valid_count = sum(
            sample.outcome == Outcome.SUCCESS and not sample.is_warmup and sample.end_to_end_ms is not None
            for sample in sample_list
        )
        required = 5 if state in (ColdState.DEVICE_COLD, ColdState.PROCESS_COLD) else 30
        if valid_count < required:
            reasons.append(f"INSUFFICIENT_SAMPLES:{valid_count}<{required}")
        if valid_count < 100:
            reasons.append(f"INSUFFICIENT_RELIABILITY_SAMPLES:{valid_count}<100")
    return ("ACCEPTED_FOR_ONEPLUS13" if not reasons else "NOT_ACCEPTED_FOR_ONEPLUS13", reasons)


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
    return (
        isinstance(response.get("model"), str)
        and response["model"]
        and isinstance(response.get("seed"), int)
        and isinstance(width, int) and width > 0
        and isinstance(height, int) and height > 0
        and _sha256(response.get("outputSha256"))
        and _sha256(response.get("modelAssetSha256"))
        and response.get("modelAssetSha256") == sample.expected_model_asset_sha256
        and quality.get("mode") in {"BIT_EXACT", "GOLDEN_SET"}
        and quality.get("passed") is True
    )


def _sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(character in "0123456789abcdef" for character in value)


def probe_as_dict(probe: RuntimeProbe) -> dict:
    return asdict(probe) | {"status": probe.status.value}


def report(probe: RuntimeProbe, samples: Iterable[Sample], run_id: str) -> dict[str, Any]:
    """Returns a deterministic report; only a VERIFIED probe can pass it."""
    sample_list = list(samples)
    conclusion, reasons = report_gate(probe, sample_list)
    payload: dict[str, Any] = {
        "runId": run_id,
        "conclusion": conclusion,
        "reasons": reasons,
        "sampleCount": len(sample_list),
    }
    if conclusion == "ACCEPTED_FOR_ONEPLUS13":
        payload["statistics"] = asdict(statistics(sample_list, run_id))
    return payload
