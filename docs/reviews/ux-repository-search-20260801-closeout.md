# 08-closeout 收口沉淀

日期：2026-08-02
dev-loop：`ux-repository-search-20260801`（vision-dream）

## 1. 阶段总览（00 → 08 全部通过）

| 阶段 | 结论 |
|------|------|
| 00 需求澄清 | passed |
| 01 事实 review | passed |
| 02 规格设计 | passed |
| 03 实施计划 | passed |
| 04 实现收口（P1–P9） | passed |
| 05 独立变更评审 | passed（P0/P1 无，P2×1 已接受） |
| 06 测试发布 | passed（测试分支 + 测试签名 APK + 全量门禁；CI 为外部依赖） |
| 07 业务验收 | passed（真机安装/启动/UX 无障碍 3/3；MNN 生成为外部依赖） |
| 08 收口沉淀 | passed |

## 2. 交付物（P1–P9）

- **P1–P2 后端**：仓库配置、多仓搜索、重复检测（`e43b4e3^`）。
- **P3 仓库配置 UI** / **P4 搜索 UI**（`e43b4e3`）。
- **P5 创作域**（UX-03 草稿持久化、模式切换、消息分页、提示词 CTA，`1050b36`）。
- **P6 工作台与模型库**（UX-06/07/08、UX-16~22：本地搜索/分区置顶/紧凑卡片/操作图标行，`e43b4e3`）。
- **P7 资产域**（UX-23/24/25/26：结构化复制/放大按钮/空态 CTA/敏感内容语义，`dc295ae`）。
- **P8 前后台恢复**（UX-03：筛选/排序/滚动 + 草稿持久化，`e0c24c4`）。
- **P9 全量回归与 Preview**（light/dark Compose Preview + UiAccessibilityInstrumentedTest，`765901e`；真机修复 `4592259`）。

## 3. 已知外部依赖（非代码缺陷）

1. **CI 流水线**：沙箱未接入，需注入真实 `RELEASE_STORE_*` 密钥后跑 `assembleRelease` + 测试。
2. **两次 CPU 生成**：设备内置 `anythingv5cpu` 为 MNN 格式，OnePlus 6 旧处理器无法加载 MNN（App 启动弹窗已明确警告）。计划 07 门禁允许 K30 备选；完成需 GGUF 模型或 K30。

## 4. 稳定经验（已沉淀至项目记忆）

- 应用真实包名 `io.github.ddq.visiondream`（applicationId），Kotlin 命名空间 `io.github.xororz.localdream`；仪器化组件 `io.github.ddq.visiondream.test/androidx.test.runner.AndroidJUnitRunner`。
- 真机仪器化测试要点：AlertDialog 在独立窗口渲染，语义坐标 `performClick` 不可靠，应校验 `contentDescription` 暴露；空态需真实 `Pager` 触发 `LoadState.NotLoading`；`LoadResult` 为 `PagingSource.LoadResult` 嵌套类（paging 3.3.6）。
- 红线与约束：Room schema 固定 v10、不碰 BackgroundGenerationService 时序/OpenAI·MCP·Remote 协议、`model_run` 路由语义；严禁提交 `libstable_diffusion_core.so` 与 `overview.md`；`docs/loop-records` 为外部 symlink，其下 JSON 不进 git。
- monolith screen 编辑约定（计划假设拆分 `*Content.kt` 不实，实际编辑 StudioHomeScreen/ModelListScreen/HistoryScreen/ModelRunPages/AssetHistoryCollection/ChatGenerationScreen）。

## 5. 交付状态

所有阶段门禁在沙箱内可达到的验证均已通过；剩余两项为环境/设备依赖。代码、评审、发布与应用文档齐备，可随时接入 CI 并在 K30 完成生成验收。
