# 一加 13 性能验收规格

## 1. 设计目标、事实锚点与范围

本规格把一加 13（PJZ110 / SM8750 / HTP V79）的工作拆为**探索**、**目标机验证**和**最终深度验证**三层。优先交付可调参数、多档预设、真实 NPU/HTP 取证及候选筛选；100 次可靠性和 30/60 分钟热稳定性只验证最终胜出候选，不得阻塞前两层。

- [实锤，高] 现有 `configJson` v1 仅表达 `sdxlLowRam`、`animaLowRam`、`animaSequentialDit` 三个布尔开关，且经 `RemoteHostService`、`ModelRunScreen`、OpenAI 与 MCP 路径传入 `BackendService`。证据：`app/src/main/java/io/github/xororz/localdream/data/PerformancePresetConfig.kt:17-91`、`app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:66-101`、`app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:214-221`、`app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt:2058-2106`。
- [实锤，高] Job 与预设快照在同一个 Room transaction 中写入，当前自动选择优先级为显式预设、模型绑定、默认绑定、compatibility fallback；当前没有“真机验证资格”数据模型或绑定门禁。证据：`app/src/main/java/io/github/xororz/localdream/data/InferenceJobRepository.kt:91-132`、`app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetEntity.kt:7-20`。
- [实锤，高] harness 的 target run 已要求 `RuntimeProbe=VERIFIED`、真实模型 SHA-256、B0 和质量证据；但它目前只读取 `--baseline-file` 与 `--quality-evidence-file`，没有采集这两项证据的入口。证据：`tools/performance-harness/localdream_perf_harness.py:96-123,414-455,640-667`。
- [实锤，高] 当前正式目标设备只能使用 Wi-Fi ADB `172.20.103.120:5555`；USB `64bb3519` 是 Redmi K30，严禁用于一加 13 推理、性能或热稳定结论。证据：`docs/loop-records/oneplus13-performance-acceptance/feedback.md:92-106`。

纳入范围：W1–W7 不可变场景、NPU/HTP 运行时取证、候选参数实验、预设 CRUD/快照、B0 和质量证据采集、分组报告、最终候选的可靠性和热稳定性。非目标：缺少 ONNX/DLC、量化、校准和既有编译参数时重编译 V79 Context Binary；商店签名及最终用户生产发布；以 Redmi K30 结果替代目标设备结论。

## 2. 三层运行与结论合同

| 层级 | 用途与准入 | 必须留存 | 可输出的结论 | 明确不能输出 |
| --- | --- | --- | --- | --- |
| `EXPLORATORY`（探索） | 仅 PJZ110 `RuntimeProbe=VERIFIED`；允许新建/编辑用户预设及参数组合。 | 不可变 snapshot、RunManifest、原始响应/遥测。 | NPU/HTP 实测、UNet/端到端延迟、吞吐、内存、热降频的候选事实。 | 默认预设资格、可靠性通过、热稳定通过、极限优化完成。 |
| `TARGET_VALIDATED`（目标验证） | `EXPLORATORY` 证据完整，且 B0、质量、目标机身份和分组均冻结匹配。 | B0、质量结果、每组样本、输出/下载摘要、目标机遥测和 qualification 记录。 | 该**精确组合**可作为目标机验证过的默认/模型绑定候选。 | 100 次可靠性、60 分钟热稳定或极限优化完成。 |
| `FINAL_VALIDATED`（最终深度验证） | `TARGET_VALIDATED` 的最终胜出组合。 | 100 次零失败、30 分钟筛选、60 分钟、3 次冷启动、至少两轮热测的原始工件。 | 最终候选可靠性与热稳定结论；达到第 4 节阈值时才可宣称极限 profile。 | 将另一模型、另一 Context、另一 snapshot 或另一热态的结论外推。 |

任何缺 `RuntimeProbe=VERIFIED`、PJZ110/SM8750 身份、native readiness、V79、库摘要、Context fingerprint 或出现 rejection reason 的 run 都必须 fail-closed；可留作诊断，但不进入三层中的任一目标机结论。`REJECTED` 表示兼容性或指纹拒绝，`UNAVAILABLE` 表示证据不可采集，二者均不等于兼容 fallback。

## 3. 不可变场景、运行记录与指标

### 3.1 场景 JSON

每份场景 JSON 严格使用现有 v1 顶层键：`schemaVersion`、`scenarioId`、`scenarioVersion`、`workflow`、`fixtures`、`model`、`request`、`measurement`、`timeoutMs`、`sha256`；摘要是除 `sha256` 外内容的 canonical SHA-256。变更任一字段必须发布新 `scenarioVersion` 和新文件，不得覆盖历史 JSON。

