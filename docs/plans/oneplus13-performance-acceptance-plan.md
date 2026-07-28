# 一加 13 性能验收实施计划

## 1. 交付目标与执行边界

本计划把规格中的验收能力拆成可实现、可测试的改动：不可变 W1–W7 场景和主机侧报告、PJZ110/SM8750/QAIRT 2.48.40/HTP V79 的运行时取证、性能预设 v1 的持久化和实际启动映射，以及历史数据兼容。完成代码与测试发布不等于一加 13 性能通过；只有 07 阶段拿到 `RuntimeProbe.status=VERIFIED` 的原始 RunManifest 与报告才能形成该结论。

依据：规格第 2–6 节；人工反馈 `docs/loop-records/oneplus13-performance-acceptance/feedback.md:5-9`。

不做 V79 Context Binary 重编译，也不做商店签名或最终用户生产发布。缺少一加 13 时，Redmi K30 只允许运行不触发推理的 UI、数据库、协议和 harness 拒绝路径验证。

## 2. 规格到任务映射

| 规格验收 | 实施任务 | 通过证据 |
| --- | --- | --- |
| S-01：W1–W7 可由场景、snapshot、RunManifest 和样本重放，W2/W2b 不混组 | P01、P04 | scenario 校验和 harness 单元测试；RunManifest/统计报告样例 |
| S-02：RuntimeProbe、分组、质量、可靠性或热稳定不满足时不得发布一加 13 性能结论 | P03、P04 | Probe 拒绝测试；缺字段、混组、质量失败和 `UNAVAILABLE` 报告测试 |
| S-03：预设 CRUD、v1 配置实际影响启动、Job snapshot 不变、删除原子回退且可见 | P02、P05、P06 | JVM 领域/命令行测试、Room instrumentation、MCP 与 Compose 集成测试 |
| S-04：历史数据库和 JSON 可读且不隐式改写，无归属历史不进入新统计 | P02、P04 | v5→v6 迁移 instrumentation 与 legacy JSON/报告过滤测试 |

## 3. 实施任务

### P01：版本化场景、报告模型与主机 harness

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 新增 | `tools/performance-harness/scenarios/v1/W1.json`、`W2.json`、`W3.json`、`W4.json`、`W5.json`、`W6.json`、`W7.json` | 固化规格中的 workflow、fixture、模型摘要、请求、measurement、timeout 与顶层 SHA-256；W2b 仅在发布方提供独立输入时新增文件。 |
| 新增 | `tools/performance-harness/localdream_perf_harness.py` | 以标准库执行 scenario 校验、RunManifest 写入、样本收集、统计、质量/可靠性/热稳定门禁和 JSON/Markdown 报告生成；拒绝调用方覆盖 fixture。 |
| 新增 | `tools/performance-harness/localdream_perf_models.py` | 定义 `RunManifest`、`RuntimeProbe`、四类冷热状态、`Outcome`、分组键、质量结果与报告数据结构。 |
| 新增 | `tools/performance-harness/tests/test_scenarios.py`、`test_report_guards.py`、`test_statistics.py` | 覆盖摘要篡改、W2/W2b 隔离、预热排除、样本下限、固定种子 bootstrap CI、全部 Outcome、B0 timeout 冻结及质量/热稳定拒绝。 |

**实现步骤**

1. 将每个 scenario 的原始内容 canonical JSON 序列化后计算 SHA-256；读取时重新计算并拒绝摘要不一致、未知 workflow、缺模型、缺 fixture 或 request 不一致。
2. 把统计分组固定为 scenario、preset snapshot、设备/运行时/Context 指纹、冷热状态和 harness 版本；任何键不同均生成新组。
3. 将冷态有效样本下限固定为 5，热态固定先预热 5 次且只对至少 30 个有效样本计算 p50、p95、MAD 和 95% bootstrap CI。
4. 由 harness 统一冻结 B0 的绝对超时与质量参考；候选报告逐项输出准入门槛、失败原因和原始样本路径，禁止只输出聚合“通过”。

**验证命令**

```bash
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
python3 tools/performance-harness/localdream_perf_harness.py validate-scenarios --scenario-dir tools/performance-harness/scenarios/v1
```

