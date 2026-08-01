# ux-repository-search-20260801：规格设计

> 阶段：`02-spec`
> 需求 slug：`ux-repository-search-20260801`
> 输入：`docs/requirements/ux-repository-search-20260801.md`、`docs/reviews/ux-repository-search-20260801-fact-review.md`

## 1. 目标与非目标

### 目标

1. 自定义仓库管理：新增、编辑、启用/禁用、删除可配置的同结构 JSON/目录索引仓库。
2. 仓库模型联合搜索：覆盖所有启用仓库，按显示名/modelId/描述/标签/后端/架构做大小写无关分词包含匹配；modelId 精确和名称前缀优先；默认相关度+名称排序；初始 30 项、追加 30 项。
3. 手动导入来源关联：导入时可搜索选择仓库模型建立 `repositoryId + remoteModelId` 关联；不选为无来源本地模型。
4. 重复下载识别：来源 ID 命中标记已安装；文件指纹命中提示内容相同并关联已有模型；同名且来源、指纹不同允许并存。
5. 30 项体验改造（UX-01 至 UX-30）：逐项入口可发现、操作可完成、状态可观察、关键空/错态存在。
6. 前后台恢复：恢复一级页面、筛选/排序、滚动位置、创作草稿、模型选择、高级参数和详情页；运行中任务由服务状态接管。

### 非目标

- 任意网页抓取、未确认的新远端模型源、账号体系、跨设备同步、云端数据迁移和模型格式转换。
- 改变模型实际加载时机以外的推理、下载传输或模型执行策略。
- 清理、迁移或重置既有用户数据、模型、资产。
- 以视觉像素一致性或性能数值作为阻塞验收阈值。

## 2. 数据模型设计

### 2.1 仓库配置

```kotlin
@Immutable
data class RepositoryConfig(
    val id: String,           // UUID，创建时生成
    val name: String,         // 用户可读名称
    val baseUrl: String,      // HF 兼容 API 根 URL
    val enabled: Boolean = true,
    val type: RepositoryType = RepositoryType.HUGGINGFACE,
)

enum class RepositoryType { HUGGINGFACE, JSON_INDEX, DIRECTORY }
```

**存储**：DataStore `stringPreferencesKey("custom_repositories")`，JSON 数组序列化。不使用 Room，避免 DB 迁移。

**兼容**：内置 HuggingFace 仓库不存入此列表，由代码隐含提供。`Preferences.BASE_URL_KEY` 和 `SELECTED_SOURCE_KEY` 保留但不再作为搜索的主入口；搜索改为遍历 `RepositoryConfig` 列表 + 内置 HF。

### 2.2 搜索结果扩展

复用现有 `ModelCatalogSearchResult`（`HuggingFaceCatalogModels.kt:120-155`），新增 `repositoryConfigId` 字段标识结果来源仓库：

```kotlin
data class ModelCatalogSearchResult(
    // ... 既有字段 ...
    val repositoryConfigId: String? = null,  // null = 内置 HF
)
```

### 2.3 来源关联

复用现有 `ModelSourceMetadata`（`ModelMetadata.kt:66-70`），无需新增字段：

```kotlin
data class ModelSourceMetadata(
    val repositoryId: String,     // 仓库 ID（HF repo id 或自定义仓库 config id）
    val revision: String,         // 仓库版本/sha
    val artifactKind: CatalogArtifactKind,
)
```

**变更点**：手动导入路径（`ModelListScreen.kt:3483/3960`）在写入 `ModelMetadata` 时补写 `source` 字段。导入 UI 新增"搜索并关联仓库模型"可选步骤。

### 2.4 重复检测

新增 `contentSha256` 字段到 `ModelMetadata`：

```kotlin
data class ModelMetadata(
    // ... 既有字段 ...
    val contentSha256: String? = null,  // 模型文件 SHA-256，安装完成后计算
)
```