`workflow` 枚举语义：`GENERATE`（文生图）、`IMAGE_TO_IMAGE`（图生图）、`MODEL_SWITCH`（冷启 A→B→A）、`SUSTAINED`（持续生成）、`UPSCALE_API`（放大/API 尾链路）、`PROTOCOL_PARITY`（MCP 与 `/v1` 对照）。未知 workflow、缺固定 fixture、非法/占位模型摘要、请求字段偏离场景或摘要不匹配均在请求前拒绝。

| 场景 | 固定业务语义 |
| --- | --- |
| W1 | `novaAsianXL_illustriousV70`，1024×1024，Euler A，20 steps，CFG 7。 |
| W2 | `novaAsianXL_illustriousV70DMD2`，1024×1024，Euler，4 steps，CFG 1；发布方变体只能新建 W2b，不能重写或并入 W2。 |
| W3 | W1 的固定 1024×1024 图生图输入，`strength=0.65`。 |
| W4 | 可审核的进程冷启以及 A→B→A；每个 PROCESS_COLD 样本都须有 lifecycle evidence。 |
| W5 | W1、W2 分组持续运行，统计吞吐及热降频。 |
| W6 | 固定输入的 upscale/API、PNG、落盘、`/assets/{assetId}` 与下载；下载必须验证 Content-Type、PNG 魔数、尺寸、字节数与 SHA-256。 |
| W7 | W1/W6 同输入的 MCP Tool、diffusion-step progress、cancel、replay/reconnect、稳定 ResourceLink/下载，并与 `/v1` 对照。 |

### 3.2 RunManifest、分组与指标

请求前持久化 `RunManifest`。它至少含 `runId`、scenario/fixture 摘要、preset snapshot 摘要、app build、设备 model/SoC/ABI/Android、完整 RuntimeProbe、库和 Context 摘要、冷态、网络/电量/屏幕/环境温度、采样器版本及开始时间。每个 `Sample` 含 sequence、`GroupKey`、`Outcome`、是否 warmup、端到端/分段指标、输出与质量证据、资源/热指标。

`GroupKey` 是 `scenarioSha256 + presetSnapshotSha256 + runtimeFingerprint + coldState + harnessVersion`；任一项不同即独立归档、独立统计和独立 B0。`ColdState` 枚举为 `DEVICE_COLD`（设备重启）、`PROCESS_COLD`（进程冷启）、`OS_CACHE_WARM`（OS page cache 热）、`CONTEXT_WARM`（模型 Context 热），互斥且禁止 root 清 page cache。冷态组至少 5 个有效样本；热态组必须先有且仅有 5 条 warmup，再有至少 30 个有效样本；warmup 永不进入统计或可靠性计数。

主指标为：已证明的 NPU/HTP V79 执行及实际加载库摘要、UNet 时长、端到端时长、W5 持续吞吐、峰值 PSS/RSS、温度与 thermal throttling。`generation_time_ms`、`first_step_time_ms` 只能作为已有响应分段，不能伪造 UNet、内存或热证据。缺任一主指标时报告须明确 `MISSING_METRIC:<name>`，不得以“通过”补齐。

## 4. B0、质量、统计与最终验收

### 4.1 B0 与质量采集产物

必须新增两个**采集**入口，而非只接受外部手写文件：

1. `capture-baseline` 在已验证目标机上按冻结场景生成 `baseline-v1.json`。每条 entry 严格含 `scenarioSha256`、`presetSnapshotSha256`、`runtimeFingerprint`、`coldState`、`absoluteTimeoutMs`、`qualityReferenceSha256`、`modelAssetSha256`；它绑定同组 B0、绝对超时、模型和质量参考。
2. `capture-quality` 以输出 SHA-256 为键生成 `quality-v1.json`；每项必须记录 `mode`、`passed`、参考摘要、计算版本及 BIT_EXACT 或 GOLDEN_SET 所需原始度量。缺、改写、摘要不匹配或越过合格样本的质量证据一律拒绝。

探索层不要求 B0/质量文件，故不会被 100 次或热测阻塞；但它只能输出候选事实。`TARGET_VALIDATED` 及以上必须消费上述两个工件，且 B0 的 model asset SHA-256、超时和 GroupKey 必须与场景/样本精确一致。

