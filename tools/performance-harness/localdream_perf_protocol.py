"""HTTP protocol driver used by the device-side performance harness.

The driver owns no benchmark policy.  It turns an immutable scenario into the
same concrete `/v1` or MCP request a device would receive and returns only
observable protocol evidence.  This keeps fixture tests independent from a
connected Android device while making a real-device run use the exact path.
"""

from __future__ import annotations

from dataclasses import dataclass
from http.client import HTTPException
import hashlib
import json
from pathlib import Path
import time
from typing import Iterator, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


MCP_PROTOCOL_VERSION = "2025-11-25"
# The parity sample remains frozen at the scenario step count. Cancellation is
# exercised on a separate long-running probe so the Job cannot naturally
# complete before the cancel reaches the scheduler.
CANCELLATION_PROBE_STEPS = 50


@dataclass(frozen=True)
class HttpResult:
    status: int
    headers: dict[str, str]
    body: bytes


class HttpTransport(Protocol):
    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int = 30_000,
    ) -> HttpResult: ...


@dataclass(frozen=True)
class SseEvent:
    event_id: int | None
    event: str
    data: str


class SseResponse(Protocol):
    status: int
    headers: dict[str, str]

    def events(self) -> Iterator[SseEvent]: ...

    def close(self) -> None: ...


class McpSseTransport(HttpTransport, Protocol):
    def open_sse(
        self,
        path: str,
        headers: dict[str, str],
        timeout_ms: int,
    ) -> SseResponse: ...


class UrlLibTransport:
    """Small stdlib transport so the harness has no host-side dependencies."""

    def __init__(self, base_url: str, bearer_token: str | None = None):
        self.base_url = base_url.rstrip("/") + "/"
        self.bearer_token = bearer_token

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int = 30_000,
    ) -> HttpResult:
        request_headers = dict(headers or {})
        if self.bearer_token:
            request_headers.setdefault("Authorization", f"Bearer {self.bearer_token}")
        request = Request(urljoin(self.base_url, path.lstrip("/")), body, request_headers, method=method)
        try:
            with urlopen(request, timeout=timeout_ms / 1000) as response:
                return HttpResult(
                    response.status,
                    {name.lower(): value for name, value in response.headers.items()},
                    response.read(),
                )
        except HTTPError as error:
            return HttpResult(
                error.code,
                {name.lower(): value for name, value in error.headers.items()},
                error.read(),
            )
        except URLError as error:
            raise _transport_error(method, path, error) from error
        except (OSError, HTTPException) as error:
            # http.client.RemoteDisconnected, connection resets and socket
            # timeouts are all OSError subclasses.  They are expected device
            # transport failures, not harness bugs, and their raw text can
            # contain a URL or proxy details.  Keep the report replayable
            # without ever reflecting credentials or connection metadata.
            raise _transport_error(method, path, error) from error

    def open_sse(self, path: str, headers: dict[str, str], timeout_ms: int) -> SseResponse:
        request_headers = dict(headers)
        if self.bearer_token:
            request_headers.setdefault("Authorization", f"Bearer {self.bearer_token}")
        request = Request(urljoin(self.base_url, path.lstrip("/")), headers=request_headers, method="GET")
        try:
            response = urlopen(request, timeout=timeout_ms / 1000)
            return _UrlLibSseResponse(response)
        except HTTPError as error:
            return _StaticSseResponse(
                error.code,
                {name.lower(): value for name, value in error.headers.items()},
                error.read(),
            )
        except URLError as error:
            raise _transport_error("SSE", path, error) from error
        except (OSError, HTTPException) as error:
            raise _transport_error("SSE", path, error) from error


class _UrlLibSseResponse:
    def __init__(self, response):
        self._response = response
        self.status = response.status
        self.headers = {name.lower(): value for name, value in response.headers.items()}

    def events(self) -> Iterator[SseEvent]:
        try:
            yield from _parse_sse_lines(self._response)
        except (OSError, HTTPException) as error:
            raise _transport_error("SSE read", "/mcp", error) from error

    def close(self) -> None:
        self._response.close()


