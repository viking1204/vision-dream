# ux-repository-search-20260801：需求事实 Review

> 阶段：`01-fact-review`
> 需求 slug：`ux-repository-search-20260801`
> 结论：**代码事实已足以进入 `02-spec`。** 仓库搜索、来源关联和重复识别的核心数据结构与安装链路已存在；30 项体验改造中的导航/工作台/服务/预设部分已由 `feat/ui-optimization` 分支完成，剩余缺口集中在创作模式统一、模型库搜索分入口和资产细节。

## 1. 取证范围

| 项目 | 结论 | 证据 |
| --- | --- | --- |
| 需求输入 | 仓库搜索、来源关联、重复识别和 30 项体验改造；保留现有数据、选择不加载、前后台恢复。 | `docs/requirements/ux-repository-search-20260801.md:7-15,32-66` |
| 体验改造现状 | `feat/ui-optimization` 分支已完成 5 项底栏、工作台、Route/Content 边界、服务卡和预设卡迁移；但创作模式统一、模型库搜索分入口和资产细节仍有缺口。 | `docs/ux-audit-2026-07-31.md` 全文；`feat/ui-optimization` 分支 commit `a5376ce` |
| 设备验证 | OnePlus 6 真机验证 5 项底栏渲染正常，无崩溃。K30 两次 CPU 生成仍属于 `07-business-e2e`。 | 2026-08-01 真机截图 + logcat crash=0 |

## 2. 仓库、目录与搜索事实

### 2.1 HuggingFace 目录格式

[实锤] `HuggingFaceCatalogModels.kt:14-90` 定义了完整的 HF 兼容数据模型：
- `HuggingFaceModelRepository`：`id`/`author`/`sha`/`pipelineTag`/`tags`/`baseModels`/`modelType`/`formats`/`cardMetadata`/`files`/`downloads`/`likes`/`isPrivate`/`isGated`/`isDisabled`/`declaredNsfw`
- `CompatibleModelArtifact`：含 `repositoryId`/`repositorySha`/`localModelId`/`backendType`/`hardwareTarget`
- `ModelCatalogSearchResult`（L120-155）：扁平化搜索结果，`installationMetadata()` 会把 `repositoryId`/`revision`/`artifactKind` 写入 `ModelSourceMetadata`

[实锤] `HuggingFaceModelCatalogClient.kt:20-42` `search()` 调用 HF `/api/models` 端点；`validateBaseUrl`（L189-199）要求 http/https、无 query/fragment；`SAFE_REPOSITORY_SEGMENT`/`SAFE_REVISION` 正则做 fail-closed 校验（L217-218）。

[实锤] `HuggingFaceCatalogJsonParser`（L224-383）已处理 `items`/`models`/`data` 多种响应壳、`siblings`+`lfs.sha256` 解析和 cardData 元数据映射。

**可复用**：HF API 解析器、兼容性评估器和 fail-closed 校验均可直接用于多仓库搜索的后端。

### 2.2 仓库配置存储

[实锤] `Preferences.kt:33-34` 仓库相关只有两个单字符串键：
- `BASE_URL_KEY = stringPreferencesKey("base_url")` — 全局单值，默认 `"https://huggingface.co/"`
- `SELECTED_SOURCE_KEY = stringPreferencesKey("selected_source")` — 单字符串标签，默认 `"huggingface"`

[实锤] `ModelRepository`（`Model.kt:441-971`）的 `baseUrl`（L447）是单字符串，由 `generationPreferences.getBaseUrl()` 刷新（L933）。`refreshAllModels`（L931-939）= 扫盘 + 内置列表合并。

**缺口**：没有"仓库列表"概念，只有一个全局 `baseUrl`。多仓库需新建结构化存储（`stringSetPreferencesKey` 或独立 DataStore）+ 仓库 CRUD。

### 2.3 搜索现状

[实锤] `ModelListScreen.kt` 本地列表**无关键词搜索 UI**，仅有 CPU/NPU 分页过滤（L440/L448）和 `isDownloaded` 过滤（L502）。"搜索"按钮（L745-754/L1117-1123）打开的是远程 HF catalog 搜索对话框 `ModelSearchDialog`。

[实锤] `ModelSearchDialog.kt:77-111` 的 `search()` 只搜远程 HF，搜索字段由 HF API 的 `search` query 参数决定（按 repo id/名称），不搜本地已装模型。L100-103 过滤仅检查 `localModelId in reservedModelIds`。

**缺口**：需新增（1）本地已装模型关键词搜索栏；（2）多仓库联合搜索（当前硬编码 HF `/api/models`）。

## 3. 模型标识、来源与重复检测

### 3.1 Model 数据模型

