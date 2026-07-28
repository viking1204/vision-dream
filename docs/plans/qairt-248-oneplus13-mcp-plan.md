# QAIRT 2.48 / OnePlus 13 / MCP 实施计划

> 需求：`qairt-248-oneplus13-mcp`
> 计划阶段：`03-plan`
> 实施顺序：先取得可审计的 SDK 输入，再改共享领域层，最后接入独立 MCP transport、UI 和真机门禁。不得以 listener 合并、共享 Token 或未核验 runtime 作为捷径。

## 1. 实施前提、交付边界与停止条件

### 1.1 M1 的外部输入门禁

**当前状态：已满足（2026-07-28）。** 官方 QAIRT
`v2.48.40.260702.zip` 已由用户在 OnePlus 13 的 Software Center 下载并复制到
`/Users/likaixuan/Library/Android/qairt/archives/`。archive SHA-256 为
`72bf9fbb177e65d05483b5cfc1e10a2864307fb031bcd7b9943b9c32693757b8`，
CRC 全量校验通过；`sdk.yaml`、Android `aarch64-android`、Hexagon V79
runtime/skel 和 LICENSE 已逐项实测。构建机 SDK 根目录是
`/Users/likaixuan/Library/Android/qairt/2.48.40.260702`。

下列内容是本门禁的通用复现要求；它不再是当前 04 阶段的
`external_blocker`：

1. archive 的官方 product/version/下载时间、原始 archive SHA-256 与厂商 checksum 或 signature 验证结果；
2. archive manifest 中 Android `aarch64-android`、Hexagon `v79` runtime/skel 的实际相对路径与每个进入 APK 文件的 SHA-256；
3. 解压目录在构建机上的绝对路径，以及清单与落盘文件逐项比对的结果。

Windows 验证命令（将 `QAIRT_ARCHIVE` 和 `QAIRT_SDK_ROOT` 替换为已下载的准确绝对路径）是：

```powershell
Get-FileHash -Algorithm SHA256 $env:QAIRT_ARCHIVE
Get-ChildItem -Recurse $env:QAIRT_SDK_ROOT | Get-FileHash -Algorithm SHA256
```

macOS/CI 复核命令是：

```bash
shasum -a 256 "$QAIRT_ARCHIVE"
find "$QNN_SDK_ROOT/lib/aarch64-android" "$QNN_SDK_ROOT/lib/hexagon-v79" -type f -print0 | xargs -0 shasum -a 256
```

本次 archive 门禁已经解除。当前 2.48 核心库 SHA-256 为
`783a35b5101260ea9a73e6fbeca62adb08157fc30b97644e5c140da13b41f905`；
仓库核心、runtime manifest 与 debug APK 内核心三方 SHA 一致，APK 内嵌
Build ID `v2.48.40.260702151143`。P1 的 archive/build/packaging 已完成；
模型 compatibility evaluator 与 VM-09 启动拒绝测试仍按 P1 的本地差量推进，
不得重新写成 archive/Windows 外部阻塞。VM-10 仍属于 P8 真机门禁。

### 1.2 已接受边界

V79 专用 Context Binary 重编译及“极限优化完成”不在本轮完成条件。ONNX/DLC、量化配置、校准集和既有编译参数缺失时，M5 只记录为 `accepted_boundary`，不能用已有 `libQnnHtpV79*.so`、旧 Context 或其他设备结果替代。

### 1.3 发布边界和回滚

- 仅产出 `app/build/outputs/apk/debug/VisionDream_armv8a_*.apk` 或测试环境等价 debug APK，并在 OnePlus 13（PJZ110 / SM8750 / HTP V79 / 24 GB）验收。
- 不做 release 签名、商店提交、最终用户生产发布或任何生产写入。
- 测试安装失败或 VM-09/VM-10 未过时，卸载候选 debug APK，重新安装上一份已验证 debug APK；代码回滚使用本功能分支的 Git revert/MR，不回合并 `test` 分支内容。
- MCP 回滚是本机 UI 停止 `McpService`、关闭 LAN、撤销 MCP client grant 并使 session/capability token 失效；不得停止 `OpenAiApiService`。OpenAI 回归异常时保持 MCP 停止并按其现有独立开关回滚。

