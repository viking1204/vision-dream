# QAIRT 2.48 一加 13极限优化与 MCP 控制面需求输入

> 状态：关键需求口径已收口，可进入 `01-fact-review` 核验现有代码、SDK 与模型资料事实。本文是 `00-requirement-capture` 的正式输入，不是技术规格或实施方案。
>
> 需求 slug：`qairt-248-oneplus13-mcp`
> 记录日期：2026-07-28

## 原始信息

1. 人工反馈要求按 `QAIRT_2_48_ONEPLUS13_EXECPLAN.md` 推进完整实现：一加 13 极致优化、性能预设可新增/修改/删除，以及 MCP 与 OpenAI HTTP 必须是独立 Service、独立开关、独立可配置端口；两者均须支持本机环回和显式开启的局域网调用，MCP 配置须可一键复制。禁止退化为共享 Service 或共享端口。
2. 人工反馈的独立预审补充：MCP Tasks 必须按 2025-11-25 协议声明；远端 elicitation 不能作为破坏性操作的可信授权；Job 必须完整映射 MCP Task 并覆盖取消/失败；停止顺序必须消除 runtime lease 与活动任务取消竞态；图片短 URL 必须是独立、一次性、短 TTL capability token；Streamable HTTP 的 GET/SSE 与官方 conformance suite 是门禁。
3. 需求方提供的 `QAIRT_2_48_ONEPLUS13_EXECPLAN.md` 是本轮范围、约束、验收和里程碑的详细输入。它记录的运行时目标为 QAIRT `2.48.40.260702`，目标设备为 OnePlus 13（SM8750/HTP V79/24 GB），并将 MCP 基线确定为 `2025-11-25` Streamable HTTP。

## 澄清记录

| 时间 | 来源 | 已确认内容 | 对需求的影响 |
| --- | --- | --- | --- |
| 2026-07-28 12:00:41 | 人工反馈 | MCP 与 OpenAI HTTP 不可共用 Service、listener、端口、鉴权或开关；可共享领域能力、模型 runtime 与推理队列。 | 双 Service 隔离是硬性范围，不可替换为单 listener 路由。 |
| 2026-07-28 12:00:41 | 人工反馈 | 性能预设必须支持新增、修改、删除。 | 预设必须是可持久化、可版本化的数据，而非仅编译期枚举。 |
| 2026-07-28 12:00:42 | 系统预审 | Tasks、破坏性授权、Job 映射、停止竞态、图片 URL 与 GET/SSE/conformance 有额外硬门禁。 | 规格和计划不得把这些项目降级为可选或事后补充。 |
| 2026-07-28 | ExecPlan | “所有应用能力”限定为经权限建模的产品领域白名单，不包含 shell/ADB、任意文件、密钥读取、客户端自提权或安全设置修改。 | MCP 的能力覆盖以白名单 Tool/Resource/Prompt 为边界，不能实现万能 action Tool。 |
| 2026-07-28 | ExecPlan | LAN 默认关闭；仅允许用户显式开启的受信局域网调试，使用独立 MCP Token；不属于公网或不受信网络方案。 | LAN 不是默认暴露能力；公网/TLS/OAuth 2.1 不属于本轮。 |
| 2026-07-28 13:36:37 | 人工反馈 | 已授权从 Qualcomm 官方渠道取得 QAIRT 2.48.40；若账号或许可登录阻塞，由需求方完成登录操作。 | `01-fact-review` 可核验官方制品、校验信息与可复现获取路径；登录态不能由实现阶段猜测或绕过。 |
| 2026-07-28 13:36:37 | 人工反馈 | 当前没有 V79 Context 编译所需 ONNX/DLC、量化配置、校准集和既有编译参数；接受 M5 作为外部阻塞/后续边界，其余里程碑继续。 | 不得宣称已完成 V79 专用 Context 或“极限优化完成”；后续仅可交付不依赖 M5 的可验证范围。 |
| 2026-07-28 13:36:37 | 人工反馈 | 破坏性 MCP 操作默认每次都必须由本机 UI 签发 `confirmationId`；远端 elicitation 不构成授权。 | 不采用客户端限时预授权作为默认策略；规格必须定义逐次确认的绑定、过期与拒绝行为。 |
| 2026-07-28 13:36:37 | 人工反馈 | 本轮交付为 debug/测试 APK 与 OnePlus 13 真机验收，不包含正式商店或最终用户生产发布。 | `06-test-release` 与 `08-closeout` 不得要求生产签名、商店分发或生产写入许可。 |

