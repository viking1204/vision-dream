# 一加 13 性能验收实施计划

## 1. 结论与实施边界

本计划以已批准规格为唯一实施合同：先让 PJZ110（SM8750 / HTP V79）的 NPU/HTP 性能探索、可调预设和候选筛选可审计，再把 100 次可靠性、30/60 分钟热稳定性作为最终胜出候选的深度验证门槛。计划不把 Redmi K30 的任何结果计入目标机结论。

- [实锤，高] 当前数据库版本为 v6，已存在预设和 binding 表，尚无资格表；新增资格表必须是 v6→v7 migration，不能沿用过期的 v5→v6 说法。证据：`app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt:14-28,271-300`。
- [实锤，高] 当前 harness 实际接受 `v4` 场景，`validate-scenarios` 已校验 W1–W7 共 7 项；新验收输入必须基于 v4，v1–v3 仅保留历史重放。证据：`tools/performance-harness/scenarios/v4/`；`python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4`。
- [实锤，高] 现有 `run` 只消费外部 `--baseline-file`、`--quality-evidence-file`，缺少规格要求的采集命令；P04 必须新增受审计的 B0 与质量采集入口。证据：`python3 tools/performance-harness/localdream_perf_harness.py run --help`；`docs/specs/oneplus13-performance-acceptance-spec.md:54-59`。
- [实锤，高] 当前唯一已授权目标设备是 USB ADB `3B15C4018L500000`，已核验为 OnePlus / PJZ110 / SM8750 / Android 16；本轮安装、操作、NPU/HTP profiling 和性能验收均只能显式指定该串号。旧 Wi-Fi 串号与 Redmi K30 约束已被此事实取代；每次真机执行前仍须复核产品、板型、SoC 和 Android 版本。证据：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:116-118`。

本阶段仅定义文件改动、测试和发布边界，不改业务代码，不执行目标设备烧机。缺 ONNX/DLC、量化配置、校准集及既有编译参数时不重编译 V79 Context Binary，也不作极限优化完成声明；商店签名和最终用户生产发布不在本轮。

## 2. 规格到计划任务映射

| 规格要求 | 实施任务 | 可验证证据 |
| --- | --- | --- |
| 不可变 W1–W7/W2b、RunManifest、完整 GroupKey、冷热样本和指标可重放 | P01、P04 | scenario 摘要/版本测试、RunManifest/样本/分组报告测试 |
| `EXPLORATORY`、`TARGET_VALIDATED`、`FINAL_VALIDATED` 的门槛分层；非 `VERIFIED` fail-closed | P03、P04 | RuntimeProbe/报告门禁/分层运行测试 |
| B0 与质量由可审核采集入口生成，目标验证和最终验证严格匹配 | P04 | baseline/quality 采集、篡改和 GroupKey 一致性测试 |
| 预设 v1/v2 严格解析、不可变 Job snapshot、资格记录和默认绑定门禁 | P02、P05、P06 | JVM 领域测试、Room migration、入口集成测试 |
| 旧 JSON/数据库/Job 可读且不伪回填资格；无归属历史不入统计 | P02、P04 | migration、legacy 反序列化和报告过滤测试 |
| W6/W7 的 PNG、资产下载、MCP progress/cancel/replay/reconnect 与 `/v1` 对照 | P03、P05 | Python 协议测试、MCP/HTTP Android integration 测试 |
| GitHub Android 单仓的构建、发布和回滚边界 | P07 | debug APK、精确提交、远端 `master` 对齐和非推理设备检查 |

## 3. 实施任务

### P01：冻结验收场景、分组模型与主机侧基础门禁

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 修改 | `tools/performance-harness/scenarios/v4/W1.json` 至 `W7.json` | 保持 v4 的不可变字段、真实模型摘要和 fixture 摘要；任何请求、fixture、模型、measurement 或 timeout 变化均发布新 `scenarioVersion` 与新文件。W2 发布方变体单独使用 W2b，绝不改写 W2。 |
| 修改 | `tools/performance-harness/localdream_perf_models.py` | 固定 `RunManifest`、`Sample`、`Outcome`、`ColdState`、`GroupKey` 与三层结论模型；`GroupKey=scenarioSha256+presetSnapshotSha256+runtimeFingerprint+coldState+harnessVersion`，任一字段不同独立归档。 |
| 修改 | `tools/performance-harness/localdream_perf_harness.py` | 在任何设备请求前落盘 RunManifest；拒绝摘要、workflow、fixture、模型摘要、请求参数或 GroupKey 不匹配的输入；只将 v4 作为验收场景集。 |
| 修改 | `tools/performance-harness/tests/test_harness.py`、`test_device_executor.py` | 覆盖摘要篡改、W2/W2b 隔离、未知 workflow、固定 fixture、RunManifest 先于请求、冷热样本下限和 warmup 排除。 |

**实施顺序**

1. 保留 v1–v3 供历史读取和重放，不将其样本与 v4 聚合。
2. `DEVICE_COLD`、`PROCESS_COLD`、`OS_CACHE_WARM`、`CONTEXT_WARM` 互斥；禁止 root 清 page cache。冷态每组至少 5 条有效样本；热态恰有 5 条 warmup 后至少 30 条有效样本，warmup 永不进入统计或可靠性计数。
3. 对每组计算 p50、p95、MAD 与以 runId 派生种子的 95% bootstrap CI；缺 UNet、端到端、资源或热主指标时输出 `MISSING_METRIC:<name>`，不得用 0 填充。

**验证命令**

```bash
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
```

### P02：预设资格数据模型、v6→v7 迁移与自动绑定门禁

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetQualificationEntity.kt`、`PerformancePresetQualificationDao.kt` | 定义 `performance_preset_qualifications` 及活跃唯一键 `(presetSnapshotSha256, modelAssetSha256, runtimeFingerprint, scenarioSetSha256, qualificationLevel)`；记录 preset/revision、model、build、evidence manifest、创建和撤销时间。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt` | 数据库升至 v7，注册实体/DAO、外键和索引，加入非破坏性的 `MIGRATION_6_7`；绝不回填资格或改写历史 JSON、revision、Job、generation history。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetConfig.kt`、`PerformancePresetRepository.kt`、`InferenceJobRepository.kt` | 严格解析 `SUPPORTED`、`LEGACY_COMPATIBILITY`、`UNSUPPORTED_VERSION`、`INVALID`；显式用户预设可探索，`DEFAULT`/`MODEL:<modelId>` 自动绑定必须精确匹配活跃 `TARGET_VALIDATED` 或 `FINAL_VALIDATED`，否则返回 `PRESET_NOT_TARGET_VALIDATED`。本版本不允许 MCP/HTTP/普通 UI 导入候选 JSON 写资格；候选只作 harness 审计，资格写入留给未来本机受控采集链路。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetBindingDao.kt` | 删除事务内撤销活跃资格、把引用 binding 指向 fallback、删除 preset，并返回全部 `reboundBindingKeys`。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/data/db/AppDatabaseMigrationTest.kt` | 覆盖 v6→v7 保留旧预设/Job/snapshot/历史行、没有伪资格、旧自动 binding fail-closed、删除全回滚、导入副本不继承资格。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/data/PerformancePresetQualificationTest.kt` | 覆盖资格唯一键、revision/model/runtime/scenario/build 变化失效、资格撤销、自动绑定拒绝和显式探索放行。 |
| 修改 | `app/src/test/java/io/github/xororz/localdream/data/PerformancePresetConfigTest.kt`、`PerformancePresetRepositoryTest.kt`、`InferenceJobRepositoryTest.kt` | 覆盖 v1/v2 严格键集、`{}` 历史兼容、非法配置拒绝、不可变 snapshot、同名规则、导入自动编号和事务原子性。 |

**验证命令**

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.PerformancePresetConfigTest' --tests 'io.github.xororz.localdream.data.PerformancePresetRepositoryTest' --tests 'io.github.xororz.localdream.data.InferenceJobRepositoryTest' --tests 'io.github.xororz.localdream.data.PerformancePresetQualificationTest'
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.data.db.AppDatabaseMigrationTest
```

