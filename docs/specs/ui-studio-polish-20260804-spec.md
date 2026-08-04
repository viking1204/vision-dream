# UI Studio Polish 20260804 — 规格设计

## 1. 数据模型变更

```kotlin
data class Image(
    override val id: Long,
    val file: File?,
    val fallbackBytes: ByteArray?,
    val modelName: String,
    val width: Int,
    val height: Int,
    val seed: Long?,
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfg: Float = 7f,
    val scheduler: String = "dpm",
    val generationTime: String = "",   // NEW，形如 "12.3s"，空串代表未知
) : ChatGenerationMessage
```

JSON 键：`gt`。写入 `put("gt", message.generationTime)`；读取 `obj.optString("gt", "")`。
**兼容性**：旧 JSON 无 `gt` → 回退空串 → UI 不渲染耗时行。新 JSON 被旧版本读到 → `optXxx` 忽略未知键。双向安全。

## 2. 图片消息卡片规格

```
┌ Card (fillMaxWidth 0.94) ─────────────────┐
│  [ 图片 RevealableImage / AsyncImage ]     │  ← 点击 = 打开大图
├───────────────────────────────────────────┤
│  提示词（bodySmall, maxLines 3, ellipsis） │  ← prompt 非空才渲染
│  ┌ Row (SpaceBetween) ───────────────────┐ │
│  │ 模型名 · 12.3s        [ⓘ] [⛶] [🗑]  │ │
│  └───────────────────────────────────────┘ │
└───────────────────────────────────────────┘
```

- 卡片内边距：`horizontal 12.dp, vertical 8.dp`（原 `OutlinedButton` 的 12dp 整宽块被替换）。
- 图标按钮：`IconButton(modifier = Modifier.size(32.dp))`，图标 `18.dp`，无文字。

### 图标语义表（无文字 → 全靠 contentDescription）

| 图标 | 语义 | contentDescription 资源 |
| --- | --- | --- |
| `Icons.Outlined.Info` | 查看详情 | `chat_generation_view_details` |
| `Icons.Outlined.OpenInFull` | 查看大图 | `chat_generation_view_large`（新增） |
| `Icons.Outlined.Delete` | 删除该条消息 | `chat_generation_delete_message`（新增） |

**新增字符串**（四语：zh / zh-rTW / en / ja）
- `chat_generation_view_large`
- `chat_generation_delete_message`
- `chat_generation_generation_time`（详情面板行标题「生成耗时」）

`chat_generation_view_details` 复用既有键（值 "View"/"查看"），用作 Info 按钮的无障碍描述。

## 3. 选择模式顶栏

顺序：`[全选/取消全选]  [删除]  [关闭]`

```kotlin
val allSelected = messages.isNotEmpty() && selectedMessageIds.size == messages.size
IconButton(onClick = {
    if (allSelected) selectedMessageIds.clear()
    else { selectedMessageIds.clear(); selectedMessageIds.addAll(messages.map { it.id }) }
}) {
    Icon(
        if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
        contentDescription = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all),
    )
}
```

- 全选作用于 **`messages` 全量**，不受 `visibleMessageCount` 分页限制。
- 取消全选后 `selectedMessageIds` 为空，但**不自动退出选择模式**（避免误触即退出），由 Close 按钮显式退出。

## 4. 提交时序变更

`submitGeneration`：删除 `messages += ChatGenerationMessage.User(...)`。
`startGeneration` 成功分支：`ChatGenerationMessage.Image(..., generationTime = generationTime)`。
错误分支不变（`Error` 消息仍即时插入）。

**副作用检查**：`LaunchedEffect(messages.size)` 的历史持久化不再被 submit 触发 —— 可接受，因为图片/错误落地时仍会触发；队列本身由 `saveGenerationQueue` 独立持久化（L719-722），提示词不会丢。

## 5. 底部导航栏折叠规格

| 状态 | 结构 | 高度 |
| --- | --- | --- |
| 展开 | `Column{ handle(12dp) + NavigationBar(80dp) }` | ≈ 92dp + inset |
| 折叠 | `Box{ handle pill 56×20 }` + `padding(vertical 4dp)` | ≈ 28dp + inset |

- 两态外层统一 `Modifier.navigationBarsPadding()`，`NavigationBar` 传 `windowInsets = WindowInsets(0)` 避免双重 inset。
- handle：`Surface(shape = RoundedCornerShape(50), color = onSurfaceVariant.copy(alpha=.35f), size 36×4)` 包在可点击的 `Box(height 12/20dp)` 内，命中区 ≥ 48dp 宽。
- 折叠态点击展开，展开态点击 handle 折叠 —— 交互对称，去掉箭头图标的视觉噪声（`collapse_nav` / `expand_nav` 继续作为 `contentDescription`）。

## 6. 滚动到底修复

```kotlin
LaunchedEffect(messages.size, isGenerating, visibleMessageCount) {
    if (visibleMessages.isEmpty()) return@LaunchedEffect   // 不再提前置 initialScrollDone
    val lastIndex = visibleMessages.size + if (isGenerating) 0 else -1
    if (lastIndex < 0) return@LaunchedEffect
    if (!initialScrollDone) {
        listState.scrollToItem(lastIndex)
        initialScrollDone = true
    } else {
        listState.animateScrollToItem(lastIndex)
    }
}
```

关键差异：空列表 **直接 return**，把 `initialScrollDone` 的翻转推迟到真正有内容的那一帧，于是历史恢复后的首次定位是瞬时 `scrollToItem`。

## 7. 密度 token

| 位置 | 原值 | 新值 |
| --- | --- | --- |
| ModelList `contentPadding` | 12.dp | 8.dp |
| ModelList item 间距 | 12.dp | 6.dp |
| 标签 chip 行间距 | 8.dp | 4.dp |
| 资产 GRID padding/spacing | 8.dp | 4.dp |
| 资产 LIST 间距 | 12.dp | 6.dp |
| 资产 WATERFALL 间距 | 10.dp | 6.dp |
| 创作页消息间距 | 10.dp | 8.dp |
| 创作页列表 padding | 16.dp | 12.dp |

## 8. 顶栏精简

| 页面 | 处理 |
| --- | --- |
| 创作（顶层） | `title = {}`，仅在选择模式显示「已选 N」 |
| 资产 | 移除标题；右上 `ViewModule` 下沉到下方工具行 |
| 模型列表 | 顶层态移除标题 |
| 工作台 | 顶层态移除标题 |

非顶层（带返回箭头）场景保留标题，避免用户失去上下文。
