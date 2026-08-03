# 08-closeout 收口沉淀

日期：2026-08-03
dev-loop：`ui-redesign-20260802`（vision-dream）

## 1. 阶段总览（00 → 08）

| 阶段 | 结论 |
|------|------|
| 00 需求澄清 | passed |
| 01 事实 review | passed |
| 02 规格设计 | passed |
| 03 实施计划 | passed |
| 04 实现收口（P2–P8） | passed |
| 05 独立变更评审 | passed（P0/P1/P2 无，P3×3 观察项） |
| 06 测试发布 | passed（测试分支 + 测试签名 APK + 全量门禁 + OnePlus 6 UI 6/6；CI 为外部依赖） |
| 07 业务验收 | partial（UI/无障碍 6/6 通过；MNN 生图为 K30/GGUF 外部依赖） |
| 08 收口沉淀 | passed |

## 2. 交付物（P2–P8，7 commits，17 文件，+2195 / -422）

- **P2 模型列表紧凑化**（`ebd34cf`）：`ModelListScreen` 去除绿色设置浮钮、三处常驻大输入压缩为紧凑卡 + 标签过滤；新增 `ModelTagDerivation`（规则提取标签）+ 单测。
- **P3 创作输入不禁用**（`8d32005`）：移除以 `enabled=!isGenerating` 锁定整输入区；发送改为 `PendingChatRequest` 快照入队，空闲边沿单件出队。
- **P4 ChatGPT 风格 composer**（`d5e1c2c`）：圆角容器 + 底栏无文字图标 + 模型选择 bottom sheet（搜索 + 描述 + 标签过滤）。
- **P5 任务队列**（`7fee437`）：DataStore 持久化（`GenerationQueue` codec/sorter）、智能同模型聚类、`GenerationQueueBar` + `GenerationQueueSheet` 面板（上下移/移除/清空/智能排序/Start queue），单并发出队，恢复停车策略。
- **P6 资产设为当前模型默认**（`5ee9809`）：`AssetDefaultsPromotion.promote()` 保守合并（seed 不复制、尺寸全有/全无、越界保留原值）+ 信息面板 `CheckCircle` 入口写回 `GenerationPreferences.saveAllFields`；13 单测。
- **P7 全量回归 + 可访问性**（`4338338` + `44ebd2c`）：扩展 `UiAccessibilityInstrumentedTest`（设为默认入口、队列条、面板重排按钮 contentDescription）；`GenerationQueueSheet` 轻/深色 Preview；OnePlus 6 真机 6/6 通过。
- **P8 变更评审准备**：四条红线 intact 自检（Room v10 / `BackgroundGenerationService` / OpenAI·MCP·Remote 协议 / `model_run` 路由均未改动）。

## 3. 已知外部依赖（非代码缺陷）

1. **CI 流水线**：沙箱未接入，需注入真实 `RELEASE_STORE_*` 密钥后跑 `assembleRelease` + 测试。
2. **端到端生图验收**：OnePlus 6 旧处理器无法加载 MNN（`anythingv5cpu` 为 MNN 格式，App 启动弹窗已明确警告）。完成需 GGUF 模型或 K30 / PJZ110 真机。

## 4. 稳定经验（已沉淀至项目记忆）

- 应用真实包名 `io.github.ddq.visiondream`（applicationId），Kotlin 命名空间 `io.github.xororz.localdream`；仪器化组件 `io.github.ddq.visiondream.test/androidx.test.runner.AndroidJUnitRunner`。
- 真机仪器化测试要点：`ModalBottomSheet` 面板内容在真机可达（`performClick` 对独立窗口不可靠，应校验 `contentDescription` 暴露）；多任务列表用 `onAllNodesWithContentDescription`/`assertExists` 需注意节点数，单任务 + 单节点断言最稳；Compose UI test v2 无 `assertCountEquals`，用单任务规避。
- 红线与约束：Room schema 固定 v10、不碰 `BackgroundGenerationService` 时序 / OpenAI·MCP·Remote 协议、`model_run` 路由语义；严禁提交 `libstable_diffusion_core.so` 与 `overview.md`；`docs/loop-records` 为外部 symlink，其下 JSON 不进 git；提交用 `--no-verify`。
- monolith screen 编辑约定：实际编辑 `ModelListScreen` / `ChatGenerationScreen` / `HistoryScreen` / `ModelRunPages` / `AssetHistoryCollection` 等巨型页面（计划假设拆分 `*Content.kt` 不实）。
- `GenerationQueueBar` 定义在 `GenerationQueueSheet.kt` 内（非独立文件）；`rememberModalBottomSheetState(skipPartiallyExpanded=...)` 为项目既有废弃用法，新面板沿用，留待整体迁移。

## 5. 交付状态

实现、评审、发布构建、真机 UI/无障碍验收在沙箱内可达到的验证均已通过；剩余两项为环境/设备依赖（CI、K30 生图）。代码、评审、发布与应用文档齐备，可随时接入 CI 并在 K30 完成生成验收。
