# 一加 13 性能验收规格

## 1. 目标、边界与事实锚点

本规格定义一套可复现的性能验收能力：在 **PJZ110 / SM8750 / HTP V79** 上，按不可变场景、配置快照和运行环境记录比较候选，且让性能预设真实影响执行。它不把已存在的 QAIRT 静态 manifest、MCP CRUD 或 Redmi K30 交互结果当作目标机结论。

- [实锤] 运行时 manifest 已声明 QAIRT 2.48.40 并打包 V79 库，但启动前校验和 native loader 不能证明目标机实际调度了 V79；设备端必须补 RuntimeProbe。证据：`app/src/main/assets/qairt-runtime-manifest.json`、`app/src/main/java/io/github/xororz/localdream/service/BackendService.kt:626-649`、`app/src/main/cpp/src/QnnRuntime.hpp:29-38`。
- [实锤] `performance_presets`、`preset_snapshots` 和 Job 受理时的事务快照已存在，但 `configJson` 未进入 `NativeBackendLaunchConfig` 或请求执行。证据：`app/src/main/java/io/github/xororz/localdream/data/InferenceJobRepository.kt:77-124`、`app/src/main/java/io/github/xororz/localdream/service/NativeBackendCommandFactory.kt:6-16,53-69`。
- 纳入：版本化 W1–W7 场景、设备/主机协作 harness、统计与质量/可靠性/热稳定报告、预设配置解码与执行映射、默认和模型绑定的引用回退。
- 非目标：缺少 ONNX/DLC、量化、校准和原编译参数时重编译 V79 Context；商店签名、生产写入；以 Redmi K30 形成任何目标设备推理结论。

## 2. 场景、运行记录与运行时证据

### 2.1 Versioned scenario 合同

每个场景文件是不可变 JSON，顶层 `schemaVersion=1`，必须含 `scenarioId`、`scenarioVersion`、`workflow`、`fixtures`、`model`、`request`、`measurement`、`timeoutMs` 和 `sha256`。`fixtures` 内直接固化 prompt、全局 negative prompt、seed、图像输入及其摘要；`model` 固化 selector 和目标模型资产摘要；`request` 固化尺寸、scheduler、steps、CFG、strength 和 API 返回断言。变更任一值必须新增 `scenarioVersion`，不能覆盖既有 JSON 或其摘要。

| `scenarioId` | 业务语义与固定关系 |
| --- | --- |
| `W1` | 标准 SDXL：`novaAsianXL_illustriousV70`、1024²、Euler A、20 steps、CFG 7。 |
| `W2` | DMD2：`novaAsianXL_illustriousV70DMD2`、1024²、Euler、4 steps、CFG 1。 |
| `W2b` | 仅发布方额外要求时新增的独立 variant；不得改写、替代或与 W2 合并统计。 |
| `W3` | W1 的固定 1024² 图生图输入，`strength=0.65`。 |
| `W4` | 进程冷启及 A→B→A 模型切换后的首次生成。 |
| `W5` | W1、W2 各自连续运行，记录持续吞吐与热态。 |
| `W6` | 固定输入的 upscale/API、PNG/文件落盘、URL 返回和下载。 |
| `W7` | W1/W6 同输入的 MCP Tool、progress、cancel、reconnect、ResourceLink/下载，与 `/v1` 对照。 |

`workflow` 枚举为 `GENERATE`（文生图）、`IMAGE_TO_IMAGE`（图生图）、`MODEL_SWITCH`（冷启/切换）、`SUSTAINED`（持续生成）、`UPSCALE_API`（上采样尾链路）和 `PROTOCOL_PARITY`（MCP 与 `/v1` 协议对照）。未知值、摘要不匹配、缺模型或请求字段与场景不一致，均在执行前拒绝，并写入失败报告；禁止用调用方动态参数覆盖 fixture。

### 2.2 不可变 RunManifest 与 RuntimeProbe