**Schema**：`ModelMetadata.SCHEMA_VERSION` 从 4 升到 5。`fromJsonString`（L171-233）的 `require(schemaVersion in 1..SCHEMA_VERSION)` 自动兼容旧版本。旧 `.vision-dream-model.json` 读取时 `contentSha256 == null`，重复检测对该模型跳过内容级比对。

**重复检测规则**：

| 条件 | 判定 | 用户提示 |
| --- | --- | --- |
| `source.repositoryId + remoteModelId` 已存在于已安装模型 | 来源 ID 命中 | "已安装" |
| `contentSha256` 匹配已安装模型 | 文件指纹命中 | "内容相同，已关联到已有模型" |
| `localModelId` 相同但来源/指纹不同 | 同名并存 | "同名但来源不同，已创建新模型" |
| 以上均不匹配 | 新模型 | 无特殊提示 |

## 3. 枚举语义

| 枚举 | 值 | 业务语义 |
| --- | --- | --- |
| `RepositoryType.HUGGINGFACE` | HF 兼容 API | 调用 `/api/models` 端点，使用 HF JSON 解析器 |
| `RepositoryType.JSON_INDEX` | 静态 JSON 索引 | 用户提供 JSON 文件 URL，结构与 HF `/api/models` 响应一致 |
| `RepositoryType.DIRECTORY` | 目录索引 | 本地或局域网目录，按 HF 仓库结构扫描 |
| `SearchStatus.IDLE` | 空闲 | 未发起搜索 |
| `SearchStatus.SEARCHING` | 搜索中 | 至少一个仓库请求进行中 |
| `SearchStatus.SUCCESS` | 成功 | 至少一个仓库返回结果 |
| `SearchStatus.PARTIAL_FAILURE` | 部分失败 | 至少一个仓库失败但其它有结果 |
| `SearchStatus.ALL_FAILED` | 全部失败 | 所有仓库均失败 |
| `SearchStatus.EMPTY` | 无匹配 | 所有仓库成功但无匹配结果 |

## 4. 兼容策略

### 4.1 ModelMetadata Schema v4 → v5

- `SCHEMA_VERSION = 5`，`fromJsonString` 的 `require(schemaVersion in 1..SCHEMA_VERSION)` 自动接受 v1-v4 旧文件。
- v4 及以下文件读取时 `contentSha256 = null`；重复检测对该模型仅按 `source` 比对，跳过内容级。
- 不需要迁移脚本；首次安装新模型或重新扫描时自动补写 `contentSha256`。

### 4.2 Preferences 兼容

- `BASE_URL_KEY` / `SELECTED_SOURCE_KEY` 保留，作为内置 HF 仓库的 baseUrl 覆盖。
- `custom_repositories` 新键，旧版本忽略。降级到旧版本时自定义仓库不可见但不损坏。

### 4.3 Model data class

- 不在 `Model` 上新增字段。源信息和 sha256 继续通过 `ModelMetadata` 暴露，由 `ModelRepository` 在扫描时读取。
- `ModelRepository.scanCustomModels`（`Model.kt:462-501`）在扫描时读取 `.vision-dream-model.json` 的 `source` 和 `contentSha256`，供搜索结果去重使用。

### 4.4 Room DB

- 不升级 Room DB 版本。仓库配置使用 DataStore，不引入新表。
- `prompt_templates` v10 迁移已在 master 分支完成，本规格不触及。

## 5. 异常边界

### 5.1 搜索

| 场景 | 行为 |
| --- | --- |
| 无启用仓库 | 显示"请先启用至少一个仓库"空态 + 仓库配置入口 |
| 搜索中 | 显示 LoadingIndicator；已有结果保留 |
| 单仓失败 | 该仓显示"加载失败，点击重试"；其它仓结果正常展示 |
| 全部失败 | 显示"搜索失败，点击重试"全屏空态 |
| 无匹配 | 显示"未找到匹配模型"空态 |
| 网络超时 | 30s 超时，视为该仓失败 |
| 搜索关键词为空 | 不发起搜索，显示"输入关键词搜索模型"提示 |