## 业务目标

在 OnePlus 13 上将 Vision Dream 升级到 QAIRT `2.48.40.260702`，以固定画质与可靠性门禁为前提，分别获得“单张极速”和“持续极速”的可复现真机 Pareto 结果；同时交付一个可由本机或用户显式允许的局域网 Agent 使用的 MCP 控制面。

MCP 让受授权的 Agent 通过受控、可审计的协议调用应用已有的领域能力（生成、模型、下载、提示词、资产、性能预设、队列和诊断），而不能绕过单一 native 推理 pipeline、权限确认、模型兼容或资产管理规则。

## 用户/角色

| 角色 | 目标与权限边界 |
| --- | --- |
| 一加 13 本机用户/设备管理员 | 在 App 中选择预设、管理 MCP 客户端与 scope、显式开启 LAN、确认破坏性操作、复制连接配置、撤销 Token。 |
| 受授权 MCP 客户端（Agent） | 按授予 scope 调用产品领域 Tool，查询 Job/Resources/Prompts；不得自行扩大权限或把 elicitation 当作本机授权。 |
| OpenAI HTTP 客户端 | 保持既有 `/v1` 兼容行为，在自己的 Service、端口、开关和鉴权中使用相同的领域能力。 |
| 性能/质量验证人员 | 使用固定场景在目标真机采集基线与候选数据，依据画质、可靠性、热稳态和吞吐门禁决定默认预设。 |

## 场景链路

1. 本机用户选择或管理性能预设；每个已入队请求取得不可变 preset snapshot，运行中修改或删除预设不会改变已接受请求。
2. 本机用户分别启用 OpenAI 和 MCP 服务。MCP 默认绑定 `127.0.0.1:8810/mcp`；只有显式开启 LAN 后才按实际 Wi-Fi 地址提供局域网连接。两个服务端口都可配置且必须独立启停。
3. 本机用户在已解锁 UI 中创建/授权 MCP 客户端，并一键复制以实际 listener 地址和独立 Bearer Token 渲染的 Streamable HTTP 配置。
4. MCP 客户端通过 `initialize`、协商能力、调用 Tool 或订阅 GET/SSE 进度。生成、模型切换、load/unload 必须进入与 UI/OpenAI 共用的有界、串行、同模型优先且防饥饿的调度器。
5. 生成、下载和 profiling 形成可查询、可取消的 Job；普通 Tool 与协商后的 MCP Task 观察同一 Job 状态。图片使用独立的一次性短 TTL 下载 capability，而非暴露 Token、Session 或文件路径。
6. 用户/验证人员使用固定 W1–W7 场景比较 QAIRT、runtime lifecycle、驻留、Qmem、CLIP、HTP 功耗/热策略与 V79 Context 候选；只有满足质量、可靠性和持续吞吐约束的候选可成为默认预设。

## 范围边界

### 本轮包含

- QAIRT 2.48.40 的可复现构建、运行库一致性门禁、旧 Context 对照和 OnePlus 13/V79 选择。
- 可归因 benchmark/profiling、QNN runtime 生命周期、兼容 metadata 门禁、驻留/Qmem/host 热路径、取消/SSR/worker 生命周期及质量/性能发布门禁。
- 可管理的版本化性能预设：新增、复制、修改、重命名、排序、删除、导入/导出、默认/模型绑定、恢复发布默认值与不可删除的内部 compatibility 回退。
- 独立 MCP Service、listener、端口、鉴权、开关、Session 与 UI；保留独立 OpenAI Service 和既有 `/v1` 兼容。
- MCP 的白名单领域能力、Resources、Prompts、Job、可协商的 Tasks、GET/SSE、取消、配置一键复制、loopback/LAN 配对、scope、确认、审计与官方 conformance 门禁。

