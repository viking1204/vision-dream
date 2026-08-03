# ui-redesign-20260802：独立变更评审（05-change-review）

- 阶段：`05-change-review`（独立变更评审）
- 迭代：1
- 评审范围：P2–P8 全量变更集（`13e2945` → `44ebd2c`，7 commits，17 文件，+2195 / -422）
- 结论：**通过（passed）**，P0/P1 无，P2 无，P3 仅观察项，评审后全量门禁复跑通过。

## 1. 红线核查（不可突破边界）

| 红线 | 结果 | 证据 |
| --- | --- | --- |
| Room schema 固定 v10 | ✅ 未越界 | `data/db/AppDatabase.kt:23` 仍为 `version = 10`；`git diff 13e2945..HEAD -- 'app/src/main/java/**/data/db/**'` 为空，无新增 entity / 迁移 / 版本提升。任务队列走 DataStore（`Preferences.kt` 新增 `saveGenerationQueue` 等），未触碰 Room。 |
| `BackgroundGenerationService` 时序/生命周期 | ✅ 未触碰 | `git diff 13e2945..HEAD` 命中 `BackgroundGenerationService` 的文件列表为空；队列仅复用既有生成触发调用（`ChatGenerationScreen` 内的出队 LaunchedEffect），未改动服务内部调度/前台/通知/生命周期顺序。 |
| OpenAI / MCP / Remote 协议语义 | ✅ 未触碰 | `OpenAi*` / `Mcp*` / `Remote*` 相关文件不在 diff 内；协议层请求解析、响应构造、工具契约均无变化。 |
| `model_run` 路由语义 | ✅ 未触碰 | `navigation/Navigation.kt:34` 仍为 `object ModelRun : Screen("model_run/{modelId}?remote={remote}")`，createRoute 不变；diff 未触及该文件。 |

## 2. 行为一致性核查

- **创作输入不禁用（P3）**：`ChatGenerationScreen` 移除 `enabled = !isGenerating` 对整输入区的绑定；发送改为 `PendingChatRequest` 快照入队，空闲边沿单件出队。行为由「生成中锁死输入」变为「生成中可继续编辑并排队」，属需求刻意变更，无协议/数据回归。
- **任务队列持久化（P5）**：`GenerationQueueCodec` 仅序列化有源字节的图生图任务（无源图字节的任务不持久化，避免写入不完整数据）；恢复时 `queueAutoRun=false`（停车策略），需用户点「Start queue」才出队，避免重开 App 静默烧 GPU。
- **资产设为默认（P6）**：`AssetDefaultsPromotion.promote()` 保守合并——seed 永不复制、尺寸全有/全无（须 128..2048 且 %64==0）、scheduler/steps/cfg 越界则保留原值；写回归 `GenerationPreferences.saveAllFields`，完全不碰 Room。

## 3. 评审发现与处置

### P0 / P1
- 无。

### P2
- 无。

### P3（观察项，不阻塞）
- **CR-01 — `ModalBottomSheet` 废弃 API 沿用**：`GenerationQueueSheet.kt` 使用 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`，该 API 已 deprecated。属项目既有用法（非本次引入），且 `ModalBottomSheet` 面板在 OnePlus 6 真机验证可正常弹出与交互，故保留现状，留待后续整体迁移。
- **CR-02 — `GenerationQueueBar` 定义位置**：`GenerationQueueBar`  composable 定义在 `GenerationQueueSheet.kt` 文件内（非独立文件）。纯组织层面观察，无功能影响；若团队偏好单文件单组件，可后续拆出。
- **CR-03 — 队列上/下移改用显式按钮**：为兼顾 TalkBack / switch access 可达性，队列重排未采用拖拽手柄，而用显式 `KeyboardArrowUp` / `KeyboardArrowDown` 按钮（AC-07 正向项，非缺陷）。

## 4. 验证证据

全量门禁（评审后重跑，日志 `/tmp/gate.log`）：

```
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug \
          :app:assembleDebug :app:testDebugUnitTest :app:assembleAndroidTest
→ BUILD SUCCESSFUL
```

- 静态：`ktlintCheck` + `detekt` 通过，`lintDebug` 无阻断。
- 单测：`testDebugUnitTest` 通过，含 `ModelTagDerivationTest`、`GenerationQueueTest`（10/10）、`AssetDefaultsPromotionTest`（13/13）。
- 包体：`assembleDebug` + `assembleAndroidTest` 通过（`UiAccessibilityInstrumentedTest` 编译通过）。
- 真机：OnePlus 6 上 `UiAccessibilityInstrumentedTest` 6/6 通过（含 `ModalBottomSheet` 面板与队列常驻条，CR-01 风险未实际发生）。

## 5. 处置结论

- P0：无。
- P1：无。
- P2：无。
- P3：3 项观察（CR-01/CR-02/CR-03），均不阻塞，记为后续打磨/迁移项。

**`05-change-review` 门禁满足：P0/P1/P2 均无问题，评审后全量门禁复跑通过 → 推进至 `06-test-release`。**