class _StaticSseResponse:
    def __init__(self, status: int, headers: dict[str, str], body: bytes):
        self.status = status
        self.headers = headers
        self._body = body

    def events(self) -> Iterator[SseEvent]:
        yield from _parse_sse_lines(self._body.splitlines(keepends=True))

    def close(self) -> None:
        return None


class ProtocolExecutionError(RuntimeError):
    pass


def _transport_error(operation: str, path: str, error: BaseException) -> ProtocolExecutionError:
    """Normalizes recoverable stdlib transport failures without leaking details."""
    return ProtocolExecutionError(
        f"transport failure for {operation} {path} ({type(error).__name__})",
    )


SCHEDULER_API_IDS = {
    "Euler": "euler",
    "Euler A": "euler_a",
    "dpm": "dpm",
    "dpm_karras": "dpm_karras",
    "dpm_sde": "dpm_sde",
    "dpm_sde_karras": "dpm_sde_karras",
    "euler": "euler",
    "euler_karras": "euler_karras",
    "euler_a": "euler_a",
    "euler_a_karras": "euler_a_karras",
    "lcm": "lcm",
}


def scheduler_api_id(display_name: str) -> str:
    """Translate a frozen scheduler label to the OpenAI API's internal ID."""
    try:
        return SCHEDULER_API_IDS[display_name]
    except KeyError as error:
        raise ProtocolExecutionError(f"unsupported scheduler label: {display_name}") from error


@dataclass(frozen=True)
class ProtocolExecution:
    endpoint: str
    elapsed_ms: float
    status: int
    output_url: str | None
    output_bytes: int
    evidence: dict[str, object]


def openai_payload(scenario: dict) -> dict:
    """Build only from the immutable scenario; callers cannot override fixtures."""
    request = scenario["request"]
    fixtures = scenario["fixtures"]
    return {
        "model": scenario["model"]["selector"],
        "prompt": fixtures["prompt"],
        "negative_prompt": fixtures.get("negativePrompt", ""),
        "seed": fixtures.get("seed"),
        "size": f"{request['width']}x{request['height']}",
        "scheduler": scheduler_api_id(request["scheduler"]),
        "steps": request["steps"],
        "cfg": request["cfg"],
        "denoise_strength": request.get("strength", 1.0),
        "response_format": "url",
    }


def execute_v1_generation(transport: HttpTransport, scenario: dict) -> ProtocolExecution:
    started = time.monotonic_ns()
    payload = json.dumps(openai_payload(scenario), separators=(",", ":")).encode("utf-8")
    result = transport.request(
        "POST",
        "/v1/images/generations",
        payload,
        {"Content-Type": "application/json"},
        scenario["timeoutMs"],
    )
    elapsed = (time.monotonic_ns() - started) / 1_000_000
    response = _json(result, "/v1/images/generations")
    image = _first_image(response, "/v1/images/generations")
    return ProtocolExecution(
        endpoint="/v1/images/generations",
        elapsed_ms=elapsed,
        status=result.status,
        output_url=image.get("url"),
        output_bytes=len(image.get("b64_json", "")),
        evidence={
            "hasData": bool(response.get("data")),
            "hasUrl": bool(image.get("url")),
            "vendorDiagnostics": _vendor_diagnostics(response),
        },
    )


def _vendor_diagnostics(response: dict) -> dict[str, float]:
    """Accept only native-supplied numeric stage evidence from the response."""
    value = response.get("vendor_diagnostics")
    if not isinstance(value, dict):
        return {}
    unet_ms = value.get("unet_ms")
    if not isinstance(unet_ms, (int, float)) or isinstance(unet_ms, bool) or unet_ms <= 0:
        return {}
    return {"unetMs": float(unet_ms)}


