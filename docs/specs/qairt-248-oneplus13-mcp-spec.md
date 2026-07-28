# QAIRT 2.48 / OnePlus 13 / MCP 规格设计

> 需求：`qairt-248-oneplus13-mcp`
> 阶段：`02-spec`
> 设计结论：[实锤，置信度高] 现有 OpenAI 网关已经具备 native 推理互斥和有界串行排队，但它把 listener、鉴权、队列和运行时停止绑定在一个 `OpenAiApiService` 中。MCP 必须作为独立 Android Service 和独立 Streamable HTTP transport 新建；两个 transport 只能共享进程级领域调度、runtime lease、Repository 与模型目录，不能共享 listener、端口、Token、开关、Session 或停止域。

## 1. 设计基线与目标

### 1.1 已有代码约束

| 已有事实 | 设计约束 | 证据 |
| --- | --- | --- |
| `OpenAiApiService` 在启动时创建 `BoundedSerialExecutor`、`OpenAiApiController` 与 `OpenAiHttpServer`，在销毁时关闭 listener、取消 controller、终止队列并可能停止 backend。 | 不能将 MCP 接入这个 Service 或复用它的 executor 生命周期；公共调度器和 runtime 持有关系必须从该 Service 中抽离。 | `app/src/main/java/io/github/xororz/localdream/service/OpenAiApiService.kt:96-127,199-235` |
| `InferenceArbiter.process` 已保证 UI 与 API 对同一 native pipeline 的互斥；它在执行真正结束后才释放 API reservation。 | 新调度器必须保留“结果已取消不等于 native 调用已退出”的 lease 语义，MCP 取消不得提前释放 pipeline。 | `app/src/main/java/io/github/xororz/localdream/openai/InferenceArbiter.kt:11-64`；`app/src/main/java/io/github/xororz/localdream/openai/BoundedSerialExecutor.kt:100-122,221-247` |
| Room 当前为 `local_dream.db` v4，只有 generation history 与 prompt templates，并显式不允许 destructive fallback。 | 所有新持久化能力必须通过 v4 起连续 migration 进入；不得重建数据库或把旧 history 反推成 MCP Job。 | `app/src/main/java/io/github/xororz/localdream/data/db/AppDatabase.kt:10-142` |
| 现有 OpenAI Token 及端口 `8809` 存在于独立 preference；native backend 占用 `8081`，Remote Host 占用 `8808`。 | MCP 必须另建凭据和端口配置，默认 `8810`，并校验不能与 8081、8808、当前 OpenAI 生效端口冲突。 | `app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiPreferences.kt:8-50`；`app/src/main/java/io/github/xororz/localdream/remote/RemoteProtocol.kt:17-27` |
| OpenAI 临时图片 URL 使用其 listener 的可重复读取 token。 | MCP 图像能力必须有完全独立、单次消费、短 TTL 的 capability store；不得改变已有 OpenAI URL 的重复读取契约。 | `app/src/main/java/io/github/xororz/localdream/openai/TemporaryImageStore.kt:62-67,106-137`；`app/src/main/java/io/github/xororz/localdream/openai/OpenAiApiController.kt:436-483` |
| 规格编写时 CMake 默认从 QAIRT `2.44.0.260225` 拷贝 Android 与 Hexagon V79 库；2026-07-28 已取得并核验 2.48 archive。 | 2.48 替换必须在官方 archive manifest、路径与 checksum 已核验后进行全套原子替换；该门禁现已满足，实施证据见计划 1.1。 | `app/src/main/cpp/CMakeLists.txt:7-69`；`docs/plans/qairt-248-oneplus13-mcp-plan.md:11-45` |

### 1.2 目标

1. 以可持久化、可版本化的性能预设替代编译期枚举；每个受理的生成请求保存不可变快照。
2. 在不改变既有 OpenAI `/v1` 协议、端口、Token 或 Service 行为的前提下，交付 MCP `2025-11-25` Streamable HTTP 控制面。
3. 将所有 UI、OpenAI、MCP 的 native 推理统一纳入一个有界、串行、同模型优先且防饥饿的调度域，并让 Service 停止互不取消对方工作。
4. 用版本、来源和实测证据约束 QAIRT 运行库、模型兼容和 OnePlus 13 性能候选，防止未验证配置成为默认值。

### 1.3 非目标

