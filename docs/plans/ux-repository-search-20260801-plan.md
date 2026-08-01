# ux-repository-search-20260801：实施计划

> 阶段：`03-plan`
> 需求 slug：`ux-repository-search-20260801`
> 实施分支：`feat/ui-optimization`（rebase 后基于 master）

## 1. 交付目标与不可突破边界

实施规格 `docs/specs/ux-repository-search-20260801-spec.md` 的全部设计。只修改 UI、导航组合、资源、UI state holder、DataStore 偏好和测试。不得修改 Room schema（保持 v10）、`BackgroundGenerationService` 生成状态时序、OpenAI/MCP/Remote 协议、`model_run` route 参数语义。

## 2. 实施顺序与文件清单

### P1：仓库配置与搜索后端

**新增文件**
- `app/src/main/java/io/github/xororz/localdream/modelcatalog/RepositoryConfig.kt`：`RepositoryConfig` data class + `RepositoryType` enum + JSON 序列化
- `app/src/main/java/io/github/xororz/localdream/modelcatalog/MultiRepositorySearchClient.kt`：遍历启用仓库、合并结果、单仓失败隔离
- `app/src/main/java/io/github/xororz/localdream/data/RepositoryPreferences.kt`：DataStore 读写 `custom_repositories` 键
- `app/src/test/java/io/github/xororz/localdream/modelcatalog/RepositoryConfigSerializationTest.kt`
- `app/src/test/java/io/github/xororz/localdream/modelcatalog/MultiRepositorySearchMergerTest.kt`

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/data/Preferences.kt`：新增 `customRepositories` 读写方法
- `app/src/main/java/io/github/xororz/localdream/modelcatalog/HuggingFaceCatalogModels.kt`：`ModelCatalogSearchResult` 新增 `repositoryConfigId` 字段

**完成条件**
```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.modelcatalog.RepositoryConfigSerializationTest' --tests 'io.github.xororz.localdream.modelcatalog.MultiRepositorySearchMergerTest'
./gradlew :app:compileDebugKotlin
```

### P2：重复检测与来源关联

**新增文件**
- `app/src/main/java/io/github/xororz/localdream/data/DuplicateDetector.kt`：按 source ID / sha256 / localModelId 判定重复
- `app/src/test/java/io/github/xororz/localdream/data/DuplicateDetectorTest.kt`

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/data/ModelMetadata.kt`：`SCHEMA_VERSION = 5`，新增 `contentSha256` 字段，`fromJsonString` 兼容 v1-v4
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`：手动导入路径补写 `ModelSourceMetadata`
- `app/src/main/java/io/github/xororz/localdream/data/Model.kt`：`ModelRepository.scanCustomModels` 读取 `contentSha256` 供去重

**完成条件**
```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.DuplicateDetectorTest'
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.ModelMetadataTest'
./gradlew :app:compileDebugKotlin
```

### P3：仓库配置 UI

**新增文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/RepositoryConfigContent.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/RepositoryConfigUiState.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/RepositoryConfigEvent.kt`
- `app/src/androidTest/java/io/github/xororz/localdream/ui/RepositoryConfigScreenTest.kt`

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`：新增仓库配置入口
- `app/src/main/res/values*/strings.xml`：四语补齐仓库配置文案

**完成条件**
```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

### P4：仓库搜索 UI

**新增文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/ModelSearchContent.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/ModelSearchUiState.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/repository/ModelSearchEvent.kt`
- `app/src/androidTest/java/io/github/xororz/localdream/ui/ModelSearchContentTest.kt`

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`：搜索入口拆分为本地搜索栏 + 仓库搜索
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelSearchDialog.kt`：改为多仓库联合搜索
- `app/src/main/res/values*/strings.xml`：四语补齐搜索文案

**完成条件**
```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