每次运行先写 `RunManifest`，再执行请求。其最小字段为 `runId`、scenario 摘要、preset snapshot 摘要、应用 build、设备型号/SoC/ABI、Android 版本、QAIRT version/build、所有已加载 runtime 库的路径和 SHA-256、模型 metadata、Context fingerprint、冷/热状态、网络/电量/屏幕/环境温度、采样程序版本及开始时间。每条样本带 `runId`、序号、端到端和分段指标、资源指标、输出摘要和 `Outcome`。

`RuntimeProbe.status` 枚举：`VERIFIED`（PJZ110、SM8750、QAIRT 2.48.40、V79 目标、runtime/Context 指纹及启动结果均已采到）、`REJECTED`（兼容校验或指纹失败）和 `UNAVAILABLE`（缺设备或无法采集）。只有 `VERIFIED` 样本允许进入一加 13统计；`REJECTED`/`UNAVAILABLE` 保留诊断证据但不得产生性能结论。`RuntimeCompatibilityRejection` 的 `QAIRT_VERSION_MISMATCH`、`ABI_MISMATCH`、`HTP_TARGET_MISMATCH`、`CONTEXT_FINGERPRINT_MISMATCH` 继续表示拒绝启动的具体原因，不与用户预设 fallback 混用。

## 3. Benchmark、质量、可靠性与热稳定性

### 3.1 分组与统计

统计分组键固定为 scenario 摘要、候选 preset snapshot 摘要、设备/运行时/Context fingerprint、冷/热状态和采样程序版本；任一键不同即为不同组。`DEVICE_COLD`（设备重启）、`PROCESS_COLD`（进程冷启）、`OS_CACHE_WARM`（OS page cache 热）和 `CONTEXT_WARM`（模型 Context 热）是四个互斥状态，禁止 root 清 page cache 或混组。

- 每个冷态组至少 5 个有效样本；热态先预热 5 次（只记录，不纳入统计）再采集至少 30 个有效样本。
- 每组输出 p50、p95、MAD 和 95% bootstrap CI；随机种子由 `runId` 派生并写入报告，保证重算一致。
- 采集 `/health` 前冷启动、Context 加载、CLIP、首步、完整 UNet、VAE decode、首次/第二次生成、端到端时间、峰值 PSS/RSS、HTP spill/fill、吞吐、温度、降频和总耗时。现有 SSE 的 `generation_time_ms`、`first_step_time_ms` 只能作为两个分段来源，不能代替完整指标。证据：`app/src/main/cpp/src/main.cpp:442-452`。
- 基线 `B0` 在同一场景与运行时环境下冻结场景绝对超时、fixture 摘要、统计分组和质量参考；候选间不得改变。旧 Context 的 QAIRT 2.48 回归门槛为热态 p50 ≤2%、p95 ≤3%；默认候选准入为 p50 ≥3%、p95 ≥5%、持续吞吐 ≥5%，或延迟退化 ≤1%且峰值内存改善 ≥10%，且 CI 排除噪声。极限 profile 仅在 W1/W2 同画质 p50 与持续吞吐各改善 ≥15% 时成立，25% 只是 stretch 目标。

### 3.2 质量、可靠性与热稳定性

`QualityMode` 为 `BIT_EXACT`（不改变模型数学语义时，目标图 hash 必须相同）或 `GOLDEN_SET`（runtime/Context 候选）。后者使用至少 30 prompt × 4 seed，要求 SSIM ≥0.98、LPIPS ≤0.05、CLIP score 相对下降 ≤1% 并通过盲测；M0 自然方差若更小只能收紧门槛，不能为候选放宽。任一 tensor/图像 NaN、Inf、破图、全黑、色序或 layout 错误为质量失败。