### P03：目标运行时 attestation、原生指标和受保护投影

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/RuntimeProbe.kt`、`RuntimeCompatibilityEvaluator.kt`、`ModelMetadata.kt` | 仅在 PJZ110、SM8750、QAIRT 2.48.40、HTP V79、ABI、已映射库摘要、Context fingerprint 与成功 native readiness 全部匹配时产生 `VERIFIED`；`REJECTED` 与 `UNAVAILABLE` 均不得进入目标机统计。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/NativeRuntimeAttestationStore.kt`、`app/src/main/java/io/github/xororz/localdream/service/NativeRuntimeAttestationRecorder.kt`、`BackendService.kt` | 将成功 native 图片生成的可验证证据存入私有完整性保护存储；存储失败不得使已成功生成失败，也不得升级为 `VERIFIED`。 |
| 修改 | `app/src/main/cpp/src/main.cpp`、`app/src/main/cpp/src/QnnRuntime.hpp` | 采集 Context load、CLIP、首 diffusion step、完整 UNet、VAE、端到端、PSS/RSS 与 HTP/thermal 可用性；未知值显式为 `UNAVAILABLE`，保留原有 SSE 字段兼容。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt`、`McpRuntimeStore.kt` | 为本地 harness 投影不含绝对路径、原始命令或密钥的 runtime/阶段证据；任何失败 probe 的响应不能造成性能通过。 |
| 修改 | `app/src/test/java/io/github/xororz/localdream/data/RuntimeProbeTest.kt`、`RuntimeCompatibilityEvaluatorTest.kt`、`app/src/test/java/io/github/xororz/localdream/service/NativeRuntimeAttestationRecorderTest.kt`、`NativeBackendCommandFactoryTest.kt` | 覆盖 VERIFIED 全条件、每类 rejection、证据缺失、attestation 写入失败非致命和配置到命令的逐字段映射。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/data/NativeRuntimeAttestationStoreInstrumentedTest.kt`、`app/src/androidTest/java/io/github/xororz/localdream/openai/NativeBackendClientInstrumentedTest.kt` | 覆盖私有证据完整性和缺失指标/PNG 响应的 Android 协议。 |

