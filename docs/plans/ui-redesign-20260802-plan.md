# VisionDream UI 重设计 — 实施计划（ui-redesign-20260802）

> 关联：需求 `docs/requirements/ui-redesign-20260802.md`、规格 `docs/specs/ui-redesign-20260802-spec.md`
> 红线（贯穿全程，违反即 P0）：Room schema 固定 v10；`BackgroundGenerationService` 时序不变；OpenAI/MCP/Remote 协议语义不变；`model_run` 路由语义不变。
> 代码编辑约定：`ModelListScreen.kt` / `ChatGenerationScreen.kt` / `AssetHistoryCollection.kt` 等为 monolith screens，改动直接落这些文件；新增组件另起文件。

## 0. 阶段总览
| 阶段 | 对应需求 | 核心产物 | 红线自检重点 |
|------|----------|----------|--------------|
| P2 | 需求1 模型列表紧凑化 | 图标浮层 + 标签过滤 + 紧凑卡 | 不新增 Room 表 |
| P3 | 需求2 输入不禁用 | 移除 `enabled=!isGenerating` | 不改 service 时序 |
| P4 | 需求3 ChatGPT 风格 composer | 圆角 composer + 模型 popover | 协议/路由不变 |
| P5 | 需求4 任务队列 | DataStore 队列 + 面板 | **不碰 Room v10、不改 service 时序** |
| P6 | 需求5 资产设为默认 | 信息面板写回默认参数 | 复用现有 `GenerationDefaults` |
| P7 | 全量回归 + 可访问性 | 扩展仪器化测试 + Preview | 全量门禁绿 |
| P8 | 变更评审准备 | 红线自检清单 | 四条红线 intact |

## 1. P2 — 模型列表页紧凑化（需求1）
**文件清单**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`（主改：TopAppBar、三处输入→浮层、标签行、紧凑卡）
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelSearchSheet.kt`（新增：search/language/add 三个 `ModalBottomSheet`）
- `app/src/main/java/io/github/xororz/localdream/data/ModelTagDerivation.kt`（新增：基于描述规则派生标签）
- `app/src/main/res/values*/strings.xml`（四语：搜索本地/仓库/添加自定义/标签名/设置）
- 如有 `ModelListViewModel` 则同步（当前逻辑在 screen 内，先确认再决定）

**实现要点**
1. TopAppBar：仅留标题 + 副标题「已安装 N 个」；移除绿色设置圆钮。
2. 右上图标行（36dp 无文字）：`search`→`language`→`add`，点击各自弹 `ModelSearchSheet`。
3. 标题下横向 `FilterChip` 行：全部 + 派生标签；右端 `sell` 图标展开标签多选浮层。
4. 模型卡：名(14dp)+描述(12-13dp 单行省略)+标签 chips+大小/尺寸；操作改 `play`/`edit`/`delete` 32-36dp 图标钮。
5. 设置入口下沉至屏幕底部功能区（仅 `tune` 图标，复用现有 `showSettingsDialog`）。

**验证命令**
```
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:testDebugUnitTest \
  --tests "io.github.xororz.localdream.data.ModelTagDerivationTest"
```
**新增单测**：`ModelTagDerivationTest`（含「动漫/写实/SD1.5/图生图/局部重绘/CPU/NPU」规则命中用例）。

## 2. P3 — 创作输入不禁用（需求2）
**文件清单**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt`（仅改 enabled 绑定与发送逻辑）

**实现要点**
1. 移除 `enabled = !isGenerating` 对提示词 `OutlinedTextField`、负面提示词、模型选择钮的绑定 → 生成中保持可编辑。
2. 发送钮：生成中点按 = 入队（衔接 P5）；非生成中 = 直接生成。
3. 仅「模型切换中」等真正互斥瞬间才局部禁用，不整体禁用。

**验证命令**：同 P2 门禁 + 新增 instrumented 用例（seed `isGenerating=true` 断言输入框可编辑、发送钮显示「入队」）。

## 3. P4 — 创作 composer 重设计（需求3）
**文件清单**
- `ChatGenerationScreen.kt`（composer 重排：圆角容器 + 底栏图标钮）
- `app/src/main/java/io/github/xororz/localdream/ui/components/ModelPickerSheet.kt`（新增：复用模型列表组件，含描述 + 标签过滤）
- `app/src/main/res/values*/strings.xml`（composer 占位/底栏 contentDescription 四语）

**实现要点**
1. 底部圆角 composer（`shape=lg`，`surfaceVariant`，0.5dp 描边）；多行 prompt（最大 ~6 行滚动）。
2. 底栏图标钮（无文字，间距 8dp）：模型 chip（→`ModelPickerSheet`）、`sliders` 负面、`image` 参考图、`auto_fix_high` 模式段控、`send`（紫，生成中变 `stop`/「已入队 N」）。
3. `ModelPickerSheet` 复用 P2 标签过滤逻辑与卡片组件，保证与外部模型列表一致。

**验证命令**：同 P2 门禁 + instrumented 渲染断言（composer 可见、底栏 5 图标有 contentDescription）+ Compose Preview light/dark。

## 4. P5 — 任务队列（需求4）【重点，双红线】
**文件清单**
- `app/src/main/java/io/github/xororz/localdream/data/GenerationQueue.kt`（新增：`GenerationTask` 模型 + DataStore 持久化）
- `app/src/main/java/io/github/xororz/localdream/ui/components/GenerationQueueSheet.kt`（新增：队列面板）
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt`（slim 队列条 + 入队触发）
- `app/src/main/java/io/github/xororz/localdream/data/Preferences.kt`（新增队列 DataStore key + 读写）
- `app/src/main/res/values*/strings.xml`（队列/状态/清空/智能排序 四语）

