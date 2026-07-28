# oneplus13-performance-acceptance 需求事实 Review

## 结论

[实锤，高] 当前仓库已具备 **QAIRT 2.48.40 运行时完整性校验、HTP V79 库打包、串行推理、MCP/OpenAI 的生成与异步 Job 基础，以及性能预设的持久化 CRUD 和 Job 快照**；但尚不存在 W1–W7 的机器可读场景、目标设备 benchmark/harness、统计与质量门禁、100 次可靠性和 30/60 分钟热稳定性实现。性能预设的 `configJson` 也尚未接入实际启动/推理参数。因此可以进入规格设计，规格必须把这些缺口收敛为一个可执行的验收系统，而不能把既有元数据和 MCP CRUD 误判为性能验收已完成。

核查基线：`HEAD` 是需求指定的 `ddd1b2df425d921a2af9cce31a5b02891719f9c3`（命令：`git rev-parse HEAD`、`git show -s --format='%H%n%ci%n%s' HEAD`）。本 review 只陈述当前源码与仓库资产事实；没有连接一加 13，也没有运行推理。

## 核查方法与范围

| 核查对象 | 实际命令/入口 | 结果 |
| --- | --- | --- |
| 需求与批准口径 | `sed -n` 读取 `docs/requirements/oneplus13-performance-acceptance.md`、`docs/loop-records/oneplus13-performance-acceptance/feedback.md` | 已确认 W1–W7、可靠性、热稳定性和 CRUD 合同是本阶段输入。 |
| 代码、DTO/枚举、服务与 DAO | `rg -n -i 'qnn|qairt|htp|preset|snapshot|benchmark|thermal|scenario' app/src/main` | 已定位 Kotlin、C++、Room、MCP/OpenAI 调用面。 |
| 历史数据/迁移 | `AppDatabase.kt` 的 `MIGRATION_4_5` 与 `AppDatabaseMigrationTest.kt` | 已查：v3 历史行保留，新增关联字段为可空。 |
| JSON/资产/模型输入 | `find app/src/main/assets`、`git ls-files | rg '(scenario|benchmark|harness|\\.onnx$|\\.dlc$|\\.bin$|\\.context$|calib|quant)'` | 已查：有 QAIRT manifest 和 V79 库；未发现场景 JSON、benchmark/harness、ONNX/DLC/校准材料或可核对的目标模型 Context。 |

## 现有能力

### 1. QAIRT/HTP 运行时与启动保护

[实锤，高]

- `app/src/main/assets/qairt-runtime-manifest.json:2-7` 声明 `QAIRT 2.48.40`、build `260702151143`，并在 `:80-92` 列出 `libQnnHtpV79.so`、Skel、Stub 的 digest；`find app/src/main/assets` 同时确认这些文件实际存在。
- `app/src/main/java/io/github/xororz/localdream/data/RuntimeCompatibilityEvaluator.kt:41-51` 定义 `QAIRT_VERSION_MISMATCH`、`ABI_MISMATCH`、`HTP_TARGET_MISMATCH` 与 `CONTEXT_FINGERPRINT_MISMATCH` 拒绝原因；`:83-114` 校验核心库、全部 runtime 文件、模型元数据和 Context 指纹。
- `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:626-649` 在 native 进程启动前执行此校验；`:72-73` 将目标固定为 `v79`。`RuntimeCompatibilityEvaluatorTest.kt:15-61` 覆盖兼容通过及缺库、混库、ABI、HTP、Context 指纹不匹配的拒绝。
- C++ 运行时目前只从 `lib_dir` 取 `libQnnHtp.so` / `libQnnSystem.so`（`app/src/main/cpp/src/QnnRuntime.hpp:29-38`），再在 `:108-112` 启用 HTP performance mode；`NativeBackendCommandFactory.kt:53-69` 传入 `--lib_dir`。

[推断，中] V79 库被打包和模型元数据会被要求标为 `v79`，但这不是一加 13 上实际加载 V79、HTP 调度或性能达标的证据；这些只能由目标机运行态采样确认。

### 2. 现有生成、API 与 MCP 功能路径

[实锤，高]

- native `/generate` 解析 prompt、negative prompt、steps、CFG、seed、尺寸、图生图等字段，并经单互斥锁串行执行；见 `app/src/main/cpp/src/main.cpp:389-470`。SSE 返回 progress、complete、seed、尺寸、`generation_time_ms` 和 `first_step_time_ms`（`:415-452`）。
- `/upscale` 已有二进制输入、图片尺寸头、落盘前结果传回路径，且通过同一 native 锁串行；`main.cpp:497-535`。OpenAI 客户端可调用 `/generate` 与 `/upscale`，见 `app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt:80-99,160-216`。
- MCP 有生成 Job、progress/cancel 投影和工具路由；`McpToolRegistry.kt:44-48` 注册 `generation.create` 与 Job 工具，`McpGenerationGatewayTest.kt:33-103` 验证创建、查询、队列拒绝和取消的领域行为。OpenAI `/v1` 与 MCP 均经过 `InferenceDispatcher` 的有界串行调度（`InferenceDispatcher.kt:11-113`）。