[实锤] `Model.kt:109-130` `Model` data class 字段：`id`/`name`/`description`/`baseUrl`/`fileUri`/`generationSize`/`approximateSize`/`isDownloaded`/`needsUpgrade`/`codeDefaults`/`configDefaults`/`runOnCpu`/`isCustom`/`isSdxl`/`isAnima`/`contentRating`。

**缺口**：`Model` 本身没有 `repositoryId`/`remoteModelId`/`source`/`fileFingerprint`/`sha256`。源信息存在于 `ModelMetadata`（独立文件），不挂在 `Model` 上。

### 3.2 ModelMetadata 与源追踪

[实锤] `ModelMetadata.kt:66-70` 已有 `ModelSourceMetadata(repositoryId, revision, artifactKind)` — 这就是源追踪结构。`SCHEMA_VERSION = 4`（L153），JSON 键 `repository_id`/`revision`/`artifact_kind`（L162-164）。`ModelMetadataStore`（L265-300）持久化到模型目录下 `.vision-dream-model.json`。

[实锤] **catalog 下载路径已写入 source**：`HuggingFaceCatalogModels.kt:144-154` `installationMetadata()` 构造 `ModelSourceMetadata`，经 `ModelSearchDialog.kt:189-191` `EXTRA_MODEL_METADATA_JSON` 传入 service，`ModelDownloadService.kt:240-250` 对 directory 安装补全 `source`。

[实锤] **manual import 路径未写入 source**：NPU 手动导入 `extractNpuModel`（`ModelListScreen.kt:3483-3490`）和 CPU 手动转换（`ModelListScreen.kt:3960-3967`）写入的 `ModelMetadata` 只有 `contentRating`/`ratingSource=USER`/`displayName`，**无 `source`**。

**缺口**：手动导入需接受并写入 `ModelSourceMetadata`。

### 3.3 重复检测

[实锤] 当前重复判定仅在 `TransactionalModelInstaller.kt:166` `if (target.exists()) return Result.AlreadyInstalled(modelId)` — 按 `localModelId`（目录名），**不**按内容 sha256 跨 modelId 去重。

[实锤] `ModelDownloadService.kt:104` `DownloadState.AlreadyInstalled(modelId)` 是当前唯一的"重复"终态信号。

[实锤] 下载校验链（`ModelDownloadService.kt:470-603`）有 sha256 流式校验，但仅用于验证下载完整性，不用于跨 modelId 去重。

**缺口**：需新增按 sha256 跨 modelId 的重复识别。

## 4. 下载与安装链路

[实锤] `ModelDownloadService.kt:74-90` Intent extras 已支持 `EXTRA_CATALOG_INSTALL_KIND`/`EXTRA_EXPECTED_SHA256`/`EXTRA_EXPECTED_SIZE_BYTES`/`EXTRA_CATALOG_DOWNLOAD_MANIFEST`/`EXTRA_MODEL_METADATA_JSON`。

[实锤] `Model.startDownload`（`Model.kt:154-172`）构造 Intent 时**不传** `EXTRA_EXPECTED_SHA256`/`EXTRA_MODEL_METADATA_JSON`（仅传空 `ModelMetadata(contentRating=...)`，无 source）。catalog 路径（`ModelSearchDialog.kt:177-204`）传完整 metadata+sha256+size。

[实锤] `TransactionalModelInstaller.kt:31-209` 事务性安装器：staging→rename 原子发布，不覆盖已存在。`ModelInstallPublisher`（L387-401）串行发布点。

**可复用**：事务性安装器和 Intent extras 通道可直接复用。

## 5. 30 项体验改造现状

### 已由 `feat/ui-optimization` 完成

| 编号 | 改造项 | 现状 | 证据 |
| --- | --- | --- | --- |
| UX-09 | 任务式概览 | ✅ 已完成 | `WorkbenchContent.kt` 展示运行状态/模型/快捷入口 |
| UX-10 | 五项一级导航 | ✅ 已完成 | `StudioScaffold.kt` 五项底栏 |
| UX-11 | 设置与帮助置底 | ✅ 已完成 | 设置入口在模型列表菜单内，非顶级 |
| UX-27 | HTTP/MCP 分组 | ✅ 已完成 | `RemoteContent.kt` 四张独立服务卡 |
| UX-28 | 局域网开关 | ✅ 已完成 | `RemoteContent.kt:186-188` MCP LAN toggle |
| UX-29 | 性能预设推荐 | ✅ 已完成 | `PerformancePresetScreen.kt` BuiltInPresetCard 含 isRecommended |
| UX-30 | 能力收敛 | ✅ 已完成 | 性能预设按设备/模型能力展示 |

### 需求与现状有交集但未完成

