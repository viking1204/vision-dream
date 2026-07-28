# LocalDream performance harness

`validate-scenarios` and the unit tests are host-only checks. They do not make
any device-performance claim. `verify --require-verified-runtime` writes a
RunManifest and will reject `UNAVAILABLE` or `REJECTED` RuntimeProbe evidence.

`run` is the W1-W6 active device executor. It posts the immutable W1/W2
generation requests, W3 multipart image-to-image request, W4 published
W1→W2→W1 switch sequence, W5 separate W1/W2 sustained requests, and W6
multipart upscale plus returned-URL download. It accepts no prompt, model, or
generation override. The caller must provide the accepted job's immutable
`--preset-snapshot-sha256` and a `VERIFIED` RuntimeProbe JSON.

W3 and W6 read the immutable `fixtures.imageFile` from `<fixture-dir>` and
verify its SHA-256 against the scenario before contacting the device. The v1
scenarios deliberately retain placeholder image identifiers and fail closed.
Published v2 freezes the deterministic, non-model-output 1024² PNG at
`fixtures/v2/oneplus13-reference-1024.png` with SHA-256
`abf199eca128320d0337474d1b3f3746ca10e7f30e11926fb2e3732b44b69cc4`;
do not replace the v1 JSON in place. A real-device invocation has the following
shape and belongs to phase 07:

```bash
python3 tools/performance-harness/localdream_perf_harness.py run \
  --scenario-dir tools/performance-harness/scenarios/v2 \
  --runtime-probe-file build/oneplus13-runtime-probe.json \
  --base-url http://127.0.0.1:8080 \
  --fixture-dir tools/performance-harness/fixtures/v2 \
  --preset-snapshot-sha256 <accepted-job-snapshot-sha256> \
  --output-dir build/perf-verification
```

The command writes a RunManifest, raw samples, and a non-passing report when a
probe, fixture, protocol request, or download check fails. It never turns a
Redmi K30 run into a OnePlus 13 performance conclusion.

For W7, `localdream_perf_protocol.py` is the active protocol driver: it
initializes the authenticated MCP session, submits the immutable scenario via
`generation.create`, and submits the identical fixture through
`/v1/images/generations`.  The fixture tests assert the two requests and reject
a missing MCP job id.  A real-device runner must provide the authenticated
OpenAI and MCP endpoints and preserve its RunManifest/raw samples; only an
OnePlus 13 `VERIFIED` probe can be considered by the later acceptance phase.
