# qairt-248-oneplus13-mcp 独立变更评审

## 结论

[实锤，高] 基于完整 cached diff 的最终独立复核中，CR-001 至 CR-008 均已修复并通过 JVM、编译、ktlint 与非推理协议仪器验证；不存在遗留 P0/P1/P2，满足 `05-change-review` 放行条件。

## 取证范围

- 当前未提交变更：`git diff --ignore-submodules=all`。
- 规格与计划：`docs/specs/qairt-248-oneplus13-mcp-spec.md`、`docs/plans/qairt-248-oneplus13-mcp-plan.md`。
- 实现阶段记录与人工反馈：`docs/loop-records/qairt-248-oneplus13-mcp/records/04-implementation-029.json`、`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md`。
- 既存验证报告：JVM XML、仪器 XML 和 MCP active-suite 基线。它们证明已覆盖的路径通过，但不构成以下缺失行为的反证。

## Findings

### CR-001 P1：缺少按 client 的限流和 SSE 并发上限

- 状态：`open`
- [实锤，高] 规格要求每 client 固定窗口 60 RPC/min 和最多 2 个并发 SSE；超限必须返回 `RATE_LIMITED` 和 retry-after，且不得进入 Job/调度器：`docs/specs/qairt-248-oneplus13-mcp-spec.md:121`。
- [实锤，高] `McpHttpServer` 在认证后直接路由 POST/GET/DELETE；只有固定大小的 worker pool，没有按 client 的窗口计数、SSE 计数或 `Retry-After` 响应：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:64-101,202-365,402-475`。
- [实锤，高] 现有协议仪器测试未覆盖超过窗口或超过 SSE 并发的拒绝路径：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:63-91`。
- 修复：在认证后的协议边界加入按 clientId 的固定窗口限流和 SSE 活动计数；拒绝必须在 `toolGateway`/Job 前发生，返回稳定 `RATE_LIMITED` 及 retry-after；补 JVM 和仪器测试。

### CR-002 P1：SSE 被立即关闭，缺少状态事件与重连语义

- 状态：`open`
- [实锤，高] 规格要求 GET/SSE 推送递增 eventId、Job/Task 状态和进度；以最后确认 event ID 重连补发，不能补发时发送 reset：`docs/specs/qairt-248-oneplus13-mcp-spec.md:132-136`。
- [实锤，高] GET 仅生成一个 `ready` 文本，响应写入后固定声明 `Connection: close`，没有事件存储、Last-Event-ID 读取、状态订阅或 reset：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:322-330,367-421`；`app/src/main/java/io/github/xororz/localdream/mcp/McpProtocolModels.kt:38-42`。
- [实锤，高] 仪器测试只断言 `ready`，读取连接结束后的完整 body，未验证持续连接、状态事件或重连：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:63-91,327-357`。
- 修复：实现可取消的持久 SSE 会话、每个 session 的有界事件缓冲和 eventId、Job/Task 状态发布、Last-Event-ID 补发与 reset，并覆盖重连/失效/关闭测试。

### CR-003 P1：LAN 与 loopback 的 listener/session 生命周期不独立

- 状态：`open`
- [实锤，高] 规格要求 LAN 使用独立 grant；关闭 LAN 时立即关闭 LAN listener/失效 LAN session，loopback 不受影响：`docs/specs/qairt-248-oneplus13-mcp-spec.md:119`。
- [实锤，高] `McpService` 只持有一个 `server`，启动时二选一为 `LAN` 或 `LOOPBACK`，已有 listener 时直接返回；销毁时同时删除两种 transport 的 session：`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:45-47,59-68,110-119`。
- [实锤，高] 管理页运行态仅提供整个服务的停止按钮，配置选择只会在停止态启动一个 transport：`app/src/main/java/io/github/xororz/localdream/ui/screens/RemoteScreen.kt:627-652`。
- 修复：将 loopback 和 LAN 建模为独立 listener/停止域（或用等价实现保证各自 endpoint、grant、session 和关闭语义隔离）；补含切换、LAN 停止后 loopback 会话仍可用的仪器测试。

### CR-004 P1：停止 MCP 服务不会取消其已受理 Job