`QualityMode` 枚举：`BIT_EXACT`（不改变数学语义时图像 hash 完全一致）与 `GOLDEN_SET`（runtime/Context 变更）。`GOLDEN_SET` 至少 30 prompt × 4 seed，SSIM ≥ 0.98、LPIPS ≤ 0.05、CLIP 相对下降 ≤ 1% 并盲测；M0 自然方差更小时只可收紧。NaN、Inf、破图、全黑、色序或 layout 错误均失败。

### 4.2 性能、可靠性和热门槛

- 报告每组 p50、p95、MAD 和以 `runId` 派生种子的 95% bootstrap CI。QAIRT 2.48 + 旧 Context 回归限制为热态 p50 ≤ 2%、p95 ≤ 3%。
- 候选筛选优先比较 W1/W2 的 NPU/HTP 证据、UNet/端到端延迟、W5 吞吐、内存及热降频。默认候选准入为 p50 改善 ≥ 3%、p95 ≥ 5%、持续吞吐 ≥ 5%，或延迟退化 ≤ 1% 且峰值内存改善 ≥ 10%，且 CI 排除噪声。
- 只有 W1/W2 同画质热态 p50 与持续吞吐各改善 ≥ 15% 才能使用“极限 profile”；25% 是 stretch goal。未到该线只能陈述实际候选事实。
- `Outcome` 枚举：`SUCCESS`、`TIMEOUT`、`HANG`、`CRASH`、`PERMANENT_LOADING`、`SECOND_REQUEST_FAILED`、`PROTOCOL_MISMATCH`、`ASSET_MISMATCH`、`SEED_OR_DIMENSION_MISMATCH`、`QUALITY_FAILED`、`CANCELLED`。除 W7 指定取消断言外，非 `SUCCESS` 均为最终可靠性失败。
- 100 次零失败、候选筛选 30 分钟、最终候选 60 分钟/3 次冷启动/至少两轮热测只约束 `FINAL_VALIDATED`。最后四分位吞吐相对最初稳定区间下降不得超过 10%，且不得 severe thermal、崩溃、LMKD、持续 swap/泄漏；释放后资源回到同一 RunManifest 的稳态范围。

## 5. 性能预设、默认资格与兼容

### 5.1 可调配置与快照

`performance_presets` 继续保留 `name`（全局唯一展示名）、`selector`（稳定选择标识）、`configJson`（执行策略）、`revision`（乐观并发）和 `isFallback`（唯一 compatibility fallback）。现有 v1 继续是严格 schema：

```json
{
  "schemaVersion": 1,
  "engine": {
    "sdxlLowRam": true,
    "animaLowRam": true,
    "animaSequentialDit": false
  }
}
```

三项均为必填布尔值，分别控制 SDXL low-RAM、Anima low-RAM 和 Anima sequential DiT。新参数必须发布 `configJson` v2，定义完整新键集和 v1→v2 反序列化分支；禁止在 v1 偷加字段。模型、prompt、seed、scheduler、steps、CFG、图像输入、runtime/Context 路径仍属于场景/模型事实，不能写入预设以规避可比性。

解析枚举：`SUPPORTED`（严格 v1/v2）、`LEGACY_COMPATIBILITY`（历史精确 `{}`）、`UNSUPPORTED_VERSION`、`INVALID`。前者才可新增、编辑、导入、绑定和执行；后两者拒绝且不静默降级。`LEGACY_COMPATIBILITY` 仅允许既有 fallback 与历史 Job snapshot 读取，不能成为性能候选或新的绑定。

Job 受理须在同一 Room transaction 中完成“选择预设 → 严格解析 → 写 Job 与原始 configJson snapshot → 传入实际启动”。执行只消费 snapshot；编辑和删除绝不改变已受理/排队 Job。

### 5.2 真机资格记录与默认门禁

为避免把未经验证的组合推广为默认，新增 `performance_preset_qualifications`，而不是向可变 `configJson` 塞状态。每条记录至少含：`id`、`presetId`、`presetRevision`、`presetSnapshotSha256`、`modelId`、`modelAssetSha256`、`scenarioSetSha256`、`runtimeFingerprint`、`appBuild`、`qualificationLevel`、`evidenceManifestSha256`、`createdAt`、`revokedAt`。活跃资格的唯一键是 `(presetSnapshotSha256, modelAssetSha256, runtimeFingerprint, scenarioSetSha256, qualificationLevel)`。

`qualificationLevel` 枚举为 `TARGET_VALIDATED`（第 2 节目标验证证据齐全）和 `FINAL_VALIDATED`（最终深度验证亦通过）。没有记录即为探索候选，不新增伪造的“未验证”持久状态。任何 preset 编辑会变更 revision/snapshot SHA-256，因此旧资格不可自动继承；模型、Context、运行时、场景集或 build 变化也必须重新验证。