def execute_mcp_generation(
    transport: HttpTransport,
    scenario: dict,
    *,
    session_id: str,
    request_id: int = 1,
    operation: str = "generation",
    steps_override: int | None = None,
    mutation_namespace: str = "fixture",
) -> ProtocolExecution:
    started = time.monotonic_ns()
    payload = openai_payload(scenario)
    arguments = {
        "modelId": payload["model"],
        "prompt": payload["prompt"],
        "negativePrompt": payload["negative_prompt"],
        "seed": payload["seed"],
        "width": scenario["request"]["width"],
        "height": scenario["request"]["height"],
        "scheduler": payload["scheduler"],
        "steps": steps_override if steps_override is not None else payload["steps"],
        "cfg": payload["cfg"],
        "denoiseStrength": payload["denoise_strength"],
        "idempotencyKey": _w7_idempotency_key(scenario, operation, mutation_namespace),
    }
    result = _mcp_call(transport, session_id, request_id, "generation.create", arguments, scenario["timeoutMs"])
    body = _json(result, "MCP generation.create")
    response = body.get("result") or {}
    job_id = response.get("jobId")
    if result.status != 200 or not isinstance(job_id, str) or not job_id:
        raise ProtocolExecutionError("MCP generation.create did not return a jobId")
    return ProtocolExecution(
        endpoint="mcp:generation.create",
        elapsed_ms=(time.monotonic_ns() - started) / 1_000_000,
        status=result.status,
        output_url=None,
        output_bytes=0,
        evidence={"tool": "generation.create", "jobId": job_id, "task": str(response.get("task", ""))},
    )


def initialize_mcp(transport: HttpTransport, timeout_ms: int) -> str:
    body = json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": MCP_PROTOCOL_VERSION}},
        separators=(",", ":"),
    ).encode("utf-8")
    result = transport.request("POST", "/mcp", body, {"Content-Type": "application/json"}, timeout_ms)
    response = _json(result, "MCP initialize")
    session_id = _header(result.headers, "mcp-session-id")
    if result.status != 200 or not session_id:
        raise ProtocolExecutionError("MCP initialize did not return mcp-session-id")
    if response.get("result", {}).get("protocolVersion") != MCP_PROTOCOL_VERSION:
        raise ProtocolExecutionError("MCP initialize returned an unsupported protocol version")
    return session_id


