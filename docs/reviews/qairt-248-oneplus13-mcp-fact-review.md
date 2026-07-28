# QAIRT 2.48 / OnePlus 13 / MCP 需求事实 Review

> 审查日期：2026-07-28
> 输入：`docs/requirements/qairt-248-oneplus13-mcp.md`、`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md`
> 结论：[实锤，置信度高] 现有工程具备可复用的 OpenAI HTTP 网关、native 推理互斥/有界串行调度、模型/历史数据能力，但在本评审发生时还没有 MCP、性能预设领域模型或 QAIRT 2.48 制品。以下下载前事实保留用于审计，不代表当前实施状态。
>
> **2026-07-28 实施证据补记：** 用户已从官方 Software Center 取得
> `v2.48.40.260702.zip`。archive SHA-256 为
> `72bf9fbb177e65d05483b5cfc1e10a2864307fb031bcd7b9943b9c32693757b8`，
> CRC 全量校验通过；`sdk.yaml`、Android `aarch64-android`、Hexagon V79
> runtime/skel 和 LICENSE 已实测。QAIRT SDK 已落盘至
> `/Users/likaixuan/Library/Android/qairt/2.48.40.260702`，2.48 core、runtime
> manifest 与 debug APK 已构建并完成三方哈希核对。因此下载与 archive
> 内容核验不再是 `04-implementation` 的 external blocker。

## 审查范围与证据方法

| 对象 | 已核验事实 | 证据 |
| --- | --- | --- |
| 原始需求与人工边界 | MCP/OpenAI 必须独立 Service、listener、端口、鉴权、开关；M5/V79 编译资料缺失为已接受边界；SDK 必须从官方渠道取得 | `docs/requirements/qairt-248-oneplus13-mcp.md:1-123`；`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md:3-16` |
| Android 组件 | Manifest 登记了 `BackendService`、`BackgroundGenerationService`、`ModelDownloadService`、`RemoteHostService`、`OpenAiApiService`，没有 `McpService` | `app/src/main/AndroidManifest.xml:59-87`；`rg -n -i 'mcp|model context protocol|streamable|confirmationid' app/src/main/java app/src/test app/src/androidTest --glob '*.kt'` 无 MCP 命中 |
| Room/DAO | `local_dream.db` 现为 v4，只含 `generation_history`、`prompt_templates`；已有 v1-v4 migration，显式禁止 destructive fallback | `app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt:10-142`；`HistoryEntity.kt`；`HistoryDao.kt`；`PromptTemplateDao.kt` |
| 运行时与 SDK | CMake 默认 SDK 是 `2.44.0.260225`；本机仅有该目录；APK assets 中的 Android `libQnnHtp.so`、`libQnnSystem.so`、`libQnnHtpV79Stub.so` 与该 SDK 的 SHA-256 一致 | `app/src/main/cpp/CMakeLists.txt:7-69`；`find /Users/likaixuan/Library/Android/qairt -maxdepth 1 -mindepth 1 -type d -print`；本审查执行的 `shasum -a 256` |
| 官方获取 | 已登录 Qualcomm Software Center 的 `Qualcomm AI Runtime SDK` 条目列出 `2.48.40.260702`；页面显示 Windows/X86、发布日 `2026/7/8`、`2272.63 MB`，并明确下载需 Windows Software Center Desktop | BrowserOS page 30：`https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_SDK?osArch=X86&osType=Windows&version=2.48.40.260702` 的 `snapshot --json`/`eval` |

## 已有能力

### 1. OpenAI HTTP 网关与领域调用基础

- [实锤，置信度高] `OpenAiApiService` 是独立前台 Service，构造 `OpenAiHttpServer`、`OpenAiApiController` 和 `BoundedSerialExecutor`；`OpenAiApiController` 已提供已安装模型、生成、编辑、放大、健康检查等 REST 路由。
  证据：`app/src/main/java/io/github/xororz/localdream/service/OpenAiApiService.kt:63-196`；`app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt:37-88`。