### P02：预设 v1、绑定、删除回退与 v5→v6 数据库迁移

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetConfig.kt` | 严格解析 `schemaVersion=1`、三个必填布尔 engine 字段和四种解码状态；提供不可变解析结果。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetRepository.kt` | 创建、更新、导入和绑定仅接受 `SUPPORTED`；保留 `{}` 的 `LEGACY_COMPATIBILITY` 读取；将删除返回值改为含回退 binding key 的 `PresetDeleteResult`。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetBindingEntity.kt`、`PerformancePresetBindingDao.kt` | 定义唯一 `bindingKey`、`presetId`、`updatedAt` 与查询/原子回退操作。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt`、`PerformancePresetDao.kt` | 数据库版本升至 v6，注册 binding 实体和 DAO，新增 v5→v6 migration、索引、fresh-db fallback 初始化及删除事务入口。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/InferenceJobRepository.kt` | 在既有 `withTransaction` 内按“显式→模型绑定→默认绑定→兼容 fallback”解析、校验并写入原始 JSON snapshot 与已解析的执行 snapshot。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/data/db/AppDatabaseMigrationTest.kt` | 增加 v5→v6 迁移、历史 `{}` snapshot、NULL 历史关联、fallback 单例和删除回退原子性测试。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/data/PerformancePresetConfigTest.kt`、`PerformancePresetBindingTest.kt` | 覆盖严格 schema、legacy/unknown/invalid 状态、revision 冲突、优先级、同名、导入编号、snapshot 不变与事务失败回滚。 |

**实现步骤**

1. 用 JSON key 集合精确匹配 v1 schema，拒绝未知字段、缺字段、非布尔值、未知版本和非法 JSON；禁止在创建、更新、导入、绑定或执行时把非法值静默转成 fallback。
2. `DEFAULT` 与 `MODEL:<modelId>` 只保存未来请求的选择；已受理 Job 只读自己的 snapshot，编辑、解绑和删除都不能改写它。
3. 在单个 `RoomDatabase.withTransaction` 中检查所有 binding、改为 compatibility fallback、删除用户预设并返回被回退键；任一 SQL 失败必须回滚，不得留下半条 binding。
4. migration 只增加 binding 表和索引，不改写 v5 的 preset、snapshot、revision 或 `generation_history` NULL 字段。

**验证命令**

```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.PerformancePresetConfigTest' --tests 'io.github.xororz.localdream.data.PerformancePresetBindingTest' --tests 'io.github.xororz.localdream.data.PerformancePresetRepositoryTest' --tests 'io.github.xororz.localdream.data.InferenceJobRepositoryTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.data.db.AppDatabaseMigrationTest
```

### P03：运行时取证、分段指标与拒绝发布结论

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/RuntimeProbe.kt` | 定义 `VERIFIED`、`REJECTED`、`UNAVAILABLE` 与设备、ABI、QAIRT、HTP、库/Context 摘要和启动结果。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluator.kt`、`app/src/main/assets/qairt-runtime-manifest.json` | 将 manifest 声明、实际设备/ABI、加载库和 Context fingerprint 统一成可序列化 probe 输入，保留既有 rejection code 语义。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt`、`app/src/main/java/io/github/xororz/localdream/service/NativeBackendCommandFactory.kt` | 启动成功后记录实际 runtime probe，并把完整已解析 preset 参数输入 `NativeBackendLaunchConfig`；启动拒绝时保留具体 rejection。 |
| 修改 | `app/src/main/cpp/src/main.cpp`、`app/src/main/cpp/src/QnnRuntime.hpp` | 在不改变生成数学语义的前提下，输出 Context load、CLIP、首步、完整 UNet、VAE、端到端、内存/HTP 资源可用性字段；不可采到的字段显式标 `UNAVAILABLE`。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpRuntimeStore.kt` | 将受保护的 runtime status 投影和请求阶段事件提供给本地 harness/MCP；不得暴露绝对文件路径或原始可执行命令。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/data/RuntimeProbeTest.kt`、`app/src/test/java/io/github/xororz/localdream/service/NativeBackendLaunchConfigTest.kt` | 覆盖 PJZ110/SM8750/V79/2.48.40 成功条件，所有 rejection code、无设备状态和 preset 到命令参数的逐字段映射。 |
| 修改 | `app/src/test/java/io/github/xororz/localdream/service/NativeBackendCommandFactoryTest.kt`、`app/src/test/java/io/github/xororz/localdream/mcp/McpGenerationGatewayTest.kt`、`app/src/androidTest/java/io/github/xororz/localdream/openai/OpenAiHttpServerInstrumentedTest.kt` | 覆盖 native command、MCP runtime 投影与 `/health`/本地诊断接口的无敏感路径响应。 |

**实现步骤**