## 2. 需求到任务的可追溯矩阵

| 规格需求 | 实施任务 | 自动验证 | 真机/外部验证 |
| --- | --- | --- | --- |
| 性能预设 CRUD、版本、不可变 Job snapshot、导入冲突和 v4 连续迁移（规格 3.1–3.3、6.1–6.3） | P2、P6 | `PerformancePresetRepositoryTest`、`InferenceJobRepositoryTest`、`AppDatabaseMigrationTest`、`HistoryBackupTest` | 管理员在 App 创建、编辑、删除、导入预设，并确认已受理 Job 的 revision 不变。 |
| OpenAI 与 MCP 的独立 Service、listener、端口、Token、开关及共享调度/lease（规格 2、4.1、5.1） | P3、P4、P7 | `InferenceDispatcherTest`、`BackendRuntimeLeaseManagerTest`、`McpServiceIsolationInstrumentedTest`、既有 OpenAI tests | 四种开关组合、端口冲突和单服务停止；正在生成时停止另一服务。 |
| `2025-11-25` Streamable HTTP、POST、GET/SSE、Session、Task/Job 与 conformance（规格 4.2、4.5） | P4、P6 | `McpProtocolIntegrationTest`、`McpTaskProjectionTest`、固定版本 conformance active suite | loopback 与显式 LAN 上 initialize、重连、DELETE、取消和图片下载。 |
| scope、逐次本机 `confirmationId`、审计和 capability URL（规格 4.3–4.5、5.2） | P5、P6、P7 | `McpAuthorizationTest`、`McpConfirmationStoreTest`、`McpImageCapabilityStoreTest`、`McpAuditRepositoryTest` | 逐项批准删除/停止/撤销；确认缺失、过期、重放、参数不匹配均被拒绝。 |
| QAIRT runtime manifest、全套原子替换、模型 compatibility 与性能门禁（规格 3.4、7 VM-09/10） | P1、P8 | `RuntimeManifestTest`、`ModelMetadataTest`、`NativeBackendCommandFactoryTest` | archive 已核验；继续执行 VM-09，并在 OnePlus 13 完成 W1–W7、100 次可靠性、30–60 分钟热稳定性。 |
| OpenAI `/v1` 与现有图片 URL 不回归（规格 6.2、7 VM-03/08） | P3、P6 | `OpenAiJsonTest`、`TemporaryImageStoreTest`、`OpenAiHttpServerInstrumentedTest` | 10 分钟内同 OpenAI 图片 URL 可重复读取；MCP capability 第二次为 404。 |

| 验证矩阵项 | 执行任务 | 通过证据 |
| --- | --- | --- |
| VM-01 | P2 | v4→v5 migration fixture、旧 history 与 fallback 断言。 |
| VM-02 | P2 | preset CRUD、revision、导入冲突与 Job snapshot 回归。 |
| VM-03 | P2、P3 | v1 history backup、preset envelope 与 OpenAI JSON golden 回归。 |
| VM-04 | P4、P6 | 四种服务组合、独立端口和 bind-failure 仪器测试。 |
| VM-05 | P3、P6 | 双 transport/UI 并发、停止和 `executionFinished` lease 断言。 |
| VM-06 | P4、P6 | `2025-11-25` POST/GET/SSE/session 与固定版本 conformance active suite。 |
| VM-07 | P5 | scope、Host/Origin、LAN、限流、逐次 confirmation 与审计脱敏测试。 |
| VM-08 | P5、P6 | Job/Task 状态投影、幂等取消、SSE 和一次性图片 capability。 |
| VM-09 | P1 | runtime manifest、完整库集、digest/ABI/HTP/Context 拒绝与启动测试。 |
| VM-10 | P8 | OnePlus 13 W1–W7、100 次、30–60 分钟和性能门槛报告。 |

