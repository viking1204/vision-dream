# ux-repository-search-20260801：独立变更评审（05-change-review）

- 阶段：`05-change-review`（独立变更评审）
- 迭代：1
- 评审范围：P1–P9 全量变更集（`41e96f5` → `765901e`，15 commits，53 文件，+4722 / -410）
- 结论：**通过（passed）**，P0/P1/P2 均有明确处置，评审修复后验证通过。

## 1. 红线核查（不可突破边界）

| 红线 | 结果 | 证据 |
| --- | --- | --- |
| Room schema 固定 v10 | ✅ 未越界 | `AppDatabase.kt` 由 v9→v10，终止于 v10；无后续版本提升。迁移 `MIGRATION_9_10` 为增量（加列 + 索引 + 新表 `prompt_sample_seed_models`），并由 `AppDatabaseMigrationTest` 覆盖。 |
| `BackgroundGenerationService` 时序/生命周期 | ✅ 未触碰 | 仅将负向提示词默认值来源由 `GenerationDefaults.resolveNegativePrompt` 改为读取 `GenerationPreferences.getGlobalNegativePrompt()`；调度、前台/通知、生命周期调用顺序均无变化。 |
| OpenAI / MCP / Remote 协议语义 | ✅ 未触碰 | `OpenAiApiController.kt`、`McpGenerationGateway.kt` 仅替换负向提示词默认值来源，HTTP 路由、请求解析、响应构造、MCP 工具契约均未变。 |
| `model_run` 路由语义 | ✅ 未触碰 | `Navigation.kt` 不在 diff 内；`ModelRunScreen.kt` 的变更均为屏幕内部控制流（全局负向提示词应用、OpenCL 弹窗简化、`PromptPickerDialog` 传 `modelId` 并应用采样参数）。初始化 `LaunchedEffect` 仍受 `hasInitialized` 守卫，不会在用户编辑中途覆盖字段。 |

## 2. 行为一致性核查

- **全局负向提示词默认值**：`GenerationPreferences.getGlobalNegativePrompt()` 在用户未设置/为空时回退到 `GenerationDefaults.DEFAULT_NEGATIVE_PROMPT`（`Preferences.kt:192`），与旧 `resolveNegativePrompt(null)` 行为**完全一致**——4 个调用点（OpenAi、Mcp、BackgroundService、ModelRunScreen）统一切换，无生成结果回归。
- **模型重命名安全加固**：`Model.rename()` 由 `newName.replace(" ", "")` 改为 `LocalModelId.normalize`，并新增 `RenameResult.Reason.InvalidId`；非法显示名安全失败，不将空格/特殊字符注入 HTTP/MCP 模型标识。

## 3. 评审发现与处置

### P2（已接受）
- **CR-01 — OpenCL 警告弹窗移除**（`ModelRunScreen.kt`）
  - 现象：原 `showOpenCLWarningDialog`（选择 GPU 时先弹风险警告、确认后才启用 OpenCL）被移除，现选择 GPU 直接启用 OpenCL。
  - 处置：**accepted**。属于本次 UX 改造范围內的刻意简化——将运行时视为普通用户设置直接生效，OpenCL 仍由用户显式选择触发，无安全/数据/协议影响；与全局负向提示词、性能预设等其他开关的去摩擦模式一致。

### P3（观察项，不阻塞）
- **CR-02 — 死字符串**：`opencl_warning`、`gpu_runtime_warning_title`（中/英/日/韩）在 OpenCL 弹窗移除后已无任何引用。留待后续清理 pass 删除，避免评审阶段改动四语文件引发多余回归。
- **CR-03 — 重命名错误文案通用**：`RenameResult.InvalidId` 在 UI 复用通用 `msgRenameFailed`，无专属文案。失败路径安全（已处理、弹 snackbar、不崩溃），属低优 UX 打磨。

## 4. 验证证据

全量门禁（评审后重跑）：

```
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest
→ BUILD SUCCESSFUL（97 actionable tasks: 1 executed, 96 up-to-date）
```

- 静态：`ktlintCheck` + `detekt` 通过，`lintDebug` 无阻断。
- 单测：`testDebugUnitTest` 通过（含 `AppDatabaseMigrationTest`、`CreationDraftPersistenceTest`、`DuplicateDetectorTest`、`MultiRepositorySearchMergerTest`、`ModelPromptSamplesTest`、`RepositoryConfigSerializationTest` 等）。
- 包体：`assembleDebug` + `assembleAndroidTest`（新增 `UiAccessibilityInstrumentedTest` 编译通过）。

## 5. 处置结论

- P0：无。
- P1：无。
- P2：1 项（CR-01），已接受并附理由。
- P3：2 项观察（CR-02/CR-03），不阻塞，纳入后续清理/打磨。

**`05-change-review` 门禁满足：P0/P1/P2 均有明确处置，评审修复后验证通过 → 推进至 `06-test-release`。**
