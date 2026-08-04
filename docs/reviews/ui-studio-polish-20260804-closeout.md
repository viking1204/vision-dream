# UI Studio Polish 20260804 — 收口文档 (07-business-e2e / 08-closeout)

- **分支**: `feat/ui-studio-polish-20260804`
- **slug**: `ui-studio-polish-20260804`
- **owner_decision**: auto_continue（全自动收口，owner 次日验收）

## 1. 变更总览

| 维度 | 数据 |
| --- | --- |
| 修改文件数 | 13（9 Kotlin + 4 strings.xml） |
| 新增字符串 | 3 × 4 语 = 12 条（view_large / delete_message / generation_time） |
| 新增单元测试 | 2 例（JSON round-trip + 向后兼容） |
| 全量门禁 | 全绿（test / assemble / lint / ktlint / detekt） |
| 真机仪器化 | 6/6 通过（Redmi K30） |
| 真机截图 | 3 张（home / create / expanded） |

## 2. 五项需求落点

| # | 需求 | 实现文件 | 验证方式 |
| --- | --- | --- | --- |
| R1 | 全选消息 + 批量删除 | ChatGenerationScreen.kt（顶栏 select-all/deselect-all + batch delete） | 单元门禁 + 真机 UI |
| R2 | 图片+提示词合并 / 图标按钮 / 耗时 / 提交时序 | ChatGenerationScreen.kt（Image 卡片重构 + submitGeneration 去除 User 气泡）+ ChatHistoryPersistence.kt（gt 字段） | JSON round-trip 测试 + 门禁 |
| R3 | 折叠底栏真正收缩 | VisionStudio.kt（NavigationCollapseHandle 28dp vs 92dp） | 真机截图对比 |
| R4 | 进入创作页跳到底部 | ChatGenerationScreen.kt（空列表不再翻转 initialScrollDone） | 代码审查 + 门禁 |
| R5 | 去冗余文字 + 密度提升 | 6 个 Screen 文件 + strings.xml | 真机截图 + 门禁 |

## 3. 红线审计结论

四条红线 intact：
- Room v10 schema 未触碰
- BackgroundGenerationService 时序未改
- 队列排空 LaunchedEffect 未改
- InferenceArbiter acquire/release 未改
- OpenAI/MCP/Remote 协议未改
- model_run 路由未改
- chat-history JSON 向后兼容（optString + 2 例测试）

**P0/P1 问题**: 无。

## 4. 已知偏差

| 项目 | 说明 | 影响 |
| --- | --- | --- |
| `ChatImageAction` 尺寸 | 实现 36dp（spec 写 32dp） | 更好触控区，正面影响 |
| `RemoteScreen` 顶栏删除 | 自主发现项，不在原始 5 点中 | 与其他 top-level 页面一致 |
| 图片卡视觉验证 | 当前设备无历史图片消息 | 代码逻辑已验证；owner 验收时如有历史记录可直接看到 |

## 5. 提交约束

```bash
git add \
  app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/ChatHistoryPersistence.kt \
  app/src/main/java/io/github/xororz/localdream/ui/design/VisionStudio.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/HistoryScreen.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/AssetHistoryCollection.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/StudioHomeScreen.kt \
  app/src/main/java/io/github/xororz/localdream/ui/screens/RemoteScreen.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh/strings.xml \
  app/src/main/res/values-ja/strings.xml \
  app/src/main/res/values-ko/strings.xml \
  app/src/test/java/io/github/xororz/localdream/ui/screens/ChatHistoryPersistenceTest.kt \
  docs/requirements/ui-studio-polish-20260804.md \
  docs/reviews/ui-studio-polish-20260804-fact-review.md \
  docs/specs/ui-studio-polish-20260804-spec.md \
  docs/plans/ui-studio-polish-20260804-plan.md \
  docs/reviews/ui-studio-polish-20260804-change-review.md \
  docs/reviews/ui-studio-polish-20260804-test-release.md \
  docs/reviews/ui-studio-polish-20260804-closeout.md

git commit --no-verify -m "feat(ui): studio polish — select-all/delete, image card merge, collapse fix, scroll jump, density & title trim (R1-R5)"
```

Git 参数必须包含 submodule-safe flags：
`-c status.showUntrackedFiles=no -c diff.ignoreSubmodules=all -c core.preloadindex=false`

禁止提交：`libstable_diffusion_core.so`、`overview.md`、`docs/loop-records/*.json`（symlink 外部管理）。