**验证命令**

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.RuntimeProbeTest' --tests 'io.github.xororz.localdream.data.RuntimeCompatibilityEvaluatorTest' --tests 'io.github.xororz.localdream.service.NativeRuntimeAttestationRecorderTest' --tests 'io.github.xororz.localdream.service.NativeBackendCommandFactoryTest'
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin
cd app/src/main/cpp && ./build.sh
```

### P04：B0/质量采集、设备执行协议与分层报告

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 修改 | `tools/performance-harness/localdream_perf_harness.py` | 新增 `capture-baseline` 与 `capture-quality` 子命令；前者仅在 `RuntimeProbe=VERIFIED` 时产出 `baseline-v1.json`，后者按下载后输出 SHA-256 产出 `quality-v1.json`。`run` 只消费同 GroupKey、模型摘要、超时、质量参考均精确匹配的工件。 |
| 修改 | `tools/performance-harness/localdream_perf_models.py`、`localdream_perf_executor.py` | 将响应/下载的 Content-Type、PNG 魔数、尺寸、字节数、SHA-256、模型/seed/尺寸、资源和热采样写入原始样本；W4 必须记录可验证 `PROCESS_COLD` lifecycle。 |
| 修改 | `tools/performance-harness/localdream_perf_protocol.py` | 固化 W7 的 `/v1` 与 MCP 同输入、20 个 diffusion step progress、cancel、replay/reconnect、稳定 `/assets/{assetId}` 与下载完整性合同。 |
| 修改 | `tools/performance-harness/README.md` | 以真实包名、v4 场景、安全 token 文件/环境变量、经授权目标 ADB 串号、目录布局、采样配置和恢复步骤更新可执行说明；不得在命令行参数、报告或日志回显 token。 |
| 修改 | `tools/performance-harness/tests/test_harness.py`、`test_device_executor.py`、`test_protocol_parity.py` | 覆盖探索层不要求 B0/质量/100，目标验证缺 B0/质量/输出完整性/模型摘要/主指标即拒绝，最终层才要求 100/30/60；B0/质量篡改、热态 warmup、独立 GroupKey、W4 lifecycle、W6/W7 PNG 下载与 token 安全入口。 |

**实施顺序**

1. `capture-baseline` 生成的每个条目必须含 `scenarioSha256`、`presetSnapshotSha256`、`runtimeFingerprint`、`coldState`、`absoluteTimeoutMs`、`qualityReferenceSha256`、`modelAssetSha256`。
2. `capture-quality` 只接受 `BIT_EXACT` 或 `GOLDEN_SET`；`GOLDEN_SET` 固化 30 prompt × 4 seed、SSIM、LPIPS、CLIP 与盲测原始度量。NaN、Inf、破图、全黑、色序或 layout 错误一律失败。
3. `EXPLORATORY` 仅输出候选事实；`TARGET_VALIDATED` 才消费 B0/质量并写资格候选工件；`FINAL_VALIDATED` 才执行每候选 100 次零失败、30 分钟筛选、60 分钟、3 次冷启动及至少两轮热测。
4. 最后四分位吞吐相对首个稳定区间下降超过 10%、severe thermal、LMKD、持续 swap/泄漏或释放后未回稳，固定产生失败报告；不得通过重试掩盖失败。

**验证命令**

```bash
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
python3 -m py_compile tools/performance-harness/localdream_perf_harness.py tools/performance-harness/localdream_perf_models.py tools/performance-harness/localdream_perf_executor.py tools/performance-harness/localdream_perf_protocol.py
```

### P05：OpenAI/MCP 执行一致性、资产授权和传输保护

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt`、`OpenAiApiController.kt` | 仅真实 diffusion step 形成 progress，不因订阅触发 VAE preview；W6 输出按 PNG 请求并验证下载响应。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt`、`McpGenerationGateway.kt`、`McpToolRegistry.kt`、`McpTransportGuards.kt` | 统一 `/assets/{assetId}`；MCP 仅使用自身 Bearer token 和 `assets.read`；mutation 使用规范化参数的幂等键与 destructive dry-run；SSE 使用有界队列、活跃订阅保活、溢出 reset 与终态清理。有效 token 加对应 scope 可直接调用工具，不引入本机确认或一次性 confirmationId。 |
| 修改 | `app/src/test/java/io/github/xororz/localdream/openai/DiffusionProgressNormalizerTest.kt`、`app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt`、`McpGenerationGatewayTest.kt`、`McpTransportGuardsTest.kt` | 覆盖 progress/preview 解耦、scope、幂等重放、dry-run、有界 SSE 的溢出、idle 保活和清理。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt`、`app/src/androidTest/java/io/github/xororz/localdream/openai/OpenAiHttpServerInstrumentedTest.kt` | 覆盖 MCP `/assets` 的 401/403/200、W7 响应形状、稳定下载与 OpenAI 兼容下载路径。 |

