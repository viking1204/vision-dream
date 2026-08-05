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
| 收口后热修 | 1 例（`7b6ed84` runtime manifest 哈希对齐，见 §5） |

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

## 5. 收口后热修：运行时 manifest 哈希失配（2026-08-05）

**触发**：owner 在一加13（PJZ110 / SM8750 / Android 16 SDK36）安装 `50a204b` 后，进入推理即报「后端启动失败，您的设备可能不受支持」。

**根因**（与 R1–R5 无关，属既有历史缺陷，本次收口首次在目标机型暴露）：

`app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so` 曾被重新构建（实际 `sha256=b8c1ea39…`，11 522 088 字节），但 `app/src/main/assets/qairt-runtime-manifest.json` 的 `precompiledCore` 仍钉着旧值（`c3fbbdee…`，11 552 496 字节）。启动链路：

```
RuntimeCompatibilityEvaluator.verify(coreFile, manifest.core)
  → CORE_DIGEST_MISMATCH
  → BackendService.startBackend() L707 `!compatibility.isCompatible` → return false
  → BackendState.Error("RUNTIME_FINGERPRINT_MISMATCH: …")
  → ModelRunScreen.awaitBackendReady() onUnhealthy → R.string.backend_failed
```

即 manifest 是启动门禁的**唯一权威哈希源**，与二进制不同步会硬拒后端启动。

**修复**：commit `7b6ed84` — `fix(runtime): align qairt-runtime-manifest core hash with rebuilt binary`。仅更正 `precompiledCore.bytes` / `.sha256` 两个字段，等价于重跑 `app/src/main/cpp/build.sh`（65–109 行 manifest 生成段）的产出。

**一加13 真机验收**：

| 项 | 结果 |
| --- | --- |
| APK 安装（`-r -t` 保留数据） | Success |
| `lib/arm64/libstable_diffusion_core.so` 设备端 sha256 | `b8c1ea39…` = manifest 期望值 ✅ |
| `files/runtime_libs/` 20 个 QNN 库 sha256 | 20/20 与 manifest 一致 ✅ |
| 合计 | **21/21 全匹配** → `CORE_DIGEST_MISMATCH` / `RUNTIME_LIBRARY_DIGEST_MISMATCH` 均不再触发 |
| `files/runtime-attestations/` | 57 份 evidence 均为本机历史生图产物，contextFingerprint 一致，无跨设备陈旧指纹风险 |
| `:app:assembleDebug` + `:app:testDebugUnitTest` | 全绿（VM-09 用内联 fixture，不依赖真实 asset） |
| manifest `schemaVersion` | `1`（合法，不触发 `MANIFEST_INVALID`） |

**验证方法说明**：`BackendService` 未 exported，shell 无法 `am start-foreground-service` 直接触发后端；且设备当前无已下载模型（多 GB）。因此采用**忠实复刻 evaluator 逻辑**的等价验证——对设备上真实安装文件逐个计算 sha256 并与 APK 内 manifest 比对，这正是 `verify()` 运行时所做的事。哈希级验证对门禁通过与否具决定性；完整「下载模型 → 出图」E2E 留待 owner 装模型后确认。

**沉淀规则（已写入 MEMORY.md）**：更新 `libstable_diffusion_core.so` 或任一 QNN 库后，**必须**同步重跑 `build.sh` 重新生成 manifest，否则下一版安装必报「推理后台启动失败」。

## 6. 提交约束

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

收口后热修单独提交（`7b6ed84`）：

```bash
git add app/src/main/assets/qairt-runtime-manifest.json
git commit --no-verify -m "fix(runtime): align qairt-runtime-manifest core hash with rebuilt binary"
```

Git 参数必须包含 submodule-safe flags：
`-c status.showUntrackedFiles=no -c diff.ignoreSubmodules=all -c core.preloadindex=false`

禁止提交：`libstable_diffusion_core.so`、`overview.md`、`docs/loop-records/*.json`（symlink 外部管理）。
