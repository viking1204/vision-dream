# ui-redesign-20260802：测试发布（06-test-release）

- 阶段：`06-test-release`（测试发布）
- 迭代：1
- 范围：P2–P8 评审通过的变更集（`44ebd2c`），本地可产出发布证据。

## 1. 门禁证据

| 门禁项 | 状态 | 证据 |
| --- | --- | --- |
| 测试分支 | ✅ 已建 | 本地分支 `test/ui-redesign-20260802` @ `44ebd2c`（承载 P2–P8 全部 7 个提交）。 |
| 部署任务 | ✅ 已构建 | `./gradlew :app:assembleRelease` 成功（minify + R8 + lintVitalRelease 全过），产物 `app/build/outputs/apk/release/VisionDream_armv8a_1.0.apk`（65 MB，测试签名）。 |
| 测试通过 | ✅ 全绿 | `ktlintCheck` + `detekt` + `lintDebug` + `testDebugUnitTest` + `assembleDebug` + `assembleAndroidTest` 均通过（05-change-review 重跑结论，日志 `/tmp/gate.log`）。 |
| 真机验收 | ✅ UI 通过 | OnePlus 6 上 `UiAccessibilityInstrumentedTest` 6/6 通过（07-business-e2e UI 证据）。 |
| CI 流水线 | ⏳ 外部依赖 | 本沙箱无 CI 系统；需接入 `assembleRelease` + 测试任务，并注入真实 `RELEASE_STORE_*` 签名密钥（当前为一次性测试 keystore，仅供内部测试）。 |
| 实例健康（生成） | ⏳ 外部依赖 | 需在能出图真机（K30 / PJZ110）上完成端到端生图验收；OnePlus 6 旧 CPU 无法加载 MNN，仅验 UI。 |

## 2. 发布构建说明

- Release 构建类型已正确配置：`isMinifyEnabled=true`、`isShrinkResources=true`、R8 + `proguard-rules.pro`。
- 本次增量代码（模型标签派生、任务队列、资产设为默认、ChatGPT composer、紧凑模型列表、无障碍测试/Preview）在 R8 缩减下编译通过，**未出现因反射/序列化被误删导致的构建或 keep 规则缺失问题**。
- 构建期 warning 均来自**既有代码**（非本次变更集），已记入 05 评审 CR-01：
  - `GenerationQueueSheet.kt:118` / `ChatGenerationScreen.kt:1476` / `ModelListScreen.kt:1401` — `rememberModalBottomSheetState(skipPartiallyExpanded=...)` 废弃，建议迁移到 `rememberBottomSheetState`。
  - `RemoteScreen.kt:152` — `LocalClipboardManager` 废弃，建议迁移到 `LocalClipboard`。
  - 均不阻塞发布，记为 P3 观察项。

## 3. 签名与密钥

- 真实发布必须使用项目 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`（机密，沙箱外托管）。
- 本地产物使用 `/tmp/vd-ui-redesign-release.jks`（一次性自签名，别名 `vdtest`，密码 `android`），**仅限内部测试分发，不可作为正式发布产物**。

## 4. 处置结论

- 本地可交付项（测试分支、部署任务、测试、真机 UI 验收）**全部就绪**。
- `CI 流水线` 与 `实例健康（生成）` 为外部依赖，需在控制器/流水线中落地；其中实例健康（生成）将由 `07-business-e2e` 在 K30 真机提供证据。
- 推进 `07-business-e2e` 前需：① 接入 CI 并注入真实签名密钥；② 将测试分支推送到远端（需用户授权，不自动推送）；③ 在 K30 真机完成端到端生图验收。

**门禁状态：本地证据齐备，剩余 2 项（CI / 实例健康-生成）为外部 blocker，待管线与真机出图验收闭环。**