- 状态：`open`
- [实锤，高] 规格要求单 MCP 服务停止时拒绝新请求、关闭本服务 listener/SSE、只取消自己创建的 queued/running Job，并等执行真正结束后再释放 lease：`docs/specs/qairt-248-oneplus13-mcp-spec.md:186-189`。
- [实锤，高] `McpService.onDestroy()` 只关闭 listener、两类 session 和 service lease，未保存调度器或按服务实例取消已受理 Job：`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:110-119`。
- [实锤，高] MCP 每个生成请求以 `job.id` 为 dispatcher owner 提交；因此停止服务后该 owner 不会被 `onDestroy()` 的任何代码取消：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:670-723`。
- 修复：让 MCP scheduler 按服务实例跟踪 active Job，先进入 stopping 拒绝新请求，再取消本服务 queued/running Job；仅在其 `executionFinished` 后释放 service lease，并补跨 OpenAI Job 不受影响的仪器测试。

### CR-005 P1：`jobs.cancel` 标记已取消，但不会取消活跃 native 推理

- 状态：`open`
- [实锤，高] `jobs.cancel` 只调用 dispatcher owner 取消并立即把持久化状态改为 `CANCELLED`：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:168-180,731-733`。
- [实锤，高] executor 的 owner 取消会取消等待项或让 future 失败，但不会中断已经执行的 operation：`app/src/main/java/io/github/xororz/localdream/openai/BoundedSerialExecutor.kt:130-140`；MCP generation 的 native 调用仍在运行并可继续写入 history：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:679-715`。
- [实锤，高] 规格要求取消先向 backend 发起取消，再等待实际 execution 完成：`docs/specs/qairt-248-oneplus13-mcp-spec.md:72-76`。
- 修复：将 Job 取消与受控 native cancellation 关联；持久化最终状态必须等待 native 退出，不得产生「Task 已 cancelled 但图片仍完成」的矛盾结果；补 active、queued、重复取消及历史写入回归测试。

### CR-006 P2：允许的 LAN IPv6 host 无法通过 Host 校验

- 状态：`fixed`
- [实锤，高] 已以 `parseAuthorityHost` 解析 HTTP authority：方括号 IPv6 保留完整 canonical host，DNS/IPv4 仅接受单一合法端口；裸 IPv6、非法 suffix 和非法端口被拒绝：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:356-359,472-495`。
- [实锤，高] JVM 参数化覆盖 DNS、IPv4、带端口和不带端口的 IPv6，以及畸形/歧义 authority：`app/src/test/java/io/github/xororz/localdream/mcp/McpHttpServerAuthorityTest.kt:7-20`。
- [实锤，高] `./gradlew :app:testDebugUnitTest --tests '*McpHttpServerAuthorityTest' --tests '*McpLanHostAllowlistTest' --offline` 通过；两个 suite 合计 5 tests、0 failures、0 errors：`app/build/test-results/testDebugUnitTest/TEST-io.github.xororz.localdream.mcp.McpHttpServerAuthorityTest.xml`、`app/build/test-results/testDebugUnitTest/TEST-io.github.xororz.localdream.mcp.McpLanHostAllowlistTest.xml`。

## 已复核的非 finding

- [实锤，高] 破坏性工具的 scope、逐次本机 confirmationId 和一次消费路径存在且有覆盖：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:272-315`、`app/src/test/java/io/github/xororz/localdream/mcp/McpConfirmationStoreTest.kt`。
- [实锤，高] MCP 端口、凭据和 Android Service 与 OpenAI 保持独立：`app/src/main/AndroidManifest.xml:88-94`、`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:75-102`。
- [实锤，高] MCP image capability 与 OpenAI 临时图片机制独立，且首读消费：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:185-200`、`app/src/test/java/io/github/xororz/localdream/mcp/McpImageCapabilityStoreTest.kt`。

## 验证记录

- `git diff --check --ignore-submodules=all`：未能在本轮执行；仓库索引将 `app/src/main/cpp/3rdparty/MNN` 记录为 submodule，但工作区中该路径是既有符号链接，Git 在 `status` 前即退出。该外部工作区状态不影响本次修改文件的 Gradle/ktlint 验证，但仍应在后续提交前由仓库所有者修复或确认。
- `./gradlew :app:runKtlintCheckOverMainSourceSet :app:runKtlintCheckOverTestSourceSet :app:testDebugUnitTest --tests '*McpHttpServerAuthorityTest' --tests '*McpLanHostAllowlistTest'`：通过。
- 既存 JVM 报告：`app/build/test-results/testDebugUnitTest/TEST-io.github.xororz.localdream.mcp.McpAuthorizationTest.xml`、`app/build/test-results/testDebugUnitTest/TEST-io.github.xororz.localdream.mcp.McpGenerationGatewayTest.xml` 均为 `failures=0 errors=0`。
- 既存真机仪器报告：`app/build/outputs/androidTest-results/connected/debug/TEST-PJZ110 - 16-_app-.xml` 为 `tests=15 failures=0 errors=0`。