### 非目标

- GenieX 接入、把 MCP 路由并入 OpenAI listener、共享端口或让任一服务的停止影响另一服务。
- 请求级并行推理、MCP 绕过现有推理队列、默认 LAN/公网开放、无鉴权远控。
- MCP 任意 shell/ADB、任意文件路径、密钥读取、修改指纹开关、客户端自提权。
- 在 APK 中混装 QAIRT 2.44 与 2.48，或在没有 ONNX/DLC、量化配置和校准数据时声称完成 V79 专用 Context 重编译。
- root、关闭热保护、内核/固件修改、厂商私有超频，以及将 V81 专属能力套用于 V79。

## 验收口径

### 功能与隔离

- 性能预设完成 CRUD 及版本/快照语义：删除或更新不会改变执行中、已接受请求；默认预设、模型绑定、导入冲突、schema migration、非法组合均可验证。
- OpenAI 与 MCP 的四种开关组合（仅 OpenAI、仅 MCP、同时开启、均关闭）均成立；端口修改、冲突/占用/非法范围/bind 失败只影响对应服务且展示实际生效端口。
- MCP 以 `2025-11-25` Streamable HTTP 提供 JSON-RPC、POST、GET/SSE、Session DELETE、鉴权和结构化错误；官方固定版本 conformance server active suite 无本方案声明能力的 unexpected failure。
- MCP Tool/Resource/Prompt/Task 使用同一领域 Job；取消、失败、重连、过期 Session、跨客户端 Task、越权 scope 与非法 Origin/Host 都有明确拒绝或收口行为。
- 本机/LAN/安全连接配置可一键复制且可解析；完整配置只在本机显式复制时包含独立 Token，Token 轮换后旧配置立即失效。

### 性能、质量与可靠性

- 目标真机上的固定场景覆盖冷/热首张、第二张、Context load、CLIP、UNet、VAE、返回链路、内存和 30–60 分钟持续生成；报告 p50、p95、MAD/置信区间。
- QAIRT 2.48 搭配旧 Context 不得使热生成 p50 退化超过 2%、p95 退化超过 3%，且 API、资产记录、seed/尺寸行为无回归。
- 同画质极限 profile 的目标为 W1/W2 热生成 p50 与持续吞吐各至少提升 15%（25% 为 stretch）；未达成时只能交付候选事实，不能声明“极限优化完成”。
- 固定用例至少 100 次无卡死、崩溃、永久 loading 或第二次请求失效；30–60 分钟稳定性、热降频、power vote/FD/RSS/MemHandle 收敛符合 ExecPlan 门禁。
- 本轮可交付范围需通过 M0–M4、M6–M8 的适用性能门禁与 M9 的 MCP 门禁；MCP 未完成时只能报告阶段性性能实验。M5 仅在完整 V79 Context 编译输入可得后的后续范围适用，不能作为本轮通过条件或被伪称为已完成。

## 约束假设

- 目标硬件是 OnePlus 13（PJZ110 / SM8750 / HTP V79 / 24 GB）；其他设备通过 capability profile 使用保守回退，不将其性能结果外推为 OnePlus 13 结果。
- 默认端口为 OpenAI `8809`、MCP `8810`，可分别配置；两者不能使用相同端口，也不能使用内部 native `8081` 或 Device Link `8808`。
- MCP LAN v1 是用户显式开启的受信局域网调试 profile；公网或不受信网络需要后续 TLS 与标准 MCP OAuth 2.1，不能以本地 Token 代替。
- 性能候选只有在真实目标 SDK、目标设备、固定基准与质量/热/可靠性证据下才能进入默认预设；未验证组合必须回退 compatibility。
- 本阶段未验证 ExecPlan 中的当前基线、依赖版本、SDK/模型资料可用性或代码落点；这些是 `01-fact-review` 的事实核查对象，不能提前当作已实现事实。
- QAIRT 制品仅从 Qualcomm 官方渠道取得；若受账号或许可登录拦截，暂停获取并由需求方完成认证，不绕过授权控制。
- 缺少 V79 Context 编译输入是已接受的外部边界：M5 及“极限优化完成”声明不在本轮可完成范围，其余不依赖 M5 的里程碑仍须按各自门禁验证。
- 本轮仅交付 debug/测试 APK 与目标真机验收；不包含任何最终用户生产发布、商店分发或生产写入。

