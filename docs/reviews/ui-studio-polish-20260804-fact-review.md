# UI Studio Polish 20260804 — 需求事实 review

对 5 项诉求逐条核实代码现状，确认「哪些是真缺失、哪些已存在只是不到位」。

## R1 全选 / 批量删除

`ChatGenerationScreen.kt`
- L279-280：`selectionMode` + `selectedMessageIds` 已存在。
- L752-782：选择模式顶栏已有 **删除**（`Icons.Default.Delete`）与 **关闭**（`Icons.Default.Close`）两个 `IconButton`。
- L854-876：长按进入多选、点击切选已实现。

**结论**：缺的只是「全选 / 取消全选」开关。`strings.xml` 已有 `select_all`(234) / `deselect_all`(235)，可直接复用，无需新增字符串。
注意：全选语义应作用于 **`messages` 全量**，而非仅 `visibleMessages`（用户说的是"选择所有消息"）。

## R2 图片消息合并

`ChatHistoryPersistence.kt`
- L20-33：`Image` 已携带 `modelName / prompt / negativePrompt / steps / cfg / scheduler / seed / width / height`。
- **缺 `generationTime`**。
- L55-74 序列化 / L105-125 反序列化：需增补 `gt` 字段，且 `optString("gt","")` 天然向后兼容。

`ChatGenerationScreen.kt`
- L558-560：`generationTime` 已算出（`"%.1fs"`），但只传给了 `GenerationParameters`（L571），**没有传给 `ChatGenerationMessage.Image`**（L581-594）。→ 纯接线。
- L667：`messages += ChatGenerationMessage.User(nextId(), submittedPrompt)` —— 这就是「加入队列就发提示词消息」的根因，删除即可。
- L1214-1221：整宽 `OutlinedButton("查看详情")` + `.padding(12.dp)` —— 即 owner 说的「按钮太大」。
- L1224-1230：`ZoomableImageOverlay` 灯箱已存在，由 `showLightbox` 驱动，当前只能通过 `RevealableImage.onOpenPreview` 触发（NSFW 遮罩场景），**普通图片没有显式入口** → 印证「不能看大图」。
- L1263-1315：`ImageDetailsSheet` 已展示模型名/提示词/参数，**无耗时行**。

**结论**：R2 全部为「已有骨架 + 缺接线/缺入口」，无架构风险。

## R3 折叠底栏

`VisionStudio.kt` L86-159
- 展开态 = `Column{ IconButton(28.dp) + NavigationBar }` ≈ 28 + 80 = 108dp（+ 系统栏 inset）。
- 折叠态 = `Surface{ Icon(size 40.dp) }` + `padding(bottom=12.dp)` ≈ 52dp。
- 两态在同一个 `Box` 内，`AnimatedVisibility` 默认 `expandVertically/shrinkVertically`，理论上折叠后 Box 会收缩。

**根因判断**：52dp 相对 108dp 只省了一半，且 40dp 图标 + 12dp 外边距在视觉上仍是一大块；`Surface` 未做 `windowInsetsPadding`，与 `NavigationBar` 自带的 navigationBars inset 行为不一致，导致折叠时底部仍留白。
**决策**：折叠态改为极简 handle（宽 56dp / 高 20dp 圆角条），展开态把独立的 28dp 收起按钮换成 12dp 细 handle 条，两态统一 `navigationBarsPadding()`。

## R4 进入创作页滚动

`ChatGenerationScreen.kt` L484-498
- `initialScrollDone` 机制已存在，首帧走 `scrollToItem`（瞬时）。
- **缺陷**：`LaunchedEffect(messages.size, isGenerating, visibleMessageCount)` 的首次触发发生在 **历史尚未从 DataStore 恢复完** 的时刻（`messages` 为空 → L486 分支直接把 `initialScrollDone` 置 true 并返回）。等历史恢复后 size 变化再次触发时，`initialScrollDone` 已是 true → 走 `animateScrollToItem`，**于是出现"滚动好久"**。

**结论**：这就是 owner 反馈的真实 bug。修法：空列表时不要提前把 `initialScrollDone` 置 true。

## R5 冗余文字与密度

- `ChatGenerationScreen.kt` L734-750：顶层态仍渲染 `CenterAlignedTopAppBar` 标题「创作」。
- `HistoryScreen.kt` L157-246：标题 +（右上角单个 `ViewModule` `IconButton` → `DropdownMenu`）；L262-308 下方已有工具行（NSFW 开关 + 计数）→ 可合并。
- `ModelListScreen.kt` L1001-1002：`contentPadding=12.dp`、`spacedBy(12.dp)`；L895-925 标签 `FilterChip` 行 `spacedBy(8.dp)`。
- `AssetHistoryCollection.kt`：GRID `contentPadding/spacing = 8.dp`，LIST 12.dp，WATERFALL 10.dp。
- `StudioHomeScreen.kt` L87：`bottomBar = VisionStudioNavigationBar`，含标题。

**结论**：均为纯视觉参数与结构搬移，无逻辑风险。

## 红线核验

| 红线 | 本次是否触碰 |
| --- | --- |
| Room v10 | 否（消息模型是内存 + DataStore JSON，非 Room） |
| BackgroundGenerationService 时序 | 否 |
| 队列 drain（idle 边沿单发，L726-730） | 否，仅移除 L667 的 UI 副作用 |
| OpenAI/MCP/Remote 协议 | 否 |
| model_run 路由 | 否 |
| InferenceArbiter | 否 |
| 聊天历史 JSON 兼容 | 新增 `gt` 为可选字段，旧数据 `optString` 回退 `""` |