- [实锤，置信度高] `InferenceArbiter.process` 为 UI 与 API 提供 process-wide native 推理互斥；接受的 API 请求由 `BoundedSerialExecutor` 单线程执行。队列有容量上限、满队列返回 `QUEUE_FULL`，同模型优先最多连续超车三次，随后回到 FIFO，具备防饥饿机制。
  证据：`app/src/main/java/io/github/xororz/localdream/openai/InferenceArbiter.kt:11-64`；`app/src/main/java/io/github/xororz/localdream/openai/BoundedSerialExecutor.kt:19-122,181-245`；`app/src/test/java/io/github/xororz/localdream/openai/BoundedSerialExecutorTest.kt:16-327`；`InferenceArbiterTest.kt:13-89`。
- [实锤，置信度高] `BackendRuntimeCoordinator` 可在持有网关串行执行位期间启动/复用 `BackendService`，并等待 `127.0.0.1:8081/health`；已安装模型目录通过 `InstalledModelCatalog` 和 `ModelRepository` 暴露。
  证据：`app/src/main/java/io/github/xororz/localdream/openai/BackendRuntimeCoordinator.kt:23-130`；`app/src/main/java/io/github/xororz/localdream/openai/InstalledModelCatalog.kt`。
- [业务解读] 这些组件解决的是“本机 UI 与 OpenAI 客户端不能同时抢占同一套 native 扩散推理进程”的问题。MCP 应复用的是这个领域仲裁和模型目录，而不是复制生成/模型加载实现。

### 2. 既有认证、图片与测试基础

- [实锤，置信度高] OpenAI API key 保存于私有 `SharedPreferences(openai_api)`；缺失时生成 24-byte 随机 Base64URL key。已有 API key 轮换和队列容量持久化。
  证据：`app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiPreferences.kt:8-50`。
- [实锤，置信度高] OpenAI 图片 URL 由 `TemporaryImageStore` 提供 32 位随机 token、10 分钟 TTL、12 条/64 MiB 容量上限；GET 下载可以不带 Bearer key，服务停止会清空内存 store。
  证据：`app/src/main/java/io/github/xororz/localdream/openai/TemporaryImageStore.kt:13-137`；`OpenAiApiController.kt:35-51,90-93,421-483,523-531`；`OpenAiHttpServerInstrumentedTest.kt:229-287`。
- [实锤，置信度高] HTTP transport 已有 Android 设备测试，覆盖 IPv4 socket、解析、超时、未授权与临时图片 capability 路径；JVM 测试覆盖串行队列、仲裁和临时图片 TTL。
  证据：`app/src/androidTest/java/io/github/xororz/localdream/openai/OpenAiHttpServerInstrumentedTest.kt`；`app/src/test/java/io/github/xororz/localdream/openai/BoundedSerialExecutorTest.kt`、`InferenceArbiterTest.kt`、`TemporaryImageStoreTest.kt`。

### 3. 历史数据、模型配置与远程协议

- [实锤，置信度高] `generation_history` 当前字段包括 `modelId`、尺寸、模式、steps、cfg、seed、prompt、scheduler、`origin`、`mimeType`、`requestId`；`origin` 的业务语义是生成来源，默认枚举值为 `local_app`，而非 MCP Job 或性能预设标识。
  证据：`app/src/main/java/io/github/xororz/localdream/data/db/HistoryEntity.kt:8-52`；`AppDatabase.kt:84-105`；`app/src/main/java/io/github/xororz/localdream/data/AssetOrigin.kt`。
- [实锤，置信度高] 旧版 `filesDir/history/<modelId>/<timestamp>.json` 会一次性迁入 Room，迁移成功后删除原 JSON；其字段只有历史生成参数，不能承载 preset/MCP/SDK provenance。
  证据：`app/src/main/java/io/github/xororz/localdream/data/HistoryMigration.kt:47-146`。
- [实锤，置信度高] `HistoryBackup` 的 JSON manifest v1 导出/导入仅覆盖旧 generation 字段与 `favorite`，未序列化 `origin`、`mimeType`、`requestId`；旧备份导入后会使用 Room 默认值。
  证据：`app/src/main/java/io/github/xororz/localdream/data/HistoryBackup.kt:31-212`；`HistoryEntity.kt:42-52`。