- 不把 MCP 路由、Token、port、开关或 foreground Service 合并进 OpenAI 网关。
- 不向 MCP 暴露 shell、ADB、任意路径读写、密钥读取、安全开关修改或“万能 action”。
- 不默认开放 LAN，不提供公网、TLS 或 OAuth 2.1 方案，不以本地 Bearer Token 代替这些安全模型。
- 不混装 QAIRT 2.44 与 2.48 的任意子集，不把 V79 runtime library 视为专用 Context Binary。
- 不在本轮完成正式签名、商店分发或最终用户生产发布。

## 2. 总体组件与边界

```text
Compose UI ───────────────┐
OpenAiApiService ────────┼─> DomainGateway ─> InferenceDispatcher ─> BackendRuntimeLeaseManager ─> BackendService :8081
McpService :8810 ────────┘        │                    │                         │
     │                             ├─> Model / Prompt / History Repository      └─> native QNN runtime
     ├─> McpSessionRegistry         ├─> PresetRepository
     ├─> McpClientCredentialStore   ├─> JobRepository
     └─> McpAuditRepository         └─> McpImageCapabilityStore
```

| 组件 | 责任 | 禁止事项 |
| --- | --- | --- |
| `OpenAiApiService` | 保持 `/v1` 路由、OpenAI 自有 Token、端口、listener、临时图片契约和开关。 | 不持有进程唯一 executor；不管理 MCP session、scope 或审计。 |
| `McpService` | 单独 foreground Service；持有 MCP listener、Session registry、独立 credentials、SSE 与 MCP 停止域。 | 不调用 OpenAI listener，不读取 OpenAI API key，不暴露 `/v1`。 |
| `DomainGateway` | 将白名单 Tool/Resource/Prompt 映射为领域 Repository、Job 和调度请求。 | 不接受任意方法名、路径或文件参数；不绕过模型兼容校验。 |
| `InferenceDispatcher` | 进程唯一的 API/MCP 串行队列、模型 affinity、公平性、取消状态和 queue metrics。 | 不由任一 transport 的 `onDestroy` 直接销毁；不执行并行 native inference。 |
| `BackendRuntimeLeaseManager` | 管理 transport/job lease 与 backend 生命周期，等待实际执行退出后才释放。 | 不根据“某个服务停止”直接 kill 仍被其他 owner 持有的 backend。 |
| `Mcp*Repository` | 保存预设、Job、client grant、审计；屏蔽 Room 与 JSON schema 演进。 | 不把 Bearer Token、confirmationId、原始 prompt、图片字节或绝对路径写入审计。 |

[业务解读] 本设计面向设备管理员和已授权 Agent：管理员仍在 App 内控制谁能操作、何时暴露局域网和何时允许危险动作；Agent 获得的是可观察、可撤销的产品能力，而不是能抢占设备推理资源或越过本机安全确认的远程控制权。

## 3. 性能预设、请求快照与运行时兼容

### 3.1 预设领域模型

`PerformancePreset` 是业务数据，不使用 Kotlin enum。所有 ID 为 UUID 字符串；列表排序用 `sortOrder`，不以名称或创建时间隐式排序。

| 字段 | 类型与语义 | 约束 |
| --- | --- | --- |
| `presetId` | stable UUID，预设身份；重命名、复制和导入时用于区分同名预设。 | 不变；导入冲突不能覆盖本地同 ID。 |
| `name` | 面向用户的名称。 | trim 后 1–64 字符；同一 `modelSelector` 内不区分大小写唯一。 |
| `modelSelector` | nullable 模型 ID；`null` 表示通用候选，非空表示仅对完全匹配的已安装模型可选。 | 不存在、已卸载或不兼容的模型不可激活。 |
| `configSchemaVersion` | `configJson` 的解析版本。 | 仅接受已注册版本；未知版本只可导出/显示，不能激活。 |
| `configJson` | 规范化 JSON，保存运行时 tuning 组合及兼容 metadata 引用。 | 白名单字段、范围和组合必须由 schema 校验；不得夹带文件路径、Token 或任意命令。 |
| `revision` | 从 1 开始的单调版本；每次有效修改递增。 | 使用 optimistic update：请求带旧 revision，不一致返回 `PRESET_REVISION_CONFLICT`。 |
| `kind` | `USER`（可 CRUD）或 `COMPATIBILITY_FALLBACK`（内置保守回退）。 | fallback 不可删除、不可覆盖、不可设为未验证组合。 |
| `isDefault` | 当前设备的默认预设指针。 | 同时最多一个；修改默认通过事务替换。 |
| `sortOrder`、`createdAt`、`updatedAt` | 展示顺序与审计时间。 | `sortOrder` 重排使用单一事务，时间为 epoch millis。 |

