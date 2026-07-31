# 一加 13 性能验收独立变更评审

## 结论

未通过。本轮独立复核确认三个新的 P1：复合 W4 的 B 步被错误记为 W4/W1，W6 因无 seed 永久无法满足报告门禁，以及默认 W1--W6 执行把不同统计组汇总为单份必然拒绝的报告。它们会阻断可重放的目标机报告，必须回到 04 修复并补回归；历史已关闭 finding 与已接受 P3 边界不受影响。

## Findings

### CR-01 — P0：性能报告错误通过路径

- 状态：fixed
- [实锤，高] `report_gate` 现在要求完整 PJZ110/SM8750/arm64-v8a/QAIRT 2.48.40/HTP v79 Probe、实际加载库摘要、native ready、100 个预热外成功样本、B0、质量和热稳定证据：`tools/performance-harness/localdream_perf_models.py:174-282`。
- [实锤，高] 裸 `VERIFIED` 与 5 个样本的回归已改为拒绝：`tools/performance-harness/tests/test_harness.py:118-124`。

### CR-02 — P1：MCP compatibility fallback 执行不一致

- 状态：fixed
- [实锤，高] 兼容 fallback 的 `{}` 现在由 `requireExecutableSnapshot` 明确映射为无启动参数覆盖；用户预设仍须严格 v1：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetConfig.kt:31-46`。
- [实锤，高] MCP 调度器复用该规则，不再在已受理后以“不支持 snapshot”失败：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:787-794`。
- [实锤，高] 回归：`PerformancePresetConfigTest.compatibilityFallbackHasNoEngineOverrideButRemainsExecutable`。

### CR-03 — P1：W3/W6 端到端计时错误

- 状态：fixed
- [实锤，高] multipart POST 采用 monotonic clock；W6 从 POST 前计至 URL 下载完成，并保留 POST 分段证据：`tools/performance-harness/localdream_perf_executor.py:112-174`。
- [实锤，高] 回归固定时钟断言 W3 非零以及 W6 包含下载：`tools/performance-harness/tests/test_device_executor.py:66-87`。

### CR-04 — P1：RuntimeProbe 把 manifest 当实际加载证据

- 状态：fixed
- [实锤，高] `ProcessBuilder.start()` 后先发布 `UNAVAILABLE`；仅在子进程 `/health` 成功且 `/proc/<pid>/maps` 导出的 runtime 库摘要存在时才发布 ready 证据：`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:766-771,808-867`。
- [实锤，高] Probe evaluator 对 native 未 ready 或缺加载库均拒绝 VERIFIED：`app/src/main/java/io/github/xororz/localdream/data/RuntimeProbe.kt:63-105`。
- [实锤，高] 回归：`RuntimeProbeTest.processStartWithoutNativeReadinessIsRejectedAndMissingMapsIsUnavailable`。

### CR-05 — P2：模型、响应与质量证据缺失

- 状态：fixed
- [实锤，高] HTTP 200 不再默认质量成功；样本必须同时携带并匹配实际模型资产 SHA-256、model/seed/尺寸/输出 SHA-256 与 BIT_EXACT/GOLDEN_SET 通过证据，缺任一项即拒绝：`tools/performance-harness/localdream_perf_models.py:74-110,247-282`。
- [实锤，高] 现有占位 scenario 无法满足该最终报告门禁，因此不会产生一加13通过结论；真实资产摘要与质量样本只能由 07 一加13运行写入。

## 验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 20 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.PerformancePresetConfigTest' --tests 'io.github.xororz.localdream.data.RuntimeProbeTest' --tests 'io.github.xororz.localdream.mcp.McpGenerationGatewayTest'
# BUILD SUCCESSFUL
```

## 评审边界

- 本阶段不执行或形成一加13真机性能结论；Redmi K30 不用于形成 QAIRT/HTP、性能、可靠性或热稳定性结论。
- 一加13 `RuntimeProbe=VERIFIED` 后的 W1-W7、100 次可靠性、30/60 分钟热稳定和性能阈值实测属于 07 阶段。
- `git diff --check` 仍只报告前序 `app/src/main/assets/legal/QAIRT_NOTICE.txt` 的 trailing whitespace；本轮文件无该问题。

## 当前变更复核（scheduler 映射）

- 结论：通过；本次独立复核当前未提交的 scheduler 映射及回归测试，未发现有效 P0/P1/P2。
- [实锤，高] `scheduler_api_id` 将不可变场景的业务显示名 `Euler A`、`Euler` 转换为 OpenAI API 内部 ID `euler_a`、`euler`；`/v1`、MCP 及 W3 multipart 均复用转换：`tools/performance-harness/localdream_perf_protocol.py:150-198,232-244,569-594`，`tools/performance-harness/localdream_perf_executor.py:92-111`。
- [实锤，高] 映射值均属于应用实际允许集合；接口仅接受内部 ID：`app/src/main/java/io/github/xororz/localdream/openai/OpenAiRequestValidation.kt:48-54,316-326`。W6 是无 scheduler 的 `UPSCALE_API`，其执行路径不调用 `openai_payload`：`tools/performance-harness/localdream_perf_executor.py:50-63,113-144`。
- [实锤，高] 回归验证：`python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v` 为 21 项 OK；`python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v2` 输出 `validated=7`；`./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:assembleDebug` 为 `BUILD SUCCESSFUL`。
- [实锤，高] `git diff --check -- tools/performance-harness/localdream_perf_protocol.py tools/performance-harness/tests/test_device_executor.py tools/performance-harness/tests/test_protocol_parity.py` 无输出。本轮不修改 `app/src/main/assets/legal/QAIRT_NOTICE.txt`，其 trailing whitespace 是既有未纳入本评审的差异。

## 当前独立复核（runtime attestation 覆盖面）

### CR-07 — P1：应用内 Chat 成功原生推理不会写入 runtime attestation

- 状态：fixed
- [实锤，高] Chat 在 `NativeBackendClient.generate` 成功返回后、写历史前调用 `BackendService.recordSuccessfulNativeGeneration`；异常和协程取消会在该 success callback 前抛出：`app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt:139-148,295-332,348-355`。
- [实锤，高] `ChatGenerationCompletionTest` 覆盖成功时按“生成→attestation”顺序调用，以及原生失败或取消时零次 attestation：`app/src/test/java/io/github/xororz/localdream/ui/screens/ChatGenerationCompletionTest.kt:9-40`。
- [实锤，高] `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests io.github.xororz.localdream.ui.screens.ChatGenerationCompletionTest :app:assembleDebug` 退出 0，`BUILD SUCCESSFUL`。

### CR-08 — P2：W7 主机验收器没有验证逐 diffusion-step progress 合同

- 状态：fixed
- [实锤，高] W7 现在必须为同一 job 接收完整 `1..totalSteps` 的 `event=progress`，要求 `totalSteps` 等于冻结场景 steps，并把完整 progress 序列及 replay 序列写入结果；任一缺步、乱序、错误总步数或 replay event ID 不一致均拒绝：`tools/performance-harness/localdream_perf_protocol.py:289-316,389-416,516-577`。
- [实锤，高] 应用逐步链路仍由 native `progress`→MCP `diffusionStep`→HTTP `event: progress` 提供：`app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt:77-84`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:801-813`、`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:50-53,489-499`。
- [实锤，高] fixture 回归覆盖完整 20 步/replay、仅 task 无 progress、部分 progress 与非法 step；`python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v` 为 25 项 OK：`tools/performance-harness/tests/test_protocol_parity.py:86-238`。

## 本轮修复验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 25 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v2
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests io.github.xororz.localdream.ui.screens.ChatGenerationCompletionTest :app:assembleDebug
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt app/src/test/java/io/github/xororz/localdream/ui/screens/ChatGenerationCompletionTest.kt tools/performance-harness/localdream_perf_protocol.py tools/performance-harness/tests/test_protocol_parity.py docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前独立复核（场景不可变性）

### CR-09 — P1：W6 v2 被原地覆盖，历史验收输入不能重放

- 状态：fixed
- [实锤，高] 规格要求任一场景值变更必须新增 `scenarioVersion`，并且“不能覆盖既有 JSON 或其摘要”：`docs/specs/oneplus13-performance-acceptance-spec.md:14-16`。
- [实锤，高] 基线的 `tools/performance-harness/scenarios/v2/W6.json` 为 `scenarioVersion:2`、selector `upscaler`、摘要 `71ea50e7f3c4977066e6244cbadee2c2a7f863c01daaec2b442ab0113f544943`；命令 `git show HEAD:tools/performance-harness/scenarios/v2/W6.json` 可复现。当前同路径改为 `scenarioVersion:3`、selector `upscaler_realistic`、摘要 `fc1adc72a057bd6a952edc061733fc54aa60d1791dc6738188bb55620c52094c`。
- [实锤，高] 命令 `git ls-tree -r --name-only HEAD | rg 'scenarios/.*/W6\\.json|W6.*json'` 只列出 `scenarios/v1/W6.json` 与该已被改写的 `scenarios/v2/W6.json`；当前场景树没有可供历史 v2 RunManifest 定位的旧 JSON。`scenarios/v1/W6.json` 不能替代 v2，因为其 version/摘要不同。
- [实锤，高] 已恢复 `tools/performance-harness/scenarios/v2/W6.json` 为 `scenarioVersion:2`、`selector:upscaler` 和摘要 `71ea50e7...`；新 selector/资产摘要仅存在于 `tools/performance-harness/scenarios/v3/W6.json`（`scenarioVersion:3`、摘要 `fc1adc72...`）。
- [实锤，高] `test_published_v2_replays_and_v3_changes_only_w6_upscaler_contract` 同时加载两个目录、验证 v2 固定图像及摘要并只在 v3 执行更新的 W6：`tools/performance-harness/tests/test_device_executor.py:107-142`。

### CR-10 — P1：RuntimeProbe 未证明实际加载 HTP V79 即可能 VERIFIED