**验证命令**

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'io.github.xororz.localdream.openai.DiffusionProgressNormalizerTest' --tests 'io.github.xororz.localdream.mcp.McpAuthorizationTest' --tests 'io.github.xororz.localdream.mcp.McpGenerationGatewayTest' --tests 'io.github.xororz.localdream.mcp.McpTransportGuardsTest'
python3 -m unittest tools/performance-harness/tests/test_protocol_parity.py -v
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin
```

### P06：预设 CRUD 消费面、运行快照与资格可见性

**文件清单**

| 操作 | 文件 | 实施内容 |
| --- | --- | --- |
| 修改 | `app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt`、`ui/screens/ModelRunScreen.kt`、`navigation/Navigation.kt` | 提供用户预设创建、编辑、删除、导入/导出、默认/模型绑定以及资格层级/失效原因展示；fallback 无编辑和删除动作；运行页展示本次 snapshot 名称、revision 与探索/目标验证状态。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt`、`McpGenerationGateway.kt` | 返回删除的 `reboundBindingKeys`，拒绝未验证自动绑定，保留显式探索请求，导入重名自动编号且不复制资格；不注册 `presets.import_qualification_evidence`，不暴露 `qualifications.write`。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt`、`service/RemoteHostService.kt` | 受理时传递不可变 preset snapshot；宿主和服务不得重新读取可变偏好覆盖已受理 Job。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/ui/PerformancePresetScreenInstrumentedTest.kt`、`app/src/test/java/io/github/xororz/localdream/remote/RemotePresetExecutionTest.kt` | 覆盖 CRUD、fallback 禁用、资格门禁提示、绑定回退、导入副本无资格及远程 snapshot 不变。 |