### 5.2 下载/导入

| 场景 | 行为 |
| --- | --- |
| 来源 ID 命中 | 拦截下载，显示"已安装" |
| 文件指纹命中 | 拦截下载，显示"内容相同，已关联到 {modelName}" |
| 同名不同来源/指纹 | 允许下载，自动追加后缀避免目录冲突 |
| sha256 校验失败 | 删除临时文件，显示"校验失败，请重试" |
| 存储空间不足 | 下载前检查，显示"存储空间不足" |
| 下载中被取消 | 清理临时文件，不残留 |

### 5.3 仓库配置

| 场景 | 行为 |
| --- | --- |
| baseUrl 无效 | 显示"请输入有效的 URL"；不保存 |
| 仓库已存在（同 baseUrl） | 显示"该仓库已存在" |
| 删除启用的仓库 | 二次确认"删除后已安装模型不受影响，但来源关联将标记为失效" |
| 仓库下架/改名 | 已安装模型的 `source` 保留，搜索时标记为"来源已失效" |

### 5.4 前后台恢复

| 场景 | 行为 |
| --- | --- |
| 进程重建 | 恢复 NavHost 当前 route；创作草稿从 DataStore 恢复；运行中任务从 `BackgroundGenerationService` 状态接管 |
| 配置变更（旋转） | 保留 ViewModel 状态；UI 由 Compose 状态自动恢复 |
| 返回栈 | 保留 NavHost 返回栈；不恢复旧弹窗 |

## 6. 30 项体验改造规格

### 创作域

| 编号 | 规格 |
| --- | --- |
| UX-01 | `ChatGenerationScreen` 新增模式切换器：文生图 / 图生图 / 局部重绘 / 放大。模式切换保持提示词和模型上下文。图生图和局部重绘复用 `ModelRunScreen` 的图片输入和裁剪逻辑。 |
| UX-02 | 会话消息列表限制 10 条可见，更早消息通过向上滚动懒加载。使用 `LazyColumn` + paging。 |
| UX-03 | 创作草稿（提示词、负面提示词、模型 ID、高级参数、模式）写入 DataStore `creation_draft` 键。`MainActivity` 恢复时读取并注入 `ChatGenerationScreen`。 |
| UX-04 | 已满足（选择不触发加载）。 |
| UX-05 | 已满足（无模型 CTA 导向模型准备）。 |
| UX-12 | 提示词输入区改为紧凑上下文栏：模型芯片 + 提示词 TextField + 负面提示词折叠 + 高级参数底部面板。移除过大模型引导卡。 |
| UX-13 | 模型上下文栏压缩为单行芯片：模型名 + 后端标签 + 加载状态。 |
| UX-14 | `PromptPickerDialog` 空态新增"新建提示词"TextButton，导航到 `PromptManagerScreen`。 |
| UX-15 | 关键图标按钮（提示词、高级参数、生成）添加文字标签或 `contentDescription` 语义。 |

### 工作台域

| 编号 | 规格 |
| --- | --- |
| UX-06 | 工作台移除"最近创作"区域；保留服务状态摘要和快捷入口。 |
| UX-07 | 快捷入口改为纯图标（无文字），使用 `IconButton` + `contentDescription`。 |
| UX-08 | 工作台服务状态卡合并为单入口，点击导航到服务页。 |
| UX-09 | 已满足（任务式概览）。 |
| UX-10 | 已满足（五项一级导航）。 |
| UX-11 | 已满足（设置与帮助置底）。 |

### 模型库域