def protocol_parity_with_cancel(
    transport_v1: HttpTransport,
    transport_mcp: HttpTransport,
    scenario: dict,
    *,
    mutation_namespace: str,
) -> dict:
    """Run all W7-observable operations without treating a queued job as success.

    ``jobs.cancel`` is authenticated by the MCP bearer Token and Tool scope.
    The driver proves both direct execution and idempotent replay.
    """
    session_id = initialize_mcp(transport_mcp, scenario["timeoutMs"])
    v1 = execute_v1_generation(transport_v1, scenario)
    mcp = execute_mcp_generation(
        transport_mcp,
        scenario,
        session_id=session_id,
        request_id=2,
        operation="primary-generation",
        mutation_namespace=mutation_namespace,
    )
    progress_events, task_event = _observe_job_events(
        transport_mcp,
        session_id,
        mcp.evidence["jobId"],
        scenario["timeoutMs"],
        expected_total_steps=scenario["request"]["steps"],
    )
    replayed_progress_events, replayed_task_event = _observe_job_events(
        transport_mcp,
        session_id,
        mcp.evidence["jobId"],
        scenario["timeoutMs"],
        expected_total_steps=scenario["request"]["steps"],
        last_event_id=min(progress_events[0].event_id, task_event.event_id) - 1,
    )
    if [event.event_id for event in replayed_progress_events] != [event.event_id for event in progress_events]:
        raise ProtocolExecutionError("MCP progress replay did not preserve the event ids")
    if replayed_task_event.event_id != task_event.event_id:
        raise ProtocolExecutionError("MCP task replay did not preserve the event id")
    completed = _mcp_tool(
        transport_mcp,
        session_id,
        3,
        "jobs.get",
        {"jobId": mcp.evidence["jobId"]},
        scenario["timeoutMs"],
    )
    image_path = _required_resource_link(completed, mcp.evidence["jobId"])

    cancelled = execute_mcp_generation(
        transport_mcp,
        scenario,
        session_id=session_id,
        request_id=4,
        operation="cancel-generation",
        steps_override=CANCELLATION_PROBE_STEPS,
        mutation_namespace=mutation_namespace,
    )
    cancel_arguments = {
        "jobId": cancelled.evidence["jobId"],
        "dryRun": False,
        "idempotencyKey": _w7_idempotency_key(
            scenario,
            f"cancel:{cancelled.evidence['jobId']}",
            mutation_namespace,
        ),
    }
    cancel = _mcp_tool(
        transport_mcp,
        session_id,
        5,
        "jobs.cancel",
        cancel_arguments,
        scenario["timeoutMs"],
    )
    cancel_task = cancel.get("task")
    if cancel_task != "cancelled":
        raise ProtocolExecutionError("MCP jobs.cancel did not return cancelled")
    cancel_replay = _mcp_tool(
        transport_mcp,
        session_id,
        6,
        "jobs.cancel",
        cancel_arguments,
        scenario["timeoutMs"],
    )
    if cancel_replay.get("task") != "cancelled":
        raise ProtocolExecutionError("MCP jobs.cancel idempotency replay did not preserve cancelled result")

    reconnect_session_id = initialize_mcp(transport_mcp, scenario["timeoutMs"])
    reconnected = _mcp_tool(
        transport_mcp,
        reconnect_session_id,
        7,
        "jobs.get",
        {"jobId": mcp.evidence["jobId"]},
        scenario["timeoutMs"],
    )
    reconnect_image_path = _required_resource_link(reconnected, mcp.evidence["jobId"])
    download = transport_mcp.request("GET", reconnect_image_path, timeout_ms=scenario["timeoutMs"])
    download_evidence = _verified_png_download(download, scenario, "MCP asset")
    return {
        "v1": v1,
        "mcp": {
            "generation": mcp,
            "progressEvents": [event.as_dict() for event in progress_events],
            "progressReplay": [event.as_dict() for event in replayed_progress_events],
            "taskEvent": task_event.as_dict(),
            "taskReplay": replayed_task_event.as_dict(),
            "completed": completed,
            "cancel": cancel,
            "cancelReplay": cancel_replay,
            "reconnected": reconnected,
            "download": download.body,
            "downloadEvidence": download_evidence,
        },
        "parity": {
            "tool": mcp.evidence["tool"] == "generation.create",
            "v1Output": bool(v1.output_url or v1.output_bytes),
            "mcpJob": bool(mcp.evidence["jobId"]),
            "progress": [event.step for event in progress_events] == list(range(1, scenario["request"]["steps"] + 1)),
            "progressReplay": [event.event_id for event in replayed_progress_events] == [event.event_id for event in progress_events],
            "taskEvent": task_event.job_id == mcp.evidence["jobId"],
            "replay": replayed_task_event.event_id == task_event.event_id,
            "cancel": cancel_task == "cancelled",
            "cancelReplay": cancel_replay.get("task") == "cancelled",
            "reconnect": reconnected.get("jobId") == mcp.evidence["jobId"],
            "resourceLink": image_path == reconnect_image_path,
            "download": bool(download_evidence),
            "sharedModel": openai_payload(scenario)["model"] == scenario["model"]["selector"],
            "fixtureParity": _mcp_can_express_full_fixture(scenario),
        },
    }


def protocol_parity(
    transport_v1: HttpTransport,
    transport_mcp: HttpTransport,
    scenario: dict,
    *,
    mutation_namespace: str = "fixture",
) -> dict:
    """Exercise the complete observable W7 contract for `/v1` and MCP."""
    return protocol_parity_with_cancel(
        transport_v1,
        transport_mcp,
        scenario,
        mutation_namespace=mutation_namespace,
    )


def _verified_png_download(download: HttpResult, scenario: dict, label: str) -> dict:
    if download.status != 200 or not download.body:
        raise ProtocolExecutionError(f"{label} download failed")
    content_type = _header(download.headers, "content-type").split(";", 1)[0].strip().lower()
    if content_type != "image/png" or not download.body.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ProtocolExecutionError(f"{label} download is not a PNG")
    if len(download.body) < 24 or download.body[12:16] != b"IHDR":
        raise ProtocolExecutionError(f"{label} PNG is truncated")
    width = int.from_bytes(download.body[16:20], "big")
    height = int.from_bytes(download.body[20:24], "big")
    if width <= 0 or height <= 0 or (width, height) != (scenario["request"]["width"], scenario["request"]["height"]):
        raise ProtocolExecutionError(f"{label} PNG dimensions do not match scenario")
    return {"contentType": content_type, "magic": "PNG", "width": width, "height": height, "bytes": len(download.body), "sha256": hashlib.sha256(download.body).hexdigest()}