```mermaid
flowchart LR
    A[MCP Tool 或 /v1 请求] --> B[OpenAiApiController / McpGenerationGateway]
    B --> C[RoomInferenceJobRepository: 接受 Job 并冻结快照]
    C --> D[InferenceDispatcher: 有界串行队列]
    D --> E[BackendService / NativeBackendClient]
    E --> F[native /generate 或 /upscale]
    F --> G[历史资产与 Job 状态]
```

[业务解读] 这条链路解决的是多个调用方（应用 UI、OpenAI 客户端、MCP 客户端）争抢同一台手机推理资源时的排队、取消、可追溯任务和结果下载问题；它提供了 W6/W7 的可复用功能底座，但没有定义任何性能实验合同。

### 3. 预设、Job snapshot 与 Room 历史兼容

[实锤，高]

- `PerformancePresetEntity.kt:7-20` 的 `performance_presets` 表确有 `name`、`selector`、`configJson`、`revision`、`isFallback`；`PerformancePresetDao.kt:9-26` 有唯一名称索引、查询、插入、更新与禁止删除 fallback 的 SQL。
- `PerformancePresetRepository.kt:42-144` 创建唯一 `Compatibility fallback`，拒绝同名、保护 fallback、以 revision 做乐观冲突检查，并给导入冲突自动编号；`PerformancePresetRepositoryTest.kt:10-49` 覆盖 revision、快照、导入冲突与 fallback 不可删除。
- `InferenceJobEntity.kt:7-27` 与 `InferenceJobDao.kt:9-45` 定义 Job 状态和 `preset_snapshots`；`RoomInferenceJobRepository.accept` 在 `database.withTransaction` 中写 Job 与不可变快照（`InferenceJobRepository.kt:77-124`）。现有状态枚举为 `QUEUED=已受理排队`、`RUNNING=执行中`、`SUCCEEDED=成功`、`FAILED=失败`、`CANCELLED=已取消`、`UNKNOWN=未知`（`:9-20`）。
- MCP 已对外提供 `presets.*` CRUD 和导入/导出：`McpPresetStore.kt:19-99`、`McpGenerationGateway.kt:202-280`；其 Android adapter 使用 v5 Room 表（`McpPresetStore.kt:41-64`）。
- 历史数据已经查过：`AppDatabase.kt:142-220` 的 `MIGRATION_4_5` 为旧 `generation_history` 增加可空 `jobId`、`presetId`、`presetRevision`、`runtimeFingerprint`，并创建预设/Job/快照表及 fallback；`AppDatabaseMigrationTest.kt:40-134` 断言 v3 历史行保留且新关联字段为 `NULL`，然后验证 v5 snapshot 写入。

[业务解读] 预设的业务目标是让维护者保存可复用性能策略，并保证已经进入队列的请求不会被之后的编辑或删除悄悄改变。当前实现已经保存了该“审计快照”，但还没有让快照实际改变 native 推理行为。

## 真实缺口

| 编号 | 缺口 | 证据 | 影响 |
| --- | --- | --- | --- |
| F-01 | W1–W7 的固定 scenario JSON、固定 prompt/negative prompt/seed、模型文件定位和机器可读执行入口均不存在。 | 对源码、assets、tracked 文件执行 scenario/benchmark/harness/模型扩展名搜索，无结果；现有 native 仅接受单次动态请求（`main.cpp:389-406`）。 | 无法复现或比较 W1–W7，W2/W2b 隔离也无实现。 |
| F-02 | 无 benchmark harness、无冷/热态分组、预热、样本计数、p50/p95/MAD/CI、分段耗时、RSS/PSS、HTP spill-fill、温度或降频采集。 | `rg` 搜索 benchmark、p50、p95、thermal、throughput、warmup、SSIM、LPIPS 仅命中需求外通用 timeout/日志；native complete 只提供两项单次时间（`main.cpp:442-452`）。 | 不能判断 2%/3% 回归、3%/5% 准入、15% 目标或 25% stretch。 |
| F-03 | 无 100 次可靠性 runner、预热排除、冻结每场景绝对超时、禁止自动重试的原始 run 报告。 | `BackgroundGenerationService.kt:56-63` 设置的是通用单请求 3600 秒 HTTP timeout；没有按场景的冻结 timeout 或运行统计器。 | 不能形成“0 失败”的候选结论。 |
| F-04 | 无 30 分钟筛选、60 分钟最终热测、三次冷启动、环境记录、severe thermal/LMKD/swap 或释放回稳观测。 | 代码搜索未定位 thermal/temperature/throughput 采样实现；现有 `BackendService` timeout 仅是前台服务超时处理。 | 不能形成热稳定性结论。 |
| F-05 | `configJson` 只做“首尾为 `{}`”校验，未定义带版本 schema，也未映射至 `NativeBackendLaunchConfig` 或 native 请求。 | `PerformancePresetRepository.kt:124-130`；`NativeBackendCommandFactory.kt:6-16,53-69` 的 launch config 没有 preset/config 字段；`OpenAiApiController.kt:368-390` 只关联 preset id/revision 后调用 dispatch。 | 预设 CRUD 目前是可审计元数据，不能作为性能策略生效。 |
| F-06 | 没有性能预设的 Compose 管理界面，也没有“未来默认/模型绑定”持久字段、引用删除原子回退或可见提示。 | `rg` 在 `ui/` 仅匹配 theme/aspect preset；在服务/OpenAI 仅匹配 Job 关联。数据库 schema 仅有 `performance_presets`、`inference_jobs`、`preset_snapshots`（`AppDatabase.kt:167-220`）。 | MCP 维护者可 CRUD，但需求要求的未来默认/模型绑定删除回退尚无承载模型。 |
| F-07 | 仓库没有 ONNX/DLC、量化/校准材料或当前目标模型 Context Binary 可供重编译/核验；已打包 V79 runtime 不能证明模型二进制匹配。 | `git ls-files` 的模型/量化搜索无 ONNX/DLC/校准/Context 命中；manifest 明示 SDK archive 不分发（`qairt-runtime-manifest.json:115-119`）。 | 不得进行 V79 Context 重编译或声称极限优化完成。 |