**实现要点**
1. `GenerationTask`：`id, modelId, prompt, negativePrompt, paramsJson, status(queued/running/done/failed), progress, createdAt, order`。
2. 持久化：**DataStore JSON 信封**（不新增 Room 表、不写迁移 → v10 不变）。
3. 执行：每个任务调用**既有生成触发路径**（与当前「点发送」同一个调用），**不改动 `BackgroundGenerationService` 时序**；单并发（running 仅 1），queued 任意。
4. 智能排序：按 `modelId` 聚合连续排列；面板开关 + 「重新聚合」复位手动拖拽。
5. UI：composer 上方 slim 条「队列 N · 当前 模型 xx%」；面板支持拖拽/删除/清空/智能排序。

**验证命令**
```
./gradlew ... :app:testDebugUnitTest --tests "io.github.xororz.localdream.data.GenerationQueueTest"
```
**新增单测**：`GenerationQueueTest`（状态机、按模型聚合排序、DataStore round-trip、清空）。
**红线自检**：grep 确认无 `@Database` 新 entity、无 `version` 变更；`BackgroundGenerationService` 未被编辑（仅 ChatGenerationScreen 调用点新增）。

## 5. P6 — 资产设为默认（需求5）
**文件清单**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/AssetHistoryCollection.kt`（信息面板加按钮）
- 现有 `GenerationDefaults`（写回当前模型默认参数，按 modelId 维度）
- `app/src/main/res/values*/strings.xml`（「设为当前模型默认」四语 + Snackbar）

**实现要点**
1. 信息面板（`onShowInfo`）底部加 `check_circle` 按钮；选择模式单条选中时顶栏出现，多选禁用并提示。
2. 点击将 `item.params`（prompt/negativePrompt/width/height/steps 等）写入当前选中模型默认值；未选模型则提示先选。
3. 成功 Snackbar：「已将参数设为 [模型名] 默认值」。

**验证命令**：同门禁 + `GenerationDefaultsTest`（参数写回 + 覆盖语义）。

## 6. P7 — 全量回归与可访问性
- 扩展 `UiAccessibilityInstrumentedTest`：紧凑模型卡、composer 底栏图标、队列面板、资产设为默认入口。
- 新增 Compose Preview（light/dark）于改动组件。
- 四语 `strings.xml` 补全（contentDescription 必填）。

**验证命令（全量门禁）**
```
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug \
  :app:testDebugUnitTest :app:assembleAndroidTest
```

## 7. P8 — 变更评审准备
- 四条红线自检清单：Room v10 / service 时序 / 协议 / model_run 路由 intact。
- 仅允许 P2 级（如死字符串清理）可接受项，P0/P1 必须 0。

## 8. 发布边界与回滚
- 测试分支：`test/ui-redesign-20260802`（承载 P2–P8）。
- release APK：测试签名 + R8 minify + lintVitalRelease 通过。
- 实例健康：在能出图设备（PJZ110/K30）真机安装运行；OnePlus 6 仅验 UI。
- 回滚：按阶段 `git revert`。

## 9. 风险与依赖
- **R1/R2（红线）**：P5 必须 DataStore + 复用既有生成调用，已显式约束。
- **R3（设备）**：出图端到端验证依赖能出图设备；OnePlus 6 仅验 UI（MNN/旧 CPU 不兼容，已知）。
- **依赖**：P5 实现前需先确认 `ChatGenerationScreen` 中既有的生成触发调用入口（只读不改），以免误触 service 时序。
- **标签派生**：纯规则（关键词匹配描述），不引入 ML，无额外依赖。