| 编号 | 改造项 | 现状 | 缺口 |
| --- | --- | --- | --- |
| UX-01 | 统一创作模式 | `ChatGenerationScreen` 只有文生图；图生图/局部重绘在 `ModelRunScreen` | 需统一为多模式创作工作区 |
| UX-02 | 最近 10 条消息与懒加载 | `ChatGenerationScreen` 有会话但无消息限制/懒加载 | 需加消息分页 |
| UX-03 | 前后台恢复 | `MainActivity.kt` 有 NavHost 状态保存，但创作草稿/滚动位置未持久化 | 需补创作态持久化 |
| UX-04 | 选择模型不立即加载 | `ChatGenerationScreen.kt:171-193` 模型选择不触发加载 | ✅ 已满足 |
| UX-05 | 首次使用 CTA | `WorkbenchContent.kt:205-215` 无模型时显示"准备模型" | ✅ 已满足 |
| UX-06 | 移除最近创作 | 工作台仍有"最近资产" | 需确认是否移除或调整 |
| UX-07 | 快捷图标无文字 | `WorkbenchContent.kt` 快捷入口有文字 | 需改为纯图标 |
| UX-08 | API 服务单入口 | 工作台有服务状态摘要卡 | 需确认是否合并为单入口 |
| UX-12 | 重做提示词输入 | `ChatGenerationContent.kt` 有提示词输入但未重做 | 需重新设计输入区 |
| UX-13 | 压缩模型引导 | `ChatGenerationContent.kt` 模型上下文栏 | 需压缩行高 |
| UX-14 | 提示词空态 CTA | `PromptPickerDialog.kt` 空态无 CTA | 需加"新建提示词" |
| UX-15 | 关键图标可发现 | 图标按钮多但缺显式含义 | 需加文字标签 |
| UX-16 | 模型选择器复用模型库 | 创作页模型选择是独立列表 | 需复用模型库组件 |
| UX-17 | 搜索与筛选描述 | 本地列表无搜索 | 需新增搜索栏 |
| UX-18 | NPU 置顶 | `ModelListScreen.kt` 有 CPU/NPU 分页 | 需 NPU 置顶 |
| UX-19 | 压缩行高 | 模型卡过大 | 需压缩 |
| UX-20 | 首层状态与能力 | 模型卡展示不完整 | 需补已加载/排队/后端信息 |
| UX-21 | 安装与仓库搜索分入口 | 当前混合在同一页 | 需分入口 |
| UX-22 | 模型动作可见 | 动作在菜单内 | 需提到首层 |
| UX-23 | 资产元信息和复制 | `HistoryScreen.kt` 有详情但复制格式 | 需结构化正负提示词 |
| UX-24 | 放大按钮位于右下 | 资产详情放大按钮位置 | 需调整 |
| UX-25 | 空态去创作 | `HistoryContent.kt` 空态有文字无 CTA | 需加"去创作" |
| UX-26 | 隐藏语义明确 | `HistoryContent.kt` 有 reveal 控件 | 需明确语义说明 |

## 6. 历史数据影响

1. [实锤] `ModelMetadata.SCHEMA_VERSION = 4`（`ModelMetadata.kt:153`）。若新增 source 字段，需 bump schema 并保持 `fromJsonString`（L171-233）向后兼容。旧 `.vision-dream-model.json` 无 source 的模型读取时 `source == null`，新逻辑必须容忍 null。
2. [实锤] `Model` data class 字段众多（L109-130），所有 `create*Model()` 构造点（L515-898）和 `scanCustomModels`（L462）都使用 `copy(...)`。若在 `Model` 上加字段，影响面大；更稳妥是继续把源信息放在 `ModelMetadata`。
3. [实锤] `ModelRepository.baseUrl` 单值缓存（L447）+ `UpscalerRepository` 同款（L353）。改为多仓库时，`refreshAllModels`（L931）和 upscaler 重建逻辑都要改。
4. [实锤] `AlreadyInstalled` 终态语义（L104）当前表示"该 modelId 已存在"。引入内容级重复检测后，需区分"同 modelId 已装"和"不同 modelId 但同 sha256 已装"。
5. [实锤] Room DB 当前 v10（`AppDatabase.kt`）。仓库配置存储若用 Room，需 v11 迁移；若用 DataStore/SharedPreferences 则无 DB 迁移风险。

## 7. 待确认点

无阻塞进入 `02-spec` 的待确认点。需求文档已关闭全部 Q1-Q5；30 项体验改造的验收口径已确认为"入口可发现、操作可完成、状态可观察、关键空/错态存在"。

## 8. [业务解读]

本轮的核心不是"美化界面"——`feat/ui-optimization` 已完成导航骨架和 Route/Content 边界。真正的增量是：**仓库搜索让用户跨源发现模型，来源关联让手动导入可追溯，重复检测避免冗余下载**。30 项体验改造中约 7 项已完成，剩余 23 项需在 `02-spec` 中逐项定义字段语义、状态机和异常边界，然后在 `03-plan` 中拆分实现文件和验证命令。