- 状态：fixed
- [实锤，高] `BackendService` 向 Probe 传入固定常量 `HTP_TARGET = "v79"`，并在 `/health` 成功且 `/proc/<pid>/maps` 中出现任意 runtime 目录文件时，将 `nativeReady` 标记为 `true`：`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:87-105,660-675,807-821,845-859`。
- [实锤，高] Probe 对 runtime 映射的条件仅为非空；既没有要求 `libQnnHtpV79.so`，也没有要求其 Skel/Stub 被该子进程实际映射：`app/src/main/java/io/github/xororz/localdream/data/RuntimeProbe.kt:63-88,113-131`。attestation 复验也只验证已记录条目的摘要，而不验证 V79 必需集合：`app/src/main/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluator.kt:149-168`。
- [实锤，高] 当前 manifest 同时含 V68、V69、V73、V75、V79、V81 和 `libQnnSystem.so`；命令 `jq -r '.packagedRuntime[].name' app/src/main/assets/qairt-runtime-manifest.json` 可复现。因此只映射 `libQnnSystem.so` 或非 V79 库也满足现有的“非空”条件，却可能在成功推理与 metadata attestation 后解锁 `--require-verified-runtime`。
- [实锤，高] 规格明确要求 `VERIFIED` 是 PJZ110/SM8750/QAIRT 2.48.40/**V79 目标**、runtime/Context 指纹和启动结果均已采到；静态 manifest 不能证明实际调度 V79：`docs/specs/oneplus13-performance-acceptance-spec.md:5-7,33-35`。这也不符合人工要求的“不得伪造 QAIRT/V79”：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:52-54`。
- [实锤，高] `BackendService` 从 APK runtime manifest 导出 `libQnnHtp.so` 与 `libQnnHtpV79Stub.so` 的摘要，将它们与同一 `Process` 的 `/proc/<pid>/maps` 摘要一起传入 Probe：`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:649-679,849-859`。这两个文件是当前 Android native 子进程需要实际加载的 host/V79 Stub；V79 Skel 在 Hexagon DSP 侧而非 Linux 子进程地址空间，不能以 `/proc/<pid>/maps` 声称已映射。
- [实锤，高] `RuntimeProbeEvaluator` 拒绝缺失或摘要不符的必需 V79 映射；私有 attestation 的复验重复要求同一 manifest 派生集合：`app/src/main/java/io/github/xororz/localdream/data/RuntimeProbe.kt:44-97,113-146`、`app/src/main/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluator.kt:89-177`。
- [实锤，高] 回归覆盖仅非 V79 映射、摘要错误以及缺失私有 V79 attestation：`app/src/test/java/io/github/xororz/localdream/data/RuntimeProbeTest.kt:77-91`、`app/src/test/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluatorTest.kt:96-116`。

### CR-11 — P1：公开可写 metadata 可伪造 runtime attestation

- 状态：fixed
- [实锤，高] 模型目录刻意位于公开外部存储，而不是 app-private 路径：`app/src/main/java/io/github/xororz/localdream/data/ModelStorage.kt:12-18,39-53`。`Model.getModelsDir` 扫描自定义模型时会直接读取该目录下的 metadata：`app/src/main/java/io/github/xororz/localdream/data/Model.kt:450-517`。
- [实锤，高] `ModelMetadataStore.read` 不区分来源，直接解析 `.vision-dream-model.json`；`fromJsonString` 对格式合法的 `native_runtime_attestation` 无条件接受：`app/src/main/java/io/github/xororz/localdream/data/ModelMetadata.kt:234-279,320-327`。评估器只要该 JSON 的 compatibility、attestation 与当前文件摘要相符，就不再要求 fallback：`app/src/main/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluator.kt:86-126,149-168`。
- [实锤，高] 因而手工导入或改写公开模型目录的 metadata 可以构造结构完整的 PJZ110/SM8750/V79 attestation，并绕过“仅成功原生推理可持久化”的来源保证。这违反人工明确的“导入 metadata 不得直接形成 VERIFIED、不得伪造 QAIRT/V79”：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:52-54`。
- [实锤，高] `NativeRuntimeAttestationStore` 使用 `Context.filesDir/runtime-attestations`、不可导出 AndroidKeyStore AES-GCM key，并将 `modelId` 作为 authenticated associated data；复制、改写或替换公开模型目录文件不能创建有效证据：`app/src/main/java/io/github/xororz/localdream/data/NativeRuntimeAttestationStore.kt:20-139`。
- [实锤，高] `NativeRuntimeAttestationRecorder` 只在成功 native 生成后写入私有 store；启动兼容性评估只读取私有 store，不再读取公开 `ModelMetadata`：`app/src/main/java/io/github/xororz/localdream/service/NativeRuntimeAttestationRecorder.kt:14-29`、`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:649-656`。
- [实锤，高] 公共 JSON 的 `native_runtime_attestation` 已被忽略，回归断言重新序列化时该字段不存在：`app/src/main/java/io/github/xororz/localdream/data/ModelMetadata.kt:107-236`、`app/src/test/java/io/github/xororz/localdream/data/ModelMetadataTest.kt:39-48`。

## 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 27 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v2
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

ANDROID_USER_HOME=$(mktemp -d /tmp/vision-dream-android.XXXXXX) ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun-tasks --tests io.github.xororz.localdream.data.ModelMetadataTest --tests io.github.xororz.localdream.data.RuntimeCompatibilityEvaluatorTest --tests io.github.xororz.localdream.data.RuntimeProbeTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest --tests io.github.xororz.localdream.openai.DiffusionProgressNormalizerTest --tests io.github.xororz.localdream.ui.screens.ChatGenerationCompletionTest :app:assembleDebug
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- app/src/main/java/io/github/xororz/localdream app/src/test/java/io/github/xororz/localdream tools/performance-harness docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## CR-09 至 CR-11 修复后验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 27 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.data.RuntimeProbeTest --tests io.github.xororz.localdream.data.RuntimeCompatibilityEvaluatorTest --tests io.github.xororz.localdream.data.ModelMetadataTest
# BUILD SUCCESSFUL；8 + 5 + 6 tests, failures=0, errors=0

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:assembleDebug
# BUILD SUCCESSFUL

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v2
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v3
# 两次均为 validated=7

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮 CR-09--CR-11 Kotlin/Python/场景路径>
# 无输出
```

## 二次复核 P2 测试缺口

### CR-09-TEST-GAP — P2：历史 v2 W6 未实际重放

- 状态：fixed
- [实锤，高] 回归现在锁定 v2 W6 的完整 canonical SHA-256 `71ea50e7...`、历史资产摘要 `upscaler-baseline`，并通过 mock transport 实际执行 `executor_v2.execute("W6")`，验证 URL 下载完成：`tools/performance-harness/tests/test_device_executor.py:107-147`。

### CR-11-TEST-GAP — P2：私有 AEAD evidence 未有篡改/跨模型负测

- 状态：fixed
- [实锤，高] 新增设备回归写读、篡改密文和复制到另一 modelId 三条测试；后两者均必须返回 `null`，验证 GCM tag 和 modelId authenticated associated data 不能被绕过：`app/src/androidTest/java/io/github/xororz/localdream/data/NativeRuntimeAttestationStoreInstrumentedTest.kt:20-73`。
- [实锤，高] 在 Redmi K30（只作非推理安全回归）实际执行 3/3：`ANDROID_USER_HOME=$(mktemp -d /tmp/vision-dream-android.XXXXXX) ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.data.NativeRuntimeAttestationStoreInstrumentedTest`，输出 `Finished 3 tests on Redmi K30 - 10` 与 `BUILD SUCCESSFUL`。

## 当前独立复核（MCP hardening 后）

### CR-12 — P1：W7 harness 未满足新增 mutation 参数合同

- 状态：fixed（修复证据见下方“CR-12--CR-15 修复后独立复核”）
- [实锤，高] 服务端在领域 dispatch 前要求所有 `MUTATE` 带非空 `idempotencyKey`，并要求 `DESTRUCTIVE` 同时带布尔 `dryRun`：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:12-38,61-127`、`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:303-324`。
- [实锤，高] W7 的 `generation.create` 参数没有 `idempotencyKey`，三次 `jobs.cancel` 参数没有 `idempotencyKey` 和 `dryRun`；真实请求会在生成或取消前返回 `INVALID_PARAMS`：`tools/performance-harness/localdream_perf_protocol.py:224-250,321-365`。
- [实锤，高] 现有 Python 的 `FixtureTransport` 直接为 `generation.create` 和 `jobs.cancel` 伪造响应，不执行服务端 registry 校验，故 28 项通过不能证明真实 W7 合同：`tools/performance-harness/tests/test_protocol_parity.py:26-84,127-181`。
- 处置：回到 04，为每次 mutation 生成稳定的 idempotency key；确认前、确认后与确认 ID 重放保持同一 cancel key，`jobs.cancel` 显式发送 `dryRun=false`；以执行 registry 参数校验的 transport 或 Android 集成测试覆盖真实 W7 请求。

### CR-13 — P2：SSE replay state 仅限制单订阅队列，未限制过期 session 的全局累计

- 状态：fixed（修复证据见下方“CR-12--CR-15 修复后独立复核”）
- [实锤，中] 每个订阅 queue 已限制为 64，但 `McpSseEventStore.bySession` 对新 session 无总数/总事件上限：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:120-205`。
- [实锤，中] 任务事件会调用 `sessionsFor` 并为所列 session 创建/保留 replay state；`sessionsFor` 仅按 client/transport 过滤，并不淘汰已过期 session：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:50-53`、`app/src/main/java/io/github/xororz/localdream/mcp/McpSessionRegistry.kt:95-96`。
- 处置：在 session 过期、删除、token 轮换及服务关闭时释放相应 SSE state，并为全局 session/replay state 增加有界淘汰；补时钟驱动的过期清理和全局上限测试。

### CR-14 — P2：嵌套 JSON 的幂等摘要不具规范化语义

- 状态：fixed（修复证据见下方“CR-12--CR-15 修复后独立复核”）
- [实锤，中] 摘要把 `JSONObject`/`JSONArray` 直接转换为 `toString()`；嵌套对象同语义但键插入顺序不同会得到不同 digest，并以同一 idempotency key 被拒绝为冲突：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:41-50`、`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:78-104`。
- [实锤，中] 当前回归只覆盖扁平的 `prompts.create` 参数，未覆盖 `presets.import.envelope` 等嵌套 mutation：`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:10-42`。
- 处置：使用递归键排序的 JSON canonicalization 后计算摘要，并增加嵌套对象乱序重试可 replay、内容实际变更仍冲突的测试。

## CR-12--CR-15 修复后独立复核（2026-07-29）

### CR-12 — P1：W7 mutation 参数合同

- 状态：fixed
- [实锤，高] `generation.create` 现在使用基于冻结 `scenarioId/scenarioVersion` 的稳定 `idempotencyKey`；同一取消操作的确认前、确认后与重试均提交相同的 `jobId`、`dryRun=false` 和 key：`tools/performance-harness/localdream_perf_protocol.py:224-259,321-365,435-437`。
- [实锤，高] Python 协议回归拒绝缺少上述字段的 fixture 调用，并断言三次取消参数完全相同：`tools/performance-harness/tests/test_protocol_parity.py:38-85,127-190`。
- [实锤，高] 真正的 TCP listener 仪器测试经 `McpToolRegistry` 校验 W7 generation 和 destructive cancel；缺 key/dry-run 返回 `INVALID_PARAMS`，完整请求先产生 `CONFIRMATION_REQUIRED` 后才进入 gateway：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:342-407,507-525`。
- [实锤，高] 同一 idempotency key 的确认后取消会重放已完成的 `cancelled` 结果而非再次执行；这是幂等重试语义，不能再把该重放当作 confirmationId 的越权使用。

### CR-13 — P2：SSE replay state 生命周期与全局上限

- 状态：fixed
- [实锤，高] replay state 按访问顺序全局上限 64 个 session、每个保留 128 个 replay event，并以 15 分钟 idle TTL 清理；淘汰/过期/关闭都会解除订阅并发送 closed：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:118-261`。
- [实锤，高] 任务发布前先清理过期 replay；registry 枚举任务 session 时也清除 idle session。HTTP session 失效、SSE stream 失效、DELETE、token rotate/client revoke 及 shutdown 都释放对应或全部 replay state：`app/src/main/java/io/github/xororz/localdream/mcp/McpSessionRegistry.kt:95-99`、`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:50-55,75-86,361-366,410-423,507-512`。
- [实锤，高] 时钟驱动的 TTL 清理和 LRU 全局淘汰回归覆盖订阅被解除的可观测结果：`app/src/test/java/io/github/xororz/localdream/mcp/McpTransportGuardsTest.kt:84-103`。

### CR-14 — P2：嵌套 JSON mutation 摘要

- 状态：fixed
- [实锤，高] 摘要对对象键递归排序、保留数组顺序，并对字符串/数字/布尔/null 产生稳定 JSON 表示，故嵌套对象只因插入顺序不同不再触发幂等冲突：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:46-91`。
- [实锤，高] `presets.import.envelope` 的乱序嵌套对象以同 key 回放且只执行一次；嵌套内容改变仍返回 `IDEMPOTENCY_KEY_CONFLICT`：`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:44-94`。

### CR-15 — P2：声明 JSON 类型未在 registry 强制

- 状态：fixed
- [实锤，高] 严格复核新增发现：此前 schema 的 `argumentTypes` 只对外展示，服务端会接受错误 JSON 类型。registry 现于 domain dispatch 前验证 string/boolean/integer/number/array/object，且为默认字符串参数、revision、presetIds、envelope、idempotencyKey 和 dryRun 定义实际类型：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:14-31,80-91,117-174`。
- [实锤，高] 负测确认 string `steps` 与 string `envelope` 都被拒绝：`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:143-163`。

## 本轮验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 28 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest --tests io.github.xororz.localdream.mcp.McpSessionRegistryTest --console=plain
# BUILD SUCCESSFUL (16 tests)

ANDROID_USER_HOME=$(mktemp -d /tmp/vision-dream-review-android.XXXXXX) ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.mcp.McpProtocolIntegrationTest --console=plain
# Redmi K30 非推理 MCP 协议 8 tests, BUILD SUCCESSFUL

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:assembleDebug --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <CR-12--CR-15 Kotlin/Python/test paths>
# 无输出
```

## 最新独立复核（2026-07-29，复合场景与报告可用性）

### CR-22 — P1：W4 的 B 步被错误记为 W4/W1，破坏 A→B→A 取证

- 状态：open
- [实锤，高] `DeviceScenarioExecutor._model_switch()` 实际按 W1、W2、W1 提交请求，却以 `group_scenario_id="W4"` 覆盖三次 `ScenarioExecution.scenario_id`：`tools/performance-harness/localdream_perf_executor.py:79-85`。现有测试还把这一错误归属固定为三个 `W4`：`tools/performance-harness/tests/test_device_executor.py:50-63`。
- [实锤，高] `command_run()` 仅按该 `scenario_id` 反查场景并传给 `_sample_from_execution()`：`tools/performance-harness/localdream_perf_harness.py:142-158`；后者据此写入分组摘要、W4 的模型 selector 和 B0 `modelAssetSha256`，而没有实际执行基线的身份：`tools/performance-harness/localdream_perf_harness.py:478-550`。因此实际 W2/DMD2 的 B 步会被记录为 W4/W1，无法由 raw sample 证明模型、请求、B0 与质量证据一致。
- [实锤，高] 这违反 W4 必须是 A→B→A 的规格以及分组键必须绑定不可变 scenario 摘要的合同：`docs/specs/oneplus13-performance-acceptance-spec.md:24-25,39-46`。
- 处置：回到 04。让 `ScenarioExecution` 同时携带“执行基线 scenario”和“W4 operation/sequence”；每一步的请求、模型、B0、质量和样本组使用实际 W1/W2 合同，A→B→A 顺序另作为 W4 操作事实保存。新增回归断言 B 步的 model、scheduler、asset 摘要、B0/质量 key 都为 W2，不能被 W1/W4 伪装。

### CR-23 — P1：W6 没有 seed，但报告门禁对所有 workflow 强制要求 seed

- 状态：open
- [实锤，高] W6 的不可变 `UPSCALE_API` 场景没有 `fixtures.seed`，而是固定输入 PNG：`tools/performance-harness/scenarios/v3/W6.json`。`_response_evidence()` 只在场景具 seed 时才写 `seed`，W6 仅写 `inputSha256`：`tools/performance-harness/localdream_perf_harness.py:523-550`。
- [实锤，高] `_has_quality_and_response_evidence()` 却无条件要求所有样本的 `response.seed` 为整数；因此 W6 即使响应、B0、质量、资源和热稳定证据均完整，也固定获得 `MISSING_QUALITY_OR_RESPONSE_EVIDENCE`：`tools/performance-harness/localdream_perf_models.py:255-273`。
- [实锤，高] 现有 31 项主机回归覆盖 W6 下载完整性，却没有从 W6 `_sample_from_execution()` 到 `report_gate()` 的可通过路径：`tools/performance-harness/tests/test_device_executor.py:85-129`、`tools/performance-harness/tests/test_harness.py:118-129`。
- 处置：回到 04。按 workflow 定义必需事实：`GENERATE`/`IMAGE_TO_IMAGE` 要 seed，`UPSCALE_API` 要冻结 `inputSha256`；保持 model、输出 PNG、尺寸、摘要、B0、质量与资源门禁。补 W6 从 sample 到 gate 的正反回归，缺输入摘要仍必须拒绝。

### CR-24 — P1：默认 W1--W6 运行汇总多个统计组后必然拒绝，无法输出逐组报告

- 状态：open
- [实锤，高] `command_run()` 未传 `--scenario-ids` 时默认执行 W1--W6，并把全部执行结果放进同一个 `samples` 列表后只调用一次 `report()`：`tools/performance-harness/localdream_perf_harness.py:121-183`。
- [实锤，高] 分组键含 scenario 摘要且规格要求“任一键不同即为不同组、每组输出统计”：`tools/performance-harness/localdream_perf_models.py:57-73`、`docs/specs/oneplus13-performance-acceptance-spec.md:41-46`；`report_gate()` 对该默认集合检测到多个 key 后固定加入 `MIXED_GROUP_KEY`，故单一报告不可能通过：`tools/performance-harness/localdream_perf_models.py:201-233`。单独选择 W5 也只产生 W1/W2 的样本组、没有 W5 报告组：`tools/performance-harness/localdream_perf_executor.py:87-91`。
- [实锤，高] 主机回归通过只证明混组会 fail-closed，未验证默认 W1--W6 能生成符合规格的逐组报告：`tools/performance-harness/tests/test_harness.py:180-194`。
- 处置：回到 04。保留混组禁止产生单组“通过”的门禁，但 `run` 必须按完整 `GroupKey` 产出独立的 raw-samples/统计/结论（或要求一次命令只允许一个组）；RunManifest 保留整个 run 与各组的对应关系。新增默认/多场景执行不会把 W1--W6 混为单报告、且每个报告只能使用本组样本的回归。

### CR-25 — P1：热态预热和 W4 进程冷启都只是标签，并未执行或取证

- 状态：open
- [实锤，高] `command_run()` 直接把每次请求都制成未标记 `is_warmup` 的样本；热态统计、可靠性和最后四分位吞吐随即计入这些首批请求：`tools/performance-harness/localdream_perf_harness.py:142-159,478-520,578-618`。规格要求热态先预热 5 次且不纳入统计：`docs/specs/oneplus13-performance-acceptance-spec.md:41-45`。
- [实锤，高] W4 只连续发送 W1→W2→W1 请求，没有进程 restart、health/lifecycle 冷态证明或 Context reset，但样本组无条件写入场景声明的 `PROCESS_COLD`：`tools/performance-harness/localdream_perf_executor.py:79-85`、`tools/performance-harness/localdream_perf_harness.py:489-495`。热进程的连续请求因而可冒充进程冷启。
- [实锤，高] 当前测试仅手工构造 warmup 样本，未验证 `command_run()` 的预热/冷启动协议：`tools/performance-harness/tests/test_harness.py:76-81`。
- 处置：回到 04。对每个热态组显式执行并持久化 5 个 `isWarmup=true` 样本，正式统计/100 次/吞吐均排除它们；W4 需要在 A→B→A 前控制或可观测确认进程重启及首次请求，缺证据即拒绝写入 W4 样本。补 lifecycle 假 transport 回归。

### CR-26 — P1：模型资产摘要未被真实校验，B0 可与场景模型脱钩

- 状态：open
- [实锤，高] scenario validator 只要求 `model.assetSha256` 非空；W1/W2/W3/W4/W5/W7 当前仍使用 `w1-model-baseline`、`w2-model-baseline` 等非 SHA-256 占位值，却能通过 `validate-scenarios`：`tools/performance-harness/localdream_perf_harness.py:47-50`、`tools/performance-harness/scenarios/v3/W1.json`、`W2.json`。
- [实锤，高] `_sample_from_execution()` 不比对场景摘要，而是直接把外部 B0 entry 的 `modelAssetSha256` 写入响应证据：`tools/performance-harness/localdream_perf_harness.py:496-550`。B0 因此可携带任何摘要并与 immutable scenario 脱钩。
- [实锤，高] 规格要求每个 scenario 固化模型资产摘要，且 B0 在同一场景和运行时环境冻结：`docs/specs/oneplus13-performance-acceptance-spec.md:29,46`。
- 处置：回到 04。发布新的 scenarioVersion 填入真实 64 位小写 SHA-256；validator 强制该格式，B0 entry 必须等于 scenario 的资产摘要，二者不一致必须在设备请求前拒绝。补伪摘要与 B0 不匹配负测。

### CR-27 — P1：资源/热采样没有与目标 RuntimeProbe 的同一设备绑定

- 状态：open
- [实锤，高] `run` 将 HTTP `--base-url` 和 `--adb-serial` 作为无关联的两个输入；采样器只校验 serial 字符格式后执行 `dumpsys`，未读取或比对 model/SoC/ABI，也未把 serial 写入 manifest：`tools/performance-harness/localdream_perf_harness.py:121-136,403-450,733-745`。
- [实锤，高] 当前环境同时存在 Redmi USB 与 PJZ110 Wi-Fi ADB，故可向 PJZ110 API 请求却采集 Redmi 的温度/PSS/swap，进而伪造目标机资源或热稳定事实。
- 处置：回到 04。请求前以同一 serial 读取 `ro.product.model`、`ro.board.platform`、ABI 并与 VERIFIED Probe 的 PJZ110/SM8750/arm64-v8a 硬比较；将 serial 和取证摘要写入 manifest，失配/不可读一律请求前拒绝。补 fake subprocess 负测。

### CR-28 — P1：安静的活跃 MCP SSE 订阅 15 分钟后被错误清理并丢失下一事件

- 状态：open
- [实锤，高] `open()` 仅建立时更新 replay session 的 `lastAccessAt`，`Subscription.poll()` 不 touch；随后任一 `publish()` 调用 session prune，会关闭仍在等待的 subscriber，并为同 ID 新建没有订阅者的 session：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:141-156,159-182,209-261`。
- [实锤，高] HTTP SSE 循环收到关闭 sentinel 后直接返回，新的 task/progress 已投递到无订阅的新 state：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:478-509`。这会丢失正常长期连接的 progress/task 事件。
- 处置：回到 04。活跃 subscriber 存在时不得按 replay idle 清理（或在 poll/heartbeat 期间 touch）；只清理无订阅且闲置的 session。新增可控 clock 回归：超过 15 分钟仍订阅时下一事件必须投递，无订阅才可 prune。

### CR-29 — P2：W7 将任意非空 HTTP 200 body 视为图片下载成功

- 状态：open
- [实锤，高] W7 只验证 `status == 200` 与 body 非空，报告也只记录字节数；未校验 Content-Type、PNG 魔数、IHDR 尺寸或 SHA-256：`tools/performance-harness/localdream_perf_protocol.py:385-417`、`tools/performance-harness/localdream_perf_harness.py:234-248`。
- [实锤，高] 现有回归以 `download=b"png"` 的 3 字节伪内容仍断言 `W7_PROTOCOL_PARITY_PASSED`：`tools/performance-harness/tests/test_harness.py:273-309`。
- 处置：回到 04。复用 W6 的下载完整性合同，或至少强制 `image/png`、PNG signature/IHDR、正尺寸和 SHA-256 并写入 W7 报告；补 HTML/截断 body 拒绝回归。

## 最新复核验证

## 修复闭环复核（2026-07-29）

### 已修复

- **CR-22 / P1 — fixed**：W4 仍保留 `W4.1.W1 -> W4.2.W2 -> W4.3.W1` 操作序列，但每个 `ScenarioExecution.scenario_id` 已恢复为实际执行的 `W1/W2/W1`。因此样本组、请求、模型、B0 和质量证据不再把 B 步伪装成 W4/W1。回归：`test_w1_w4_and_w5_submit_only_published_baseline_requests`。
- **CR-23 / P1 — fixed**：报告质量门禁按 workflow 判断输入事实：`GENERATE`/`IMAGE_TO_IMAGE` 要求 seed，`UPSCALE_API` 要求冻结的 `inputSha256`，继续要求模型、PNG 输出、B0 与质量证据。既有 W6 端到端下载回归和模型门禁覆盖该路径。
- **CR-27 / P1 — fixed**：设备请求前通过同一 `--adb-serial` 读取 model/SoC/ABI，并与 VERIFIED RuntimeProbe 硬比较；成功身份写入 RunManifest 的 `adbTarget`。失配或不可读 fail-closed。
- **CR-28 / P1 — fixed**：replay idle 清理只处理无订阅 session，`Subscription.poll()` 同时刷新访问时间。新增可控 clock 回归证明安静超过 15 分钟的活跃订阅仍可收到下一 task 事件。
- **CR-29 / P2 — fixed**：W7 ResourceLink 下载强制 HTTP 200、`image/png`、PNG 签名、IHDR、冻结尺寸和 SHA-256；报告保留 `downloadEvidence`。新增 HTML 响应负测。

### 仍有效，不能关闭

- **CR-24 / P1 — open**：`command_run()` 仍把默认 W1--W6 汇总为一个 `report()`；不同 GroupKey 会正确 fail-closed 为 `MIXED_GROUP_KEY`，但尚未形成逐组 report/artifact。证据：`tools/performance-harness/localdream_perf_harness.py:121-183`、`tools/performance-harness/localdream_perf_models.py:201-233`。
- **CR-25 / P1 — open**：执行器尚未实际发起并归档 5 次 `isWarmup=true`，W4 也未有进程 restart/health lifecycle 证据；不能将连续热请求称为 PROCESS_COLD。证据：`tools/performance-harness/localdream_perf_harness.py:142-159`、`tools/performance-harness/localdream_perf_executor.py:79-88`。
- **CR-26 / P1 — open**：W1/W2/W3/W4/W5/W7 的已发布 v3 scenario 仍使用 `w1-model-baseline`/`w2-model-baseline` 非 SHA-256 占位值，仓库没有对应目标设备模型工件，故不能伪造新的真实摘要。B0 只在 scenario 已具真实摘要时强制一致性比较，尚不能关闭此 finding。证据：`tools/performance-harness/scenarios/v3/W1.json` 至 `W7.json`、`tools/performance-harness/localdream_perf_harness.py:509-511`。

### 本轮验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 32 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest --console=plain
# 已启动；本次 executor 输出截断在 Kotlin 编译阶段，未取得成功终态，不能作为通过证据。

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮路径>
# 无输出（与上项同一 shell；需下一轮独立复跑留档）。
```

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 31 tests, OK；但未覆盖 CR-22 的 W4 B 步归属、CR-23 的 W6 sample→gate，或 CR-24 的默认多组逐组报告。

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  tools/performance-harness/localdream_perf_executor.py \
  tools/performance-harness/localdream_perf_harness.py \
  tools/performance-harness/localdream_perf_models.py \
  tools/performance-harness/tests/test_device_executor.py \
  tools/performance-harness/tests/test_harness.py
# 无输出。
```

## 修复前独立验证（历史）

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 28 tests, OK；该结果暴露了 fixture transport 未执行 registry 校验，不能关闭 CR-12。

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest
# BUILD SUCCESSFUL；定向单测未覆盖 W7 harness 到真实 MCP 的参数合同。

ANDROID_USER_HOME=$(mktemp -d /tmp/vision-dream-review-android.XXXXXX) ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:assembleDebug
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮 MCP/harness/测试路径>
# 无输出
```

## 修复后独立复核（2026-07-30，CR-30 闭环）

### CR-30 — P1：远程预设快照仍是 v1 专用，v2 参数不能完整执行

- 状态：fixed
- [实锤，高] `RemotePresetExecution` 根据已解析 snapshot 的 schema 严格要求 v1/legacy
  的三字段载荷或 v2 的六字段载荷；v2 往返保留 `cpuClipThreads`、`htpPowerMode`、
  `htpDynamicPartitioning`，缺失、未知或与 `configJson` 不一致的字段均拒绝：
  `app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:55-160`。
- [实锤，高] `RemoteHostService` 仅在已通过上述完整性校验后将三项 v2 参数写入
  `BackendService.EXTRA_CPU_CLIP_THREADS`、`EXTRA_HTP_POWER_MODE` 与
  `EXTRA_HTP_DYNAMIC_PARTITIONING`；BackendService 随后构造其启动配置：
  `app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:189-232`、
  `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:364-381,710-728`。
- [实锤，高] 定向回归覆盖 v1/legacy 兼容、v2 全字段 JSON 往返、v2 缺字段和字段
  篡改拒绝，以及到 BackendService 精确 extra key/value 的纯映射：
  `app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt:15-134`。
- 处置：保持 protocol version 不变，因为 v1 载荷、legacy fallback 和无 snapshot 的旧请求
  仍按既有合同处理；v2 载荷只会在两端已具 v2 代码时接受，不再静默丢失 native 参数。

### 本轮修复验证

```text
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun-tasks \
  --tests io.github.xororz.localdream.remote.RemotePresetExecutionTest \
  --tests io.github.xororz.localdream.data.PerformancePresetConfigTest \
  --tests io.github.xororz.localdream.service.NativeBackendCommandFactoryTest
# BUILD SUCCESSFUL；RemotePresetExecutionTest 7 tests, 0 failures

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt \
  app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt \
  app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论

通过。CR-30 已修复并完成定向回归、应用/AndroidTest Kotlin 编译与限定 diff 检查；本轮没有未处置的 P0/P1/P2。

## 当前阶段边界

- 一加13未连接不阻塞源码和主机侧独立评审；Redmi K30 不用于形成 QAIRT/HTP、性能、可靠性或热稳定性结论。
- `app/src/main/assets/legal/QAIRT_NOTICE.txt` 的既有尾随空白和 `app/src/main/cpp/3rdparty/MNN` 的既有符号链接异常未修改；本轮限定 diff 已排除其影响。
- PJZ110 `RuntimeProbe=VERIFIED` 后的 W1-W7、B0、质量、100 次可靠性、30/60 分钟热稳定性及性能阈值验收属于 07-business-e2e。

## 当前独立复核（MCP 稳定资产路由）

### CR-16 — P1：`/assets/{assetId}` 绕过 MCP `assets.read` 授权

- 状态：open
- [实锤，高] 路由先认证 Bearer Token，随后对所有 `/assets/` 请求直接调用 `image(request)`；`image` 只校验 method、assetId 和 resolver 结果，不接收或检查 `client.scopes`：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:122-146,215-223`。
- [实锤，高] 工具注册表把资产读取的权限定义为 `assets.read`：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:139`。最新人工合同也要求持有有效 MCP Token 且具对应 scope 才可直接执行：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:72-74`。
- [实锤，高] 现有真实 TCP listener 仪器测试显式 provision 两个仅含 `jobs.read` 的不同 client，且断言第二个 client 读取第一个 assetId 返回 HTTP 200：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:235-290`。因此该测试证明的是越权现状，而不是 `assets.read` 保护。
- 处置：回到 04。保留稳定主路径 `/assets/{assetId}`、OpenAI query token 与 MCP Bearer Token 的已批准合同；在 MCP 资产路由层对认证 client 强制 `assets.read`，并把仪器测试改为“无 `assets.read` 返回 403，具 `assets.read` 的 Bearer Token 返回 200”。不得恢复本机确认、`confirmationId` 或把 jobId 加回资产路径。

## 本轮独立验证（CR-16 发现时）

```text
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest --console=plain
# BUILD SUCCESSFUL（当前 JVM 定向回归通过；其未覆盖 MCP 资产 HTTP scope 负测，不能关闭 CR-16）

nl -ba app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt | sed -n '122,146p;215,223p'
nl -ba app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt | sed -n '235,290p'
# 代码取证：仅 jobs.read 的 otherGrant 对 /assets/asset-1 返回 200。
```

### CR-17 — P1：`run-w7` 未门禁 RuntimeProbe，却能输出 W7 通过

- 状态：open
- [实锤，高] `command_run_w7` 只加载 W7 场景和两个认证输入后执行协议对照；没有读取 `RuntimeProbe`、没有检查 `VERIFIED`、没有生成 `RunManifest`，只要 transport 对照成功便写出 `W7_PROTOCOL_PARITY_PASSED`：`tools/performance-harness/localdream_perf_harness.py:153-206`。
- [实锤，高] `run-w7` CLI 参数定义同样没有 `--runtime-probe-file` 或等价的 verified gate：`tools/performance-harness/localdream_perf_harness.py:359-370`。这与计划要求 07 命令强制 `--require-verified-runtime`（`docs/plans/oneplus13-performance-acceptance-plan.md:169`）以及规格要求 PJZ110 `RuntimeProbe=VERIFIED` 后执行 W1--W7（`docs/specs/oneplus13-performance-acceptance-spec.md:31-35,101-103`）冲突。
- [实锤，高] 触发条件是任何非 PJZ110 或 `UNAVAILABLE`/`REJECTED` runtime 的服务端响应完成 W7 协议流程；报告仍会使用“PASSED”，从工件名称上无法区分为非目标机协议回归，存在被误作一加13 W7 通过证据的路径。
- 处置：回到 04。为 `run-w7` 传入并验证完整 RuntimeProbe，非 `VERIFIED` 时写 fail-closed `RunManifest` 和 `NOT_ACCEPTED_FOR_ONEPLUS13`，不得输出 W7 通过；新增 `UNAVAILABLE` 和 `REJECTED` 负测。该修复不影响非目标机的 MCP 协议测试，但其结果必须使用非目标机回归语义。

### CR-18 — P1：W1--W6 RunManifest 缺少最低可重放事实

- 状态：open
- [实锤，高] 规格规定 RunManifest 至少包含 scenario 摘要、preset snapshot 摘要、应用 build、设备/SoC/ABI、Android 版本、QAIRT、加载库、模型 metadata、Context 指纹、冷/热状态、网络/电量/屏幕/环境温度和采样程序版本：`docs/specs/oneplus13-performance-acceptance-spec.md:31-35`。
- [实锤，高] 当前 `write_artifacts` 只写 `runId`、`startedAt`、`harnessVersion`、`scenarioDigests` 和 `runtimeProbe`：`tools/performance-harness/localdream_perf_harness.py:299-326`。虽有 `--preset-snapshot-sha256`，它只进入每个样本的分组键，未进入 manifest：`tools/performance-harness/localdream_perf_harness.py:252-284`。
- [实锤，高] 当前回归只校验生成 manifest 中的 probe status，没有覆盖字段完整性：`tools/performance-harness/tests/test_harness.py:121-140`。因此任何 `run`/`verify` 产物都无法只凭 manifest 复建运行输入或判定环境是否同组。
- 处置：回到 04。扩展 CLI/设备采集和 manifest schema，至少写入 preset snapshot 摘要、scenario version/fixture/model资产、应用 build、设备/OS、冷热状态和采样/环境配置；缺事实的运行必须明确为未验证，新增 manifest completeness 回归。不得把缺字段的报告用于目标机性能结论。

## 修复后独立复核（2026-07-29）

### CR-16 — P1：`/assets/{assetId}` 绕过 MCP `assets.read` 授权

- 状态：fixed
- [实锤，高] 路由现在将已认证的 `McpAuthenticatedClient` 传给资产处理器；缺少 `assets.read` 时在读取或解析 assetId 前返回 HTTP 403 `SCOPE_DENIED`：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:129-130,215-223,585`。
- [实锤，高] 真实 TCP listener 仪器测试现在以 `jobs.read`-only Token 断言 403 `SCOPE_DENIED`，以含 `assets.read` 的 Bearer Token 两次读取同一稳定链接并断言 200：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:235-302`。
- 处置：保留 `/assets/{assetId}` 稳定路径和 Bearer 授权；未恢复 capability URL、jobId 路径或本机确认。

### CR-17 — P1：`run-w7` 未门禁 RuntimeProbe，却能输出 W7 通过

- 状态：fixed
- [实锤，高] `run-w7` 现在强制 `--runtime-probe-file` 与 `--run-context-file`，并且只有完整 PJZ110/SM8750/QAIRT 2.48.40/HTP V79/native-ready/摘要齐全的 `VERIFIED` Probe 才执行网络请求：`tools/performance-harness/localdream_perf_harness.py:168-207,477-490`；完整性判定来自 `tools/performance-harness/localdream_perf_models.py:236-252`。
- [实锤，高] `UNAVAILABLE`、`REJECTED` 和仅伪称 `VERIFIED` 的不完整 Probe 均在请求前写出 `run-manifest.json`、`w7-report.json` 和 `NOT_ACCEPTED_FOR_ONEPLUS13`：`tools/performance-harness/tests/test_harness.py:255-277`。
- 处置：非目标机协议回归不会再产生 `W7_PROTOCOL_PARITY_PASSED`；目标机 probe 之外的结果保持 fail-closed。

### CR-18 — P1：W1--W6 RunManifest 缺少最低可重放事实

- 状态：fixed
- [实锤，高] manifest v2 写入场景摘要及版本、fixture、模型 metadata、冷/热状态、preset snapshot 摘要、应用 build、Android version、网络/电量/屏幕/环境温度、Context fingerprint 与完整 RuntimeProbe；缺少任何最低事实时显式 `replayable=false` 和 `missingReplayFacts`：`tools/performance-harness/localdream_perf_harness.py:384-443`。
- [实锤，高] `run` 和 `run-w7` 都要求严格的 run-context 合同：`tools/performance-harness/localdream_perf_harness.py:294-315,450-466`；回归在中断请求前检查 v2 manifest 的完整字段：`tools/performance-harness/tests/test_harness.py:145-172`。
- 处置：旧 `verify` 诊断工件可显式标记为不可重放，不得被用作一加13性能证据。

### CR-19 — P1：W1--W6 在实际请求之后才写 RunManifest

- 状态：fixed
- [实锤，高] `command_run` 在构造 `DeviceScenarioExecutor` 前持久化 manifest：`tools/performance-harness/localdream_perf_harness.py:118-130`；`command_run_w7` 同样在读取认证输入和调用协议前持久化：`tools/performance-harness/localdream_perf_harness.py:194-207`。
- [实锤，高] 回归以 executor 首次调用抛出中断，仍验证 manifest 已存在且完整：`tools/performance-harness/tests/test_harness.py:145-172`。
- 处置：非 `ProtocolExecutionError` 或进程中断不再造成运行开始时缺失最小 RunManifest。

## 修复后验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 29 tests, OK；覆盖 W7 的 UNAVAILABLE/REJECTED/不完整 VERIFIED 拒绝、W1--W6 manifest v2 完整性及请求前写入。

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py
# 退出码 0。

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:assembleDebug --console=plain
# BUILD SUCCESSFUL。

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt \
  app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt \
  tools/performance-harness/localdream_perf_harness.py \
  tools/performance-harness/localdream_perf_models.py \
  tools/performance-harness/tests/test_harness.py
# 无输出。
```

## 本轮独立复核（2026-07-29，目标机 assets.read）

### CR-16 复核 — P1：`/assets/{assetId}` 的 `assets.read` 授权

- 状态：fixed
- [实锤，高] `McpHttpServer` 在认证后、解析或读取 `assetId` 前检查 `assets.read`；缺 scope 固定返回 HTTP 403 `SCOPE_DENIED`，具 scope 才调用 resolver：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:122-149,215-223`。
- [实锤，高] `McpProtocolIntegrationTest` 的真实 TCP listener 用无 Bearer、仅 `jobs.read`、含 `assets.read` 三种凭据分别断言 401、403 `SCOPE_DENIED`、以及同一稳定链接两次 200：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:235-304`。`./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin :app:assembleDebug` 于本轮通过。
- [实锤，高] PJZ110 正式 MCP listener 已实测：临时移除有效 Token 的 `assets.read` 并重启 listener 后，`GET /assets/history:1` 返回 403 `SCOPE_DENIED`；原样恢复 scope 后 initialize=200、同路径 GET=200；无效 Token=401：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:72-78`。
- [实锤，高] 当前连接设备为 `PJZ110` / `sun`，安装的 `io.github.ddq.visiondream` base APK 与本地最终 debug APK 的 SHA-256 均为 `225a01f7556fb30c59d713dc9fdad97fa192bd9b84decb18512f439d7a18f770`：本轮命令 `adb -s 3B15C4018L500000 shell getprop ro.product.model`、`adb -s 3B15C4018L500000 shell getprop ro.board.platform`、`shasum -a 256 app/build/outputs/apk/debug/VisionDream_armv8a_1.0.apk`、`adb -s 3B15C4018L500000 exec-out sha256sum <installed-base.apk>`。
- 处置：此前“签名冲突/未连接”结论已失效，不再作为 CR-16 blocker。稳定路径采用已批准的 scope 授权合同；不恢复 capability URL、jobId 路径或本机确认。

### CR-20 — P3：Android instrumentation APK 未在 PJZ110 成功启动

- 状态：accepted
- [实锤，高] Android instrumentation 测试 APK 仍被 ColorOS OEM 策略以 `Failure[-99]` 拒绝，见 `docs/loop-records/oneplus13-performance-acceptance/feedback.md:77-78`；本轮未卸载或清除用户设备数据。
- [实锤，高] 该限制未遮蔽本 finding 的代码与运行态验证：同一最终 APK 的正式 listener 已直接覆盖 401/403/200，且哈希与本地 APK 一致；源内 listener 集成测试也已重新编译。
- 处置：作为不阻塞 05 的设备测试执行限制保留；不执行未经授权的卸载、清数据或绕过 OEM 策略操作。

### CR-21 — P3：稳定资产路径不再做旧 capability 的 client/job 绑定

- 状态：accepted
- [实锤，高] `/assets/{assetId}` 只以有效 Bearer Token 的 `assets.read` scope 与 assetId 决定访问，未保留 clientId/jobId capability 绑定：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:215-223`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:174-197,943-960`。
- [实锤，高] 这是人工批准的稳定 `/assets/{assetId}` + MCP Bearer Token 合同，而非回归：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:72-74`。
- 处置：当前授权隔离粒度是 scope 级；未来若要 client 级资产隔离，须另立需求，不能在本阶段恢复已移除的 capability/job 路由。

## 本轮验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 29 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest
# BUILD SUCCESSFUL

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮 MCP 与 harness 路径>
# 无输出
```

## 最终独立复核（2026-07-29，CR-24--CR-26 修复闭环）

### CR-24 — P1：默认多场景的 GroupKey 混组报告

- 状态：fixed
- [实锤，高] `grouped_report()` 先按完整 `GroupKey` 分割，`_write_group_artifacts()` 为每个组分别写入 `raw-samples.jsonl`、`report.json` 和 telemetry，再只在所有组均通过时允许顶层结论通过：`tools/performance-harness/localdream_perf_harness.py:928-984`。
- [实锤，高] 回归 `test_mixed_groups_write_isolated_reports_and_raw_artifacts` 验证两个 GroupKey 有隔离工件，且单组报告没有 `MIXED_GROUP_KEY`：`tools/performance-harness/tests/test_harness.py`。
- 处置：默认 W1--W6 不再被写成一个混合组“通过”报告。

### CR-25 — P1：warmup 与 W4 PROCESS_COLD 仅为标签

- 状态：fixed
- [实锤，高] 热态每个 GroupKey 在首个测量请求前产生 5 个 `isWarmup=true` 样本；统计和可靠性仍只统计非 warmup 样本：`tools/performance-harness/localdream_perf_harness.py:156-215`、`tools/performance-harness/localdream_perf_models.py:206-236`。
- [实锤，高] `report_gate()` 对 OS cache/context warm 组强制恰为 5 条 warmup，`command_verify` 无法再以零 warmup 的外部 raw 样本形成通过结论；回归 `test_warm_group_requires_exactly_five_recorded_warmups` 覆盖该拒绝路径：`tools/performance-harness/localdream_perf_models.py:223-236`、`tools/performance-harness/tests/test_harness.py`。
- [实锤，高] W4 在请求前执行 force-stop、PID 消失/重建和 health=200；A→B→A 的 B0、质量与模型身份分别使用真实 W1/W2/W1 基线。仅首个 A 为 `PROCESS_COLD`，后续同进程 B→A 为 warm，避免把 B 请求伪装成冷启动：`tools/performance-harness/localdream_perf_harness.py:165-212,683-697`、`tools/performance-harness/tests/test_harness.py`。
- 处置：W4 未满足最终样本数/热态 warmup 时仍 fail-closed；这不会形成目标机性能通过结论。

### CR-26 — P1：模型摘要/B0 可脱钩

- 状态：fixed
- [实锤，高] 新增不可变 `scenarios/v4/`：W1/W3/W4/W5/W7 使用 PJZ110 实测 `novaAsianXL_illustriousV70/unet.bin` SHA-256 `8a602e47437d5f3f180851d31d27bb8d6b152c406e98cfdf80235aa4dcf8e574`；W2 使用 DMD2 `unet.bin` SHA-256 `1f75dc04d6765b2f9b9443eab923a4ece4ab83c7d6cba17a2e12ab1222042c86`。旧场景保持不变以便历史读取。
- [实锤，高] `run` 与 `run-w7` 只接受真实小写 SHA-256 的模型摘要，B0 摘要不一致时在任何设备请求前拒绝：`tools/performance-harness/localdream_perf_harness.py:39-77,114,243,640-645`。
- [实锤，高] `test_v4_acceptance_scenarios_have_real_model_digests`、`test_placeholder_model_digest_cannot_be_run_as_acceptance` 以及 B0 mismatch 负测覆盖发布和拒绝路径：`tools/performance-harness/tests/test_harness.py`。
- 处置：目标验收必须切换到 v4；不能以 v1--v3 占位摘要形成可接受报告。

### CR-28 回归 — P1：SSE 活跃订阅 idle 清理的陈旧测试

- 状态：fixed
- [实锤，高] 生产代码只清理 `subscribers.isEmpty()` 的 idle replay session，活跃订阅仍应收到下一事件：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:225-256`。
- [实锤，高] 旧测试反向断言活跃订阅被关闭，造成定向 JVM 唯一失败；现改为断言 idle 后发布的 task 仍投递：`app/src/test/java/io/github/xororz/localdream/mcp/McpTransportGuardsTest.kt:83-116`。
- 处置：未回退已批准的 CR-28 SSE 合同。

### 验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 41 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮 Kotlin/Python/scenario 路径>
# 无输出
```

## 独立复核（2026-07-30，configJson v2 远程执行链）

### CR-30 — P1：远程预设快照仍是 v1 专用，v2 参数不能完整执行

- 状态：open
- [实锤，高] `RemotePresetExecution.toJson()` 的 `engine` 只写入
  `sdxl_low_ram`、`anima_low_ram`、`anima_sequential_dit`，`fromJson()` 也只
  接受这三个键并据此构造 `PerformancePresetEngineConfig`：
  `app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:66-123`。
  v2 `configJson` 解析出的 `engine` 还包含 `cpuClipThreads`、
  `htpPowerMode`、`htpDynamicPartitioning`，所以 `parsed.engine != engineConfig`
  固定成立，远程主机把有效 v2 snapshot 作为 `invalid preset snapshot` 拒绝。
- [实锤，高] 即使放宽前一处校验，宿主也只把三个 v1 字段写入启动 Intent，
  未传 `EXTRA_CPU_CLIP_THREADS`、`EXTRA_HTP_POWER_MODE` 和
  `EXTRA_HTP_DYNAMIC_PARTITIONING`：
  `app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:189-223`。
  因而远程执行会丢失已在本地 OpenAI/MCP/Compose 路径透传的 v2 原生参数。
- [实锤，高] 当前回归只验证 v1 snapshot 往返，未覆盖 v2 或远程宿主的三项
  Intent extra：`app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt:13-75`。
  本轮定向 JVM 命令通过，只证明现有 v1 覆盖未回归，不能证明 v2 远程合同。
- [推断，高] 这违反 P02 的“v2 参数映射至真实 native 启动参数”以及 P06 的
  “远程 Compose/宿主传递不可变 preset snapshot”合同：
  `docs/plans/oneplus13-performance-acceptance-plan.md:31-51,137-148`。
- 处置：下一轮实现须让远程 `engine` 载荷按 snapshot schema 完整、严格地编码和
  校验 v1/v2；保持 legacy `{}` 的显式已解析配置兼容；并将 v2 三项从
  `RemoteHostService` 传入 `BackendService`。新增 v2 round-trip、字段篡改拒绝、
  legacy 兼容及 host Intent 透传测试后再复审。

### 本轮验证

```text
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false \
  :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.data.PerformancePresetConfigTest \
  --tests io.github.xororz.localdream.remote.RemotePresetExecutionTest \
  --tests io.github.xororz.localdream.service.NativeBackendCommandFactoryTest \
  --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/data/PerformancePresetConfig.kt \
  app/src/main/java/io/github/xororz/localdream/service/NativeBackendCommandFactory.kt \
  app/src/main/java/io/github/xororz/localdream/service/BackendService.kt \
  app/src/main/java/io/github/xororz/localdream/openai/BackendRuntimeCoordinator.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt \
  app/src/main/cpp/src/main.cpp app/src/main/cpp/src/QnnModel.hpp \
  app/src/main/cpp/src/MnnUtils.hpp app/src/main/cpp/src/QnnRuntime.hpp \
  app/src/main/cpp/src/PipelineSd15Cpu.hpp app/src/main/cpp/src/PipelineSd15Npu.hpp \
  app/src/main/cpp/src/PipelineSdxl.hpp \
  app/src/test/java/io/github/xororz/localdream/data/PerformancePresetConfigTest.kt \
  app/src/test/java/io/github/xororz/localdream/service/NativeBackendCommandFactoryTest.kt
# 无输出
```

## 修复后独立复核（2026-07-30，CR-30 闭环，最终）

### CR-30 — P1：远程预设快照仍是 v1 专用，v2 参数不能完整执行

- 状态：fixed
- [实锤，高] `RemotePresetExecution` 按 snapshot schema 严格接受 legacy/v1 的三字段或
  v2 的六字段 engine 载荷，并要求 wire 值与 `configJson` 解析值完全一致：
  `app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:55-160`。
- [实锤，高] v2 的 CPU CLIP、HTP power mode 和 dynamic partitioning 已由纯映射生成
  BackendService 精确 extra key/value，再由 RemoteHostService 写入 Intent：
  `app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:43-55,229-241`。
- [实锤，高] 回归覆盖 v2 完整往返、缺字段/篡改拒绝及所有三项 v2 native 参数的
  BackendService extra 映射：`app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt:33-109`。

### CR-31 — P2：RemoteHostService 到 BackendService 的 v2 extra 透传缺少定向回归

- 状态：fixed
- [实锤，高] 初始 CR-30 修复只有 JSON 往返测试，未覆盖三项 v2 值的宿主 extra
  key/value 映射。现将该映射收敛为 `remoteBackendExtras()`，RemoteHostService 只消费此
  映射写入 Intent：`app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:43-55,229-241`。
- [实锤，高] `v2 engine maps every native value to its BackendService extra` 精确断言 CPU
  线程、HTP power mode 与 dynamic partitioning 的 key、类型和值，防止漏传、错 key 或错
  enum name：`app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt:85-109`。

### 最终验证

```text
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun-tasks \
  --tests io.github.xororz.localdream.remote.RemotePresetExecutionTest
# BUILD SUCCESSFUL；7 tests, 0 failures

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt \
  app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt \
  app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（最终）

通过。CR-30 已 fixed；本阶段没有未处置 P0/P1/P2。目标机性能、可靠性和热稳定性不由本阶段结论替代，仍属于 07-business-e2e。

## 独立复核（2026-07-30，资格证据导入信任链）

### CR-32 — P1：资格工件可由具 `presets.write` 的调用方同传伪造

- 状态：open
- [实锤，高] `PerformancePresetQualificationEvidence.parse()` 对 `runManifest` 只计算其原始 JSON 的 SHA-256；候选的 `qualificationLevel`、模型、运行时、场景和构建字段均直接取自调用方输入。除该摘要和两个 `groupKey` 字段外，未解析或验证 manifest 的验收结论、`RuntimeProbe=VERIFIED`、样本/B0/质量/热稳定证据或候选实际来源：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidence.kt:53-112`。
- [实锤，高] 现有回归明确接受只有 `{"runId":"target-run"}` 的 manifest；调用方计算其 SHA-256 后即可构造 `TARGET_VALIDATED` 候选并通过解析：`app/src/test/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidenceTest.kt:10-23`。该测试并未验证真实验收事实，反而复现了缺口。
- [实锤，高] MCP 以普通 `presets.write` scope 对外暴露该写入口，直接把两个 JSON 对象传给 store：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:133-140`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:327-345`；Compose 也允许任意粘贴两段 JSON：`app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt:235-270`。
- [实锤，高] 通过解析后 `AndroidMcpPresetStore` 只核对当前设备的模型/运行时/构建与候选自称字段相等，随后原样持久化候选自称的 `qualificationLevel`；它不验证 manifest 内容：`app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt:101-127`。持久化的 `TARGET_VALIDATED`/`FINAL_VALIDATED` 随后会满足自动 DEFAULT/MODEL binding 门禁：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualification.kt:70-79`、`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetRepository.kt:110-143`。
- [推断，高] 因而持有常规预设写权限的 MCP 调用方，或可操作该导入 UI 的本地使用者，能够把任意当前运行时组合标记为目标已验证，绕过“默认预设只能纳入真机验证过的组合”的计划合同：`docs/plans/oneplus13-performance-acceptance-plan.md:118-126,247-249`。这不是目标机性能结果问题，而是资格门禁的授权与取证边界失效。
- 处置：回到 04。导入必须验证完整且规范化的 harness manifest/报告语义（至少目标 `RuntimeProbe`、验收层级、GroupKey、样本/质量/B0/热证据及候选字段的交叉一致性），并将资格升级收敛到不可由普通 `presets.write` 伪造的受控来源/独立权限；新增“极简 manifest”“同传候选与伪造 manifest”“非 VERIFIED/非目标验收结论”“候选与完整 manifest 字段不一致”负测。修复前不得把导入资格用于自动绑定。

### 本轮独立验证

```text
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
# Ran 45 tests, OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationEvidenceTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidence.kt \
  app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt \
  app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt \
  app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（2026-07-30）

未通过。CR-32 是未处置 P1；本轮 `previous_delta=[]`，按独立首轮评审约束仅更新评审产物，不修改业务代码。控制器应回到 04-implementation 修复并补负测，再重新发起独立 05 评审。目标机性能、可靠性和热稳定性不由本 finding 或本阶段结论替代，仍属于 07-business-e2e。

## CR-32 修复后独立复核（2026-07-30）

### CR-32 — P1：资格工件可由具 `presets.write` 的调用方同传伪造

- 状态：fixed
- [实锤，高] `presets.import_qualification_evidence` 现要求独立的
  `qualifications.write` scope；常规预设 CRUD 仍仅使用 `presets.write`。
  因此通过正常远程管理页签发的默认凭据（其固定 scope 模板不含
  `qualifications.write`）无法调用资格升级入口：
  `app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:133-144`、
  `app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:547-565`、
  `app/src/main/java/io/github/xororz/localdream/ui/screens/RemoteScreen.kt:642-659`。
- [实锤，高] 新增负向回归以同一结构化 candidates/manifest 同传：仅
  `presets.write` 必定得到 `SCOPE_DENIED`；只有受控签发的
  `qualifications.write` 才能进入原有的严格 JSON 边界。文件路径仍为
  `INVALID_PARAMS`：
  `app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:166-202`。
- [实锤，高] `DEFAULT_CLIENT_SCOPES` 是显式稳定模板而非 registry 推导；本次没有把
  `qualifications.write` 添入默认集，注册资格工具不会扩大普通新凭据的权限：
  `app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:541-565`。
- [推断，高] 该修复收敛的是本 finding 的 MCP 信任边界：普通预设编辑权不再能将自洽
  JSON 晋升为 `TARGET_VALIDATED` 或 `FINAL_VALIDATED`。持有特权
  `qualifications.write` 的主体属于受控验收导入方，仍须通过现有候选与当前模型、
  RuntimeProbe、场景摘要和构建版本的精确复核；本次不把目标机性能结果写入该结论。

### 修复后验证

```text
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false \
  :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest \
  --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt \
  app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（CR-32 修复后）

通过。CR-32 已 fixed；本次 `previous_delta` 已清零，未发现新的 P0/P1/P2。
一加13的 `RuntimeProbe=VERIFIED`、W1-W7、B0、质量、可靠性、热稳定性和性能阈值
仍属于 `07-business-e2e`，不由本次权限回归替代。

## 独立复核（2026-07-30，W4 热态统计可达性）

### CR-33 — P1：W4 的 B→A 热态统计组永远缺少必需 warmup

- 状态：open
- [实锤，高] `command_run()` 对 `MODEL_SWITCH` 明确排除 warmup 分支；W4 每轮只执行
  `W1(PROCESS_COLD) -> W2(CONTEXT_WARM) -> W1(CONTEXT_WARM)` 三个测量样本，B→A
  两个热态组不会产生任何 `isWarmup=true` 样本：
  `tools/performance-harness/localdream_perf_harness.py:251-286,899-917`。
- [实锤，高] `report_gate()` 对每个 `CONTEXT_WARM` GroupKey 强制恰有 5 条 warmup；其后
  才按至少 30 条测量样本判断。因 W4 的热态组被前述分支排除，重复 W4 也固定得到
  `INVALID_WARMUP_COUNT:0!=5`，无法形成 `TARGET_VALIDATED` 或 `FINAL_VALIDATED`：
  `tools/performance-harness/localdream_perf_models.py:245-258`。
- [实锤，高] 当前 W4 回归只断言一次操作的 A→B→A 冷/热标签与 lifecycle 调用，未覆盖
  重复执行后的热态组能通过 warmup 门禁；完整 Python harness 47 项通过不能证明该可达性：
  `tools/performance-harness/tests/test_harness.py:325-372`。
- [推断，高] 这使规格要求的 W4 可审核进程冷启与 A→B→A 无法进入预期的分层报告流程，
  违反“热态组必须先有且仅有 5 条 warmup”的合同：
  `docs/specs/oneplus13-performance-acceptance-spec.md:39-46`。
- 处置：回到 04。为 W4 设计不破坏被测 A→B→A 序列的 warmup/统计方案（或将切换操作
  与基线性能统计明确拆分），并新增正向回归证明重复 W4 后每个热态统计组恰有 5 条
  warmup 且可达到相应层级；保留少一条、多一条和缺 lifecycle 的拒绝回归。

## 当前阶段结论（2026-07-30，CR-33）

未通过。CR-33 是未处置 P1；本轮 `previous_delta=[]`，按独立首轮评审约束只更新评审
产物，不修改业务代码。控制器应回到 `04-implementation` 修复并补回归，再重新发起独立
05 评审。目标机性能、可靠性和热稳定性结论仍属于 `07-business-e2e`，不由本 finding 替代。

## 独立复核（2026-07-30，CR-32 导入信任链复开）

### CR-32 — P1：资格导入仍可用调用方自洽 JSON 伪造，`qualifications.write` 不是充分修复

- 状态：open
- [实锤，高] Compose 页面无独立授权、受控来源或签名检查，任何本地操作者都可以粘贴两段
  JSON 并直接调用 `importQualificationEvidence`：
  `app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt:101-104,235-270`。
  因此仅将 MCP 工具改为 `qualifications.write`，不能封闭本地 UI 路径。
- [实锤，高] `PerformancePresetQualificationEvidence.parse()` 只对调用方提交的原始 manifest
  字节计算 SHA-256，并校验候选自身的两个 GroupKey 字段；它没有解析或交叉验证 manifest
  的 `RuntimeProbe=VERIFIED`、验收层级、B0、质量、样本、热稳定、组报告或候选完整字段：
  `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidence.kt:53-112`。
  当前正向单测甚至把仅含 `{"runId":"target-run"}` 的 manifest 当作可导入资格的有效证据：
  `app/src/test/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidenceTest.kt:8-20`。
- [实锤，高] 成功解析后，store 只将候选自称字段与当前环境匹配便持久化其自称的
  `TARGET_VALIDATED`/`FINAL_VALIDATED`；这些行随即满足 DEFAULT/MODEL 自动绑定的资格门禁：
  `app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt:101-127`、
  `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetRepository.kt:136-171`。
- [推断，高] 这仍允许本地 UI 调用方，或持特权 scope 的调用方，提交自洽但伪造的
  candidates/manifest 并让未真实验收组合成为自动绑定；与 `TARGET_VALIDATED`/`FINAL_VALIDATED`
  必须有完整目标/深度验收证据的规格冲突：
  `docs/specs/oneplus13-performance-acceptance-spec.md:94-100,115-121`。
- 处置：回到 04。导入必须解析并规范化 harness 的 manifest/分组报告，交叉验证目标
  RuntimeProbe、层级、GroupKey、候选全字段、B0、质量、样本数与 FINAL 的可靠性/热稳定证据；
  UI 必须采用同等受控授权，或移除本地资格导入。新增极简 manifest、伪造候选、REJECTED、
  缺 B0/质量、FINAL 缺 100 次/热稳定和 UI 绕过负测。

## 当前阶段结论（2026-07-30，CR-32/CR-33）

未通过。CR-32 与 CR-33 均为未处置 P1；本轮 `previous_delta=[]`，按独立首轮评审约束仅更新
评审产物，不修改业务代码。控制器应回到 `04-implementation` 修复并补回归，再重新发起独立
05 评审。目标机性能、可靠性和热稳定性结论仍属于 `07-business-e2e`，不由本 findings 替代。

## 修复复核（2026-07-30，CR-32/CR-33）

### CR-33 — P1：W4 热态统计组缺少 warmup

- 状态：fixed
- [实锤，高] `MODEL_SWITCH` 现在先执行五个完整的 `A→B→A` 周期；每个周期都重新证明
  `PROCESS_COLD` lifecycle。前五个周期中 B 与尾部 A 分别写为其 `CONTEXT_WARM` GroupKey 的
  warmup，冷态 A 仍是有效的 `PROCESS_COLD` 测量，随后才执行一个完整测量周期：
  `tools/performance-harness/localdream_perf_harness.py:247-325`。
- [实锤，高] 回归将 W4 的六个完整周期、18 条样本和 B/A 两组各恰好 5 条 warmup 固定下来，
  并保持每轮 `A→B→A` 与 lifecycle 取证：
  `tools/performance-harness/tests/test_harness.py:325-390`。`report_gate()` 继续排除 warmup，
  因而不会把预热计入统计或可靠性：
  `tools/performance-harness/localdream_perf_models.py:245-258`。
- 处置：已修复；未满足后续 30/100 样本或热稳定门槛时仍 fail-closed，不形成目标机性能通过。

### CR-32 — P1：资格导入缺不可伪造的来源证明

- 状态：fixed
- [实锤，高] 人工决定本版本不引入签名服务或外部密钥托管，且禁止 HTTP/MCP 客户端和普通 UI 将 JSON 提升为资格：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:184-186`。
- [实锤，高] 已移除 `presets.import_qualification_evidence`、`qualifications.write` 与 MCP 到 Room 的资格写入路径；该工具以任意 scope 调用均为 `TOOL_NOT_FOUND`：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt`、`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt`。
- [实锤，高] harness candidates 保留为审计工件，普通预设 CRUD/显式探索不受影响；自动绑定仍要求已存在的精确活跃资格，因而在没有未来本机受控采集链路前保持 fail-closed。
- 处置：固定为移除外部提升边界，不以字段校验冒充来源验真。未来本机受控资格采集将作为独立需求设计可信来源与写入权。

### 本轮验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 47 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios \
  --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false \
  :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationEvidenceTest \
  --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮 CR-32/CR-33 路径>
# 无输出
```

## 当前阶段结论（修复复核）

未通过，需人工决策。CR-33 已 fixed；CR-32 仍是未处置 P1，因为不存在已批准的、可验证的
资格工件签发根。目标机性能、可靠性和热稳定性结论仍属于 `07-business-e2e`，不由本阶段替代。

## CR-32 人工决策后的独立复核（2026-07-30）

### CR-32 — P1：资格工件可由调用方自洽 JSON 伪造

- 状态：fixed
- [实锤，高] 人工决策没有以字段校验替代来源验真：当前版本不引入云签名服务或外部密钥托管，改为完全取消外部资格提升面。HTTP/MCP 客户端和普通 UI 不得把 `candidates`/`manifest` JSON 写入 Room 或提升为 `TARGET_VALIDATED`/`FINAL_VALIDATED`：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:184-186`、`docs/specs/oneplus13-performance-acceptance-spec.md:94-102`。
- [实锤，高] 重新从当前工具目录取证，`McpToolRegistry.definitions` 不包含 `presets.import_qualification_evidence` 或任何 `qualifications.write` 工具；对该名称传入任意 scope 均返回 `TOOL_NOT_FOUND`：`app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt:98-149`、`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:165-177`。
- [实锤，高] 普通 UI 的预设编辑器只暴露 name、selector、configJson 与可选 model binding；仪器测试确认页面不存在“导入验收资格”入口：`app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt:58-297`、`app/src/androidTest/java/io/github/xororz/localdream/ui/PerformancePresetScreenInstrumentedTest.kt:87-93`。
- [实锤，高] `AndroidMcpPresetStore` 的公开 MCP/普通 UI 操作只委托预设 CRUD、绑定和普通 envelope 导入；资格 Room adapter 为文件私有实现，当前生产源码没有调用其 `save`/`saveAllAtomically`，候选解析器也没有生产调用点。因此候选 JSON 只能作为 harness 审计格式，不能到达资格持久化或自动绑定：`app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt:66-130,301-339`、`rg -n 'qualificationStore\\.save|\\.save\\(qualification|saveAllAtomically|PerformancePresetQualificationEvidence\\.parse' app/src/main app/src/test app/src/androidTest --glob '*.kt'`。
- [实锤，高] 自动 DEFAULT/MODEL binding 仍需已存在且精确匹配的活跃资格；当前版本没有外部可写入路径时，该门禁保持 fail-closed，显式选择预设仍可作为探索运行：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualification.kt:72-86`、`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetRepository.kt:136-171`。
- 处置：按人工批准的最小安全边界移除外部提升路径，而非伪造签发根。未来 App 本机受控采集链路如需写入资格，必须以独立需求确定可信来源、签发和写入权；在此之前不得重新暴露任何 JSON 导入提升入口。

### CR-34 — P3：旧资格解析与私有写入适配器仍保留为不可达代码

- 状态：deferred
- [实锤，高] `PerformancePresetQualificationEvidence.parse` 和 `RoomPerformancePresetQualificationStore.saveAllAtomically` 当前没有生产调用点，且后者为文件私有；因此它们不能被 HTTP/MCP/普通 UI 用于提升资格：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualificationEvidence.kt:50-274`、`app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt:301-339`。
- [推断，中] 保留这组旧解析/写入代码会增加未来误接入、再把字段一致性校验误当可信签发的维护风险，但没有当前可达攻击路径，故不阻塞 CR-32 关闭。
- 处置：随未来“App 本机受控资格采集”独立需求删除或以明确的可信签发实现替换；届时不得直接复用当前 JSON 解析结果作为写入依据。

[业务解读] 性能预设可继续让用户显式试用；但“自动成为默认/模型绑定”的资格意味着该组合已经通过目标机验收。当前版本宁可不自动推广，也不允许客户端提交一份看似完整的报告把未验证组合升级为默认。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 47 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationEvidenceTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.mcp.McpGenerationGatewayTest \
  --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  app/src/main/java/io/github/xororz/localdream/mcp/McpToolRegistry.kt \
  app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt \
  app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt \
  app/src/main/java/io/github/xororz/localdream/data/PerformancePresetQualification.kt \
  app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt \
  app/src/androidTest/java/io/github/xororz/localdream/ui/PerformancePresetScreenInstrumentedTest.kt \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（CR-32 人工决策后）

通过。CR-32 已按 `feedback.md` 的安全边界 fixed；本次独立复核未发现新的 P0/P1/P2。CR-34 是不阻塞的 P3 延后项。真实目标机性能、可靠性和热稳定性仍不由本阶段结论替代，属于 `07-business-e2e`。

## 独立复核（2026-07-30，CR-35 逐操作 RuntimeProbe 绑定）

### CR-35 — P1：跨模型运行时指纹错绑

- 状态：fixed
- [实锤，高] 预检 probe 现在只用于启动前设备与运行时门禁。每个真实 operation 完成后，`observe_completed_execution` 都从已认证 `/health` 读取新的 `RuntimeProbe`，并用该 operation 实际模型的 `assetSha256` 校验 `contextFingerprint`；不匹配或非 VERIFIED 均抛出错误并停止：`tools/performance-harness/localdream_perf_harness.py:246-251`。
- [实锤，高] 每条样本以此次观察到的 fingerprint 生成 `GroupKey`。W4 的 A→B→A 三步分别按实际 W1/W2 模型取证，预热样本同样取各自 probe；因此不能再用启动时单一 probe 混合归档不同模型：`tools/performance-harness/localdream_perf_harness.py:275-325`。
- [实锤，高] 报告按完整 `GroupKey` 分组，并从同 fingerprint 的 observed probe 取证；新 RunManifest 同时保留 preflight 与去重 observed probes，缺失对应 probe 即退化为 `UNAVAILABLE`，不会放行报告：`tools/performance-harness/localdream_perf_harness.py:1209-1216,1229-1258,1330-1338`。
- [实锤，高] 回归覆盖正常 context、错误 context、REJECTED probe，以及 observed probes 到 group artifacts 的绑定：`tools/performance-harness/tests/test_harness.py:303-343`。
- 处置：04 已修复；独立复核未发现可使 CR-35 重开的 P0/P1/P2。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 50 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false \
  :app:testDebugUnitTest \
  --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest \
  --tests io.github.xororz.localdream.mcp.McpAuthorizationTest \
  --tests io.github.xororz.localdream.data.PerformancePresetQualificationTest \
  --tests io.github.xororz.localdream.remote.RemotePresetExecutionTest \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <当前差异，排除既有 QAIRT_NOTICE.txt 尾随空白与 app/src/main/cpp/3rdparty/MNN 符号链接异常>
# 无输出
```

## 当前阶段结论（CR-35 修复复核）

通过。CR-35 已 fixed；当前独立复核未发现新的 P0/P1/P2。CR-34 保持不阻塞的 P3 deferred。PJZ110 的 VERIFIED RuntimeProbe、W1-W7、B0、质量、100 次可靠性、30/60 分钟热稳定性与性能阈值实测仍属于 `07-business-e2e`；本阶段主机/构建验证不构成该结论。

## 独立复核（2026-07-30，报告指标与 SSE session 生命周期）

### CR-36 — P1：最终报告缺少主性能/资源指标时仍可能通过

- 状态：open
- [实锤，高] 规格与计划均要求 UNet、端到端、W5 持续吞吐、峰值 PSS/RSS、温度及 thermal throttling 为主指标；缺任一项必须输出 `MISSING_METRIC:<name>` 且不得通过：`docs/specs/oneplus13-performance-acceptance-spec.md:42-48`、`docs/plans/oneplus13-performance-acceptance-plan.md:41-43`。
- [实锤，高] 当前样本只写入 endpoint 与 outputBytes 到 `stageMetrics`；`report_gate()` 未验证 UNet、W5 吞吐或主指标完整性，因此 100 条其余字段齐全的样本可返回 `ACCEPTED_FOR_ONEPLUS13`：`tools/performance-harness/localdream_perf_harness.py:936-951`、`tools/performance-harness/localdream_perf_models.py:205-268`。
- [实锤，高] 热稳定判定只检查采样字段是否存在、thermalStatus 小于 3 与最后四分位吞吐降幅；不会拒绝持续 swap、PSS 增长/泄漏或卸载后的资源未回稳：`tools/performance-harness/localdream_perf_harness.py:1065-1105`。这与人工确认的“无持续 swap/资源泄漏，卸载后资源回稳”最终验收条件不一致：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:8-10`。
- 处置：回到 04。采集并强制校验 UNet、W5 吞吐、PSS/RSS、swap、泄漏与释放后回稳指标；缺失时输出明确 `MISSING_METRIC:<name>` 并拒绝 TARGET/FINAL，新增主指标缺失、持续 swap、内存增长和释放未回稳的正反回归。

### CR-37 — P1：活跃 SSE 订阅不会续租 MCP session

- 状态：open
- [实锤，高] SSE 循环在每次 poll 前以 `sessions.isActive()` 检查 session；该方法只读取 `lastActivityAt` 且明确不刷新 idle timeout：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:478-502`、`app/src/main/java/io/github/xororz/localdream/mcp/McpSessionRegistry.kt:79-93`。
- [实锤，高] `Subscription.poll()` 只更新 SSE replay store 的 `lastAccessAt`，不更新 MCP registry 的 `lastActivityAt`：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:247-257`。因此 SSE 建立后若没有普通 RPC 超过 15 分钟，下一 heartbeat 会关闭 stream，后续 progress/task 不能送达该活跃订阅。
- [实锤，高] 当前回归只验证 `McpSseEventStore` 在 replay idle 后保留活跃订阅，未覆盖 `McpHttpServer` 与 `McpSessionRegistry` 的联动续租：`app/src/test/java/io/github/xororz/localdream/mcp/McpTransportGuardsTest.kt:102-117`。
- 处置：回到 04。为通过既有所有权校验的活动 SSE 增加 registry session touch/lease（或等价续租），并增加可控时钟集成回归，证明 idle 超过 15 分钟后下一 task/progress 仍可投递。

## 当前阶段结论（CR-36/CR-37）

未通过。CR-36、CR-37 均是未处置 P1，且没有人工风险接受记录。本轮 `previous_delta=[]`，按独立首轮评审约束只更新评审产物，不修改业务代码。控制器应回到 `04-implementation` 修复并补回归，再重新发起独立 `05-change-review`。这两个 finding 不改变 07 的目标机真实性边界：PJZ110 性能、可靠性和热稳定性结论仍须由目标机完整工件形成。

## 独立复核（2026-07-30，CR-36/CR-37 修复后）

### CR-36 — P1：最终报告主指标与资源稳定性错误通过

- 状态：fixed
- [实锤，高] `TARGET_VALIDATED` 和 `FINAL_VALIDATED` 现在均强制 `unetMs`、端到端时间、场景标识、W5 吞吐、PSS/RSS、swap、内存泄漏判定、基线及释放后 PSS/RSS；任何缺失均返回精确的 `MISSING_METRIC:<name>` 并拒绝结论：`tools/performance-harness/localdream_perf_models.py:205-331`。
- [实锤，高] 连续正 swap、受控泄漏判定为真、或释放后 PSS/RSS 超过基线分别返回 `SUSTAINED_SWAP`、`MEMORY_LEAK_DETECTED`、`RESOURCE_RELEASE_NOT_RECOVERED`；报告同时输出 UNet p50、W5 持续吞吐、PSS/RSS/swap 峰值和释放回稳状态，禁止零填充：`tools/performance-harness/localdream_perf_models.py:393-456`。
- [实锤，高] ADB 采集改为强制解析 RSS 与 swap PSS；OEM 未暴露 swap 时记录 collection error，而非填充 `0`：`tools/performance-harness/localdream_perf_harness.py:637-660`。运行样本已标注场景和端到端时间；native 尚未提供的 UNet、释放/泄漏证据会使目标/最终报告 fail-closed，而不会伪造性能通过：`tools/performance-harness/localdream_perf_harness.py:940-958`。
- [实锤，高] 回归覆盖主指标缺失、W5 吞吐缺失、持续 swap、内存泄漏、释放未回稳、指标摘要及 swap 计数器缺失：`tools/performance-harness/tests/test_harness.py:193-335`。
- 处置：fixed。真实目标机仍须提供所有指标后才可能进入目标/最终验收。

### CR-37 — P1：活跃 SSE 未续租 MCP session

- 状态：fixed
- [实锤，高] SSE 写循环每个 heartbeat/poll 周期调用 `renewLease()`；该操作原子重验 client、token generation、transport 和 idle 状态后才刷新活动时间，不能复活过期、撤销或跨 transport session：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:478-502`、`app/src/main/java/io/github/xororz/localdream/mcp/McpSessionRegistry.kt:95-118`。
- [实锤，高] 可控时钟回归证明在普通 RPC idle 窗口内续租后，超过原始 idle 窗口 session 仍活跃；token generation 不匹配不能续租：`app/src/test/java/io/github/xororz/localdream/mcp/McpSessionRegistryTest.kt:47-59`。
- 处置：fixed。任务/progress 投递仍只面向所有权、token generation 和 transport 匹配的 session。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 55 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false \
  :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpSessionRegistryTest \
  --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --ignore-submodules=all --check -- <本轮六个代码/测试文件>
# 无输出
```

## 当前阶段结论（CR-36/CR-37 修复复核）

通过。CR-36、CR-37 均已 fixed；本轮独立复核未发现新的 P0/P1/P2。目标机仍缺可审计的 UNet、W5、资源基线/释放、质量与长测输入时，报告将拒绝而不会形成性能结论；在 PJZ110 上取得完整原始工件属于 `07-business-e2e`，不由本阶段主机/构建验证替代。

## 独立复核（2026-07-30，CR-38 探索层门禁）

### CR-38 — P1：EXPLORATORY 错误复用 TARGET/FINAL 的统计样本量门槛

- 状态：fixed
- [实锤，高] 所有层级继续强制 `VERIFIED` 目标 RuntimeProbe、单一 `GroupKey`、至少一个正式样本、失败路径拒绝；热态组仍强制恰好 5 条 warmup：`tools/performance-harness/localdream_perf_models.py:205-250`。
- [实锤，高] `EXPLORATORY` 不再应用 TARGET/FINAL 的 5/30 正式样本与 100 次可靠性门槛；`TARGET_VALIDATED` 仍对热态组要求至少 30 个正式样本，`FINAL_VALIDATED` 仍额外要求 100 个：`tools/performance-harness/localdream_perf_models.py:251-269`。
- [实锤，高] 回归证明“5 条 warmup + 1 条正式热态样本”可完成探索、却仍因 `INSUFFICIENT_SAMPLES:1<30` 被目标验证拒绝；零正式样本、失败样本仍拒绝：`tools/performance-harness/tests/test_harness.py:289-343`。
- 处置：已 fixed。探索结果只用于候选筛选，不提升自动默认/模型绑定资格，也不构成目标机性能、可靠性或热稳定性通过。

## 独立复核（2026-07-30，warmup 物理顺序）

### CR-39 — P1：热态组的首个正式请求发生在必需 warmup 之前

- 状态：open
- [实锤，高] 对非 `MODEL_SWITCH` 的热态场景，循环先调用 `executor.execute()` 发送并观察第一个请求；仅在该请求完成、已取得 `GroupKey` 后，才调用 `_single_execution()` 发出五个 warmup，最后把先前请求写为 `is_warmup=false` 的正式样本：`tools/performance-harness/localdream_perf_harness.py:275-326`。
- [实锤，高] 热态识别覆盖 `OS_CACHE_WARM` 与 `CONTEXT_WARM`：`tools/performance-harness/localdream_perf_harness.py:988-989`。因此当前实现虽在工件中标注五条 warmup 和一条正式样本，却不能满足“热态组必须先有且仅有 5 条 warmup，再有至少 30 个有效样本”的顺序合同：`docs/specs/oneplus13-performance-acceptance-spec.md:42-46`。
- [实锤，高] 当前回归只验证请求总次数以及 `Sample.is_warmup` 标记序列，未验证物理请求顺序；它可在该错误顺序下通过：`tools/performance-harness/tests/test_harness.py:498-532`。
- [推断，高] 若首请求引入模型加载、缓存填充或后端初始化，其被记录为正式热态样本会污染探索与后续 TARGET/FINAL 统计，无法由事后标记修正。
- 处置：回到 04。对每个热态统计组，先执行并归档五个实际 warmup（每次均取 operation RuntimeProbe/资源采样），再执行首个正式请求；若 warmup 与正式请求的 RuntimeProbe 指纹不同，应按真实 `GroupKey` 重新分组或 fail-closed。补回归断言物理调用顺序，并覆盖 probe 在 warmup 与正式请求间变化的边界。

## 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 57 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_protocol.py
# 退出 0

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpSessionRegistryTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- tools/performance-harness/localdream_perf_models.py tools/performance-harness/tests/test_harness.py docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（CR-38/CR-39）

未通过。CR-38 已 fixed；CR-39 是未处置 P1。由于本轮输入 `previous_delta=[]`，独立首轮评审只更新评审产物，不修改业务代码。控制器应回到 `04-implementation` 修复热态物理 warmup 顺序、补定向回归后，再重新发起独立 05 评审。PJZ110 的性能、可靠性和热稳定性结论仍属于 `07-business-e2e`，不由本 finding 或本阶段验证替代。

## 独立复核（2026-07-30，CR-39 热态 warmup 物理顺序修复后）

### CR-39 — P1：热态组的首个正式请求发生在必需 warmup 之前

- 状态：fixed
- [实锤，高] 非 `MODEL_SWITCH` 的热态场景现先执行并记录 5 个物理 warmup，才调用场景的正式 `executor.execute()`；W5 在其正式 W1/W2 sustained 请求前，分别先对 W1 与 W2 执行 5 次实际 warmup：`tools/performance-harness/localdream_perf_harness.py:314-332`。
- [实锤，高] 每次 warmup 与正式请求都在完成后单独读取 RuntimeProbe、构造自己的 GroupKey 并采集资源事实：`tools/performance-harness/localdream_perf_harness.py:247-252,256-291`。只有正式请求的 GroupKey 已具有恰好 5 个同组 warmup 才登记为已预热；探针在 warmup 与正式请求之间变化时，正式组保持 0 条 warmup，报告因 `INVALID_WARMUP_COUNT:0!=5` fail-closed：`tools/performance-harness/localdream_perf_harness.py:348-352`。
- [实锤，高] 回归以物理调用序号断言 W1 的请求顺序为 warmup 1-5、正式 6；W5 回归断言 W1×5、W2×5 均发生在 W5 的正式请求前：`tools/performance-harness/tests/test_harness.py:498-541,666-703`。
- [实锤，高] 新增 probe 变化回归：5 个 warmup 使用 fingerprint A、正式请求使用 fingerprint B 时，两个 GroupKey 分离，B 组没有 warmup 且报告拒绝：`tools/performance-harness/tests/test_harness.py:543-591`。
- 处置：fixed。该实现不把 warmup 后 RuntimeProbe 变化重新标记为已预热，避免错误形成 TARGET/FINAL 结论。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 58 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/tests/test_harness.py
# exit 0

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- \
  tools/performance-harness/localdream_perf_harness.py \
  tools/performance-harness/tests/test_harness.py \
  docs/reviews/oneplus13-performance-acceptance-code-review.md
# 无输出
```

## 当前阶段结论（CR-39 修复复核）

通过。CR-39 已 fixed；本轮独立复核未发现新的 P0/P1/P2。`app/src/main/assets/legal/QAIRT_NOTICE.txt` 的既有尾随空白和 `app/src/main/cpp/3rdparty/MNN` 符号链接异常未修改、未纳入本 finding。PJZ110/SM8750 的 W1-W7、B0、质量、每候选 100 次可靠性、30/60 分钟热稳定性和性能阈值仍属于 `07-business-e2e`，本阶段主机回归不形成目标设备性能结论。

## 独立复核（2026-07-30，CR-40 传输失败归档与 B0 来源链）

### CR-40 — P1：可恢复传输失败绕过 harness 失败归档

- 状态：fixed
- [实锤，高] `UrlLibTransport` 已将 `URLError`、`OSError` 和 `HTTPException` 在普通 HTTP、SSE 建连与 SSE 读取三条路径统一归一为不回显异常细节的 `ProtocolExecutionError`：`tools/performance-harness/localdream_perf_protocol.py:92-145,168-172`。
- [实锤，高] `command_run()` 只捕获该领域错误及输入校验错误，随后写入已有样本、遥测、observed RuntimeProbe、分组工件和拒绝报告，并返回退出码 2；未吞没 `KeyboardInterrupt`、`SystemExit` 或未预期的编程错误：`tools/performance-harness/localdream_perf_harness.py:353-364,1153-1193`。
- [实锤，高] 回归覆盖 `RemoteDisconnected`、reset、timeout 的脱敏，以及中途失败后 partial samples、observed probes 与 group artifacts 的留存：`tools/performance-harness/tests/test_protocol_parity.py:121-153`、`tools/performance-harness/tests/test_harness.py:939-985`。
- 处置：保持 fixed。独立复核未发现可使传输错误再次穿透为未归档栈退出的 P0/P1/P2。

### CR-41 — P1：`capture-baseline` 信任可替换静态 RuntimeProbe，B0 未绑定实时 PJZ110 运行时与设备身份

- 状态：open
- [实锤，高] `capture-baseline` 只读取 `--runtime-probe-file` 并由其计算 `runtimeFingerprint`；其 CLI 没有认证 `--base-url`/token、`--adb-serial` 或应用包名参数，因而不会读取实时 `/health` 或核验实际设备的 PJZ110/SM8750/ABI 身份：`tools/performance-harness/localdream_perf_harness.py:141-188,1454-1461`。
- [实锤，高] 同一 harness 的真实运行路径已经把这两类事实作为强制门禁：请求前以 ADB 与 probe 做身份硬比较，且每个操作后从认证 `/health` 获取并校验实际模型的 RuntimeProbe：`tools/performance-harness/localdream_perf_harness.py:220-229,247-252,525-554,688-705`。因此 capture 与消费路径的信任模型不一致。
- [实锤，高] 现有 B0 采集回归只构造静态 probe 文件并断言能够产出，未覆盖陈旧/伪造 probe、ADB 身份失配或实时 probe 与冻结模型指纹不一致的拒绝路径：`tools/performance-harness/tests/test_harness.py:124-188`。
- [实锤，高] 规格要求 B0 必须在已验证目标机上生成，计划明确要求拒绝来自手写、其他模型、其他 runtime 或其他 GroupKey 的 B0/质量；当前实现不能证明该来源链：`docs/specs/oneplus13-performance-acceptance-spec.md:54-59,127-129`、`docs/plans/oneplus13-performance-acceptance-plan.md:100-110,190-198`。
- [推断，高] 只要静态文件中的 fingerprint 恰好匹配，后续 `TARGET_VALIDATED`/`FINAL_VALIDATED` run 就可消费这份与真实设备状态无来源绑定的 B0，破坏目标机验收的可审计性。
- 处置：回到 04。`capture-baseline` 必须强制使用安全 token 文件或环境变量、认证 `/health` 的实时 RuntimeProbe、显式 ADB serial/包名和同一设备身份校验；将来源摘要写入 B0 工件，并补“静态 probe 单独不可产出”“ADB identity 不匹配拒绝”“实时 probe/模型 fingerprint 不匹配拒绝”的回归。

### 评审中未成立的候选问题

- `W3` 的下载是否计入 elapsed 与旧文字表述存在张力，但当前规格只对 W6/W7 明确下载完整性，不能据此建立 P0/P1/P2；保留为未来规格变更时的审查点，不作为本轮 finding。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 61 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_protocol.py
# exit 0

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.data.PerformancePresetQualificationTest --tests io.github.xororz.localdream.remote.RemotePresetExecutionTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- . ':(exclude)app/src/main/assets/legal/QAIRT_NOTICE.txt' ':(exclude)app/src/main/cpp/3rdparty/**'
# 无输出
```

## 当前阶段结论（CR-41）

未通过。CR-41 是未处置 P1，且没有人工风险接受记录。由于本轮输入 `previous_delta=[]`，独立首轮评审仅更新本评审产物，不修改业务代码；控制器应回到 `04-implementation` 修复 B0 的实时目标设备来源链、补强制拒绝回归后，再重新执行独立 `05-change-review`。这不改变边界：主机、PJZ110 非完整运行或 Redmi K30 的结果均不能替代 07 的一加13完整性能、可靠性和热稳定性验收。

## 本次独立复核（2026-07-30，CR-41 复现确认）

### CR-41 — P1：`capture-baseline` 的 B0 来源链不可审计

- 状态：open
- [实锤，高] 重新读取当前代码确认：`capture-baseline` 只从可替换的 `--runtime-probe-file` 读取 RuntimeProbe，并据其静态内容生成 `runtimeFingerprint`；它既不接收安全 token 输入，也不接收 `--base-url`、`--adb-serial` 和包名，故不能认证读取实时 `/health` 或核验实际 PJZ110 身份：`tools/performance-harness/localdream_perf_harness.py:141-188,1454-1461`。
- [实锤，高] 真实 `run` 路径的信任模型更严格：运行前以 ADB 对 RuntimeProbe 做目标身份绑定，operation 完成后再从认证 `/health` 获取 RuntimeProbe 并校验实际模型 context：`tools/performance-harness/localdream_perf_harness.py:525-554,688-705`。B0 采集遗漏这些绑定，允许手写但字段完整的 probe 文件产生后续可消费的 B0。
- [实锤，高] 当前 `CaptureEvidenceCommandTest` 的成功路径只提供静态 probe 文件；其拒绝用例只覆盖 `UNAVAILABLE`，没有静态 VERIFIED probe、ADB 不匹配或实时模型 context 不匹配的拒绝测试：`tools/performance-harness/tests/test_harness.py:124-188`。
- [实锤，高] 这违反 B0 必须在已验证目标机上生成、并拒绝其他 runtime/GroupKey 来源的规格和计划：`docs/specs/oneplus13-performance-acceptance-spec.md:54-59,127-129`；`docs/plans/oneplus13-performance-acceptance-plan.md:100-110,190-198`。
- 处置：回到 04。让 `capture-baseline` 强制使用 token-file 或环境变量获取认证 `/health` 的实时 RuntimeProbe，并要求显式 ADB serial/包名与该 probe 的 PJZ110/SM8750/ABI 身份一致；把实时 probe 和设备来源摘要写入 B0，同时新增“仅静态 probe 不可产出”“ADB 身份不匹配拒绝”“实时 context 与冻结模型摘要不匹配拒绝”的回归。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 61 tests, OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_protocol.py
# exit 0

git -c core.fsmonitor=false -c submodule.recurse=false diff --ignore-submodules=all --check -- . ':(exclude)app/src/main/assets/legal/QAIRT_NOTICE.txt' ':(exclude)app/src/main/cpp/3rdparty/**'
# 无输出
```

## 当前阶段结论（本次独立复核）

未通过。CR-41 为无人工风险接受的 P1；本轮无业务代码改动，只有评审产物更新。Python 回归与场景/语法/限定 diff 检查通过并不能证明 B0 的目标机来源可信，故必须回到 04 修复后再进入 05。PJZ110 的完整 W1-W7、B0、质量、每候选 100 次可靠性、30/60 分钟热稳定性及性能阈值验收仍属于 `07-business-e2e`。

## 严格独立复核（2026-07-30，MCP mutation 幂等）

### CR-42 — P1：幂等记录全局容量淘汰后会重新执行 mutation

- 状态：open
- [实锤，高] `McpMutationReplayStore` 将所有 client/token/tool 的幂等记录置于同一个最多 256 条的 `LinkedHashMap`；第 257 条 mutation 会删除最早记录。被删除 key 随后在 `replay()` 中变为 miss，`execute()` 直接调用 mutation operation 并写入新条目：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:64-105,113-115`。
- [实锤，高] `McpHttpServer` 对非只读、非 dry-run 调用先尝试 `replay()`，miss 后直接委托 `mutationReplays.execute()`；没有“淘汰后不得安全重试”的拒绝分支：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:316-337`。
- [实锤，高] 计划要求 mutation 以规范化参数幂等键重放，并以测试覆盖幂等重放：`docs/plans/oneplus13-performance-acceptance-plan.md:128-129`。现有 `McpTransportGuardsTest` 仅覆盖 RPC、SSE 限流与 replay，未覆盖 257+ mutation 驱逐后旧 key 重试：`app/src/test/java/io/github/xororz/localdream/mcp/McpTransportGuardsTest.kt:8-117`。
- [推断，高] 同一认证客户端持续执行 257 个 mutation 后，网络重试早期的相同 `presets.create`、`prompts.create` 或其他写操作会再次触发侧效；这与 mutation 重试不得重复侧效的合同不相容。
- 处置：回到 04。幂等记录应在可声明的重试窗口内按 client/token 分区保留，或持久化；窗口外必须明确拒绝“不安全重试”，不得重新执行。新增超过 256 条后的相同 key 回归，断言不会产生第二次写入。

## 当前阶段结论（CR-41 / CR-42）

未通过。CR-41 与 CR-42 都是无人工风险接受的 P1；本轮只修改评审产物，未修改业务代码。控制器应回到 `04-implementation` 同时修复 B0 实时目标来源链和 mutation 幂等淘汰重放，再重新进入独立 05 评审。

## 独立复核（2026-07-30，CR-41 / CR-42 修复后）

### CR-41 — P1：B0 的实时 RuntimeProbe 与 PJZ110 ADB 来源链

- 状态：fixed
- [实锤，高] `capture-baseline` 不再接受静态 probe 文件；命令行强制提供 `--base-url`、`--adb-serial`、`--app-package` 与互斥且必填的 token-file/token-env。它以认证 `/health` 取得实时 probe，并要求选中场景只对应一个模型 Context：`tools/performance-harness/localdream_perf_harness.py:141-167,1518-1530`。
- [实锤，高] 实时 probe 同时受 `VERIFIED` 目标机及冻结模型 context 约束；随后 `AdbResourceSampler.verify_target_identity` 比对 PJZ110/SM8750/ABI 并记录实际包路径摘要。B0 provenance 写入 RuntimeProbe、其摘要和 ADB target：`tools/performance-harness/localdream_perf_harness.py:160-167,197-210,550-579,745-775`。
- [实锤，高] B0 消费侧再次验证 provenance、probe 摘要、模型 context、包名、设备型号/SoC/ABI 和包路径摘要，且每个条目的模型摘要必须等于该实时 context：`tools/performance-harness/localdream_perf_harness.py:632-704`。
- [实锤，高] 定向回归覆盖未验证实时 probe 与实时模型 context 不匹配时拒绝且不写 B0，以及通过路径的来源绑定：`tools/performance-harness/tests/test_harness.py`（`CaptureEvidenceCommandTest`）；完整 Python harness 62/62 通过。
- 处置：保持 fixed。静态 `--runtime-probe-file` 仅仍用于 `run` 的预检和拒绝工件；不能授权 `capture-baseline`。

### CR-42 — P1：MCP mutation 在旧缓存容量外重复执行

- 状态：fixed
- [实锤，高] 旧的全局 256 条驱逐已移除。重放记录按 `clientId + tokenGeneration + tool + idempotencyKey` 分区，并以 app-private `SharedPreferences` 持久化；服务重建后仍可恢复：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:71-183`、`app/src/main/java/io/github/xororz/localdream/mcp/McpMutationReplayPersistence.kt:6-44`、`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:79-115`。
- [实锤，高] 记录先持久化 `IN_FLIGHT` 再执行领域 mutation；15 分钟安全重试窗口内返回副本，窗口外明确拒绝 `IDEMPOTENCY_RETRY_WINDOW_EXPIRED`，24 小时 tombstone 到期才移除。持久化提交失败也拒绝写操作，避免写后丢失重放记录导致二次副作用：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:101-159`。
- [实锤，高] 回归覆盖 257 条填充后原 key 返回首次结果且仅执行 258 次、窗口外拒绝、跨服务重建恢复和 client/token 分区：`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:11-115`。`McpHttpServer` 对非只读 mutation 始终经该 replay store 路由：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:305-342`。
- 处置：保持 fixed。未发现缓存淘汰后可以再次触发相同 mutation 的 P0/P1/P2 路径。

## 验证与结论（本轮）

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 62 tests ... OK

./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.mcp.McpAuthorizationTest --tests io.github.xororz.localdream.mcp.McpTransportGuardsTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- . ':(exclude)app/src/main/assets/legal/QAIRT_NOTICE.txt' ':(exclude)app/src/main/cpp/3rdparty/**'
# 无输出
```

通过。CR-41、CR-42 均已按代码、规格和计划的 fail-closed 合同修复并有定向回归。本轮未发现未处置的 P0/P1/P2。PJZ110 的完整 W1-W7、B0/质量冻结、100 次可靠性、30/60 分钟热稳定和性能阈值仍属于 `07-business-e2e`，不能由本阶段的源码/主机验证替代。

## 交叉独立评审更正（2026-07-30）

> 下述结论取代紧邻上一节的“通过”结论。上一节确认的是 CR-41/CR-42 的原始复现路径已消除；交叉评审发现两个由本次修复引入或未覆盖的有效风险，不能以原 finding 已 fixed 宣称本阶段通过。

### CR-43 — P1：B0 的 health 与 ADB 并非同一设备/安装实例的可证明来源

- 状态：open
- [实锤，高] `capture-baseline` 将任意 `--base-url` 直接交给 `UrlLibTransport`，认证 `/health` 只证明该 URL 的 RuntimeProbe；它没有强制该 HTTP 端点经 `adb -s <serial> forward` 到采样设备，也没有取得可与 ADB 交叉验证的安装实例证明：`tools/performance-harness/localdream_perf_harness.py:158-167,550-567`、`tools/performance-harness/localdream_perf_protocol.py:76-93`。
- [实锤，高] ADB 侧只比对 model/SoC/ABI、非空 hardware serial 和包路径存在性；它不比对 health 来源、hardware serial 或安装工件摘要。后续 `command_run` 只验证 B0 内部 provenance 自洽，亦未将 capture 时 `adbTarget` 与本次 `device_identity` 精确匹配：`tools/performance-harness/localdream_perf_harness.py:245-254,628-701,745-769`。
- [推断，高] 若 `--base-url` 指向另一台同为 PJZ110/SM8750 的设备，而 ADB 指向目标机，现有校验仍能写出并消费自洽 B0；这不满足反馈所要求的“同一 PJZ110/SM8750 ADB 身份”及规格的目标身份可追溯：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:210`、`docs/specs/oneplus13-performance-acceptance-spec.md:44-46,54-59`。
- 处置：回到 04。强制使用目标 serial 的 ADB forward，或由 health 提供可由 ADB 核验的设备/安装实例证明；在 capture 与 consume 两端逐项比对 hardware serial、APK/构建摘要。新增错 serial、错包摘要、非 forward URL 与 capture 后换机的拒绝回归，且均不得写 B0/目标验收工件。

### CR-44 — P2：持久 mutation replay ledger 无资源上界且每次全量扫描

- 状态：open
- [实锤，高] CR-42 移除 256 条驱逐后，`entries` 成为无容量限制的 `LinkedHashMap`，记录在 24 小时 tombstone 期内只按时间清理：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:91,98,109,152-156,199-201`。
- [实锤，高] Android 持久层的 `pruneExpired()` 在每次 replay/execute 前调用 `SharedPreferences.all` 全量复制并逐条 JSON 解析：`app/src/main/java/io/github/xororz/localdream/mcp/McpMutationReplayPersistence.kt:28-35`。
- [推断，高] 已授权 client 在既有 60 RPC/min 限流内持续发送不同幂等键，24 小时可累计 86,400 条记录并令每次写操作做 O(n) 扫描；这会使移动端 listener 逐渐退化。类注释中的 “bounded replay state” 已不符合实现。
- 处置：回到 04。改为含 expiry index 的容量受控私有 durable ledger；容量满时以 `IDEMPOTENCY_CAPACITY_EXHAUSTED` fail-closed 拒绝新 mutation，绝不淘汰仍处安全窗口内的 tombstone。新增容量耗尽、过期清理、重建后容量拒绝和“不产生第二次副作用”回归。

## 本轮最终结论

未通过。CR-41/CR-42 的原始问题可标记 fixed（完整 Python harness 62/62、定向 `McpAuthorizationTest`/`McpTransportGuardsTest` 和 APK 构建均通过），但 CR-43 是未处置 P1、CR-44 是未处置 P2。二者都无人工风险接受记录，必须回到 04 修复；本轮 remaining delta 的数量没有减少，按阶段规则不再继续迭代。

## 独立复核闭环（2026-07-30，CR-43 / CR-44 修复后）

### CR-43 — P1：B0 health、Wi-Fi ADB 与安装实例来源链

- 状态：fixed
- [实锤，高] `capture-baseline` 只接受与 Wi-Fi ADB serial 主机一致的无 path HTTP origin；`/health` 必须同时返回已认证 RuntimeProbe 和安装实例摘要。该摘要与 `adb shell cmd package path` 的私有安装路径摘要、包名精确一致，否则不写 B0：`tools/performance-harness/localdream_perf_harness.py:141-215,560-605,745-785`；`app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt:119-153`。
- [实锤，高] B0 schema 升级为 v2，provenance 固化 health endpoint、health installation、完整 ADB target 与 RuntimeProbe；`run` 在首次设备请求前重新获取 ADB identity，并对 base URL、serial、hardware serial、包路径摘要与安装摘要逐项精确匹配，任一变化 fail-closed：`tools/performance-harness/localdream_perf_harness.py:235-258,646-727`。
- [实锤，高] 回归覆盖未绑定 URL、health/ADB 安装摘要不一致、B0 消费时安装实例变化或 endpoint 变化，以及同一来源正向闭环：`tools/performance-harness/tests/test_harness.py` 的 `CaptureEvidenceCommandTest`；`python3 -m unittest tools/performance-harness/tests/test_harness.py -v` 为 44/44 OK。
- 处置：fixed。该绑定限定为当前已批准的 Wi-Fi PJZ110 目标入口；它不把任何主机或 Redmi K30 结果提升为一加13性能结论。

### CR-44 — P2：durable mutation replay ledger 的容量与 tombstone 安全

- 状态：fixed
- [实锤，高] `McpMutationReplayStore` 的内存与持久层均以 256 条为容量上限；新 key 在任何安全窗口记录存在时绝不驱逐既有 `IN_FLIGHT`、已结算记录或 tombstone，满额直接返回 `IDEMPOTENCY_LEDGER_FULL` 且不执行领域 mutation：`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:71-181`。
- [实锤，高] Android 私有持久层在同一临界区内先删除 24 小时外记录、后计数并 `commit` 新记录；重建后的 store 仍由持久层容量门禁保护。过期后才允许新 key 准入：`app/src/main/java/io/github/xororz/localdream/mcp/McpMutationReplayPersistence.kt:6-45`。
- [实锤，高] 回归覆盖满额拒绝（operation 不执行）、原 key 仍重放、过期清理后重新准入及 listener 重建后仍不越界：`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt:11-76,398-412`；定向 JVM 测试通过。
- 处置：fixed。容量是全局 durable ledger 上限，达到上限时有意拒绝新 mutation，以内存/存储可用性换取不重复副作用的 fail-closed 合同。

### 验证与结论

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
# Ran 64 tests ... OK

./gradlew --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun-tasks --tests io.github.xororz.localdream.mcp.McpAuthorizationTest
# BUILD SUCCESSFUL

./gradlew --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process :app:assembleDebug
# BUILD SUCCESSFUL

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <本轮修改文件>
# 无输出
```

通过。CR-43（P1）与 CR-44（P2）均已修复并完成定向验证；本轮未发现未处置 P0/P1/P2。PJZ110 的完整 W1-W7、B0/质量冻结、每候选 100 次可靠性、30/60 分钟热稳定与性能阈值仍属于 `07-business-e2e`，不由本阶段源码或主机验证替代。

## 独立复核（2026-07-30，前一执行器异常后的重新取证）

### CR-45 — P1：真实 W1–W6 样本未生产目标报告所必需的性能与资源指标

- 状态：open
- [实锤，高] `record_execution_sample()` 仅在每次请求完成后调用一次 `AdbResourceSampler.collect()`；该采样只有 PSS、RSS、swap、温度和 thermal status。`_sample_from_execution()` 只将 operation 与该单次采样写入 `resourceMetrics`，且没有把 native complete event 中的阶段数据转换为 `stageMetrics`：`tools/performance-harness/localdream_perf_harness.py:290-320,757-780,1022-1084`。
- [实锤，高] `report_gate()` 对 `TARGET_VALIDATED` 与 `FINAL_VALIDATED` 强制要求 `unetMs`、请求前的 `baselinePssKb`/`baselineRssKb`、释放后的 `releasePssKb`/`releaseRssKb`、`memoryLeakDetected`；W5 还强制 `w5ThroughputPerSecond`。缺任一字段都会形成 `MISSING_METRIC:*`，不会形成通过结论：`tools/performance-harness/localdream_perf_models.py:273-315,400-435`。
- [实锤，高] native complete event 当前明确把 `unet_ms`、`clip_ms`、`vae_decode_ms` 等标记为 `UNAVAILABLE`，Android `NativeBackendClient` 又只返回图片、MIME 与 seed；即使 native 已返回的 `generation_time_ms` 也没有进入 OpenAI 响应或 host harness：`app/src/main/cpp/src/main.cpp:490-515`、`app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt:74-91`、`app/src/main/java/io/github/xororz/localdream/openai/OpenAiImageModels.kt:30-34`。
- [实锤，高] W5 executor 真实发出的是 W1/W2 基线请求，harness 也按 `execution.scenario_id` 将样本归入 W1/W2，故不会在样本中保留 W5 的 sustained measurement identity 或计算 W5 吞吐：`tools/performance-harness/localdream_perf_executor.py:105-112`、`tools/performance-harness/localdream_perf_harness.py:299-304`、`tools/performance-harness/localdream_perf_models.py:290-294`。
- [实锤，高] 现有门禁测试以人工构造的 `Sample` 注入这些字段，W5 的 command-run 测试只验证预热请求顺序；未覆盖真实执行路径能生产一组可接受的 target/final 指标：`tools/performance-harness/tests/test_harness.py:273-321,739-775`。
- [推断，高] 当前实现会正确 fail-closed，但其真实目标机 run 必然带有 `MISSING_METRIC:unetMs` 等原因，无法完成规格/计划定义的性能、资源释放与 W5 吞吐判定；把端到端时间或单次请求后 PSS 冒充上述指标会削弱已批准的验收合同。
- 处置：需要人工确定指标采集架构后回到 04 实施：至少应定义 native 真实 UNet 计时如何以每次请求身份安全地传到 OpenAI/harness，定义请求前基线与“释放后”资源采样由何种受控生命周期动作产生，并明确 W5 两个基线请求如何归属于一个 sustained measurement。不得以伪造值、端到端时间替名或放宽 `report_gate()` 关闭本 finding。

### 复核结论

前一执行器的 `code 70` 是 controller containment cleanup 异常，不能作为业务代码评审通过或失败的证据：`docs/loop-records/oneplus13-performance-acceptance/records/05-change-review-029.json` 的 `executor.termination_reason=containment_cleanup_failed`。本次重新运行的 host harness、v4 场景校验、定向 JVM 和 APK 构建均通过，但只证明已有 fail-closed 门禁未回归，不能反证 CR-45。

本 finding 需要影响 native SSE、Android OpenAI 响应合同、host 样本模型以及受控设备生命周期的指标采集架构决策。没有该决定时，任何“修复”都会在关键性能数据上伪造语义或降低门禁，故本阶段暂停等待人工决定；不得把 PJZ110、主机或 Redmi K30 的已有结果表述为性能、可靠性或热稳定性通过。

## 独立复核闭环（2026-07-30，CR-45 修复后）

### 评审范围与方法

- [实锤，高] 本次不采信 04 的自评，重新比对当前差异、规格、计划、`04-implementation-046/047.json` 与人工已批准架构。CR-45 的合同是 native UNet 计时只能来自实际调用边界、资源必须有分组前 baseline/组内峰值/显式 unload 后 release，W5 必须保留持续测量身份：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`、`docs/specs/oneplus13-performance-acceptance-spec.md:44-48,63-69`、`docs/plans/oneplus13-performance-acceptance-plan.md:81-82,100-111`。

### CR-45 — P1：真实性能与资源生命周期指标缺失

- 状态：partial。资源生命周期已收口；严格交叉评审发现 UNet 计时边界与 W5 归档身份仍有两个 P1，不能据 69 项回归判定完成。
- [实锤，高] `Pipeline::generate()` 在每个 `runUnetTiled`/UNet forward 周围累计 `unet_time_ms`，native complete event 的 `stage_metrics.unet_ms` 使用该值，未把请求端到端时间改名：`app/src/main/cpp/src/Pipeline.hpp:778-808,888-1080,1269-1280`、`app/src/main/cpp/src/main.cpp:488-506`。
- [实锤，高] Android 仅从 native complete SSE event 的正数 `stage_metrics.unet_ms` 构造 `NativeGenerationDiagnostics`，再以向后兼容、只读的 `vendor_diagnostics.unet_ms` 投影到 OpenAI URL/B64 响应；指标缺失或非正数不会被合成：`app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt:33-108`、`app/src/main/java/io/github/xororz/localdream/openai/OpenAiImageModels.kt:30-44`、`app/src/main/java/io/github/xororz/localdream/openai/OpenAiJson.kt:72-101`、`app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt:527-568`。
- [实锤，高] 执行器在每次物理 HTTP 请求前采集资源；harness 以 `GroupKey` 保存首次 baseline 与组内 PSS/RSS/swap 峰值，在整个 run 结束后显式 `adb am force-stop`、确认 PID 缺席，才写入 release 零值。任何采样失败、进程未退出、未回稳、持续 swap 或漏检结果都会让 `TARGET_VALIDATED`/`FINAL_VALIDATED` 保持拒绝：`tools/performance-harness/localdream_perf_executor.py:54-148`、`tools/performance-harness/localdream_perf_harness.py:282-340,769-849,1185-1236`、`tools/performance-harness/localdream_perf_models.py:273-315`。
- [实锤，高] W5 的请求仍是冻结 W1/W2，样本的 stage 字段写入 `scenarioId=W5` 与 `variantId=W1|W2`；但当前 GroupKey 仍从 W1/W2 的 `scenario_id` 构造：`tools/performance-harness/localdream_perf_executor.py:111-148`、`tools/performance-harness/localdream_perf_harness.py:304-312,1147-1166`。
- [实锤，高] 正反回归覆盖：请求前采样与 W5 保留、unload 前必须确认进程缺席、资源生命周期缺失不得伪造 leak/release、缺 `unetMs`/资源/W5 吞吐拒绝：`tools/performance-harness/tests/test_device_executor.py:91-116`、`tools/performance-harness/tests/test_harness.py`（`AdbTargetIdentityTest`、`ResourceLifecycleTest`、`ReportGateTest`）、`tools/performance-harness/tests/test_protocol_parity.py:329-342`。
- 处置：资源 baseline/peak/unload/release 部分保持 fixed；下列 CR-45.1 和 CR-45.2 必须回到 04 修复。

### CR-45.1 — P1：`unet_ms` 的计时边界包含非 UNet CPU 工作

- 状态：open。
- [实锤，高] 非 tiled 路径在 `unet_start` 后执行 `runUnetStep()` 之外的 tensor/CFG 合成工作才累计 `unet_time_ms`；tiled 路径还将 tile 拆分、拼接和 blend 包含在 `runUnetTiled()` 内。实际 SDXL HTP UNet 执行位于 `PipelineSdxl.hpp` 的 `runUnetStep()`：`app/src/main/cpp/src/Pipeline.hpp:643-724,1048-1079`、`app/src/main/cpp/src/PipelineSdxl.hpp:179-200`。
- [推断，高] 当前数值会混入 CPU copy/CFG/tile 后处理，且 tiled/non-tiled 语义不一致，不能满足人工已批准的“真实 UNet 调用边界”合同，候选间 UNet p50 不可直接比较：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`。
- 处置：将累计计时下沉至实际 `runUnetStep`/QNN execute 边界；如需保留前后处理，另以 `denoiseStageMs` 表达，禁止复用为 `unet_ms`。新增 native 级回归，证明注入 CPU 前后处理不会改变 `unet_ms`。

### CR-45.2 — P1：W5 没有独立的持续测量 GroupKey/B0/质量/报告身份

- 状态：open。
- [实锤，高] W5 不可变场景有自己的 SHA，但 `_sustained()` 产出 `scenario_id=W1/W2`，只将 W5 放进 stage 字段；harness 因此以 W1/W2 场景 SHA 生成 GroupKey、B0 和 group artifact。两个请求的耗时被相加为一个吞吐值，再复制给两个 variant：`tools/performance-harness/scenarios/v4/W5.json`、`tools/performance-harness/localdream_perf_executor.py:111-148`、`tools/performance-harness/localdream_perf_harness.py:304-312,1104-1166`。
- [实锤，高] 现有回归固化而非反证该行为：`test_device_executor.py` 断言 W5 execution 的 `scenario_id` 是 W1/W2，`test_harness.py` 只断言 W1/W2 warmup：`tools/performance-harness/tests/test_device_executor.py:45-56`、`tools/performance-harness/tests/test_harness.py:795-831`。
- [推断，高] W5 的 B0、质量和报告被普通 W1/W2 统计组吸收，不能证明“W5 + variant”的独立持续窗口或逐候选吞吐，违反已批准 W5 身份合同：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`、`docs/specs/oneplus13-performance-acceptance-spec.md:38,44-48,66-69`。
- 处置：引入 W5 measurement identity，令 GroupKey/B0/质量/工件同时精确表达 W5 与 variant 的模型事实；每个 variant 按自身连续 wall-clock 窗口计算吞吐。补正反回归：W5 不得落入 W1/W2 GroupKey，两个 variant 吞吐不得共享，W5 B0/质量错配必须拒绝。

### 本轮独立验证

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 69 tests ... OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

python3 -m py_compile tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_protocol.py
# exit 0

./gradlew --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:testDebugUnitTest --tests io.github.xororz.localdream.openai.OpenAiJsonTest --tests io.github.xororz.localdream.openai.DiffusionProgressNormalizerTest :app:assembleDebug
# BUILD SUCCESSFUL

cd app/src/main/cpp && ninja -C build/android
# ninja: no work to do.

git -c core.fsmonitor=false -c submodule.recurse=false diff --no-ext-diff --check -- <CR-45 相关文件>
# 无输出
```

## 当前阶段结论（CR-45）

未通过。资源 lifecycle 部分已 fixed，但 CR-45.1、CR-45.2 均为无人工风险接受的 P1；69 项回归覆盖了当前合同，却无法反证这两条路径，且部分 W5 测试固化了错误身份。`previous_delta` 未实质缩小，本阶段按规则停止并交回控制器进入 04 修复。上述验证不形成 PJZ110/SM8750 的性能、可靠性或热稳定性通过结论；真实目标设备的 B0/质量冻结、W1-W7、每候选 100 次、30/60 分钟热稳定与阈值判定继续留在 `07-business-e2e`。

## 独立复核收口（2026-07-30，CR-45.1 / CR-45.2 修复后）

### 复核方法

- [实锤，高] 本轮重新读取当前 diff、规格、计划、`04-implementation-048.json`、人工反馈和实际调用链；未采信实现阶段的自评。`CR-45.1` 的目标是让 `unet_ms` 只覆盖 native UNet/QNN 调用，`CR-45.2` 的目标是让 `W5 + variant` 具独立 GroupKey、B0、质量、报告和连续吞吐窗口：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`、`docs/specs/oneplus13-performance-acceptance-spec.md:38,44-48,63-69`、`docs/plans/oneplus13-performance-acceptance-plan.md:81-82,100-111`。

### CR-45.1 — P1：`unet_ms` 混入 CPU 前后处理

- 状态：fixed。
- [实锤，高] SD15、SDXL 和 Anima 管线不再在高层 `runUnetStep()` 周围计时；它们把累计器传入 QNN 执行器：`app/src/main/cpp/src/PipelineSd15Npu.hpp`、`app/src/main/cpp/src/PipelineSdxl.hpp`、`app/src/main/cpp/src/PipelineAnima.hpp`。
- [实锤，高] `QnnModel::executeUnetGraphs()`、`executeUnetGraphsSDXL()` 和 Anima 的 `runGraph()` 仅把 `graphExecute()` 前后单调时钟差累加至 `unet_execution_ms`。输入量化、`memcpy`、输出反量化和宿主 tensor copy 均处于该区间之外：`app/src/main/cpp/src/QnnModel.hpp`。
- [实锤，高] native 隔离回归静态校验三个 QNN 调用点的计时区间不包含 CPU copy/转换标识，运行期 stub 也证明注入 CPU 前后处理不会改变计时：`tools/performance-harness/tests/test_native_unet_timing_contract.py`。
- 处置：fixed。`stage_metrics.unet_ms` 仍只能由正数 native complete event 投影；缺失时报告门禁继续拒绝，未用端到端时间替代。

### CR-45.2 — P1：W5 吞吐、B0、质量和报告吸收为 W1/W2 身份

- 状态：fixed。
- [实锤，高] `ScenarioExecution` 现在同时保留物理请求 `scenario_id=W1|W2`、`measurement_scenario_id=W5` 和 `variant_id=W1|W2`；harness 用 measurement scenario 生成统计 GroupKey，故 W5 场景摘要、variant、RuntimeProbe 和 preset snapshot 共同绑定 B0、质量与分组工件：`tools/performance-harness/localdream_perf_executor.py`、`tools/performance-harness/localdream_perf_harness.py`。
- [实锤，高] W5 在普通场景循环外执行：每个 variant 先有五次独立 warmup，随后完整执行该 variant 的正式批次，再切换另一个 variant。每个窗口从请求前资源采样完成后的首个物理 HTTP 请求开始，不混入另一 variant 或 ADB 采样的耗时：`tools/performance-harness/localdream_perf_executor.py`、`tools/performance-harness/localdream_perf_harness.py`。
- [实锤，高] 指标模型按最大窗口样本数选择同一 variant 的最终 `w5SustainedThroughputPerSecond`，并同时记录 `w5SustainedWindowSampleCount`，不再把两个候选的窗口或吞吐合并：`tools/performance-harness/localdream_perf_models.py`。
- [实锤，高] 回归覆盖 W5 variant 连续调度、warmup 不进入分母、每个 variant 的窗口独立计数、W5 measurement identity，以及报告消费最终窗口：`tools/performance-harness/tests/test_device_executor.py`、`tools/performance-harness/tests/test_harness.py`。
- 处置：fixed。W1/W2 仍保留为实际模型请求身份；仅统计、B0、质量、报告和 sustained window 使用 `W5 + variant` measurement identity，避免篡改其模型/RuntimeProbe 事实。

### 验证与结论

```text
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 73 tests ... OK

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

./gradlew --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.enabled=false :app:assembleDebug
# BUILD SUCCESSFUL

cd app/src/main/cpp && ninja -C build/android -n
# ninja: no work to do.

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- <CR-45.1/CR-45.2 修改文件>
# 无输出
```

通过。独立复核未发现未处置的 P0/P1/P2；CR-45.1 与 CR-45.2 均已修复并由针对性回归、场景校验、debug APK 构建、native 构建图复核和限定 diff 检查证实。

[业务解读] 这两项修复让性能验收报告回答的是“同一候选真实 QNN UNet 调用及其持续生成能力”，而不是把手机 CPU 拷贝、ADB 采样或另一个候选的请求时间混进指标。它保证后续一加 13 上的候选比较、基线和质量归档可以追溯到准确对象；它本身不构成一加 13 的性能、可靠性或热稳定性通过。

## 独立复核收口（2026-07-30，CR-45.3 pidof 语义）

### 复核方法

- [实锤，高] 本次独立读取当前差异、规格/计划、`04-implementation-049.json` 与真实调用代码；没有采信实现阶段“已通过”的自评。评审范围限定为 CR-45.3 的 Android `pidof` 退出码语义，以及其在资源释放和 W4 `PROCESS_COLD` 两条调用链的一致性。
- [实锤，高] 目标验收仍要求资源释放未回稳时 fail-closed，不能把成功 force-stop 的 Android 常规 `pidof exit=1` 误记为采集失败：`docs/specs/oneplus13-performance-acceptance-spec.md:44-48,63-69`、`docs/plans/oneplus13-performance-acceptance-plan.md:100-111`。

### CR-45.3 — P1：Android `pidof` 正常缺进程退出码被误判

- 状态：fixed。
- [实锤，高] `AdbResourceSampler` 不再复用固定 `check=True` 的 `_shell()` 读取 unload 后 PID；`_pidof_after_unload()` 显式执行 `check=False`，仅将 `exit=1 && stdout为空` 解释为进程已消失、将 `exit=0 && stdout非空` 解释为仍存活，任何其他退出码/输出组合均抛异常并 fail-closed：`tools/performance-harness/localdream_perf_harness.py:927-960`。
- [实锤，高] W4 的 `ProcessLifecycleController._pid()` 使用相同的三态判定；因此 force-stop 后的常规缺进程语义会继续启动下一步，而存活或模糊状态会阻断 `PROCESS_COLD`：`tools/performance-harness/localdream_perf_harness.py:995-1039`。
- [实锤，高] 回归分别覆盖 unload 的 `exit=1/空输出`、三类模糊组合的拒绝，以及 W4 的 `PID存在 -> exit=1/空输出 -> 新PID + health=200` 生命周期；测试不把任意不明状态降级为成功：`tools/performance-harness/tests/test_harness.py:95-127,938-980`。
- [实锤，高] 本次实际验证：`python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v` 退出 0，76 项通过；`python3 -m py_compile tools/performance-harness/localdream_perf_harness.py` 退出 0；v4 场景校验输出 `validated=7`；限定 `git diff --check` 无输出。
- 处置：fixed。未发现 `pidof` 成功缺进程仍被当作 collection error 的 P0/P1/P2 路径，也未发现 W4 与资源 release 的退出码语义分叉。

## 当前阶段结论（CR-45.3 独立复核）

通过。本轮只更新评审产物，未修改业务代码。所有本轮 P0/P1/P2 finding 均已处置；当前没有需要回到 04 的有效 finding。PJZ110/SM8750 的 RuntimeProbe=VERIFIED、冻结 B0/质量、W1-W7、每候选 100 次可靠性、30/60 分钟热稳定性和性能阈值仍属于 `07-business-e2e`，本次主机侧复核不形成其通过结论。

## 交叉独立评审更正（2026-07-30，CR-46）

> 本节取代紧邻上一节“通过”的结论。该结论只验证了 CR-45.3 的单一 `pidof` 退出码分支；交叉独立评审发现资源 release 被错误复用于多个统计组，故 05 不能通过。

### CR-46 — P1：单次全局 release 被伪绑定为每个统计组的 release

- 状态：open。
- [实锤，高] 已批准合同要求资源按**统计组生命周期**采集组前 baseline、组内峰值和显式 unload 后 release；并且任一 `GroupKey` 不同就必须独立归档、统计和 B0：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`、`docs/specs/oneplus13-performance-acceptance-spec.md:42-48,127`。
- [实锤，高] 当前 `command_run()` 在所有 W1--W6/W5 请求结束后只执行一次 `sampler.unload_and_collect(sequence)`，然后把同一个 `release` 传给所有样本：`tools/performance-harness/localdream_perf_harness.py:461-476`。
- [实锤，高] `_attach_group_resource_lifecycle()` 将这个同一 release 复制给每个 `GroupKey`，实现注释亦确认“一次显式 app unload 释放 completed run 的所有组”；这不是每组完成后的资源回稳取证：`tools/performance-harness/localdream_perf_harness.py:1283-1333`。
- [实锤，高] 组工件的 telemetry 仅按该组 sample sequence 过滤；最后一次 release 的 sequence 位于所有样本之后，通常不进入前面组的 `telemetry.jsonl`，故前面组无法附带其声称的 release 原始记录：`tools/performance-harness/localdream_perf_harness.py:1697-1739`。
- [实锤，高] 现有 `ResourceLifecycleTest` 只向一个 `GroupKey` 直接传入 release，未覆盖多组时 release 被错绑或其中一组缺 release 的反例：`tools/performance-harness/tests/test_harness.py:130-166`。本次完整 harness 76 项通过不能反证该路径。
- [推断，高] 前一组完成后，后续组可继续加载不同模型、预设或 runtime；最终单次 force-stop 的零值不能证明前一组在自身结束时已释放/回稳，还可能掩盖后续组引入的泄漏。因此 `TARGET_VALIDATED`/`FINAL_VALIDATED` 不能把该共享 release 当作各组的独立资源证据。
- 处置：回到 04。以完整 `GroupKey` 为单位执行 `baseline -> samples/peak -> explicit force-stop -> release`，把 release 原始 record 与该 `GroupKey` 显式绑定并写入其工件。下一组必须在重新启动/重新 probe 后采样；W5 每个 variant 的连续窗口结束后也必须独立 release。补多 GroupKey 正反回归，断言 release 不可跨组复用、任一组缺 release 或错绑均拒绝、后续组资源变化不能回填前一组。

## 当前阶段结论（CR-46）

未通过。CR-46 是无人工风险接受的 P1，当前只修改评审产物，未修改业务代码。虽然 `python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v` 76 项通过、v4 场景校验为 `validated=7`、Python 编译及限定 diff 检查通过，但它们没有覆盖多统计组 release 错绑。必须返回 04 修复并新增回归，再启动新的独立 05 复核。PJZ110/SM8750 的目标性能、可靠性和热稳定性验收仍属于 `07-business-e2e`。

## 独立复核闭环（2026-07-30，CR-46 分组资源生命周期）

### 复核方法

- [实锤，高] 本轮从 `previous_delta` 重新读取当前差异、规格、计划、人工反馈和真实 harness，不采信 04 的自评。审查重点是不同 `GroupKey` 的 baseline、peak、显式 `ADB_FORCE_STOP`、release 原始 telemetry 与组工件是否一一对应：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:216-218`、`docs/specs/oneplus13-performance-acceptance-spec.md:42-48,127`、`docs/plans/oneplus13-performance-acceptance-plan.md:100-111`。

### CR-46 — P1：单次全局 release 被伪绑定为每个统计组的 release

- 状态：fixed。
- [实锤，高] `command_run()` 的成功和可恢复失败路径现在都调用 `_collect_group_releases()`；该函数按完整 `GroupKey` 排序，对每组分别调用 `sampler.unload_and_collect()` 并为原始 record 写入不可从其他组借用的 `resourceGroupId`：`tools/performance-harness/localdream_perf_harness.py:461-476,1283-1304`。
- [实锤，高] `_attach_group_resource_lifecycle()` 仅消费其同 key release，并强制 `resourceGroupId == _group_artifact_id(sample.group_key)`；缺 release、收集失败或将另一组 record 放进当前组 map 时都不会写 `releasePssKb` / `memoryLeakDetected`，后续 TARGET/FINAL 报告维持 fail-closed：`tools/performance-harness/localdream_perf_harness.py:1307-1360`。
- [实锤，高] 分组 telemetry 除该组 sample sequence 外，还显式收录样本引用的 `releaseTelemetrySequence`，所以每个组目录都有自己的 `ADB_FORCE_STOP` 原始 record，而非遗漏全局尾部 release：`tools/performance-harness/localdream_perf_harness.py:1724-1763`。
- [实锤，高] 多组正反回归构造两个不同 `GroupKey`，断言产生两个不同 release source/sequence 和各自归档 telemetry；把 B 组 release 错放入 A 组时，A 不会得到 release 指标：`tools/performance-harness/tests/test_harness.py:170-229`。
- 处置：fixed。release 现在是带 `resourceGroupId` 的每组独立原始证据，禁止单一全局 release 的跨组复制；采样、PID 缺失确认或 group-id 任一环节不成立时不得形成目标/最终性能通过。

### 本轮独立验证

```text
python3 -m unittest tools/performance-harness/tests/test_harness.py -v
# Ran 52 tests ... OK

python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py' -v
# Ran 77 tests ... OK

python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_protocol.py
# exit 0

python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
# {"validated": 7, "scenarioIds": ["W1", "W2", "W3", "W4", "W5", "W6", "W7"]}

git -c core.fsmonitor=false -c submodule.recurse=false diff --check -- tools/performance-harness/localdream_perf_harness.py tools/performance-harness/tests/test_harness.py
# 无输出
```

### 当前阶段结论（CR-46）

通过。独立 review 的唯一有效 P1 已修复，并以多 GroupKey 正反、分组 telemetry 归档与完整 harness 回归验证；未发现新的 P0/P1/P2。此结论仅确认主机侧资源生命周期与工件合同，不构成 PJZ110/SM8750 的 QAIRT、性能、可靠性或热稳定性通过。真实目标机仍须在 07 以 `RuntimeProbe=VERIFIED`、冻结 B0/质量、W1-W7、每候选 100 次及 30/60 分钟热稳定运行完成验收。

[业务解读] 资源释放证据现在能回答“某个候选和运行时组合是否具有自身绑定的显式回收原始记录”，而不是用另一个统计组的收尾状态替它背书；这避免把跨模型、跨预设或跨 runtime 的内存问题隐藏在一份共享 release 中。