`Outcome` 为 `SUCCESS`、`TIMEOUT`、`HANG`、`CRASH`、`PERMANENT_LOADING`、`SECOND_REQUEST_FAILED`、`PROTOCOL_MISMATCH`、`ASSET_MISMATCH`、`SEED_OR_DIMENSION_MISMATCH`、`QUALITY_FAILED`、`CANCELLED`。前十项（除 `SUCCESS`）均是 100 次可靠性失败；`CANCELLED` 仅允许 W7 指定取消断言，其他场景出现也失败。每个最终候选预热外至少 100 次且允许失败数为 0；不自动重试，故障后的重试必须是新 `runId` 的诊断运行。

热测由候选筛选 30 分钟和最终候选 60 分钟构成；最终候选跨 3 次冷启动并至少两轮热测。固定 Wi-Fi ADB、拔除 USB、屏幕/网络/电量范围，记录环境温度与采样配置。最后四分位吞吐相对最初稳定区间下降不得超过 10%，且不得有 severe thermal、崩溃、LMKD、持续 swap/泄漏；卸载/释放后资源必须回到该 RunManifest 的稳态范围。违反任一条件立即标记本轮失败，不用下一轮覆盖。

## 4. 性能预设与实际执行映射

### 4.1 `configJson` v1

`performance_presets` 保留 `name`（全局唯一展示名）、`selector`（`[A-Za-z0-9_.-]{1,80}` 的稳定选择标识）、`configJson`（策略内容）、`revision`（乐观并发版本）和 `isFallback`（唯一兼容回退标识）。新建、修改和导入只接受严格的 v1 JSON：

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

三个 `engine` 字段都是必填布尔值：分别决定 SDXL 低内存、Anima 低内存和 Anima 顺序 DiT 的启动参数；它们映射到扩展后的 `NativeBackendLaunchConfig`，再由 `NativeBackendCommandFactory` 生成实际命令。模型、backend 类型、分辨率、prompt、seed、scheduler、steps、CFG、图像输入和 runtime/Context 路径属于场景或模型选择，禁止写入 preset，防止候选间改变模型数学语义或绕过 W1–W7。

解码状态为 `SUPPORTED`（v1 且 schema 严格合法）、`LEGACY_COMPATIBILITY`（历史 `{}`）、`UNSUPPORTED_VERSION` 和 `INVALID`。`SUPPORTED` 才可创建、更新、导入、绑定和执行；未知字段、缺字段、非布尔值、未知版本和非法 JSON 均拒绝，不静默降级。`LEGACY_COMPATIBILITY` 仅允许既有 `Compatibility fallback` 与历史 snapshot 读取，并在无绑定时按当前兼容语义执行；它不能成为性能候选、被新绑定引用或导出为 v1。兼容 fallback 是模型元数据缺失时的保守启动概念，与 `isFallback` 的用户策略概念独立，禁止互相推导。

### 4.2 Snapshot、绑定与删除

Job 受理必须在同一 Room transaction 内完成“读取已验证 preset → 解析 v1 → 写 Job 与原始 configJson snapshot → 将解析结果传给实际启动/请求”。执行只消费 snapshot，绝不回读可变 preset，因此已受理/排队 Job 不受编辑或删除影响；现有 `withTransaction` 写 Job/snapshot 是该不变量的基础。证据：`app/src/main/java/io/github/xororz/localdream/data/InferenceJobRepository.kt:88-124`。

新增 `performance_preset_bindings`，每行含 `bindingKey`（`DEFAULT` 或 `MODEL:<modelId>`，唯一）、`presetId`、`updatedAt`。`DEFAULT` 表示没有调用方显式选择时的未来请求策略；`MODEL:<modelId>` 只覆盖该模型，优先于 `DEFAULT`。解析顺序是显式 `presetId` → 模型绑定 → 默认绑定 → compatibility fallback；任一候选无效应拒绝受理而不是悄悄换性能配置，只有“没有绑定”才选择 fallback。