## 3. 文件清单

下列是实施时允许改动或新增的文件；实现不得把 MCP 代码塞入 OpenAI listener，也不得改动 vendored `app/src/main/cpp/3rdparty/`。

| 类型 | 路径 | 具体改动 |
| --- | --- | --- |
| 修改 | `app/src/main/cpp/CMakeLists.txt` | 仅在 P1 archive 证据齐全后，将所有 Android/Hexagon QNN copy 输入同步切至 2.48，并调用 manifest 生成输入；禁止 2.44/2.48 混用。 |
| 新增 | `tools/runtime/generate-runtime-manifest.sh`、`tools/runtime/verify-runtime-manifest.sh` | 前者仅从已核验 archive manifest 和 SDK 根目录生成 `runtime-manifest.json`；后者在构建前逐项检查 archive identity、路径、SHA-256、ABI、HTP target 与 Context fingerprint，任何缺项或混版均以非零退出。 |
| 新增 | `app/src/main/assets/runtime/runtime-manifest.json` | 保存 SDK/archive identity、ABI、HTP target、Context fingerprint、APK runtime 库相对路径与 SHA-256；只能由 `tools/runtime/generate-runtime-manifest.sh` 生成，并由 `tools/runtime/verify-runtime-manifest.sh` 在纳入 APK 前校验。 |
| 修改 | `app/build.gradle.kts`、`gradle/libs.versions.toml` | 增加已锁定的 MCP transport/conformance 测试依赖与 manifest 打包/校验任务；不改变 release 签名配置。 |
| 修改 | `app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings.xml` | 注册非导出的 `McpService`、其 foreground 类型、通知和安全状态文案。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetEntity.kt`、`InferenceJobEntity.kt`、`PresetSnapshotEntity.kt`、`McpClientGrantEntity.kt`、`McpAuditEventEntity.kt` | Room 实体、wire enum 和敏感字段最小化持久化。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetDao.kt`、`InferenceJobDao.kt`、`McpClientGrantDao.kt`、`McpAuditEventDao.kt` | 事务式 CRUD、snapshot、owner 查询和 append-only 审计 DAO。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt`、`HistoryEntity.kt`、`HistoryDao.kt` | v4→v5 单一连续 migration、DAOs、`generation_history` nullable 关联列/索引；保留无 destructive fallback。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/data/PerformancePresetRepository.kt`、`InferenceJobRepository.kt`、`RuntimeManifest.kt`、`RuntimeCompatibilityEvaluator.kt` | 预设 schema/导入导出、Job/snapshot、runtime 指纹和模型可用性判定。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/data/HistoryBackup.kt`、`ModelMetadata.kt` | backup version 兼容读取和 runtime compatibility metadata；不改变既有 v1 导入与旧 schema 可读性。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/inference/InferenceDispatcher.kt`、`BackendRuntimeLeaseManager.kt`、`DomainGateway.kt` | 进程唯一有界串行调度、owner/job lease、白名单领域入口和停止编排。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/openai/BoundedSerialExecutor.kt`、`InferenceArbiter.kt`、`BackendRuntimeCoordinator.kt`、`OpenAiApiController.kt`、`OpenAiApiService.kt`、`service/BackendService.kt` | 将 queue/lease 生命周期从 OpenAI Service 抽离，保持 `/v1`、Token 与图片 URL 行为；仅实际执行完成后释放 reservation/lease。 |
| 新增 | `app/src/main/java/io/github/xororz/localdream/mcp/McpService.kt`、`McpHttpServer.kt`、`McpJsonRpcController.kt`、`McpSessionRegistry.kt`、`McpClientCredentialStore.kt`、`McpConfirmationStore.kt`、`McpAuditRepository.kt`、`McpImageCapabilityStore.kt`、`McpProtocolModels.kt`、`McpToolRegistry.kt` | 独立 foreground service/HTTP listener、2025-11-25 JSON-RPC/SSE/session、Keystore grant、confirmation、审计、capability 和静态白名单。 |
| 修改 | `app/src/main/java/io/github/xororz/localdream/ui/screens/RemoteScreen.kt`、`MainActivity.kt` | 增加 MCP 独立卡片、端口/LAN/client/scope/复制/轮换/撤销/本机确认 UI；不改变 OpenAI 控件归属。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/data/PerformancePresetRepositoryTest.kt`、`InferenceJobRepositoryTest.kt`、`HistoryBackupTest.kt`、`RuntimeManifestTest.kt` | 覆盖 VM-01/02/03/09 的纯 JVM 规则。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/inference/InferenceDispatcherTest.kt`、`BackendRuntimeLeaseManagerTest.kt` | 覆盖模型 affinity、公平性、队列满、取消与 `executionFinished` 之后释放。 |
| 新增 | `app/src/test/java/io/github/xororz/localdream/mcp/McpAuthorizationTest.kt`、`McpConfirmationStoreTest.kt`、`McpImageCapabilityStoreTest.kt`、`McpTaskProjectionTest.kt`、`McpAuditRepositoryTest.kt` | 覆盖 VM-06/07/08 的协议无关规则。 |
| 新增 | `app/src/androidTest/java/io/github/xororz/localdream/data/db/AppDatabaseV4ToV5MigrationTest.kt`、`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt`、`McpServiceIsolationInstrumentedTest.kt` | 覆盖真实 Room migration、HTTP/SSE/session 与双 Service 隔离。 |
| 新增 | `tools/mcp-conformance/package.json`、`tools/mcp-conformance/package-lock.json`、`tools/mcp-conformance/run-active-suite.mjs` | 锁定官方 conformance runner 版本，向已安装的 debug APK MCP URL 发起 active suite；脚本仅在明确传入 loopback/LAN URL、Token 和设备序列号时运行。 |
| 新增 | `tools/benchmark/oneplus13-w1-w7.sh`、`tools/benchmark/parse-oneplus13-report.mjs` | 固定 W1–W7、采集设备/SDK/runtime fingerprint、p50/p95/MAD、100 次和热稳态，生成可审查结果；脚本拒绝非目标产品/HTP 指纹。 |

## 4. 分步实施与每步门禁

### P1 — QAIRT 2.48 输入、manifest 与 runtime 兼容（先决）

1. [x] 完成第 1.1 节 archive 证据；记录准确 Android aarch64、Hexagon V79 runtime/skel 路径和许可边界。
2. [x] `app/src/main/cpp/build.sh` 从已核验 SDK 生成 `app/src/main/assets/qairt-runtime-manifest.json`，记录核心及完整 APK runtime 集合的 SHA-256，并随 APK 打包 `QAIRT_NOTICE.txt`。
3. [x] CMake 默认 SDK 目标切至 2.48，configure 阶段 fail-closed 校验 version/build ID 和必需文件；fresh native build 与 debug APK 三方 SHA 校验通过。
4. 扩展 `ModelMetadata` 和 compatibility evaluator：未知或不匹配仅能走 `COMPATIBILITY_FALLBACK`，不能设默认或进入目标性能预设。

已通过：`QNN_SDK_ROOT="/Users/likaixuan/Library/Android/qairt/2.48.40.260702" ./app/src/main/cpp/build.sh`、`./gradlew :app:assembleDebug`、仓库/manifest/APK 三方 SHA 与 Build ID 校验。待执行：`./gradlew :app:testDebugUnitTest --tests '*RuntimeManifestTest' --tests '*ModelMetadataTest' --tests '*NativeBackendCommandFactoryTest'`。P1 剩余项是本地可执行差量，不是外部输入阻塞。

### P2 — 预设、Job、快照与连续数据库迁移

1. 将 Room 升至 v5，新增预设、Job、快照、client grant、审计表和必要索引；migration 仅新增结构并创建唯一 `COMPATIBILITY_FALLBACK`，不回填旧 history。
2. 落地 UUID/revision/selector/schema/config 校验、默认预设事务、导入批量原子性、冲突重命名和 fallback 不可删规则。
3. 在入队同一事务写 `InferenceJob` 和不可更新 `PresetSnapshot`；历史只追加 nullable job/preset/runtime 关联。
4. 扩展 history backup 版本读取；preset export 使用独立 format，拒绝未知更高 history version，永不导出 grant/Token/Job/历史图片。

验证：`./gradlew :app:testDebugUnitTest --tests '*PerformancePresetRepositoryTest' --tests '*InferenceJobRepositoryTest' --tests '*HistoryBackupTest'`、`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.data.db.AppDatabaseV4ToV5MigrationTest`。迁移测试需以 v4 fixture 断言旧 history 数量、ID、路径和筛选结果不变，且新增关联全为 null。

### P3 — 公共调度和 runtime lease，保持 OpenAI 行为

1. 新建进程唯一 `InferenceDispatcher` 与 `BackendRuntimeLeaseManager`，将 OpenAI private executor 的排队、公平性、job ownership 和 active execution 语义移入公共层。
2. 让 UI、OpenAI 和 MCP 通过 `DomainGateway` 请求同一个 dispatcher；唯一 native pipeline 不并行，同模型优先不得饿死其他模型。
3. 重构 `OpenAiApiService.onDestroy()`：它只拒绝自身新请求、关闭自身 listener、取消自身 Job，并在实际 `executionFinished` 后释放自己 lease；它不关闭 MCP listener/Session，也不直接终止仍被其他 owner 持有的 backend。
4. 固化 backend 仅在 UI、OpenAI、MCP service 与 active Job lease 全部为零后停止的规则；保留 `InferenceArbiter` reservation 至 native 调用实际退出。

验证：`./gradlew :app:testDebugUnitTest --tests '*InferenceDispatcherTest' --tests '*BackendRuntimeLeaseManagerTest' --tests '*BoundedSerialExecutorTest' --tests '*InferenceArbiterTest' --tests '*OpenAiJsonTest' --tests '*TemporaryImageStoreTest'`，以及 P7 的双 Service 仪器测试。任一跨服务取消、提前 release 或 OpenAI URL 回归均阻断 MCP 接入。

### P4 — 独立 MCP Service、transport 和协议状态机

1. 新增非导出的 `McpService` 与 `McpHttpServer`，以独立前台通知、状态、开关、port、listener、Keystore credential store 和 `/mcp` 路径工作；默认仅绑定 `127.0.0.1:8810`。
2. 严格校验 1024–65535、8081、8808、当前 OpenAI 有效端口和 bind 冲突；失败仅将 MCP 标为 error。LAN 必须经已解锁本机 UI 开启并使用单独 grant，关闭 LAN 只关闭 LAN listener/Session。
3. 实现固定 `2025-11-25` 的 `initialize`、认证后 POST、GET/SSE resume、DELETE、15 分钟 idle expiry、服务重启/Token 轮换/撤销失效；仅实际启用的 capability 参与 initialize 声明。
4. 对 Host/Origin、Token generation、session/client/transport 绑定、固定窗口限流和 JSON-RPC `data.code` 做输入边界；错误不得泄露 exception、Token、路径或 native command。

验证：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.mcp.McpProtocolIntegrationTest`。该测试必须覆盖 initialize、未经初始化拒绝、POST、GET/SSE/reconnect/reset、DELETE、idle/restart、非法 Host/Origin、端口冲突、loopback/LAN 隔离与稳定错误 code。