`configJson` 的 v1 只允许以下命名空间：`runtime`（QAIRT/QNN runtime target 与库集版本）、`execution`（设备、线程、内存/驻留及 power 策略）、`generation`（steps、CFG、scheduler、尺寸约束）和 `compatibility`（模型/Context/SDK 版本与 digest）。字段缺失表示“使用已解析模型默认值”，不能表示“不校验”。解析顺序固定为：请求显式参数 > 入队时 preset snapshot > 模型 config > `GenerationDefaults.GLOBAL`；入队后不得重新解析当前 preset。现有默认参数的字段来源可见 `GenerationDefaults.kt:14-63`。

### 3.2 快照和 Job

每一个通过校验并进入调度器的请求创建 `InferenceJob`，并在同一数据库事务内保存 `PresetSnapshot`。快照包含 `presetId`（可空）、`presetRevision`（可空）、`configSchemaVersion`、规范化 `configJson`、`resolvedGenerationParams`、模型 ID、请求 owner、目标 runtime fingerprint 与创建时间。它是不可更新的审计事实：预设的更新、删除、重命名、排序、导入或默认切换不影响已创建 Job。

| `InferenceJob.status` | 业务语义 | 允许转移 |
| --- | --- | --- |
| `QUEUED` | 已受理，等待唯一 native pipeline。 | `RUNNING`、`CANCELLED`、`FAILED` |
| `RUNNING` | 已取得调度执行位，可能正在准备模型、推理或落盘。 | `SUCCEEDED`、`CANCELLED`、`FAILED` |
| `SUCCEEDED` | 已生成并成功保存历史/结果元数据。 | 终态 |
| `FAILED` | 校验后执行失败、runtime 不可用、超时或持久化失败。 | 终态 |
| `CANCELLED` | 提交方或本机管理员取消；无结果保证。 | 终态 |

取消只允许 `QUEUED` 或 `RUNNING`。队列中的 Job 必须原子移除且转 `CANCELLED`；运行中的 Job 先向 backend 发取消，再等待调度器 `executionFinished`，最后释放 runtime lease 并转终态。取消请求幂等：终态 Job 返回其终态，不重启或重复取消。

`generation_history` 可增加 nullable 的 `presetId`、`presetRevision`、`jobId` 和 `runtimeFingerprint`，仅记录新生成结果的关联；权威快照保存在 Job 表，不放在可被以后 UI 改写的预设表。已有 `requestId` 为 nullable，故旧行必须保持全部新增关联为 `null`，禁止以 `origin`、当前默认预设或模型配置反推。证据：`HistoryEntity.kt:42-52`、`AppDatabase.kt:91-123`。

### 3.3 导入、导出与冲突

预设导出 envelope 固定为：`format: "vision-dream-performance-preset"`、`schemaVersion`、`exportedAt` 与 `presets`。每个对象携带上述持久字段和 `configJson`，不携带 `isDefault`、Token、client grant、Job、历史图片或设备私密路径。

- 导入前先校验 envelope/version、ID、名称、schema、字段范围、模型兼容和 runtime fingerprint；任一项非法则整批拒绝并不写入。
- 同 `presetId` 且内容摘要相同为幂等跳过；同 ID 内容不同或同 selector 下名称冲突，创建新 UUID，并追加“导入”后缀直到名称合法；不得静默覆盖本地预设。
- `COMPATIBILITY_FALLBACK` 只能由应用随 migration/首次启动建立，导入项一律成为 `USER`；它永远可被选择但不能删除。
- 未通过目标 SDK、模型或 Context compatibility gate 的预设可保存为草稿并导出，但不能设默认、不能入队；返回 `PRESET_INCOMPATIBLE` 及不兼容字段。

### 3.4 QAIRT 与模型 metadata

构建输入必须产出版本化 runtime manifest：SDK 版本、官方 archive identity、每个进入 APK 的 Android/Hexagon 库相对路径与 SHA-256、ABI、HTP target、Context fingerprint。安装时只接受 manifest 内完整同一 SDK 集合；库集版本或 digest 不一致立即阻止启动并报告 `RUNTIME_FINGERPRINT_MISMATCH`。

