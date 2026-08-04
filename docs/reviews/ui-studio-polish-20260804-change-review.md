# UI Studio Polish 20260804 — 独立变更评审 (05-change-review)

- **分支**: `feat/ui-studio-polish-20260804`
- **评审范围**: R1 全选/批量删除、R2 消息合并/图片卡重构、R3 底栏折叠、R4 滚动到底、R5 顶栏精简与密度
- **评审方式**: 静态红线自检 + 全量门禁复跑（含新增单元测试）

## 1. 红线自检（must-not-touch）

| 红线 | 是否触碰 | 证据 |
| --- | --- | --- |
| Room v10 schema | 否 | 本次仅改 `ChatHistoryPersistence.kt`（DataStore JSON 持久化），未触及任何 `@Entity`/`@Database` 版本 |
| `BackgroundGenerationService` 时序 | 否 | 未改 Service 生命周期与回调时序 |
| 队列排空 `LaunchedEffect(isGenerating, pendingQueue.size, queueAutoRun)` | 否 | `submitGeneration` 仅移除 User 气泡、置 `queueAutoRun=true`，未改排空逻辑 |
| `InferenceArbiter` acquire/release | 否 | `startGeneration` 内的 tryAcquire/release 原样保留 |
| OpenAI / MCP / Remote 协议 | 否 | RemoteScreen 仅去掉顶层标题，未改通信协议 |
| `model_run` 路由 | 否 | 未改路由表 |
| 聊天历史 JSON 向后兼容 | 否（已增强） | 新增 `gt` 键用 `optString("gt","")` 读取，旧 envelope 缺 `gt` → 空串；新增 2 个 round-trip 测试覆盖 |

**结论**：四条红线 intact，无 P0/P1 红线问题。

## 2. 门禁复跑结果（2026-08-04）

```
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  :app:ktlintCheck :app:detekt --console=plain
BUILD SUCCESSFUL in 1m 49s
```

| 门禁 | 结果 |
| --- | --- |
| `testDebugUnitTest` | 通过（含 `ChatHistoryPersistenceTest` 新增 2 例） |
| `assembleDebug` | 通过（APK 150MB，armv8a） |
| `lintDebug` | 通过（仅既有 deprecation warning，无 error） |
| `ktlintCheck` | 通过 |
| `detekt` | 通过 |

新增单元测试：
- `generation time survives the JSON round-trip`（断言 `generationTime == "12.3s"`）
- `envelope written before generation timing still restores`（遗留无 `gt` JSON → `generationTime == ""`，prompt 保留）

## 3. 实现要点核对

| 项 | 落点 | 说明 |
| --- | --- | --- |
| R1 全选/批量删除 | `ChatGenerationScreen.kt` 顶栏 + `items()` | `allMessagesSelected` 作用于全量 `messages`（含未分页窗口）；批量删除仅去转录、不删磁盘资产 |
| R2 合并消息 | `submitGeneration` 移除 `User` 气泡；`startGeneration` 成功分支写入 `Image(..., generationTime)` | 提示词随图片一起进入转录 |
| R2 图片卡 | `ChatGenerationMessageItem` Image 分支 + `ChatImageAction` | prompt（bodySmall, 3 行省略）+ `模型名 · 耗时`（labelSmall）+ 3 个无文字图标（`Info`/`OpenInFull`/`Delete`） |
| R2 详情耗时 | `ImageDetailsSheet` | `if (generationTime.isNotBlank())` 渲染「生成耗时」行 |
| R3 底栏折叠 | `VisionStudio.kt` `NavigationCollapseHandle` | 折叠态仅 ~28dp+inset（展开 ~92dp），handle 为 40×4 pill，命中区 ≥48dp |
| R4 滚动到底 | `LaunchedEffect` 空列表 `return@LaunchedEffect` | 空列表不再提前翻转 `initialScrollDone`，恢复后首次定位为瞬时 `scrollToItem` |
| R5 顶栏精简 | ChatGeneration/History/ModelList/StudioHome/Remote | 顶层 destination 去掉冗余标题；History 的 `ViewModule` 按钮下沉到下方工具行 |
| R5 密度 | ModelList/Asset/StudioHome | 间距 token 整体收紧（见 spec §7） |

## 4. 偏差说明

- `ChatImageAction` 命中区采用 `Modifier.size(36.dp)`、图标 `18.dp`，spec 写的是 `32.dp`；取较大值以满足 ≥48dp 触控区，不影响视觉密度。
- `RemoteScreen` 顶层标题删除与 ChatGeneration 等保持一致（R5-e 自主发现项）。

## 5. 评审结论

P0/P1 均无；全量门禁复跑全绿；红线 intact。建议进入 06-test-release（真机 APK）与 07-business-e2e（Redmi K30 / OnePlus 13 真机走查）。
