"""HTTP protocol driver used by the device-side performance harness.

The driver owns no benchmark policy.  It turns an immutable scenario into the
same concrete `/v1` or MCP request a device would receive and returns only
observable protocol evidence.  This keeps fixture tests independent from a
connected Android device while making a real-device run use the exact path.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import time
from typing import Callable, Iterator, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


MCP_PROTOCOL_VERSION = "2025-11-25"


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
            raise ProtocolExecutionError(f"transport error for {method} {path}: {error.reason}") from error

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
            raise ProtocolExecutionError(f"transport error for SSE {path}: {error.reason}") from error


class _UrlLibSseResponse:
    def __init__(self, response):
        self._response = response
        self.status = response.status
        self.headers = {name.lower(): value for name, value in response.headers.items()}

    def events(self) -> Iterator[SseEvent]:
        yield from _parse_sse_lines(self._response)

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


@dataclass(frozen=True)
class ProtocolExecution:
    endpoint: str
    elapsed_ms: float
    status: int
    output_url: str | None
    output_bytes: int
    evidence: dict[str, str | int | bool]


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
        "scheduler": request["scheduler"],
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
        evidence={"hasData": bool(response.get("data")), "hasUrl": bool(image.get("url"))},
    )


def execute_mcp_generation(
    transport: HttpTransport,
    scenario: dict,
    *,
    session_id: str,
    request_id: int = 1,
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
        "steps": payload["steps"],
        "cfg": payload["cfg"],
        "denoiseStrength": payload["denoise_strength"],
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
    confirmation_id_supplier: Callable[[], str] | None,
) -> dict:
    """Run all W7-observable operations without treating a queued job as success.

    ``jobs.cancel`` is a destructive MCP tool.  The driver first verifies that
    the server asks the product UI for confirmation, then obtains the approved
    id through a caller-owned UI bridge and proves the id cannot be replayed.
    """
    session_id = initialize_mcp(transport_mcp, scenario["timeoutMs"])
    v1 = execute_v1_generation(transport_v1, scenario)
    mcp = execute_mcp_generation(transport_mcp, scenario, session_id=session_id, request_id=2)
    task_event = _observe_task_event(transport_mcp, session_id, mcp.evidence["jobId"], scenario["timeoutMs"])
    replayed_task_event = _observe_task_event(
        transport_mcp,
        session_id,
        mcp.evidence["jobId"],
        scenario["timeoutMs"],
        last_event_id=task_event.event_id - 1,
    )
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

    cancelled = execute_mcp_generation(transport_mcp, scenario, session_id=session_id, request_id=4)
    confirmation_required = _mcp_error_code(
        _mcp_call(
            transport_mcp,
            session_id,
            5,
            "jobs.cancel",
            {"jobId": cancelled.evidence["jobId"]},
            scenario["timeoutMs"],
        ),
        "jobs.cancel without confirmation",
    )
    if confirmation_required != "CONFIRMATION_REQUIRED":
        raise ProtocolExecutionError("MCP jobs.cancel did not require confirmation")
    if confirmation_id_supplier is None:
        raise ProtocolExecutionError("W7 jobs.cancel requires a confirmation UI approval bridge")
    confirmation_id = confirmation_id_supplier()
    if not confirmation_id:
        raise ProtocolExecutionError("W7 UI approval bridge did not return a confirmation id")
    cancel = _mcp_tool(
        transport_mcp,
        session_id,
        6,
        "jobs.cancel",
        {"jobId": cancelled.evidence["jobId"]},
        scenario["timeoutMs"],
        confirmation_id=confirmation_id,
    )
    cancel_task = cancel.get("task")
    if cancel_task != "cancelled":
        raise ProtocolExecutionError("MCP jobs.cancel did not return cancelled")
    confirmation_replay = _mcp_error_code(
        _mcp_call(
            transport_mcp,
            session_id,
            7,
            "jobs.cancel",
            {"jobId": cancelled.evidence["jobId"]},
            scenario["timeoutMs"],
            confirmation_id=confirmation_id,
        ),
        "MCP jobs.cancel confirmation replay",
    )
    if confirmation_replay != "CONFIRMATION_INVALID":
        raise ProtocolExecutionError("MCP jobs.cancel confirmation id was not one-time")

    reconnect_session_id = initialize_mcp(transport_mcp, scenario["timeoutMs"])
    reconnected = _mcp_tool(
        transport_mcp,
        reconnect_session_id,
        8,
        "jobs.get",
        {"jobId": mcp.evidence["jobId"]},
        scenario["timeoutMs"],
    )
    reconnect_image_path = _required_resource_link(reconnected, mcp.evidence["jobId"])
    download = transport_mcp.request("GET", reconnect_image_path, timeout_ms=scenario["timeoutMs"])
    if download.status != 200 or not download.body:
        raise ProtocolExecutionError("MCP image capability download failed")
    return {
        "v1": v1,
        "mcp": {
            "generation": mcp,
            "taskEvent": task_event.as_dict(),
            "taskReplay": replayed_task_event.as_dict(),
            "completed": completed,
            "cancel": cancel,
            "confirmationRequired": confirmation_required,
            "confirmationReplay": confirmation_replay,
            "reconnected": reconnected,
            "download": download.body,
        },
        "parity": {
            "tool": mcp.evidence["tool"] == "generation.create",
            "v1Output": bool(v1.output_url or v1.output_bytes),
            "mcpJob": bool(mcp.evidence["jobId"]),
            "taskEvent": task_event.job_id == mcp.evidence["jobId"],
            "replay": replayed_task_event.event_id == task_event.event_id,
            "cancel": cancel_task == "cancelled",
            "confirmationRequired": confirmation_required == "CONFIRMATION_REQUIRED",
            "confirmationOneTime": confirmation_replay == "CONFIRMATION_INVALID",
            "reconnect": reconnected.get("jobId") == mcp.evidence["jobId"],
            "resourceLink": image_path == reconnect_image_path,
            "download": bool(download.body),
            "sharedModel": openai_payload(scenario)["model"] == scenario["model"]["selector"],
            "fixtureParity": _mcp_can_express_full_fixture(scenario),
        },
    }


def protocol_parity(
    transport_v1: HttpTransport,
    transport_mcp: HttpTransport,
    scenario: dict,
    *,
    confirmation_id_supplier: Callable[[], str] | None = None,
) -> dict:
    """Exercise the complete observable W7 contract for `/v1` and MCP."""
    return protocol_parity_with_cancel(
        transport_v1,
        transport_mcp,
        scenario,
        confirmation_id_supplier=confirmation_id_supplier,
    )


def _mcp_call(
    transport: HttpTransport,
    session_id: str,
    request_id: int,
    tool: str,
    arguments: dict,
    timeout_ms: int,
    confirmation_id: str | None = None,
) -> HttpResult:
    params = {"name": tool, "arguments": arguments}
    if confirmation_id:
        params["confirmationId"] = confirmation_id
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
    *,
    confirmation_id: str | None = None,
) -> dict:
    result = _mcp_call(
        transport,
        session_id,
        request_id,
        tool,
        arguments,
        timeout_ms,
        confirmation_id,
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


def _observe_task_event(
    transport: McpSseTransport,
    session_id: str,
    job_id: str,
    timeout_ms: int,
    *,
    last_event_id: int | None = None,
) -> "ObservedTaskEvent":
    return _observe_task_event_after(transport, session_id, job_id, timeout_ms, last_event_id=last_event_id)


@dataclass(frozen=True)
class ObservedTaskEvent:
    event_id: int
    job_id: str
    task: str

    def as_dict(self) -> dict[str, str | int]:
        return {"eventId": self.event_id, "jobId": self.job_id, "task": self.task}


def _observe_task_event_after(
    transport: McpSseTransport,
    session_id: str,
    job_id: str,
    timeout_ms: int,
    *,
    last_event_id: int | None,
) -> ObservedTaskEvent:
    headers = {"Mcp-Session-Id": session_id}
    if last_event_id is not None:
        headers["Last-Event-ID"] = str(last_event_id)
    response = transport.open_sse("/mcp", headers, timeout_ms)
    content_type = _header(response.headers, "content-type") or ""
    if response.status != 200 or not content_type.startswith("text/event-stream"):
        response.close()
        raise ProtocolExecutionError("MCP task stream is unavailable")
    try:
        for event in response.events():
            if event.event == "reset":
                raise ProtocolExecutionError("MCP task stream reset before requested replay")
            if event.event != "task" or event.event_id is None:
                continue
            try:
                value = json.loads(event.data)
            except json.JSONDecodeError:
                continue
            if value.get("jobId") == job_id and isinstance(value.get("task"), str):
                return ObservedTaskEvent(event.event_id, job_id, value["task"])
    finally:
        response.close()
    raise ProtocolExecutionError("MCP task event for the generated job was not observed")


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
    expected_prefix = f"/mcp/images/{job_id}/"
    if not isinstance(path, str) or not path.startswith(expected_prefix):
        raise ProtocolExecutionError("MCP resource_link did not return the job image capability URI")
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
        "scheduler": scenario["request"]["scheduler"],
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