模型目录现有 `.vision-dream-model.json` 已有显式 `schema_version` 与失败即返回 `null` 的读取路径（`ModelMetadata.kt:76-165`）。扩展 runtime compatibility 时采用新 metadata schema version；旧 schema 保持可读，并解析为 `compatibility=unknown`。未知、缺失、SDK 不匹配、ABI 不匹配或 Context fingerprint 不匹配的模型只能走 `COMPATIBILITY_FALLBACK` 对照，不可被带有目标性能声明的预设激活。

## 4. MCP transport 规格

### 4.1 监听、鉴权和配置

| 项目 | 规格 |
| --- | --- |
| Service / listener | 新建 `McpService`，单独 foreground notification、开关、启动状态和错误状态；仅监听 `/mcp` 与 MCP 图片 capability 路径。 |
| 默认地址 | loopback `127.0.0.1:8810/mcp`；只有本机已解锁 UI 显式开启 LAN 后，listener 才绑定所有接口并向用户展示实际 Wi-Fi 地址。 |
| 端口 | 可配置有效 TCP 端口 1024–65535；禁止 8081、8808、当前 OpenAI 生效端口及已被其他 listener 占用的端口。失败只使 MCP 进入 error，不能停止 OpenAI。 |
| 身份 | 每个 MCP client 有独立 Bearer Token 和 client ID，Token 只以 Android Keystore 保护的密文保存；明文只在本机 UI 创建/轮换/复制时显示一次。MCP 不读取 `openai_api` preferences。 |
| LAN | 默认 false；LAN 需要单独 client Token，不能复用 loopback grant。停用 LAN 后立即关闭 LAN listener、失效 LAN session，loopback 不受影响。 |
| Host / Origin | 允许 loopback 或本机当前 LAN host；拒绝不在 allowlist 的 `Host`，拒绝有值但不在 allowlist 的 browser `Origin`，不把缺失 Origin 的非浏览器 MCP client 误判为失败。 |
| 限流 | 对 client ID 做固定窗口：默认 60 RPC/min、2 个并发 SSE；范围 1–120 与 1–4。超限返回结构化 `RATE_LIMITED` 和 retry-after，不进入 Job 或调度器。 |
| 复制配置 | 仅在用户明确点击时渲染有效 listener URL、`Authorization: Bearer`、协议版本和已授 scope；轮换/撤销后旧配置立刻返回 `UNAUTHORIZED`。复制文本不可写审计。 |

### 4.2 JSON-RPC、Session 与 SSE

MCP 基线固定为 `2025-11-25` Streamable HTTP。所有 MCP 错误使用 JSON-RPC error object，保留稳定 `data.code`，不得把 Android exception、Token、文件路径或 native command 输出给客户端。

| 流程 | 行为 |
| --- | --- |
| `initialize` / POST | 成功协商后创建随机 `Mcp-Session-Id`，与 client ID、token generation、transport（loopback/LAN）、scope snapshot、创建/最后活动时间绑定。未初始化 session 不得调用 Tool。 |
| 后续 POST | Bearer Token、session ID 和 transport 必须同时匹配；session ID 不可跨 Token、跨 client 或跨 listener 使用。 |
| GET / SSE | 只接受已认证、已初始化的 session；事件包含递增 `eventId`、Job/Task 状态与最小可显示进度。重连以最后已确认 event ID 补发；不可补发时发送 reset 事件和当前快照。 |
| Session DELETE | 立即关闭该 session 的 SSE，清理内存状态；不自动取消该 session 已创建的 Job。显式 `jobs.cancel` 才能取消。 |
| 过期 / 重启 | 15 分钟无活动过期；McpService 重启、Token 轮换、client revoke 或 LAN 关闭都使受影响 session 失效，返回 `SESSION_EXPIRED` 或 `UNAUTHORIZED`，不隐式重建。 |

服务端在 `initialize` 的 capability 声明中只声明实际启用的 Tools、Resources、Prompts、SSE 与 Tasks。不能以“未来实现”声明能力。官方固定版本 conformance server 的 active suite 是上线前协议门禁；任何属于本规格声明能力的 unexpected failure 均失败。

