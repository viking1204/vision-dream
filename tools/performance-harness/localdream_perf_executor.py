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

    def execute(self, scenario_id: str) -> list[ScenarioExecution]:
        scenario = self._scenario(scenario_id)
        workflow = scenario["workflow"]
        if workflow == "GENERATE":
            return [self._generation(scenario, scenario_id)]
        if workflow == "IMAGE_TO_IMAGE":
            return [self._edit(scenario, scenario_id)]
        if workflow == "MODEL_SWITCH":
            return self._model_switch(scenario)
        if workflow == "SUSTAINED":
            return self._sustained(scenario)
        if workflow == "UPSCALE_API":
            return [self._upscale(scenario, scenario_id)]
        raise ProtocolExecutionError(f"{scenario_id} is not a W1-W6 executable workflow")

    def _scenario(self, scenario_id: str) -> dict:
        scenario = self._scenarios.get(scenario_id)
        if scenario is None:
            raise ProtocolExecutionError(f"required baseline scenario is missing: {scenario_id}")
        return scenario

    def _generation(self, scenario: dict, operation: str, group_scenario_id: str | None = None) -> ScenarioExecution:
        return ScenarioExecution(
            group_scenario_id or scenario["scenarioId"],
            operation,
            execute_v1_generation(self._transport, scenario),
        )

    def _model_switch(self, scenario: dict) -> list[ScenarioExecution]:
        if scenario["fixtures"].get("sequence") != "A-B-A":
            raise ProtocolExecutionError("W4 must preserve the published A-B-A sequence")
        return [
            self._generation(self._scenario(baseline), f"W4.{index + 1}.{baseline}", group_scenario_id="W4")
            for index, baseline in enumerate(("W1", "W2", "W1"))
        ]

    def _sustained(self, scenario: dict) -> list[ScenarioExecution]:
        variants = scenario["fixtures"].get("variants")
        if variants != ["W1", "W2"]:
            raise ProtocolExecutionError("W5 must preserve the published W1/W2 sustained variants")
        return [self._generation(self._scenario(baseline), f"W5.{baseline}") for baseline in variants]

    def _edit(self, scenario: dict, operation: str) -> ScenarioExecution:
        image = self._fixture_bytes(scenario)
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
        return ScenarioExecution(
            scenario["scenarioId"],
            operation,
            self._multipart_image_request("/v1/images/edits", fields, image, scenario["timeoutMs"]),
        )

    def _upscale(self, scenario: dict, operation: str) -> ScenarioExecution:
        image = self._fixture_bytes(scenario)
        started = time.monotonic_ns()
        result = self._multipart_image_request(
            "/v1/images/upscales",
            {"model": scenario["model"]["selector"], "response_format": "url"},
            image,
            scenario["timeoutMs"],
        )
        if not result.output_url:
            raise ProtocolExecutionError("W6 must return a URL before download verification")
        download = self._transport.request("GET", result.output_url, timeout_ms=scenario["timeoutMs"])
        if download.status != 200 or not download.body:
            raise ProtocolExecutionError("W6 URL download did not return image bytes")
        elapsed = (time.monotonic_ns() - started) / 1_000_000
        return ScenarioExecution(
            scenario["scenarioId"],
            operation,
            ProtocolExecution(
                endpoint=result.endpoint,
                elapsed_ms=elapsed,
                status=result.status,
                output_url=result.output_url,
                output_bytes=len(download.body),
                evidence=result.evidence | {
                    "downloaded": True,
                    "downloadBytes": len(download.body),
                    "postElapsedMs": result.elapsed_ms,
                    "endToEndIncludesDownload": True,
                },
            ),
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