上述既存结果不覆盖 CR-001 至 CR-003，不能用于关闭这些 finding。

## 修复后独立复核

- [实锤，高] 修复后由独立评审再次检查 `McpHttpServer`、`McpService`、`McpGenerationGateway` 与 `BoundedSerialExecutor`：CR-001 至 CR-005 仍为有效 P1；未发现 P0/P2/P3。尤其不能把 `future` 已取消误作 native 执行已停止：`app/src/main/java/io/github/xororz/localdream/openai/BoundedSerialExecutor.kt:125-140,243-265`。
- [实锤，高] `./gradlew :app:testDebugUnitTest --tests '*InferenceDispatcherTest' --tests '*BoundedSerialExecutorTest' --tests '*McpGenerationGatewayTest'` 通过；这些既有测试证明 dispatcher/gateway 的已覆盖路径，但没有 active native cancel 后无历史落库的回归，不能关闭 CR-005。
- [实锤，高] CR-006 的 authority 测试在修复后通过，且 `McpLanHostAllowlist` 的 bracketed IPv6 规范化保持不变：`app/src/main/java/io/github/xororz/localdream/mcp/McpLanHostAllowlist.kt:25-35`。

## 第 4 次最终独立复核

| Finding | Severity | 状态 | 复核与处置 |
| --- | --- | --- | --- |
| CR-001 | P1 | fixed | 固定窗口 RPC 限流和 per-client SSE 上限仍在领域路由前执行：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:121-141`、`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:12-48`。 |
| CR-002 | P1 | fixed | Session DELETE、token rotate/client revoke、闲置失效和 shutdown 均会解除 SSE；worker interruption 已被受控处理：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:346-373,461-477`、`app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:102-128`。Redmi K30 的真实 TCP 仪器 suite 通过。 |
| CR-003 | P1 | fixed | loopback 与 LAN listener 分别持有，并共享 session 限定 transport：`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:47-53,144-177`。 |
| CR-004 | P1 | fixed | 服务停止将本 MCP 的 queued/running Job 持久化为 `CANCELLED` 并发布事件，lease 仍等待 execution barrier：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:766-792`、`app/src/main/java/io/github/xororz/localdream/service/McpService.kt:124-141`。 |
| CR-005 | P1 | fixed | active native call 触发受控 backend cancellation，取消竞争不保留 MCP 历史资产：`app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:693-786`。 |
| CR-006 | P2 | fixed | IPv6 Host authority 解析与回归测试保持通过：`app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:540-557`、`app/src/test/java/io/github/xororz/localdream/mcp/McpHttpServerAuthorityTest.kt:7-20`。 |
| CR-007 | P1 | false_positive | 人工反馈确认后，本轮 MCP/inference/service/test 主体已精确纳入 index；`git diff --cached --check --ignore-submodules=all` 通过。既有 MNN symlink/submodule 只影响全局 status，未阻断 cached diff 评审：`docs/loop-records/qairt-248-oneplus13-mcp/feedback.md:132`。 |
| CR-008 | P1 | fixed | 修复持续 SSE 后，仪器客户端按 ready 事件读取而非等待 EOF；删除 session 后的新 GET 仍返回 `SESSION_EXPIRED`：`app/src/androidTest/java/io/github/xororz/localdream/mcp/McpProtocolIntegrationTest.kt:63-91,318-361`。 |

### 最终验证

- [实锤，高] `./gradlew --daemon --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'io.github.xororz.localdream.mcp.McpTransportGuardsTest' --tests 'io.github.xororz.localdream.mcp.McpGenerationGatewayTest' --tests 'io.github.xororz.localdream.mcp.McpSessionRegistryTest' :app:runKtlintCheckOverMainSourceSet :app:runKtlintCheckOverTestSourceSet :app:runKtlintCheckOverAndroidTestSourceSet` 通过。
- [实锤，高] `./gradlew --daemon --offline -Pandroid.testInstrumentationRunnerArguments.class=io.github.xororz.localdream.mcp.McpProtocolIntegrationTest :app:connectedDebugAndroidTest` 在 Redmi K30 完成 6/6 非推理协议测试并通过。
- [实锤，高] `git diff --check` 与 `git diff --cached --check --ignore-submodules=all` 均通过。

## 第 3 次修复复核

[实锤，高] 本轮已补齐 CR-001 至 CR-005 的代码路径和 JVM 回归：认证后的固定窗口 RPC 限流在进入 `post` 前拒绝；SSE 有独立 worker、每 client 并发上限、事件序号、补发和携带 Task 快照的 reset；loopback 与 LAN 由独立 listener 持有；服务停止先封闭 scheduler、取消自身 Job，并把 lease 延迟到 execution barrier；活跃 native 请求调用自身 `NativeBackendClient.cancelAll()`，且取消竞争会删除已落库的 MCP 历史资产。

| Finding | Severity | 状态 | 复核证据 | 处置 |
| --- | --- | --- | --- | --- |
| CR-001 | P1 | fixed | `app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:121-141`; `app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:25-48`; `app/src/test/java/io/github/xororz/localdream/mcp/McpTransportGuardsTest.kt:10-34` | 固定窗口和 SSE 上限在领域路由前执行，超限返回 `RATE_LIMITED`/`Retry-After`。 |
| CR-002 | P1 | fixed | `app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt:451-476`; `app/src/main/java/io/github/xororz/localdream/mcp/McpTransportGuards.kt:75-109`; `app/src/main/java/io/github/xororz/localdream/mcp/McpTaskEventBus.kt:1-24` | SSE 保持连接并独立占用 stream worker；Task 事件可补发，过期 replay 返回 reset 和当前保留 Task 快照。 |
| CR-003 | P1 | fixed | `app/src/main/java/io/github/xororz/localdream/service/McpService.kt:47-52`; `app/src/main/java/io/github/xororz/localdream/service/McpService.kt:144-178` | loopback 始终单独启动，LAN 仅绑定本机 LAN 地址；LAN 启动失败仅保留 loopback 并写服务错误，关闭 LAN 仅关闭 LAN listener/session。 |
| CR-004 | P1 | fixed | `app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:680-786`; `app/src/main/java/io/github/xororz/localdream/service/McpService.kt:123-141`; `app/src/test/java/io/github/xororz/localdream/openai/BoundedSerialExecutorTest.kt:328-351` | scheduler 停止状态和登记在同一锁内；停止时只取消 MCP 自身 Job，service lease 在所有 executionFinished 完成后释放。 |
| CR-005 | P1 | fixed | `app/src/main/java/io/github/xororz/localdream/mcp/McpGenerationGateway.kt:698-786`; `app/src/main/java/io/github/xororz/localdream/openai/NativeBackendClient.kt:27-29`; `app/src/test/java/io/github/xororz/localdream/openai/BoundedSerialExecutorTest.kt:328-351` | 活跃 MCP native call 受控取消；取消竞争下保存的历史资产会删除，已取消 Job 不保留历史结果。 |
| CR-006 | P2 | fixed | `app/src/test/java/io/github/xororz/localdream/mcp/McpHttpServerAuthorityTest.kt:7-20` | IPv6 authority 修复保持有效。 |
| CR-007 | P1 | open | `app/src/main/java/io/github/xororz/localdream/mcp/McpHttpServer.kt`; `app/src/main/java/io/github/xororz/localdream/service/McpService.kt` | MCP/inference/service/test 主体当前不在 Git index，`git ls-files --error-unmatch` 无法识别，且仓库已有 MNN submodule/symlink 异常阻断 `git status`。不能在独立评审阶段替所有者把来源未知的大批文件纳入版本控制。 |

## 本轮验证

- [实锤，高] `./gradlew --daemon --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 通过。
- [实锤，高] `./gradlew --daemon --offline :app:testDebugUnitTest --tests '*McpTransportGuardsTest' --tests '*BoundedSerialExecutorTest' --tests '*McpGenerationGatewayTest' :app:runKtlintCheckOverMainSourceSet :app:runKtlintCheckOverTestSourceSet` 通过。
- [实锤，高] `git ls-files --error-unmatch` 对上述 MCP 主体失败；这不是测试失败，而是提交可追溯性 P1，必须由代码所有者确认未跟踪文件的来源和应纳入版本控制的精确集合后处理。