| 编号 | 规格 |
| --- | --- |
| UX-16 | 创作页模型选择器复用 `ModelListContent` 的模型卡组件，不再使用独立列表。 |
| UX-17 | 本地模型列表新增搜索栏（`OutlinedTextField`），按名称/modelId 过滤。仓库搜索为独立入口。 |
| UX-18 | NPU 模型分区置顶，CPU 模型在下。保留 CPU/NPU 标签切换。 |
| UX-19 | 模型卡行高从 ~120dp 压缩到 ~72dp；描述截断为 1 行；移除巨型 modelId 区块。 |
| UX-20 | 模型卡首层展示：已加载状态、后端标签、是否支持图生图/Inpaint、适用硬件。 |
| UX-21 | "安装"（仓库搜索/下载）和"本地模型管理"分为两个入口；仓库搜索从模型页顶部搜索栏进入。 |
| UX-22 | 模型操作（运行/卸载/重命名/删除）从菜单提到模型卡右侧图标行。 |

### 资产域

| 编号 | 规格 |
| --- | --- |
| UX-23 | 资产详情展示结构化正/负提示词，各自带"复制"按钮。复制格式：`正向: ...\n负面: ...`。 |
| UX-24 | 资产详情放大按钮固定在右下角 `FloatingActionButton`。 |
| UX-25 | 资产空态新增"去创作"TextButton，导航到创作页。 |
| UX-26 | "显示全部图片"改为"显示敏感内容"，添加 `semantics` 解释作用范围。 |

### 服务域

| 编号 | 规格 |
| --- | --- |
| UX-27 | 已满足（HTTP/MCP 分组）。 |
| UX-28 | 已满足（局域网开关）。 |

### 性能域

| 编号 | 规格 |
| --- | --- |
| UX-29 | 已满足（性能预设推荐与高级展开）。 |
| UX-30 | 已满足（能力收敛）。 |

## 7. 测试矩阵

### 数据层 JVM 测试

| 测试 | 覆盖 |
| --- | --- |
| `RepositoryConfigSerializationTest` | 仓库配置 JSON 序列化/反序列化、空列表、降级兼容 |
| `ModelMetadataSchemaV5Test` | v5 含 `contentSha256` 解析；v4 无 `contentSha256` 时 null 兼容 |
| `DuplicateDetectorTest` | 来源 ID 命中、sha256 命中、同名并存、均不匹配四种判定 |
| `MultiRepositorySearchMergerTest` | 多仓结果合并、相关度排序、单仓失败不阻断、全部失败 |
| `CreationDraftPersistenceTest` | 草稿写入/读取/清除；进程重建后恢复 |

### UI Compose 测试

| 测试 | 覆盖 |
| --- | --- |
| `RepositoryConfigScreenTest` | 新增/编辑/删除/启用禁用；baseUrl 校验；重复检测 |
| `ModelSearchContentTest` | 搜索输入、LoadingIndicator、结果列表、空态、单仓失败重试 |
| `ChatGenerationModeSwitcherTest` | 模式切换保持上下文；图生图/局部重绘入口可发现 |
| `ModelListLocalSearchTest` | 本地搜索栏过滤；空结果态；仓库搜索独立入口 |
| `AssetEmptyStateTest` | 空态"去创作"CTA；显示敏感内容语义 |
| `PromptPickerEmptyStateTest` | 空态"新建提示词"CTA 导航 |

### 既有回归测试（不得破坏）

- `NavigationStructureTest`、`AssetLayoutModeTest`、`AssetOriginTest`、`PerformancePresetConfigTest`、`PromptRepositoryTest`、`AppDatabaseMigrationTest`

## 8. 发布边界

1. 在 `feat/ui-optimization` 分支或从 master 新建的功能分支实施。
2. 每个阶段（spec → plan → implementation）独立提交。
3. `04-implementation` 通过后启动独立 `05-change-review`。
4. 测试发布仅在用户要求时进行；禁止商店发布和生产部署。
5. 回滚通过撤销功能提交完成；不清空 Room、模型目录或用户偏好。

## 9. 待确认点

无。需求文档已关闭全部 Q1-Q5；本规格基于代码事实和已确认需求设计，无影响实现方向的未确认项。