### P5 — Tool 白名单、确认、审计、Job/Task 和结果 capability

1. 用静态 `McpToolRegistry` 为规格 4.3 所列模型、生成、预设、提示词、下载/资产、诊断和受控动作定义 input/output schema、scope 和风险；拒绝未注册 Tool、额外字段、未知 enum、超限 binary 和未授权 scope，均不得抵达领域层。
2. 对每一个 `DESTRUCTIVE` Tool 先生成规范化 action/target/参数摘要，再要求已解锁本机 UI 逐次签发 confirmation。confirmation 绑定 client、Token generation、action、digest、targets，60 秒且一次消费；elicitation 不参与授权。
3. 每次调用追加最小化审计事件；禁止落库 Token、confirmation、原始 prompt、图片、绝对路径、文件内容和复制文本。
4. 同一 Job 映射 MCP Task 的 working/completed/failed/cancelled；跨 client 统一返回 `JOB_NOT_FOUND`。MCP 图片 capability 限制为 client/job/listener/mime 绑定的 60 秒一次性 GET，成功传输开始时原子消费。

验证：`./gradlew :app:testDebugUnitTest --tests '*McpAuthorizationTest' --tests '*McpConfirmationStoreTest' --tests '*McpImageCapabilityStoreTest' --tests '*McpTaskProjectionTest' --tests '*McpAuditRepositoryTest'`。每个 destructive action 均测 absence、expiry、replay、client/token/action/target/digest mismatch 与拒绝后的零副作用；图片首读成功、二读和过期均为 404。