当前版本没有外部资格签发根：HTTP/MCP 客户端和普通 UI 都不得把 candidates/manifest JSON 写入 Room 或提升为 `TARGET_VALIDATED`/`FINAL_VALIDATED`，`qualifications.write` 不属于默认或可授予 MCP scope。harness 产生的候选仅用于审计。未来仅可由 App 本机受控采集链路在具备可信来源证明后写入资格；在此之前自动绑定必须保持 fail-closed。

`DEFAULT` 与 `MODEL:<modelId>` 的**自动**绑定只可指向与当前模型资产、RuntimeProbe fingerprint 和 app build 完全匹配的活跃 `TARGET_VALIDATED`（或更高）资格；否则绑定更新/自动受理失败并给出 `PRESET_NOT_TARGET_VALIDATED`，不得悄悄切换至未验证组合。显式选择的用户 preset 仍可进入 `EXPLORATORY`。无绑定时的 compatibility fallback 是历史保守行为，不是“默认性能预设”，不得被资格记录或性能宣传冒充。

删除用户 preset 必须在同一 Room transaction 内撤销其活跃资格、将引用 binding 改为 fallback、删除 preset，并返回 `PresetDeleteResult(reboundBindingKeys)`。fallback 不可编辑、不可删除；同名创建/编辑拒绝，导入重名自动编号且新副本没有资格记录。

### 5.3 历史数据与反序列化

- v5/v6 历史 `configJson="{}"` 与 snapshot 反序列化为 `LEGACY_COMPATIBILITY`；迁移不得改写 JSON、revision 或历史 Job。
- 迁移新增 qualification 表、外键/索引和必要 binding 校验，不回填任何“真机已验证”记录；历史 DEFAULT/MODEL binding 在无匹配资格时保留展示但在自动受理时拒绝，直到重新验证或用户显式改绑。
- `generation_history` 的 `jobId`、`presetId`、`presetRevision`、`runtimeFingerprint` 为 NULL 时标为 `LEGACY_UNATTRIBUTED`，不得进入 benchmark、资格、可靠性或热稳定统计。
- Room migration 必测保留旧预设/Job/snapshot/历史行、资格不被伪回填、旧绑定 fail-closed、删除事务回滚和导入副本不继承资格。

## 6. 异常边界、测试矩阵与发布边界

| 层级 | 必测结论 |
| --- | --- |
| JVM schema/领域 | 场景摘要、W2/W2b 隔离、config v1/v2 严格解析、`{}` 兼容、资格唯一键/失效、默认绑定拒绝未验证组合、删除原子回退。 |
| harness | 探索 run 不要求 B0/质量/100；目标验证缺 B0、质量、输出完整性、模型摘要或主指标必拒绝；最终验证才要求 100/30/60；GroupKey 独立归档、5 条 warmup、W4 lifecycle、Wi-Fi ADB 身份绑定。 |
| native/API/MCP | 原生 NPU/HTP、UNet/端到端指标可追溯；W6/W7 PNG 与 `/assets/{assetId}` 下载完整性，MCP progress/cancel/replay/reconnect 与 `/v1` 同输入对照。 |
| Android instrumentation | 数据库迁移、CRUD、snapshot、资格门禁及回滚；Redmi K30 只覆盖非推理 UI/数据库/MCP，不产生目标机性能结论。 |
| PJZ110 实机 | 仅 `adb -s 172.20.103.120:5555` 运行；从 `EXPLORATORY` 逐步生成 `TARGET_VALIDATED` 和最终 `FINAL_VALIDATED` 工件。 |

本阶段只定义合同，不执行业务代码修改或真机烧机。V79 Context Binary 重编译仍因缺输入排除；商店签名和最终用户生产发布不在本轮。真机功能证据不自动等于正式 B0、质量、可靠性或热稳定性结论；只有以上不可变工件可改变资格层级。

## 7. 规格验收

1. W1–W7、W2b、RunManifest、GroupKey、B0、质量和原始样本均可按摘要重放/重算，且不同 runtime、模型、预设或冷态绝不混组。
2. 探索、目标验证、最终深度验证的门槛不同；100 次和热测不会阻塞 NPU profiling、参数实验或候选筛选，但仍是最终结论的硬门槛。
3. 自动 DEFAULT/MODEL binding 只能选择当前 PJZ110 真机验证过的精确组合；编辑、导入、模型/runtime/场景变化均使旧资格失效。
4. 历史 JSON、数据库和 Job 可读且不被隐式改写；没有可审计 target evidence 的历史数据不能被升级为“已验证”。