def _w7_idempotency_key(scenario: dict, operation: str, mutation_namespace: str) -> str:
    """Stable within one run while preventing stale Jobs from poisoning later runs."""
    return f"w7:{scenario['scenarioId']}:{scenario['scenarioVersion']}:{mutation_namespace}:{operation}"


def _mcp_call(
    transport: HttpTransport,
    session_id: str,
    request_id: int,
    tool: str,
    arguments: dict,
    timeout_ms: int,
) -> HttpResult:
    params = {"name": tool, "arguments": arguments}
    payload = json.dumps(
        {"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": params},
        separators=(",", ":"),
    ).encode("utf-8")
    return transport.request(
        "POST",
        "/mcp",
        payload,
        {"Content-Type": "application/json", "Mcp-Session-Id": session_id},
        timeout_ms,
    )


def _mcp_tool(
    transport: HttpTransport,
    session_id: str,
    request_id: int,
    tool: str,
    arguments: dict,
    timeout_ms: int,
) -> dict:
    result = _mcp_call(
        transport,
        session_id,
        request_id,
        tool,
        arguments,
        timeout_ms,
    )
    body = _json(result, f"MCP {tool}")
    value = body.get("result")
    if result.status != 200 or not isinstance(value, dict):
        raise ProtocolExecutionError(f"MCP {tool} was rejected")
    return value


def _mcp_error_code(result: HttpResult, operation: str) -> str:
    body = _json(result, f"MCP {operation}")
    code = body.get("error", {}).get("data", {}).get("code")
    if result.status != 200 or not isinstance(code, str) or not code:
        raise ProtocolExecutionError(f"MCP {operation} did not return a stable rejection code")
    return code


@dataclass(frozen=True)
class ObservedTaskEvent:
    event_id: int
    job_id: str
    task: str

    def as_dict(self) -> dict[str, str | int]:
        return {"eventId": self.event_id, "jobId": self.job_id, "task": self.task}


@dataclass(frozen=True)
class ObservedProgressEvent:
    event_id: int
    job_id: str
    step: int
    total_steps: int

    def as_dict(self) -> dict[str, str | int]:
        return {
            "eventId": self.event_id,
            "jobId": self.job_id,
            "step": self.step,
            "totalSteps": self.total_steps,
        }


def _observe_job_events(
    transport: McpSseTransport,
    session_id: str,
    job_id: str,
    timeout_ms: int,
    *,
    expected_total_steps: int,
    last_event_id: int | None = None,
) -> tuple[tuple[ObservedProgressEvent, ...], ObservedTaskEvent]:
    """Observe one Job on one SSE stream, including its progress and task state.

    MCP clients are expected to keep a stream open. Opening a fresh stream for
    every event category creates a disconnect-detection race and can exhaust a
    server's bounded SSE slots even though the client closed each response.
    """
    headers = {"Mcp-Session-Id": session_id}
    if last_event_id is not None:
        headers["Last-Event-ID"] = str(last_event_id)
    response = transport.open_sse("/mcp", headers, timeout_ms)
    content_type = _header(response.headers, "content-type") or ""
    if response.status != 200 or not content_type.startswith("text/event-stream"):
        response.close()
        raise ProtocolExecutionError(f"MCP job stream is unavailable (HTTP {response.status})")
    observed: list[ObservedProgressEvent] = []
    task_event: ObservedTaskEvent | None = None
    try:
        for event in response.events():
            if event.event == "reset":
                raise ProtocolExecutionError("MCP job stream reset before requested replay")
            if event.event_id is None:
                continue
            try:
                value = json.loads(event.data)
            except json.JSONDecodeError:
                continue
            if value.get("jobId") != job_id:
                continue
            if event.event == "progress":
                step = value.get("step")
                total_steps = value.get("totalSteps")
                if not isinstance(step, int) or isinstance(step, bool) or not isinstance(total_steps, int) or isinstance(total_steps, bool):
                    raise ProtocolExecutionError("MCP progress event has invalid step fields")
                expected_step = len(observed) + 1
                if total_steps != expected_total_steps or step != expected_step:
                    raise ProtocolExecutionError(
                        "MCP progress event does not match the generated job steps "
                        f"(expected {expected_step}/{expected_total_steps}, observed {step}/{total_steps})",
                    )
                observed.append(ObservedProgressEvent(event.event_id, job_id, step, total_steps))
            elif event.event == "task" and isinstance(value.get("task"), str):
                task = value["task"]
                if task in {"failed", "cancelled"}:
                    raise ProtocolExecutionError(f"MCP generated job entered terminal state {task}")
                if task == "completed":
                    task_event = ObservedTaskEvent(event.event_id, job_id, task)
            if len(observed) == expected_total_steps and task_event is not None:
                return tuple(observed), task_event
    finally:
        response.close()
    if len(observed) != expected_total_steps:
        raise ProtocolExecutionError("MCP diffusion-step progress for the generated job was not fully observed")
    raise ProtocolExecutionError("MCP completed task event for the generated job was not observed")