- [实锤，置信度高] 模型目录仍可含 `config.json`（默认 prompt、negative prompt、steps、cfg、scheduler）和 `.vision-dream-model.json`（schema v1、内容评级、源仓库 metadata）；ParamShare 是 clipboard JSON v1。均非版本化性能 preset。
  证据：`app/src/main/java/io/github/xororz/localdream/data/ModelConfig.kt:12-105`；`ModelMetadata.kt:57-166`；`app/src/main/java/io/github/xororz/localdream/utils/ParamShare.kt:1-160`。
- [实锤，置信度高] 已有 `RemoteHostService`/`RemoteProtocol` 使用独立控制端口 `8808` 和 native 端口 `8081`，其职责是设备对设备的 host 控制协议，不是 MCP。
  证据：`app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:7-38`；`app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt`。

## 真实缺口

| 编号 | 缺口 | 代码事实与影响 |
| --- | --- | --- |
| FR-01 | MCP transport/协议不存在 | 未发现 `McpService`、JSON-RPC、2025-11-25 Streamable HTTP、GET/SSE、session DELETE、Task、Resource、Prompt 或 conformance suite。必须新建独立 Android Service/listener/auth/switch，不能给 OpenAI controller 增加 MCP route。证据见 Manifest 与本章范围命令。 |
| FR-02 | 性能预设领域模型不存在 | 搜索 `preset/profile` 仅命中主题 preset、模型安装 profile 和 UI 文本；Room 也没有 preset entity/DAO。`GenerationDefaults`/`ModelConfig` 是可变默认值，不具备 CRUD、版本、不可变请求 snapshot、排序/导入冲突或 compatibility fallback。 |
| FR-03 | 两种服务不能满足独立并发与独立配置 | `OpenAiApiService` 启动时若 `RemoteHostService` 正在运行即失败；Remote UI 也禁用 host 与 API 并开。OpenAI port 是常量 `8809`，无端口/bind/LAN 开关字段。MCP 不得复用 Remote Host 的互斥模型。证据：`OpenAiApiService.kt:72-85,120-124`；`OpenAiApiPreferences.kt:41-50`；`ui/screens/RemoteScreen.kt:462-566`。 |
| FR-04 | 当前 OpenAI 默认暴露 LAN | `OpenAiHttpServer.start()` 固定 bind `0.0.0.0`，注释明确同时接受 `127.0.0.1` 与 LAN IPv4；这与本需求“默认 loopback、LAN 仅显式开启”不符。MCP 的 bind 行为不能照抄。证据：`OpenAiHttpServer.kt:67-86`。 |
| FR-05 | MCP 破坏性授权/审计/Scope/Job 无现成模型 | 现有 bearer key 只做 transport auth；没有 client、scope、confirmationId、请求绑定、到期、拒绝、审计或 Job 持久化类型。已有 `requestId` 仅记录生成历史，不能等价为 MCP Job。证据：`OpenAiApiPreferences.kt:8-50`；`OpenAiApiController.kt:511-531`；`HistoryEntity.kt:42-52`。 |
| FR-06 | 图片 capability 不满足 MCP 一次性要求 | `TemporaryImageStore.get()` 只读取，不消费 token；token 在 10 分钟 TTL 内可重复下载，且 URL 属于 OpenAI listener。MCP 必须有独立 listener-owned、一次性消费的 token，不能改变已有 OpenAI URL 契约。证据：`TemporaryImageStore.kt:62-67,106-137`；`OpenAiApiController.kt:470-483`。 |
| FR-07 | QAIRT 2.48 未落盘 | 运行时和 assets 实锤为 2.44.0.260225，CMake 会从该 SDK copy Android/HTP V79 libraries；未取得 2.48.40.260702，不能混装或声称升级。证据见下节。 |

## 兼容风险与历史数据影响