## 待确认点

本阶段没有仍需人工回答的业务口径。以下是已确认但必须在后续阶段持续处理的边界，不能误报为已具备：

1. **QAIRT 2.48.40 制品可得性与校验尚待事实核验。**已获从 Qualcomm 官方渠道下载的授权；若账号或许可登录阻塞，由需求方操作，不能绕过。**影响：01-fact-review 核验获取路径和校验信息，03-plan 的依赖步骤，04-implementation 的 M1 构建，以及 06/07 的真机验收。**
2. **V79 专用 Context 编译资料当前缺失，M5 是已接受的外部阻塞/后续边界。**缺少 ONNX/DLC、量化配置、校准集和既有编译参数时，禁止完成 V79 专用 Context 或“极限优化完成”声明。**影响：02-spec 的产物兼容策略，03-plan 的 M5，04-implementation，以及 08-closeout 的完成度矩阵。**
3. **破坏性 MCP 动作已确定为逐次本机确认。**每次必须由本机 UI 签发绑定请求的 `confirmationId`；远端 elicitation 与客户端预授权均不可替代。**影响：02-spec 的权限语义，04-implementation 的 Tool/Job 流程，07-business-e2e 的删除和停止服务验收。**
4. **发布范围已限制为 debug/测试 APK 和 OnePlus 13 真机验收。**正式签名、商店分发、最终用户生产发布不在本轮。**影响：03-plan 的发布边界，06-test-release 与 08-closeout 的可完成范围。**

## 可进入 fact review 的正式输入

后续事实核查应以本输入为准，并不得把尚待核验的 SDK/模型资料事实当作已实现结论：

1. 目标是将 Vision Dream 的目标运行时升级至 QAIRT `2.48.40.260702`，并在 OnePlus 13 上通过可复现测试寻找受画质、可靠性和热约束限制的局部性能上限。
2. 性能预设是业务数据，必须可 CRUD；所有请求在入队时冻结可审计的 preset snapshot，未验证/不支持的配置不可静默生效。
3. OpenAI 与 MCP 是严格独立的 Android Service/transport/listener/port/auth/switch；仅领域能力、runtime lease、数据 Repository 和单一串行推理调度器可共享。
4. MCP 采用 `2025-11-25` Streamable HTTP，正式支持 POST、GET/SSE、Session、鉴权、强类型白名单 Tool、Resources、Prompts、Job 和可协商 Tasks；必须经过官方 conformance suite。
5. 本机与显式 LAN 都需独立 MCP Token、scope、审计、限流和本机授权。删除、停止服务等破坏性操作必须逐次取得本机 UI 签发、绑定请求且会过期的 `confirmationId`；远端 elicitation 和客户端预授权均不得替代。
6. MCP 图片交付使用 listener 自己的一次性短 TTL capability URL；不能把 Token、Session、文件路径或默认 Base64 暴露给客户端。
7. 本轮可完成范围的停止条件受不依赖 M5 的性能门禁及 M9 MCP 门禁约束；M5/V79 专用 Context 和“极限优化完成”因编译输入缺失为已接受的外部边界，必须在后续完成度矩阵中如实保留。整体交付仅包含 debug/测试 APK 与 OnePlus 13 真机验收。

## 输入证据

- 人工反馈：`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md`（2026-07-28 12:00:41–13:36:37）。
- 需求方提供的计划输入：`QAIRT_2_48_ONEPLUS13_EXECPLAN.md`（目标与结论、范围与非目标、可配置性能预设、MCP Agent Control Plane、M8/M9 验收、实施阻塞与事实边界）。