1. `RuntimeProbe` 只有在型号 PJZ110、SoC SM8750、QAIRT 2.48.40、HTP V79、ABI、已加载库摘要、Context fingerprint 与启动成功均可采集并匹配时才为 `VERIFIED`。
2. mismatch 使用现有 `QAIRT_VERSION_MISMATCH`、`ABI_MISMATCH`、`HTP_TARGET_MISMATCH`、`CONTEXT_FINGERPRINT_MISMATCH` 拒绝；无设备或无法采集只产生 `UNAVAILABLE` 诊断，不能进入 benchmark 聚合。
3. native 事件新增指标字段必须向后兼容当前 SSE 的 `generation_time_ms`、`first_step_time_ms`；客户端解析未知/缺失指标时只标不可用，不能伪造为 0。

**验证命令**

```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.RuntimeProbeTest' --tests 'io.github.xororz.localdream.service.NativeBackendLaunchConfigTest' --tests 'io.github.xororz.localdream.service.NativeBackendCommandFactoryTest' --tests 'io.github.xororz.localdream.mcp.McpGenerationGatewayTest'
cd app/src/main/cpp && ./build.sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.openai.OpenAiHttpServerInstrumentedTest
```

### P04：设备运行协议与报告门禁

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 修改 | `tools/performance-harness/localdream_perf_harness.py`、`localdream_perf_models.py` | 对接本地 `/v1`、MCP 与 runtime status，执行 W1–W7、100 次可靠性和 30/60 分钟协议，写入原始样本、报告与结论状态。 |
| 新增 | `tools/performance-harness/tests/test_runtime_probe_gate.py`、`test_protocol_parity.py` | 覆盖 `UNAVAILABLE`/`REJECTED` 不入组、W6 文件/URL/下载、W7 progress/cancel/reconnect/ResourceLink 对照、100 次零失败和热稳定门禁。 |
| 新增 | `tools/performance-harness/README.md` | 固定设备前置条件、ADB/Wi-Fi 采样配置、输出目录布局、B0 冻结及恢复执行方法，不含设备性能结论。 |

**实现步骤**

1. 执行前记录 app build、设备、网络、电量、屏幕、环境温度、harness 版本和 RuntimeProbe；每条样本记录 runId、序号、分段/端到端指标、资源、输出摘要和 Outcome。
2. W4 固定冷启动及 A→B→A，W5 采集持续吞吐，W6 核验上采样/API/落盘/URL/下载，W7 在相同 fixture 下对 MCP 与 `/v1` 的 Tool/progress/cancel/reconnect/ResourceLink/下载逐项对照。
3. 最终候选要求预热外 100 次零失败，跨三次冷启动并至少两轮热测；最后四分位吞吐相对首个稳定区间的下降超过 10%、出现 severe thermal、LMKD、持续 swap/泄漏或卸载后未回稳，都写失败报告。
4. `--require-verified-runtime` 只接受 `VERIFIED`，非目标设备运行时报告结论固定为 `NOT_ACCEPTED_FOR_ONEPLUS13`，不输出性能阈值通过。

**验证命令**

```bash
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
ANDROID_SERIAL="$ANDROID_SERIAL" python3 tools/performance-harness/localdream_perf_harness.py verify --require-verified-runtime --scenario-dir tools/performance-harness/scenarios/v1 --output-dir build/perf-verification
```

第二条命令仅在 07 阶段由已连接的一加 13 执行；03–06 只运行其拒绝路径和无推理测试。

### P05：将 snapshot 的预设配置接入真实执行路径

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt`、`app/src/main/java/io/github/xororz/localdream/service/BackgroundGenerationService.kt` | 在各入口受理时解析并传递同一份 immutable snapshot，显式 preset 无效时拒绝；无绑定时才选择 compatibility fallback。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/BackendRuntimeCoordinator.kt`、`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt` | 将已解析 `sdxlLowRam`、`animaLowRam`、`animaSequentialDit` 带入本次启动请求，禁止服务层重新读取可变 SharedPreferences 覆盖 snapshot。 |
| 修改 | `app/src/test/java/io/github/xororz/localdream/openai/InferenceArbiterTest.kt`、`app/src/test/java/io/github/xororz/localdream/mcp/McpGenerationGatewayTest.kt`、`app/src/test/java/io/github/xororz/localdream/service/NativeBackendCommandFactoryTest.kt` | 验证排队 Job 在预设编辑/删除后仍使用旧 snapshot，显式/模型/默认/fallback 优先级和三 engine 字段的命令可观察性。 |

**验证命令**

```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.openai.InferenceArbiterTest' --tests 'io.github.xororz.localdream.mcp.McpGenerationGatewayTest' --tests 'io.github.xororz.localdream.service.NativeBackendCommandFactoryTest'
```

### P06：预设 CRUD 消费面、发布与回滚证据

**修改/新增文件**