def _parse_sse_lines(lines) -> Iterator[SseEvent]:
    event_id: int | None = None
    event = "message"
    data: list[str] = []
    for raw_line in lines:
        line = raw_line.decode("utf-8", errors="replace") if isinstance(raw_line, bytes) else raw_line
        line = line.rstrip("\r\n")
        if not line:
            if data or event != "message" or event_id is not None:
                yield SseEvent(event_id, event, "\n".join(data))
            event_id = None
            event = "message"
            data = []
        elif line.startswith(":"):
            continue
        elif line.startswith("id:"):
            candidate = line[3:].strip()
            event_id = int(candidate) if candidate.isdigit() else None
        elif line.startswith("event:"):
            event = line[6:].strip() or "message"
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())


def _required_resource_link(result: dict, job_id: str) -> str:
    content = result.get("content")
    if not isinstance(content, list):
        raise ProtocolExecutionError("MCP jobs.get did not return a resource_link content block")
    links = [item for item in content if isinstance(item, dict) and item.get("type") == "resource_link"]
    if len(links) != 1:
        raise ProtocolExecutionError("MCP jobs.get must return exactly one resource_link content block")
    path = links[0].get("uri")
    expected_prefix = "/assets/"
    if not isinstance(path, str) or not path.startswith(expected_prefix) or len(path) <= len(expected_prefix):
        raise ProtocolExecutionError("MCP resource_link did not return a stable asset URI")
    return path


def _mcp_can_express_full_fixture(scenario: dict) -> bool:
    payload = openai_payload(scenario)
    arguments = {
        "modelId": payload["model"],
        "prompt": payload["prompt"],
        "negativePrompt": payload["negative_prompt"],
        "seed": payload["seed"],
        "width": scenario["request"]["width"],
        "height": scenario["request"]["height"],
        "scheduler": payload["scheduler"],
        "steps": payload["steps"],
        "cfg": payload["cfg"],
        "denoiseStrength": payload["denoise_strength"],
    }
    return all(arguments[key] == value for key, value in {
        "modelId": scenario["model"]["selector"],
        "prompt": scenario["fixtures"]["prompt"],
        "negativePrompt": scenario["fixtures"].get("negativePrompt", ""),
        "seed": scenario["fixtures"].get("seed"),
        "width": scenario["request"]["width"],
        "height": scenario["request"]["height"],
        "scheduler": scheduler_api_id(scenario["request"]["scheduler"]),
        "steps": scenario["request"]["steps"],
        "cfg": scenario["request"]["cfg"],
        "denoiseStrength": scenario["request"].get("strength", 1.0),
    }.items())


def _header(headers: dict[str, str], name: str) -> str | None:
    target = name.lower()
    return next((value for key, value in headers.items() if key.lower() == target), None)


def _json(result: HttpResult, source: str) -> dict:
    try:
        value = json.loads(result.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProtocolExecutionError(f"{source} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise ProtocolExecutionError(f"{source} returned a non-object JSON value")
    return value


def _first_image(response: dict, source: str) -> dict:
    data = response.get("data")
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        raise ProtocolExecutionError(f"{source} did not return image data")
    return data[0]
