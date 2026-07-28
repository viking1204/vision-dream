# 一加 13 性能验收独立变更评审

## 结论

通过。本轮独立复核了当前 diff、规格、计划和人工反馈；首轮发现的 1 个 P0、3 个 P1 与 1 个 P2 均已修复并通过定向真实验证。没有遗留 P0/P1/P2。

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

- 一加13未连接不阻塞本阶段源码与主机侧评审；Redmi K30 不用于形成 QAIRT/HTP、性能、可靠性或热稳定性结论。
- 一加13 `RuntimeProbe=VERIFIED` 后的 W1-W7、100 次可靠性、30/60 分钟热稳定和性能阈值实测属于 07 阶段。
- `git diff --check` 仍只报告前序 `app/src/main/assets/legal/QAIRT_NOTICE.txt` 的 trailing whitespace；本轮文件无该问题。