### P6 — 管理 UI、配置复制与协议 conformance

1. 在 `RemoteScreen` 增加独立 MCP 管理区域：开关、有效端口、loopback/LAN 状态、client scope、创建/轮换/撤销、审计查询、提示性错误和本机确认卡片；OpenAI 控件和数据源保持独立。
2. 只在已解锁用户明确点击复制时渲染实际 listener URL、Bearer、协议版本和授予 scope；明文 Token 不进状态恢复、日志、审计或截图自动化文本。
3. 固定 conformance runner 版本并让其对已安装 APK 的 MCP endpoint 运行 active suite；执行前将实际声明 capability 与预期结果写入 runner 配置，unexpected failure 使门禁失败。

验证：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.mcp.McpServiceIsolationInstrumentedTest`、`cd tools/mcp-conformance && npm ci && node run-active-suite.mjs --serial "$ANDROID_SERIAL" --base-url "$MCP_BASE_URL" --token "$MCP_TEST_TOKEN"`。隔离测试需覆盖 OpenAI-only、MCP-only、both、neither 和任一 listener bind 失败；conformance 使用临时测试 grant，完成后从本机 UI 撤销。

### P7 — 完整自动化回归与 debug APK

1. 合并 P2–P6 测试并修复 P0/P1/P2 review finding；不删除既有 `BoundedSerialExecutorTest`、`InferenceArbiterTest` 或 OpenAI golden/instrumentation coverage。
2. 静态检查、unit、instrumentation、debug build 都必须通过；仅产生 debug APK。

验证命令：

```bash
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug
```

### P8 — OnePlus 13 性能、质量与热稳定性门禁

1. 在确认 QAIRT archive、runtime manifest 和目标设备指纹一致后安装 debug APK；收集 cold/hot 首张、第二张、Context load、CLIP、UNet、VAE、返回链路、RSS/FD/MemHandle、Qmem、HTP 功耗/热策略。
2. 使用固定 W1–W7 各场景的 seed、尺寸和模型，输出 p50、p95、MAD/置信区间；同一候选至少 100 次且连续 30–60 分钟。
3. 对 QAIRT 2.48 + 旧 Context 执行门槛：热生成 p50 不退化超过 2%，p95 不退化超过 3%；未达到 15% 提升目标时只记录候选事实，不改默认预设且不声称极限优化完成。

验证：

```bash
adb -s "$ANDROID_SERIAL" shell getprop ro.product.device
adb -s "$ANDROID_SERIAL" shell getprop ro.soc.model
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/VisionDream_armv8a_*.apk
ANDROID_SERIAL="$ANDROID_SERIAL" tools/benchmark/oneplus13-w1-w7.sh --apk app/build/outputs/apk/debug/VisionDream_armv8a_*.apk --report-dir build/oneplus13-benchmark
node tools/benchmark/parse-oneplus13-report.mjs build/oneplus13-benchmark
```

P8 结果必须同时包含 runtime manifest digest、设备标识、W1–W7 原始样本摘要和门禁结论。设备不是 PJZ110/SM8750/HTP V79、缺 archive 证据、缺 100 次或缺热稳态时，不得以其他设备结果替代。

## 5. 交付顺序与高风险控制

```text
P1 官方 archive/manifest ──────────────────────────────> 2.48 CMake/manifest ──> P7 2.48 debug APK ──> P8 OnePlus 13
                                                        ▲