`Compatibility fallback` 不能修改或删除。删除用户 preset 必须在一个 Room transaction 内检查所有 binding、把引用改为 fallback、删除 preset，并返回 `PresetDeleteResult(reboundBindingKeys)`；MCP/界面消费者必须将该结果显示为“已回退 compatibility fallback”。事务失败时全部回滚。当前 DAO 的“仅按 `isFallback=0` 删除”不满足此要求。证据：`app/src/main/java/io/github/xororz/localdream/data/db/PerformancePresetDao.kt:26`。

## 5. 历史兼容、异常边界、测试与发布

### 5.1 历史兼容

- v5 既有 `performance_presets.configJson="{}"` 和 `preset_snapshots.configJson="{}"` 按 `LEGACY_COMPATIBILITY` 反序列化；迁移不得改写它们、不得重算 revision，也不得让旧 Job 改用新配置。
- `generation_history` 的 `jobId`、`presetId`、`presetRevision`、`runtimeFingerprint` 可为 `NULL`；报告标记为 `LEGACY_UNATTRIBUTED`，不得与 benchmark 组、可靠性或热稳定统计混合。证据：`app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt:142-220`、`app/src/androidTest/java/io/github/xororz/localdream/data/db/AppDatabaseMigrationTest.kt:40-83`。
- Room 升级只新增 binding 结构与索引，并保留 v5 预设、Job、snapshot 和历史行；迁移测试覆盖 v5→新版本、fallback 单例、旧快照可读和回退删除原子性。

### 5.2 测试矩阵

| 层级 | 必测结论 |
| --- | --- |
| JVM schema | scenario v1、摘要、W2/W2b 隔离；config v1 严格解析；`{}` 与未知版本的兼容/拒绝路径。 |
| JVM 领域规则 | revision 冲突、fallback 保护、同名与导入编号、snapshot 不变、绑定优先级、删除回退事务与失败回滚。 |
| JVM 统计/harness | 冷热分组、预热排除、样本下限、p50/p95/MAD/固定种子 CI、B0 timeout 冻结及每种 `Outcome`。 |
| Android instrumentation | v5→新 Room migration 保留历史 NULL 和历史 snapshot；真实 DAO 事务不留下半条 binding/删除结果。 |
| native/API 集成 | preset v1 确实进入 launch config；W6/W7 断言生成、文件、URL、下载、progress/cancel/reconnect 与 `/v1` 的同输入结果。 |
| 主机 harness | 生成可重算 RunManifest/报告，缺 RuntimeProbe、摘要不一致、混组或质量失败均拒绝发布结论。 |
| 一加 13 实机 | `VERIFIED` RuntimeProbe 后执行 W1–W7、B0、100 次、30/60 分钟与质量门禁；Redmi K30 只可跑前六层的非推理部分。 |

### 5.3 发布边界

[风险，高] 目标设备未连接，因此没有 QAIRT/HTP 实际加载、Context fingerprint、W1–W7、100 次或热稳定性结论。本阶段及 03–06 只交付代码、主机验证与测试发布证据；07 必须以一加 13 `VERIFIED` RunManifest 和原始报告决定业务验收。未提供编译输入时，V79 Context 重编译与“极限优化完成”声明保持排除；正式商店签名与最终用户生产发布不在本轮。

## 6. 规格验收

1. 所有 W1–W7 run 可由 scenario 摘要、snapshot、RunManifest 和原始样本重放或重算，W2 与 W2b 永不混合。
2. 缺失或不匹配 RuntimeProbe、样本分组、质量/可靠性/热稳定任一门槛时，报告只输出失败或未验证，不能输出一加 13性能通过。
3. 用户 preset 的 CRUD、名称/导入/revision 规则保持；v1 配置能实际影响启动，已受理 Job 语义不变，引用删除原子回退且可见。
4. 历史数据库和 JSON 都可读取且不被隐式改写；历史无归属数据不进入新统计。