## 兼容风险与历史影响

1. [实锤，高] native 运行时总是以固定 `HTP_TARGET="v79"` 校验模型元数据（`BackendService.kt:626-645`），而 C++ loader 只显式装载通用 `libQnnHtp.so` / `libQnnSystem.so`（`QnnRuntime.hpp:29-38`）。规格需要定义“V79 设备/模型/库”的运行态证据格式，不能仅以 manifest 文件存在为结论。
2. [实锤，高] `requiresCompatibilityFallback` 只在元数据缺失时输出日志（`BackendService.kt:647-649`），没有与 `performance_presets.isFallback` 绑定；两种 fallback 概念目前彼此独立。若直接把它们视作同一语义，会造成模型兼容和用户性能策略的错误回退。
3. [实锤，高] 已受理 Job 的 snapshot 可保留，所以删除用户 preset 不会改变这些 Job 的快照；但 `PerformancePresetRepository.delete` 仅执行 `store.delete(id)`（`PerformancePresetRepository.kt:99-103`）。未来加入默认/模型绑定时，必须将“查引用、回退到 compatibility、删除”放在同一 Room transaction。
4. [实锤，高] 旧 `generation_history` 的新增关联字段均可空且迁移测试断言为 `NULL`（`AppDatabaseMigrationTest.kt:54-83`）。性能报告不得假设历史记录具备 preset、runtime fingerprint 或 benchmark 分组，必须区分 legacy 数据。
5. [实锤，高] OpenAI 请求在 `submit` 中总是接受 compatibility fallback（`OpenAiApiController.kt:368-379`），没有把调用者选择的 performance preset 传进来。MCP 虽可传 `presetId`，其 scheduler request 也没有携带 snapshot 配置供实际启动消费。现阶段不能宣称不同预设可 A/B。

## 未验证项与原因

| 未验证项 | 原因 | 处理 |
| --- | --- | --- |
| 一加 13 PJZ110 / SM8750 上 QAIRT、HTP V79、Context 实际加载、性能、可靠性和热稳定性 | 目标设备尚未连接；本阶段不以 Redmi K30 替代。 | 延后至设备到位后的 07 业务验收；01–06 将它作为 accepted/deferred boundary。 |
| 实际安装模型的 `ModelMetadata`、`unet.bin` 和用户历史 SQLite 内容 | 它们在 Android 私有/外部存储，不属于当前仓库；本阶段只读工作树。 | 后续 harness 在目标设备上导出只读环境清单、模型 fingerprint 和报告。 |
| QAIRT 2.48 与 V79 Context 的真机动态加载 | manifest 和 JVM fixture 只能验证静态完整性与规则。 | 用目标机启动日志、`/health` 和 runtime fingerprint 采集验证。 |

## 进入规格的最小任务

1. 设计 versioned scenario schema、W1–W7 fixtures、固定 timeout 与不可变 run manifest。
2. 设计主机/设备协作 harness：冷/热分组、指标采集、统计计算、100 次零失败、30/60 分钟热实验和结果报告。
3. 设计并实现 `configJson` 的版本 schema、校验、snapshot 到实际启动/请求的映射；明确配置可改变的性能字段与不得改变的模型数学语义。
4. 设计性能 preset 的产品入口与默认/模型绑定引用表，删除时以单事务回退到 fallback，并保留可见提示。
5. 为所有 legacy `generation_history` 空关联字段定义报告降级策略；不得将旧记录混入 benchmark 统计。

## 阶段结论

[实锤，高] 现有代码、DTO/枚举、服务、DAO、DB 字段、迁移历史与 JSON/资产均已核查；真实缺口足够明确，且没有需要人工重新定义的业务口径。可以进入 02-spec。目标设备缺失仅限制真机最终结论，不阻塞本阶段。