P2 Room + preset/job snapshot ──> P3 dispatcher/lease ─┼─> P4 MCP transport ──> P5 安全边界 ──> P6 UI/conformance
                                                        │
                         P2–P6 可在现有 2.44 输入上完成结构与自动化准备；不产生 2.48 交付声明
```

| 风险 | 防护及可执行验证 |
| --- | --- |
| 2.48 archive 误配、混版或 V79 文件缺失 | P1 只接受厂商来源 archive、逐文件 SHA-256 和 manifest；`RuntimeManifestTest` + CMake/debug APK 前置校验。 |
| 任一 Service 停止误杀另一个正在运行的推理 | P3 lease/reference 计数和 `executionFinished` 门槛；`InferenceDispatcherTest`、`BackendRuntimeLeaseManagerTest`、`McpServiceIsolationInstrumentedTest`。 |
| MCP 成为万能远控或凭据泄露点 | P4/P5 固定 registry/schema/scope、Keystore grant、Host/Origin/限流、逐次 confirmation 和最小化审计；`McpAuthorizationTest`、`McpConfirmationStoreTest`、审计敏感字段断言。 |
| Session/SSE/Task 生命周期与 Job 脱节 | P4/P5 使用同一 Job ID 和 event sequence；`McpProtocolIntegrationTest`、`McpTaskProjectionTest` 和 conformance active suite。 |
| 已受理请求受预设编辑/删除或历史迁移损坏 | P2 同事务 snapshot、v4 fixture migration 和 fallback 不可删；`AppDatabaseV4ToV5MigrationTest`、`PerformancePresetRepositoryTest`。 |
| MCP 结果 URL 复用 OpenAI 凭据或破坏 OpenAI 兼容 | P5 独立 capability store，60 秒一次消费；`McpImageCapabilityStoreTest` 与既有 `TemporaryImageStoreTest`/`OpenAiHttpServerInstrumentedTest`。 |
| 仅凭单张速度把不稳定候选设为默认 | P8 W1–W7、100 次、30–60 分钟、质量/热门禁；达不到门槛保持 compatibility fallback。 |

## 6. 阶段完成判定

P1 的官方 archive 输入门禁和 2.48 构建已完成；P1 剩余 compatibility/VM-09 属于本地可执行差量。P8 另需目标 OnePlus 13 真机。P2–P6 的结构性完成以各自指定的 Gradle 测试和 MCP conformance 为准；P7 的 2.48 debug APK 必须同时满足 P1；P8 以目标设备报告为准。M5 的 V79 Context 编译输入缺失保留为 accepted boundary，不阻塞本计划，也不允许被列为已完成。

本计划覆盖规格 VM-01 至 VM-10。QAIRT 2.48 构建和 APK 打包已验真；VM-09 的模型兼容拒绝测试与 VM-10 的 OnePlus 13 性能结果仍须分别完成，不能由构建成功替代。