### 4.3 白名单能力、scope 和风险级别

Tool 名称、输入 JSON schema、输出 schema 和所需 scope 必须静态注册。未注册 name、额外字段、未知 enum、过大 binary payload 和未授权 scope 返回结构化拒绝，不进入领域层。

| 能力组 | scope | Tool / Resource / Prompt | 风险级别 |
| --- | --- | --- | --- |
| 模型与运行态查询 | `models.read` | `models.list`、`models.get`、`runtime.status`、models resources | `READ` |
| 生成与结果读取 | `generation.run`、`jobs.read` | `generation.create`、`jobs.get`、`jobs.list`、Job resources | `MUTATE` / `READ` |
| 预设 | `presets.read`、`presets.write` | `presets.list/get/create/update/reorder/export/import` | `READ` / `MUTATE` |
| 提示词 | `prompts.read`、`prompts.write` | 已有 prompt template 的 list/get/create/update | `READ` / `MUTATE` |
| 下载和资产查询 | `downloads.read`、`downloads.write`、`assets.read` | 下载状态、已安装资产查询、受控下载创建 | `READ` / `MUTATE` |
| 诊断 | `diagnostics.read` | 只读 queue/runtime/compatibility 摘要 resources | `READ` |
| 受控动作 | 相应 write scope + 本机确认 | `jobs.cancel`、`presets.delete`、`prompts.delete`、`assets.delete`、`downloads.cancel`、`runtime.unload`、`server.stop`、`client.revoke`、`token.rotate` | `DESTRUCTIVE` |

`READ` 只查询，`MUTATE` 写入可恢复或不影响正在执行的状态，`DESTRUCTIVE` 删除资产/配置、取消已受理工作、卸载 runtime、停止服务、撤销凭据或轮换 Token。风险级别由服务端静态映射，客户端不能通过 scope、MCP elicitation 或参数标志降低它。任何可能因模型切换而取消其他 Job 的动作都属于 `DESTRUCTIVE`。

### 4.4 本机确认与审计

破坏性 Tool 必须同时满足 client scope 和逐次本机 UI `confirmationId`：

1. MCP 先以完整规范化 action、target IDs 和参数摘要请求确认；没有有效 confirmation 时返回 `CONFIRMATION_REQUIRED`，不创建 Job、不改状态、不发起 elicitation。
2. 已解锁的本机 UI 展示 action、目标、调用 client、scope、参数摘要和过期时间；用户接受后生成高熵一次性 `confirmationId`。
3. `confirmationId` 绑定 client ID、Token generation、action name、canonical parameter digest、目标集合和 60 秒 TTL；使用一次即消费。任一绑定项不一致、过期、已消费、服务重启、Token 轮换或用户拒绝均返回 `CONFIRMATION_INVALID`。
4. 远端 elicitation 只可用于不具安全效力的交互信息；它不能创建、刷新或替代 `confirmationId`，也不能预授权一段时间内的同类操作。

每个 MCP 调用写一条 append-only `McpAuditEvent`：`eventId`、时间、client ID、transport、session hash、method/tool、scope snapshot、risk、canonical parameter digest、关联 job ID、outcome code 和 duration。审计不保存 Bearer Token、confirmationId、原始 prompt、图片、绝对路径、文件内容或完整复制配置。管理员可按时间/client/job 查询，撤销 client 不删除既有审计。

### 4.5 Job、MCP Task 与结果 capability

每个异步 generation/download/profiling Tool 创建一个 `InferenceJob` 或相应领域 Job；MCP Task 只是同一个 Job 的协议投影，不能另建无关联任务。映射固定为：

| Job | MCP Task | 说明 |
| --- | --- | --- |
| `QUEUED`、`RUNNING` | `working` | Task progress 由 Job 事件驱动。 |
| `SUCCEEDED` | `completed` | result 仅含结果 metadata 和一次性图片 capability URL。 |
| `FAILED` | `failed` | 保留稳定错误 code 与可显示信息。 |
| `CANCELLED` | `cancelled` | 取消原因只说明发起方类型，不泄露其他 client 信息。 |

Tool 协商 Tasks 前只返回 Job reference；协商成功后返回同一 Job 的 Task reference。`tasks/get`、`tasks/result` 和取消必须验证调用 client 对 Job 的所有权或显式管理员 scope；跨 client 返回 `JOB_NOT_FOUND`，防止枚举。