| 操作 | 文件 | 内容 |
| --- | --- | --- |
| 修改 | `app/src/main/java/io/github/xororz/localdream/mcp/McpPresetStore.kt`、`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt` | 将 delete 结果中的 `reboundBindingKeys` 返回给 MCP，导入前验证 v1，保留同名自动编号。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/ui/screens/PerformancePresetScreen.kt` | 提供用户预设列表、创建、编辑、删除、导入/导出与默认/模型绑定；compatibility fallback 不提供编辑或删除动作；回退结果明确展示。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/navigation/Navigation.kt`、`app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt` | 增加预设入口与模型绑定入口，运行页显示本次选择的 snapshot 名称/修订版本。 |
| 新增 | `app/src/androidTest/java/io/github/xororz/localdream/ui/PerformancePresetScreenInstrumentedTest.kt` | 在不启动推理的前提下覆盖 CRUD、fallback 禁用、导入编号、绑定回退提示与运行页 snapshot 展示。 |
| 修改 | `app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt` | 验证 MCP CRUD、删除回退字段与 W7 protocol parity 所需的响应结构。 |

**验证命令**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.ui.PerformancePresetScreenInstrumentedTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.mcp.McpProtocolIntegrationTest
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug
```

## 4. 高风险点与强制门禁

| 风险 | 不可接受的结果 | 强制验证 |
| --- | --- | --- |
| 把 Redmi K30 或未采到 probe 的样本计入一加 13结果 | 伪造目标机性能结论 | `test_runtime_probe_gate.py` 断言 `UNAVAILABLE`/`REJECTED` 不入组；07 命令强制 `--require-verified-runtime`。 |
| 预设只是 CRUD 而不改变执行 | 用户配置无效且结果不可追溯 | `NativeBackendLaunchConfigTest` 与 `NativeBackendCommandFactoryTest` 对三个 engine 字段做逐字段命令断言；排队 Job snapshot 测试。 |
| 删除/迁移留下半条 binding 或修改旧数据 | 未来请求选择错误、历史运行不可重放 | v5→v6 instrumentation 迁移和故障注入回滚测试；Room transaction 测试。 |
| W2/W2b、冷/热或不同 Context 混组 | 统计阈值失真 | scenario/统计测试比较完整分组键并拒绝混组。 |
| 指标缺失被当成 0 或 SSE 破坏旧客户端 | 假改善或 API 回归 | native/API 集成测试同时断言旧字段保留、缺失新字段为 `UNAVAILABLE`。 |
| W7 仅验证一条协议路径 | MCP 与 `/v1` 行为不一致 | `test_protocol_parity.py` 与 `McpProtocolIntegrationTest` 对同 fixture 的生成、下载、取消、重连、ResourceLink 全量比对。 |

## 5. 执行顺序与阶段验证

1. P01 先建立可审计场景与纯主机测试，P02 再建立 schema、绑定和 migration 基座。
2. P03 把 runtime/指标证据接入应用与 native，P05 以 immutable snapshot 打通 API、MCP、服务和命令行。
3. P04 在上述接口稳定后对接 harness，P06 最后完成 UI/MCP 消费面及全部构建检查。
4. 实现阶段结束前必须执行：

```bash
python3 -m unittest discover -s tools/performance-harness/tests -p 'test_*.py'
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug
cd app/src/main/cpp && ./build.sh
```

## 6. 测试发布、回滚与设备验收边界

本项目是 GitHub Android 单仓，不存在 GitLab、`test` 分支、Pod 或 HTTP 测试环境。实际测试发布链路固定为：完成本地自动化与 debug APK 构建，以中文提交信息提交精确交付文件，推送 GitHub `master`，再把最终 APK 安装到当前 Redmi K30 完成非推理 UI/协议检查。发布证据至少包括 commit、远端 `master` 对齐、APK、上述自动化命令结果和设备检查；`docs/loop-records/` ledger 不提交。没有一加 13 时，不得把这些证据标记为性能、可靠性或热稳定性验收通过。

回滚只允许恢复到本次发布提交之前已验证的 APK/提交；数据库 v6 migration 不做破坏性降级。若发布后发现配置解析、binding 或 native 指标问题，先停用新预设选择和 harness 发布结论，保留既有 `Compatibility fallback` 与已受理 Job snapshot，再以前进修复提交处理。不得通过删除用户数据库、重写历史 snapshot 或静默重编译 Context 来回滚。

07 业务验收的前置条件是：一加 13 已连接、`RuntimeProbe=VERIFIED`、对应 W1–W7 资产已安装，且能按 P04 命令生成原始 RunManifest、样本和报告。V79 重编译输入缺失、商店签名和最终用户生产发布仍不在本轮范围。