1. [实锤，置信度高] **运行库不可静默混用。**`CMakeLists.txt` 把 `QNN_SDK_ROOT` 默认写死为 `~/Library/Android/qairt/2.44.0.260225`，并复制 Android backend/system/stub 及 HTP V68/V69/V73/V75/V79/V81 skel。实际 SHA-256 复核表明 assets 的 Android `libQnnHtp.so`、`libQnnSystem.so`、`libQnnHtpV79Stub.so` 正是本机 2.44 文件。2.48 必须作为全套原子 SDK/构建输入替换并校验，不能只替换个别 `.so`。
   证据：`app/src/main/cpp/CMakeLists.txt:7-69`；`app/src/main/cpp/build.sh:1-7`；本审查执行的 `shasum -a 256`。
2. [实锤，置信度高] **未发现 V79 Context 的编译输入。**在 `app/src/main/assets` 的 QNN 相关文件只有 runtime/stub/skel 库；没有本轮可核验的 ONNX、DLC、量化配置、校准集、既有 Context 生成命令或 V79 context binary。该事实与已接受的 M5 边界一致，不能把库中已有 `libQnnHtpV79*.so` 误写为 V79 专用 Context。
   证据：`find app/src/main/assets -type f | rg -i 'qnn|context|bin|so'`；`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md:14-16`。
3. [实锤，置信度高] **数据库迁移必须保留现有历史。**Room builder 不允许 destructive fallback；新 preset/Job/审计表需要从 v4 迁移，不能重建 `local_dream.db`。既有历史表的 `requestId` nullable，不能反向补齐每条旧记录的 MCP Job/preset snapshot。
   证据：`AppDatabase.kt:130-141`；`HistoryEntity.kt:42-52`。
4. [实锤，置信度高] **备份/旧 JSON 不保存新关联。**历史 JSON migration 和 backup v1 都没有 MCP、preset、SDK runtime 元数据。新设计只能让这些字段对新请求生效，历史记录以 `null`/未知兼容；不得从模型默认或 `origin` 猜测。
   证据：`HistoryMigration.kt:101-146`；`HistoryBackup.kt:151-212`。
5. [推断，置信度高] **Service 停止流程需抽取为共享 runtime lease。**OpenAI onDestroy 按 listener shutdown、controller cancel、清除 active executor、`shutdownNow()` 顺序清理；而 MCP 要独立停止且不得取消 OpenAI 请求。现有 executor 由 `OpenAiApiService` 私有持有，直接复用会造成跨服务生命周期耦合。规格必须定义共享 runtime lease 与每 transport 自己的 listener/session/task 停止顺序。
   证据：`OpenAiApiService.kt:199-238`；`InferenceArbiter.kt:36-49`；`BoundedSerialExecutor.kt:100-122`。
6. [实锤，置信度高] **端口保留。**`8808` 已是 Remote Host control、`8081` 是 native backend、`8809` 是现有 OpenAI 常量。MCP 默认 `8810` 与需求一致，但 port 校验和冲突检测必须包含三者。
   证据：`RemoteProtocol.kt:17-27`；`OpenAiApiPreferences.kt:41-50`；`NativeBackendClient.kt:223`。

## SDK 官方制品核验

| 项目 | 结果 | 证据/原因 |
| --- | --- | --- |
| Qualcomm 官方渠道 | [实锤] 已定位 | Qualcomm Developer 的 QA Engine Direct 页面链接到 `softwarecenter.qualcomm.com/#/catalog/item/qualcomm_neural_processing_sdk_public`。 |
| 本机 2.48.40.260702 | [实锤] 不存在 | `find /Users/likaixuan/Library/Android/qairt -maxdepth 1 -mindepth 1 -type d -print` 仅返回 `2.44.0.260225`。 |
| 2.44 assets 对账 | [实锤] 一致 | `libQnnHtp.so=090e993822564851eab1405aff171643b21e644e3f696c95c96f2732aaed813a`、`libQnnSystem.so=7e69258e1278cc9b2bb62dbc6e2a52c227a100d6505a13fd6324a87993d0bba8`、`libQnnHtpV79Stub.so=005bd3de462851ce3dde55260d7d8560d6d07dbc309f554780b1f6412e6d9df1` 在 SDK 与 `app/src/main/assets/qnnlibs/` 均相同。 |
| 2.48.40.260702 官方 release 条目 | [实锤] 已存在 | 已登录 catalog 的版本 selector 列出 `2.48.40.260702`，条目显示 Windows/X86、发布日 `2026/7/8`、`2272.63 MB` 和 Download；同一 selector 还列出当前本机基线 `2.44.0.260225`。BrowserOS page 30 的 `snapshot --json`/`eval`。 |
| 2.48.40.260702 Android/Hexagon V79 archive 清单、厂商 checksum/signature | [未验证] | 当前官方网页只呈现 Windows/X86 宿主下载条目，并提示“requires the Software Center desktop application on Windows-based host PCs”；没有显示 archive 内部路径、Android aarch64/Hexagon V79 文件或 checksum/signature。未触发下载，不能用旧 2.44 目录推断新包内容。BrowserOS page 30 的 `snapshot --json`/`eval`。 |