MCP 图片能力使用单独随机 token：绑定 client、job、listener、mime、创建时间和 60 秒 expiry；GET 成功开始传输时原子消费，第二次或过期访问一律 404。下载路径仅从 MCP listener 生成，绝不复用 `TemporaryImageStore` 的 token、OpenAI host/port、Session 或 API key。图片内容不默认 Base64；只有协议 schema 明确要求且受固定大小上限保护的字段才允许内联。

## 5. 生命周期、取消与异常边界

### 5.1 启动与停止顺序

| 阶段 | OpenAI 或 MCP transport 自己完成 | 公共层行为 |
| --- | --- | --- |
| 启动 | 校验自己的开关、端口、凭据和绑定；创建自己的 listener；成功后才宣布 ready。 | 取得 service runtime lease；不抢占另一个 transport listener。 |
| 提交 | 做 transport auth/schema/scope/confirmation 校验，创建 Job。 | 通过统一 dispatcher 取得 job lease；队列满为 `QUEUE_FULL`，UI 已占用为 `PIPELINE_BUSY`。 |
| 单服务停止 | 先标记 stopping，拒绝新请求；关闭自身 listener/SSE；失效自身 session；只取消自己创建的 queued/running Job。 | 等每个活动执行的 `executionFinished`，再释放对应 job lease；最后释放 service lease。 |
| 最后 lease 释放 | 无。 | 仅当无 UI、OpenAI、MCP service 和 active Job lease 时，才可按 BackendService 现有规则停止 backend。 |

此顺序修复现有 `OpenAiApiService.onDestroy()` 同时 shutdown executor 和 backend 的耦合风险。核心不变量是：`future` 取消或 HTTP 已断开不代表 native 调用已结束；直到实际执行结束前，runtime lease 与 `InferenceArbiter` reservation 必须保留。现有 executor 的 `executionFinished` 已提供这一语义（`BoundedSerialExecutor.kt:100-122,221-247`）。

### 5.2 结构化错误

| code | HTTP / JSON-RPC 类别 | 含义与恢复方式 |
| --- | --- | --- |
| `UNAUTHORIZED` | auth | Bearer 缺失、错误、已轮换或已撤销；重新在本机复制配置。 |
| `SESSION_EXPIRED` | session | session 不存在、过期或属于另一 transport/client；重新 initialize。 |
| `SCOPE_DENIED` | authorization | client 没有静态注册的所需 scope；管理员需新建或修改 grant。 |
| `CONFIRMATION_REQUIRED` / `CONFIRMATION_INVALID` | authorization | 破坏性 action 无逐次本机确认或 confirmation 绑定/TTL 失效；在本机 UI 重新确认。 |
| `RATE_LIMITED` / `QUEUE_FULL` / `PIPELINE_BUSY` | capacity | 限流、等待队列已满或 UI 独占 native pipeline；带 retry 信息，不创建重复 Job。 |
| `PRESET_INCOMPATIBLE` / `PRESET_REVISION_CONFLICT` | validation | 预设未通过 compatibility gate 或被并发修改；刷新预设后重试。 |
| `RUNTIME_FINGERPRINT_MISMATCH` | runtime | SDK 库集、ABI、HTP、Context 或 digest 不一致；拒绝启动/激活并保留 compatibility 回退。 |
| `JOB_NOT_FOUND` / `JOB_TERMINAL` | resource | Job 不属于调用 client、已清理或不能再取消；不泄露其他 client 状态。 |
| `INFERENCE_CANCELLED` / `INFERENCE_FAILED` | execution | 已进入 backend 的取消或失败；保留关联 Job 和稳定诊断摘要。 |

任何 listener bind 失败、端口冲突、Keystore 不可用、Room migration 失败、backend startup 失败、SSE 写失败或 token store 清理异常都必须落入该 transport/Job 的 error 状态并写审计。不得吞异常后报告 ready，也不得因 MCP 异常更改 OpenAI 开关或 listener。

## 6. 数据库、JSON 与历史兼容

### 6.1 Room migration

从 v4 开始按一次连续 schema migration 升级：新增 `performance_presets`、`inference_jobs`、`preset_snapshots`、`mcp_client_grants`（只存 token 的 Keystore alias/generation，不存明文）、`mcp_audit_events`，并向 `generation_history` 增加第 3.2 节所列 nullable 关联列及索引。所有 foreign key 采用逻辑关联或 `SET NULL` 语义：删除 `USER` preset 不得删除 Job、snapshot 或历史。