### P5：创作域体验改造（UX-01/02/03/12/13/14/15）

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt`：模式切换器、消息懒加载、紧凑上下文栏、图标标签
- `app/src/main/java/io/github/xororz/localdream/ui/screens/chat/ChatGenerationContent.kt`：模式切换 UI、压缩模型引导
- `app/src/main/java/io/github/xororz/localdream/ui/screens/chat/ChatGenerationUiState.kt`：新增 `mode` 字段
- `app/src/main/java/io/github/xororz/localdream/ui/components/PromptPickerDialog.kt`：空态 CTA
- `app/src/main/java/io/github/xororz/localdream/data/Preferences.kt`：创作草稿持久化
- `app/src/main/res/values*/strings.xml`：四语补齐创作域文案
- `app/src/test/java/io/github/xororz/localdream/data/CreationDraftPersistenceTest.kt`

**完成条件**
```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.CreationDraftPersistenceTest'
./gradlew :app:compileDebugKotlin :app:assembleDebug
```

### P6：工作台与模型库体验改造（UX-06/07/08/16/17/18/19/20/21/22）

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/WorkbenchContent.kt`：移除最近创作、快捷图标无文字、服务单入口
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`：本地搜索栏、NPU 置顶、压缩行高、首层状态、安装与搜索分入口、模型动作可见
- `app/src/main/java/io/github/xororz/localdream/ui/screens/model/ModelListContent.kt`：压缩模型卡、首层状态
- `app/src/main/res/values*/strings.xml`：四语补齐

**完成条件**
```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug
```

### P7：资产域体验改造（UX-23/24/25/26）

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/ui/screens/HistoryScreen.kt`：结构化提示词复制、放大按钮右下、空态 CTA、隐藏语义
- `app/src/main/java/io/github/xororz/localdream/ui/screens/history/HistoryContent.kt`：空态 CTA、语义说明
- `app/src/main/java/io/github/xororz/localdream/ui/screens/AssetHistoryCollection.kt`：放大按钮位置、详情结构化
- `app/src/main/res/values*/strings.xml`：四语补齐

**完成条件**
```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug
```

### P8：前后台恢复（UX-03）

**修改文件**
- `app/src/main/java/io/github/xororz/localdream/data/Preferences.kt`：创作草稿、滚动位置、筛选/排序持久化
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ChatGenerationScreen.kt`：恢复时读取草稿
- `app/src/main/java/io/github/xororz/localdream/ui/screens/HistoryScreen.kt`：恢复筛选/排序/滚动位置
- `app/src/test/java/io/github/xororz/localdream/data/CreationDraftPersistenceTest.kt`

**完成条件**
```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.xororz.localdream.data.CreationDraftPersistenceTest'
./gradlew :app:compileDebugKotlin :app:assembleDebug
```

### P9：全量回归与 Preview

**修改文件**
- P1-P8 新增的每个 `*Content.kt`：补 light/dark Preview
- `app/src/androidTest/java/io/github/xororz/localdream/ui/UiAccessibilityInstrumentedTest.kt`：扩展覆盖新组件

**完成条件**
```bash
./gradlew :app:ktlintCheck :app:detekt :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## 3. 风险与验证

| 高风险点 | 禁止 | 验证 |
| --- | --- | --- |
| ModelMetadata schema 升级 | 破坏 v1-v4 反序列化 | `ModelMetadataTest` 覆盖 v1-v5 |
| 重复检测误判 | 把同名不同内容模型判为重复 | `DuplicateDetectorTest` 四种判定 |
| 多仓搜索单仓失败 | 阻断其它仓结果 | `MultiRepositorySearchMergerTest` |
| 创作模式切换丢失上下文 | 切换清空提示词 | `ChatGenerationModeSwitcherTest` |
| 前后台恢复 | 恢复旧弹窗或丢失草稿 | `CreationDraftPersistenceTest` |
| 本地搜索 | 搜索破坏 CPU/NPU 分页 | `ModelListLocalSearchTest` |

## 4. 发布与回滚

1. 在 `feat/ui-optimization` 分支分阶段提交；每个 P 独立提交。
2. `04-implementation` 通过后启动 `05-change-review`。
3. 测试发布仅在用户要求时进行。
4. 回滚通过撤销功能提交；不清空 Room、模型目录或用户偏好。
5. `07-business-e2e` 在 OnePlus 6 或 K30 上验证两次 CPU 生成。
