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

真实目标机的 `run` 还在**首个设备请求前**要求 `--baseline-file`、
`--quality-evidence-file`、`--adb-serial`、`--app-package` 和
`--thermal-duration-minutes 30|60`，避免缺 B0、质量或热采样时继续消耗设备：

- B0 JSON 以 scenario、preset snapshot、runtime fingerprint 和 cold state 精确绑定，
  冻结绝对超时、质量参考摘要和真实模型资产 SHA-256；
- 质量 JSON 以实际下载的输出 SHA-256 为键；`BIT_EXACT` 匹配参考输出，
  `GOLDEN_SET` 要求 30 prompt × 4 seed、SSIM、LPIPS、CLIP 回归和盲测证据；
- W1-W6 均下载 PNG 并记录 Content-Type、魔数、尺寸、字节数和 SHA-256，HTTP 200
  本身不能作为完成输出；
- 每样本经指定 ADB 记录电池温度、thermal status、应用 PSS、Swap PSS。OEM 未暴露
  任一必需项会写入 `collectionError` 并保持 fail-closed；
- 只有跑满所选 30/60 分钟、无 severe thermal（status >= 3），且末四分位吞吐相对
  首四分位下降不超过 10% 时才写 `thermalStable=true`。

产物新增 `telemetry.jsonl`；RunManifest 只存 B0/质量文件摘要，不持久化可能带短期
 token 的输出 URL。已有 `pjz110-wifi-20260729-1825` 工件缺这些输入，仅为功能链路
证据，不能回填成性能结论。

## B0 与质量工件采集

先从 `EXPLORATORY` 的下载输出和原始质量度量制作 `quality-input-v1.json`，再生成不可
覆盖的 `quality-v1.json`。每个输入项必须绑定 v4 场景 SHA-256 和下载后输出 SHA-256；
`GOLDEN_SET` 必须包含 30 prompt × 4 seed、SSIM、LPIPS、CLIP 相对回归、盲测与
`nanCount`、`infCount`、`corruptImageCount`、`blackImageCount`、`colorLayoutValid` 等
原始测量。工具自行计算 `passed`，不会接受调用方写入的通过结论。

```bash
python3 tools/performance-harness/localdream_perf_harness.py capture-quality \
  --scenario-dir tools/performance-harness/scenarios/v4 \
  --quality-input-file build/perf-verification/quality-input-v1.json \
  --output-file build/perf-verification/quality-v1.json

python3 tools/performance-harness/localdream_perf_harness.py capture-baseline \
  --scenario-dir tools/performance-harness/scenarios/v4 \
  --base-url http://127.0.0.1:8080 \
  --adb-serial 172.20.103.120:5555 \
  --app-package io.github.ddq.visiondream \
  --preset-snapshot-sha256 <sha256> \
  --quality-evidence-file build/perf-verification/quality-v1.json \
  --output-file build/perf-verification/baseline-v1.json \
  --scenario-ids W1 \
  --bearer-token-file <0600-token-file>
```

`capture-baseline` 不接受静态 RuntimeProbe 文件：它必须用安全 token 读取认证 `/health`，
并将实时完整 `RuntimeProbe=VERIFIED`、ADB PJZ110/SM8750/ABI、硬件 serial、已安装包路径摘要和
同一模型 context 写入不可覆盖的来源摘要。一次只允许同一模型 context 的场景；每条 B0 绑定到
场景、preset snapshot、runtime fingerprint、冷态、绝对超时、质量参考和真实模型摘要。两个命令均
拒绝覆盖已有文件；输入缺少通过的同场景质量参考、摘要或原始测量时 fail-closed。

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
  --scenario-dir tools/performance-harness/scenarios/v4 \
  --runtime-probe-file build/oneplus13-runtime-probe.json \
  --base-url http://127.0.0.1:8080 \
  --fixture-dir tools/performance-harness/fixtures/v2 \
  --preset-snapshot-sha256 <accepted-job-snapshot-sha256> \
  --baseline-file build/perf-verification/B0.json \
  --quality-evidence-file build/perf-verification/quality.json \
  --adb-serial 3B15C4018L500000 \
  --app-package io.github.xororz.localdream \
  --thermal-duration-minutes 30 \
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