迁移必须满足：

- migration 只新增表/列/索引和写入一个 `COMPATIBILITY_FALLBACK`；不修改旧 history 的业务值。
- migration 前的 `generation_history` 行保持可读，其新增字段为 null；`mode=UNKNOWN` 的旧数据仍遵循现有历史筛选兼容规则。证据：`HistoryFilter.kt:7-24,66-77`。
- 新实体的 Room converter 对未知 enum/未知 JSON schema 解析为显式 `UNKNOWN`/不可激活，不抛弃整条历史记录；只有当前请求的入队校验才失败。
- 不调用 `fallbackToDestructiveMigration`，Room open failure 必须显式暴露，符合当前数据库策略。证据：`AppDatabase.kt:130-141`。

### 6.2 备份与外部 JSON

当前 history backup manifest 是 v1，导出字段不含 `origin`、`mimeType`、`requestId` 或新关联（`HistoryBackup.kt:194-224`）。兼容策略如下：

1. 历史 backup v1 导入保持原行为，不要求 preset/Job/MCP 字段；这些字段全部为 null/unknown。
2. history backup 升级时 envelope `version` 递增；导入器先读 version，再以可选字段读取新增关联。未知新字段忽略，未知更高版本拒绝并报 `UNSUPPORTED_BACKUP_VERSION`，不部分导入。
3. performance preset export 与 history backup 使用不同 `format`，互不嵌入；preset 导入永不生成 History，也不能恢复 MCP Token/client grants。
4. 既有 OpenAI `/v1` request/response JSON 不新增必填字段、不改 endpoint、不改图片 URL 语义。MCP JSON-RPC 是新 listener 的新契约，不能影响 `OpenAiJson` 或 `TemporaryImageStore`。

### 6.3 枚举反序列化

已有 `GenerationMode.fromString` 已将未知值收敛为 `UNKNOWN`（`HistoryFilter.kt:7-24`）。新增持久枚举一律使用显式 wire string 与 `fromWire`：未知 `PresetKind` 降级为不可激活 `UNKNOWN`，未知 Job/Task 状态保留为 `UNKNOWN` 只读，未知 audit risk 记为 `UNKNOWN`。MCP 对外请求的未知 enum 必须立即 `INVALID_PARAMS`，不能做 fallback 执行。这样将“读取历史的宽容性”与“执行新动作的严格性”分开。

## 7. 验证矩阵

| 编号 | 维度 | 最小验证与通过条件 | 关联缺口 |
| --- | --- | --- | --- |
| VM-01 | Room / 迁移 | 用 v4 fixture 打开并迁移；旧 history 数量、ID、图片路径、筛选结果不变；fallback 记录存在；无 destructive migration。 | FR-01、FR-02 |
| VM-02 | preset CRUD / snapshot | create/update/rename/reorder/delete/import conflict；已入队 Job 在每种操作后保持原 snapshot/revision；fallback 删除被拒绝。 | FR-01 |
| VM-03 | 备份 / JSON | 导入 v1 history backup；导出/导入 preset envelope；未知 history optional 字段兼容、未知新 version 拒绝、MCP 不影响 OpenAI JSON golden cases。 | FR-02 |
| VM-04 | listener 隔离 | OpenAI-only、MCP-only、both、neither 四组合；各自 port 改动/冲突/bind 失败只影响本服务；8808/8081/同端口拒绝。 | FR-03 |
| VM-05 | 生命周期 / 并发 | OpenAI active + MCP stop、MCP active + OpenAI stop、UI active + 两 transport submit；验证无跨 transport cancel、lease 在 `executionFinished` 后释放、backend 仅最后 owner 释放后停。 | FR-04 |
| VM-06 | MCP 协议 | `2025-11-25` initialize、POST、GET/SSE、resume、DELETE、session expiry/restart、错误 JSON-RPC；固定 conformance active suite 对已声明能力无 unexpected failure。 | FR-03、FR-05 |
| VM-07 | scope / confirmation / audit | 未授权、跨 client、已撤销 token、LAN disabled、坏 Host/Origin、限流、每种 destructive action 的 absent/expired/replayed/mismatched confirmation；审计不出现密钥、prompt、路径或图片。 | FR-05 |
| VM-08 | Job / Task / image | queued/running/succeeded/failed/cancelled 一一映射；Job cancel 幂等；GET/SSE 收到状态；图片 URL 60 秒 TTL 且首次下载后第二次 404；OpenAI 10 分钟重复 URL 回归不变。 | FR-05、FR-06 |
| VM-09 | runtime 兼容 | runtime manifest 缺库、混版、SHA/ABI/HTP/Context 不匹配均拒绝；完整匹配时可启动；未验证 preset 不能默认/入队。 | FR-07 |
| VM-10 | 目标设备性能 | OnePlus 13 使用固定 W1–W7 场景，采集冷/热、p50/p95/MAD、100 次可靠性和 30–60 分钟热稳定性；QAIRT 2.48 + 旧 Context 的回归门槛和候选提升门槛按需求验收。 | FR-07 |