## 待确认点、后续前置任务与已接受边界

### 转交后续阶段的制品核验前置任务

1. [实锤，置信度高] **REQ-001：需要可运行 Qualcomm Software Center Desktop 的 Windows host 完成制品核验。**登录/许可门禁已由人工解除，官方页面也已确认精确版本和下载条目；但网页明确该工具需 Windows host，当前 macOS 环境无法实际下载并列举包内文件。`03-plan` 必须把 Windows host 下载 `2.48.40.260702`、archive 文件名、Android aarch64 与 Hexagon V79 路径、厂商 checksum/signature 和实际落盘目录列为可执行前置检查；`04-implementation` M1 在该证据到位前保持外部阻塞。依据最新阶段反馈，这不是 `01-fact-review` 的 remaining delta 或 external blocker。

### 已接受边界（非当前阻塞）

1. [实锤，置信度高] **M5/V79 专用 Context 重编译仍不在本轮可完成范围。**ONNX/DLC、量化配置、校准集和既有编译参数尚未提供；后续规格和完成度矩阵要保留此边界，不得把现存 V79 runtime library 当作完成证据。
   证据：`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md:14-16`。

## 可进入规格阶段的事实结论

- [实锤，置信度高] 可在 `02-spec` 设计独立 MCP Service、独立 preference/repository/port/auth/switch，以及共享但从 OpenAI Service 生命周期中抽离的 runtime lease/serial scheduler。
- [实锤，置信度高] 可设计 Room v5+ 的性能 preset 与 MCP 新表；历史 generation rows、旧 JSON、backup v1 必须保持可读，新增关联字段必须可空且只对新请求保证完整性。
- [实锤，置信度高] MCP 图片 capability 必须独立实现一次性消费；现有 OpenAI 10 分钟可重复 URL 继续维持兼容。
- [实锤，置信度高] QAIRT `2.48.40.260702` 的官方发布存在且可作为规格/计划的目标版本；其 Windows/X86 宿主包显示为 `2272.63 MB`、发布日期 `2026/7/8`。
- [未验证] QAIRT 2.48.40 的 archive 内 Android/Hexagon V79 文件清单与厂商校验信息尚不能进入实施计划的可执行替换清单，直到在 Windows Software Center Desktop 实际下载并核验；它是 `03-plan` 的前置任务，而非 `01-fact-review` 的阻塞。

## 未验证项

| 项目 | 未验证原因 | 影响阶段 |
| --- | --- | --- |
| QAIRT 2.48.40.260702 archive 内 Android/Hexagon V79 文件及厂商 checksum/signature | 官方页面只显示 Windows/X86 宿主下载，且要求 Windows Software Center Desktop；当前 macOS 环境未下载且页面未公开 manifest/checksum。已按人工反馈转交后续阶段，不作为 01 当前 delta。 | 03、04、06、07 |
| V79 Context Binary 重编译与极限性能 | 用户已确认缺 ONNX/DLC、量化配置、校准集、既有参数 | 已接受边界；M5/08 完成度矩阵 |
| OnePlus 13 真机性能/热稳定性 | 本阶段只做代码与制品事实审查，未连接目标设备且不得把其他设备结果外推 | 06、07 |