**验证命令**

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'io.github.xororz.localdream.remote.RemotePresetExecutionTest' --tests 'io.github.xororz.localdream.data.PerformancePresetRepositoryTest'
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin
```

### P07：本地验证、测试发布、回滚和 07 真机验收交接

**04–06 本地验证**

```bash
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v4
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
cd app/src/main/cpp && ./build.sh
```

Android instrumentation 只在授权设备运行，本轮任何目标机操作均显式使用 `ANDROID_SERIAL=3B15C4018L500000`。执行前先核验该串号仍为 OnePlus / PJZ110 / SM8750 / Android 16；若不匹配则停止，不能换用其他设备。ColorOS 拒绝 instrumentation APK 时，保留失败码并用同一最终 APK 的正式 listener 协议回归补充该协议证据，不把它转写为目标机性能结论。

**测试发布与回滚**

1. 本仓是 GitHub Android 单仓，无 GitLab、`test` 分支、Pod、镜像或 HTTP 测试环境。仅在全部本地门禁通过后，以中文提交信息提交精确交付文件，推送 GitHub `master`；`docs/loop-records/` ledger 不纳入发布提交。
2. 发布证据为提交 SHA、`git ls-remote origin refs/heads/master`、debug APK 路径、自动化命令结果和经授权 PJZ110 的协议/验收交接记录；协议回归不能替代性能、可靠性或热稳定性结论。
3. 回滚恢复到本次提交之前已验证的 APK/提交；v7 migration 不做破坏性降级。发现问题时先停用新自动绑定及资格发布结论，保留 compatibility fallback 和已受理 Job snapshot，再以前进修复提交恢复，不删除用户数据库、不重写历史 snapshot、不静默重编译 Context。

**07 业务验收命令边界**

只有 PJZ110 `RuntimeProbe=VERIFIED`、v4 资产、B0/质量工件、环境温度和采样配置均已冻结时，才在 07 执行设备运行。认证仅由受保护文件或环境变量传入，不回显 token；每条 ADB 命令及 harness `--adb-serial` 必须是 `3B15C4018L500000`，并在执行前复核该串号仍为 OnePlus / PJZ110 / SM8750 / Android 16。目标机命令会在 P04 实现 `capture-baseline` 与 `capture-quality` 后固定为实际 CLI，不在本计划阶段盲跑。

```bash
ANDROID_SERIAL=3B15C4018L500000 python3 tools/performance-harness/localdream_perf_harness.py run --scenario-dir tools/performance-harness/scenarios/v4 --runtime-probe-file <private-runtime-probe.json> --base-url <openai-base-url> --fixture-dir tools/performance-harness/fixtures/v2 --output-dir build/perf-verification/<run-id> --preset-snapshot-sha256 <sha256> --run-context-file <private-run-context.json> --baseline-file <baseline-v1.json> --quality-evidence-file <quality-v1.json> --adb-serial 3B15C4018L500000 --app-package io.github.xororz.localdream --thermal-duration-minutes 30 --bearer-token-file <0600-token-file>
```

尖括号只表示由 07 的受保护工件替换的运行输入，不得把该命令的示例值、token 或设备输出写入版本库。目标机未满足前置条件时，harness 必须产出拒绝结果，不能输出性能、可靠性或热稳定性通过。

## 4. 高风险点与强制门禁

| 风险 | 不可接受的结果 | 强制验证 |
| --- | --- | --- |
| 非 PJZ110、非 VERIFIED 或缺 native attestation 的样本进入目标统计 | 伪造一加13结论 | `RuntimeProbeTest`、harness 拒绝测试与 `--require-verified-runtime` 断言固定拒绝。 |
| B0/质量来自手写、其他模型、其他 runtime 或其他 GroupKey | 候选比较失真 | `capture-baseline`/`capture-quality` 的生成、摘要篡改、模型/GroupKey 不一致测试。 |
| 100 次可靠性阻断探索或反过来被探索结果宣称通过 | 错置业务优先级 | 分层报告测试：探索不要求 B0/质量/100，最终层缺任一深度证据必失败。 |
| 预设编辑、导入或模型/runtime 变化后旧资格仍用于自动绑定 | 未验证组合成为默认 | qualification 唯一键、失效、导入无继承和 `PRESET_NOT_TARGET_VALIDATED` 测试。 |
| 删除预设留下半条 binding 或修改排队 Job | 用户配置错误且结果不可重放 | Room migration/事务回滚测试与 Job snapshot 回归。 |
| W6/W7 只校验 HTTP 200、MCP scope 或 SSE 慢消费者 | 损坏图片、越权读取或事件丢失 | PNG Content-Type/魔数/尺寸/SHA-256、401/403/200、SSE overflow/replay/idle 测试。 |
| 使用非当前授权串号或未经预检的设备执行目标机命令 | 把非目标设备数据混入一加13工件 | 所有 07 命令显式 `ANDROID_SERIAL=3B15C4018L500000` 与 `--adb-serial=3B15C4018L500000`，运行前核验 OnePlus / PJZ110 / SM8750 / Android 16，并在 RunManifest 写入设备身份。 |

## 5. 执行顺序

1. P01 先固定 v4 场景、分组、统计和 RunManifest，再完成 P02 的 v7 数据库与资格门禁。
2. P03 将真实 native runtime/指标证据接入应用，P04 在接口稳定后补齐 B0、质量、资源、热采样和分层报告。
3. P05 对齐 OpenAI/MCP 传输、资产和 SSE 合同，P06 将资格门禁与不可变 snapshot 暴露给 CRUD/远程消费面。
4. P07 运行本地门禁、debug APK 构建和 GitHub 测试发布。只有完成 P04 工件准备后，07 才可在 PJZ110 执行逐层真机验收。