测试层次：VM-01 至 VM-08 以 JVM/unit、Room migration、协议集成和 mock native backend 自动化；VM-09 需要官方 archive 与真实 APK；VM-10 必须在 OnePlus 13 真机执行。现有 `BoundedSerialExecutorTest` 与 `InferenceArbiterTest` 是调度公平性和 lease 语义的回归基线，不能因抽取公共组件而删除。证据：`docs/reviews/qairt-248-oneplus13-mcp-fact-review.md:43-50`。

## 8. 发布与接受边界

### 8.1 本轮发布边界

- 交付物为可安装 debug/测试 APK、Room migration/协议/单测证据、MCP conformance 结果和 OnePlus 13 真机验收记录。
- 不执行正式签名、商店提交、最终用户生产发布或生产写入。
- OpenAI `/v1` 是回归对象；MCP 不完整时不得伪称完整控制面或以性能实验替代 MCP 门禁。

### 8.2 已接受边界

[实锤，置信度高] V79 专用 Context Binary 的 ONNX/DLC、量化配置、校准集和既有编译参数尚未提供。因此 M5 专用 Context 重编译及“极限优化完成”声明不属于本轮完成条件；完成度矩阵必须将其标为 accepted boundary，不能以已有 `libQnnHtpV79*.so` 或其它设备结果替代。证据：`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md:14-16`；`docs/reviews/qairt-248-oneplus13-mcp-fact-review.md:94-96`。

### 8.3 实施前外部前置

[实锤，置信度高] QAIRT `2.48.40.260702` 官方条目和下载门禁已确认。规格编写时 archive 的 Android aarch64 / Hexagon V79 文件清单与校验尚未实测；2026-07-28 用户取得官方 archive 后，已完成 SHA-256、CRC、`sdk.yaml`、Android aarch64、Hexagon V79 runtime/skel 和 LICENSE 核验，并构建出带 Build ID `v2.48.40.260702151143` 的 core 与 debug APK。当前实施不得再把 Windows host 或 archive 获取列为 external blocker；仍需保留 runtime compatibility evaluator、VM-09 和 P8 真机门禁。证据：`docs/plans/qairt-248-oneplus13-mcp-plan.md:11-45`；`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md`。

## 9. 缺口可追溯性

| Fact review 缺口 | 本规格落点 |
| --- | --- |
| FR-01：性能 preset 不存在 | 第 3.1–3.3 节，v4 连续迁移与 VM-01/02。 |
| FR-02：历史/备份不含新关系 | 第 3.2、6.1–6.3 节与 VM-01/03。 |
| FR-03：无独立 MCP Service/listener | 第 2、4.1、5.1 节与 VM-04/06。 |
| FR-04：OpenAI 私有 executor/停止耦合 | 第 2、5.1 节与 VM-05。 |
| FR-05：无 MCP 协议、scope、Task、确认和审计 | 第 4.2–4.5、5.2 节与 VM-06/07/08。 |
| FR-06：OpenAI 图片 URL 可复用 | 第 4.5 节与 VM-08。 |
| FR-07：仅有 QAIRT 2.44、2.48 未核验 | 第 3.4、7 VM-09/10、8.3 节。 |

本规格未保留未决语义：性能预设的快照/删除/导入、MCP Task/Job 映射、scope、逐次确认、listener 生命周期、JSON 兼容、异常码、测试与发布边界均已明确；实施阶段只能在这些契约内选择具体类、DAO、UI 和协议库实现。
