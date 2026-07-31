"""Fail-closed W1-W6 HTTP executor for the immutable performance scenarios.

The executor deliberately contains no performance-pass policy.  It only turns a
validated scenario into the real local OpenAI API requests and returns the
observable request evidence needed by ``localdream_perf_harness``.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import time
import uuid
from pathlib import Path

from localdream_perf_protocol import (
    HttpResult,
    HttpTransport,
    ProtocolExecution,
    ProtocolExecutionError,
    _first_image,
    _json,
    execute_v1_generation,
    openai_payload,
)


@dataclass(frozen=True)
class ScenarioExecution:
    scenario_id: str
    operation: str
    protocol: ProtocolExecution
    measurement_scenario_id: str | None = None
    variant_id: str | None = None
    sustained_throughput_per_second: float | None = None
    sustained_window_elapsed_ms: float | None = None
    sustained_window_sample_count: int | None = None
    request_baseline: dict | None = None


class DeviceScenarioExecutor:
    """Executes W1-W6 strictly from the immutable scenario set.

    W4 and W5 reference the published W1/W2 baselines instead of accepting a
    caller-provided model override.  W3 and W6 require a fixture whose SHA-256
    exactly equals the value frozen in their scenario; placeholders are rejected
    before a device request is sent.
    """

    def __init__(self, transport: HttpTransport, scenarios: list[dict], fixture_dir: Path):
        self._transport = transport
        self._scenarios = {scenario["scenarioId"]: scenario for scenario in scenarios}
        self._fixture_dir = fixture_dir
        # W5 is a measurement that continues across formal requests.  Warmups
        # deliberately do not enter this state: their purpose is context
        # preparation, not a sustained-throughput denominator.
        self._sustained_windows: dict[str, dict[str, int]] = {}

    def begin_sustained_measurement(self) -> None:
        """Starts fresh, per-variant W5 windows after the required warmups."""
        self._sustained_windows.clear()

    def execute(
        self,
        scenario_id: str,
        after_execution=None,
        before_execution=None,
        *,
        measure_sustained: bool = True,
    ) -> list[ScenarioExecution]:
        """Runs a scenario and observes each completed request before the next.

        W4's A→B→A model switch is intentionally sequential.  Deferring the
        observer until its whole list is returned would let B be reported with
        the final A runtime context.
        """
        scenario = self._scenario(scenario_id)
        workflow = scenario["workflow"]
        if workflow == "GENERATE":
            return [self._after(self._generation(scenario, scenario_id, before_execution=before_execution), after_execution)]
        if workflow == "IMAGE_TO_IMAGE":
            return [self._after(self._edit(scenario, scenario_id, before_execution=before_execution), after_execution)]
        if workflow == "MODEL_SWITCH":
            return self._model_switch(scenario, after_execution, before_execution)
        if workflow == "SUSTAINED":
            return self._sustained(
                scenario,
                after_execution,
                before_execution,
                measure_sustained=measure_sustained,
            )
        if workflow == "UPSCALE_API":
            return [self._after(self._upscale(scenario, scenario_id, before_execution=before_execution), after_execution)]
        raise ProtocolExecutionError(f"{scenario_id} is not a W1-W6 executable workflow")

    @staticmethod
    def _after(execution: ScenarioExecution, observer) -> ScenarioExecution:
        if observer is not None:
            observer(execution)
        return execution

    def _scenario(self, scenario_id: str) -> dict:
        scenario = self._scenarios.get(scenario_id)
        if scenario is None:
            raise ProtocolExecutionError(f"required baseline scenario is missing: {scenario_id}")
        return scenario

    def _generation(self, scenario: dict, operation: str, group_scenario_id: str | None = None, before_execution=None) -> ScenarioExecution:
        request_baseline = before_execution(scenario["scenarioId"], operation) if before_execution else None
        protocol = execute_v1_generation(self._transport, scenario)
        return ScenarioExecution(
            group_scenario_id or scenario["scenarioId"],
            operation,
            self._download_output(protocol, scenario, include_download_in_elapsed=False),
            request_baseline=request_baseline,
        )

    def _model_switch(self, scenario: dict, after_execution, before_execution) -> list[ScenarioExecution]:
        return self.execute_model_switch_prefix(
            3,
            after_execution=after_execution,
            before_execution=before_execution,
            observe_prefixes=True,
        )

    def execute_model_switch_prefix(
        self,
        prefix_length: int,
        *,
        after_execution=None,
        before_execution=None,
        observe_prefixes: bool = False,
    ) -> list[ScenarioExecution]:
        """Run a verified W4 A, A->B, or A->B->A resource prefix.

        Resource-release evidence belongs to the terminal request of exactly one
        prefix.  The harness can therefore replay the required setup without
        assigning B's unload to trailing A (or the reverse).  Prefix operations
        are still observable for RuntimeProbe audit, but only the terminal
        operation is emitted to the statistical batch unless ``observe_prefixes``
        is requested by the legacy full W4 execution path.
        """
        scenario = self._scenario("W4")
        if scenario["fixtures"].get("sequence") != "A-B-A" or prefix_length not in {1, 2, 3}:
            raise ProtocolExecutionError("W4 must preserve a published A, A-B, or A-B-A prefix")
        executions = []
        for index, baseline in enumerate(("W1", "W2", "W1")[:prefix_length], start=1):
            execution = self._generation(
                self._scenario(baseline),
                f"W4.{index}.{baseline}",
                before_execution=before_execution,
            )
            if observe_prefixes or index == prefix_length:
                self._after(execution, after_execution)
            executions.append(execution)
        return executions

    def _sustained(
        self,
        scenario: dict,
        after_execution,
        before_execution,
        *,
        measure_sustained: bool,
    ) -> list[ScenarioExecution]:
        variants = scenario["fixtures"].get("variants")
        if variants != ["W1", "W2"]:
            raise ProtocolExecutionError("W5 must preserve the published W1/W2 sustained variants")
        return [
            self.execute_sustained_variant(
                baseline,
                after_execution,
                before_execution,
                measure_sustained=measure_sustained,
            )
            for baseline in variants
        ]

    def execute_sustained_variant(
        self,
        variant_id: str,
        after_execution=None,
        before_execution=None,
        *,
        measure_sustained: bool = True,
    ) -> ScenarioExecution:
        """Executes one W5 variant without switching to another candidate.

        The harness schedules a complete W5:W1 batch before W5:W2.  Keeping
        this operation single-variant prevents another scenario from entering
        either candidate's wall-clock sustained-throughput window.
        """
        sustained = self._scenario("W5")
        if variant_id not in sustained["fixtures"].get("variants", []):
            raise ProtocolExecutionError(f"W5 variant is not published: {variant_id}")
        baseline_scenario = self._scenario(variant_id)
        # Resource sampling is evidence for a request, not work performed by
        # the candidate.  Take it before opening the W5 window so its adb
        # latency cannot lower a candidate's sustained throughput.
        request_baseline = (
            before_execution(baseline_scenario["scenarioId"], f"W5.{variant_id}")
            if before_execution
            else None
        )
        window = self._sustained_windows.get(variant_id) if measure_sustained else None
        if window is None and measure_sustained:
            window = {"started_ns": time.monotonic_ns(), "sample_count": 0}
            self._sustained_windows[variant_id] = window
        protocol = execute_v1_generation(self._transport, baseline_scenario)
        verified_protocol = self._download_output(
            protocol,
            baseline_scenario,
            include_download_in_elapsed=False,
        )
        if window is not None:
            window["sample_count"] += 1
            window_elapsed_ms = (time.monotonic_ns() - window["started_ns"]) / 1_000_000
            if window_elapsed_ms <= 0:
                raise ProtocolExecutionError(
                    f"W5.{variant_id} sustained window elapsed time must be positive",
                )
            window_sample_count = window["sample_count"]
            throughput = window_sample_count * 1_000 / window_elapsed_ms
        else:
            window_elapsed_ms = None
            window_sample_count = None
            throughput = None
        return self._after(
            ScenarioExecution(
                scenario_id=variant_id,
                operation=f"W5.{variant_id}",
                protocol=verified_protocol,
                measurement_scenario_id="W5",
                variant_id=variant_id,
                sustained_throughput_per_second=throughput,
                sustained_window_elapsed_ms=window_elapsed_ms,
                sustained_window_sample_count=window_sample_count,
                request_baseline=request_baseline,
            ),
            after_execution,
        )

    def _edit(self, scenario: dict, operation: str, before_execution=None) -> ScenarioExecution:
        image = self._fixture_bytes(scenario)
        request_baseline = before_execution(scenario["scenarioId"], operation) if before_execution else None
        payload = openai_payload(scenario)
        fields = {
            "model": payload["model"],
            "prompt": payload["prompt"],
            "negative_prompt": payload["negative_prompt"],
            "seed": str(payload["seed"]),
            "size": payload["size"],
            "scheduler": payload["scheduler"],
            "steps": str(payload["steps"]),
            "cfg": str(payload["cfg"]),
            "denoise_strength": str(payload["denoise_strength"]),
            "response_format": "url",
        }
        protocol = self._multipart_image_request("/v1/images/edits", fields, image, scenario["timeoutMs"])
        return ScenarioExecution(
            scenario["scenarioId"],
            operation,
            self._download_output(protocol, scenario, include_download_in_elapsed=False),
            request_baseline=request_baseline,
        )

    def _upscale(self, scenario: dict, operation: str, before_execution=None) -> ScenarioExecution:
        if scenario["request"].get("format") != "PNG":
            raise ProtocolExecutionError("W6 must declare PNG as its immutable output format")
        image = self._fixture_bytes(scenario)
        request_baseline = before_execution(scenario["scenarioId"], operation) if before_execution else None
        started = time.monotonic_ns()
        result = self._multipart_image_request(
            "/v1/images/upscales",
            {
                "model": scenario["model"]["selector"],
                "output_format": "png",
                "response_format": "url",
            },
            image,
            scenario["timeoutMs"],
        )
        verified = self._download_output(result, scenario, include_download_in_elapsed=True, started_ns=started)
        return ScenarioExecution(
            scenario["scenarioId"],
            operation,
            verified,
            request_baseline=request_baseline,
        )

    def _download_output(
        self,
        result: ProtocolExecution,
        scenario: dict,
        *,
        include_download_in_elapsed: bool,
        started_ns: int | None = None,
    ) -> ProtocolExecution:
        """Downloads every returned output before it may enter an acceptance sample.

        The API URL can carry a short-lived query credential, so only its
        derived integrity facts are persisted by the harness.  HTTP 200 from
        the generation endpoint alone is not an output-complete sample.
        """
        if not result.output_url:
            raise ProtocolExecutionError(f"{scenario['scenarioId']} must return a URL before output verification")
        download = self._transport.request("GET", result.output_url, timeout_ms=scenario["timeoutMs"])
        if download.status != 200 or not download.body:
            raise ProtocolExecutionError(f"{scenario['scenarioId']} URL download did not return image bytes")
        content_type = _media_type(download.headers.get("content-type"))
        if content_type != "image/png":
            raise ProtocolExecutionError(f"{scenario['scenarioId']} URL download must return Content-Type image/png")
        width, height = _png_dimensions(download.body)
        if scenario["scenarioId"] == "W6" and scenario["request"].get("format") != "PNG":
            raise ProtocolExecutionError("W6 must declare PNG as its immutable output format")
        elapsed = (
            (time.monotonic_ns() - started_ns) / 1_000_000
            if include_download_in_elapsed and started_ns is not None
            else result.elapsed_ms
        )
        return ProtocolExecution(
            endpoint=result.endpoint,
            elapsed_ms=elapsed,
            status=result.status,
            output_url=result.output_url,
            output_bytes=len(download.body),
            evidence=result.evidence | {
                "downloaded": True,
                "downloadBytes": len(download.body),
                "downloadContentType": content_type,
                "downloadMagic": "PNG",
                "downloadWidth": width,
                "downloadHeight": height,
                "downloadSha256": hashlib.sha256(download.body).hexdigest(),
                "postElapsedMs": result.elapsed_ms,
                "endToEndIncludesDownload": include_download_in_elapsed,
            },
        )

    def _multipart_image_request(
        self,
        path: str,
        fields: dict[str, str],
        image: bytes,
        timeout_ms: int,
    ) -> ProtocolExecution:
        boundary = f"----localdream-{uuid.uuid4().hex}"
        body = _multipart_body(boundary, fields, image)
        started = time.monotonic_ns()
        result = self._transport.request(
            "POST",
            path,
            body,
            {"Content-Type": f"multipart/form-data; boundary={boundary}"},
            timeout_ms,
        )
        response = _json(result, path)
        image_response = _first_image(response, path)
        if result.status != 200:
            raise ProtocolExecutionError(f"{path} returned HTTP {result.status}")
        return ProtocolExecution(
            endpoint=path,
            elapsed_ms=(time.monotonic_ns() - started) / 1_000_000,
            status=result.status,
            output_url=image_response.get("url"),
            output_bytes=len(image_response.get("b64_json", "")),
            evidence={"hasData": bool(response.get("data")), "hasUrl": bool(image_response.get("url"))},
        )

    def _fixture_bytes(self, scenario: dict) -> bytes:
        expected = scenario["fixtures"].get("imageSha256")
        if not isinstance(expected, str) or len(expected) != 64 or any(char not in "0123456789abcdef" for char in expected):
            raise ProtocolExecutionError(
                f"{scenario['scenarioId']} imageSha256 is not a frozen SHA-256; publish a new scenario version first",
            )
        fixture_name = scenario["fixtures"].get("imageFile", f"{scenario['scenarioId']}.png")
        if not isinstance(fixture_name, str) or Path(fixture_name).name != fixture_name:
            raise ProtocolExecutionError(f"{scenario['scenarioId']} fixture file must be a plain filename")
        path = self._fixture_dir / fixture_name
        try:
            image = path.read_bytes()
        except OSError as error:
            raise ProtocolExecutionError(f"{scenario['scenarioId']} fixture is unavailable: {path}") from error
        actual = hashlib.sha256(image).hexdigest()
        if actual != expected:
            raise ProtocolExecutionError(f"{scenario['scenarioId']} fixture SHA-256 mismatch")
        return image


def _multipart_body(boundary: str, fields: dict[str, str], image: bytes) -> bytes:
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.extend((
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            value.encode(),
            b"\r\n",
        ))
    parts.extend((
        f"--{boundary}\r\n".encode(),
        b'Content-Disposition: form-data; name="image"; filename="fixture.png"\r\n',
        b"Content-Type: image/png\r\n\r\n",
        image,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ))
    return b"".join(parts)


def _media_type(value: str | None) -> str:
    return (value or "").split(";", 1)[0].strip().lower()


def _png_dimensions(image: bytes) -> tuple[int, int]:
    if image[:8] != b"\x89PNG\r\n\x1a\n" or image[12:16] != b"IHDR" or len(image) < 24:
        raise ProtocolExecutionError("W6 URL download must contain a PNG signature and IHDR")
    width = int.from_bytes(image[16:20], "big")
    height = int.from_bytes(image[20:24], "big")
    if width <= 0 or height <= 0:
        raise ProtocolExecutionError("W6 PNG dimensions must be positive")
    return width, height
