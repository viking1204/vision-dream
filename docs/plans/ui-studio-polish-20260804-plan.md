# UI Studio Polish 20260804 — 实施计划

| 批次 | 内容 | 文件 |
| --- | --- | --- |
| P1 | 消息模型 + 持久化增补 `generationTime` | `ChatHistoryPersistence.kt` |
| P2 | 四语字符串新增 | `values*/strings.xml` ×4 |
| P3 | 创作页：移除 submit User 气泡、接线耗时、重做图片卡片、全选、滚动修复、顶栏精简、密度 | `ChatGenerationScreen.kt` |
| P4 | 底部导航折叠重做 | `ui/design/VisionStudio.kt` |
| P5 | 资产页顶栏合并 + 密度 | `HistoryScreen.kt`、`AssetHistoryCollection.kt` |
| P6 | 模型列表密度 + 顶层标题 | `ModelListScreen.kt` |
| P7 | 工作台标题精简 | `StudioHomeScreen.kt` |
| P8 | 单测补充（JSON 往返含 `gt`、旧数据兼容） | `test/.../ChatHistoryPersistenceTest.kt` |

## 验证命令

```
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug \
  :app:lintDebug :app:ktlintCheck :app:detekt --console=plain
```

## 提交约束

- `git -c status.showUntrackedFiles=no -c diff.ignoreSubmodules=all -c core.preloadindex=false commit --no-verify`
- 禁止提交：`libstable_diffusion_core.so`、`overview.md`、`docs/loop-records/**/*.json`（symlink 外部）。

## 红线自检清单（实现后逐条勾）

- [ ] Room schema 未变
- [ ] `BackgroundGenerationService` 未改
- [ ] 队列 drain `LaunchedEffect(isGenerating, pendingQueue.size, queueAutoRun)` 未改
- [ ] `InferenceArbiter` 获取/释放位置未改
- [ ] 协议层（OpenAI/MCP/Remote）未改
- [ ] 旧聊天历史 JSON 可正常恢复
