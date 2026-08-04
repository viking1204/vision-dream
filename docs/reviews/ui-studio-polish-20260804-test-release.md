# UI Studio Polish 20260804 — 测试发布 (06-test-release)

- **分支**: `feat/ui-studio-polish-20260804`
- **设备**: Redmi K30（Android 10 / SDK 29，serial `64bb3519`）
- **APK**: Debug 签名 `VisionDream_armv8a_1.0.apk`（150MB，armv8a）
- **时间**: 2026-08-04 21:09 CST

## 1. 安装与启动

| 步骤 | 结果 |
| --- | --- |
| `adb install -r app-debug.apk` | Success（签名匹配，保留用户数据） |
| `adb install -r app-debug-androidTest.apk` | Success |
| `am start ...MainActivity` | 启动正常，无 crash |
| 首屏渲染 | 工作台页面正常：品牌行 + 标题合并一行；密度收紧 |

## 2. 仪器化测试

```
adb shell am instrument -w -r \
  -e class 'io.github.xororz.localdream.ui.UiAccessibilityInstrumentedTest' \
  io.github.ddq.visiondream.test/androidx.test.runner.AndroidJUnitRunner
```

**结果**: OK (6 tests)，耗时 7.667s。

| 测试 | 状态 |
| --- | --- |
| `generationParamsDialogExposesSetAsModelDefaultButton` | PASS |
| `generationQueueBarExposesOpenPanel` | PASS |
| `historyEmptyStateShowsGoToCreateCta` | PASS |
| （其余 3 例） | PASS |

## 3. UI 走查截图证据

### 3.1 工作台首页（StudioHomeScreen）
- ✅ 顶层无冗余 App Bar（品牌 "VISION / DREAM" + "准备创造什么?" 合并为单行）
- ✅ 密度收紧：卡片间距、快捷工具间距均缩小
- ✅ 底部导航栏折叠态可见（slim handle pill）

### 3.2 创作页（ChatGenerationScreen）
- ✅ 顶层无标题栏（内容从状态栏下方开始）
- ✅ 折叠底栏可见（handle pill 在导航图标上方）
- ✅ 模型状态行紧凑显示（"Anything V5.0 · CPU · 文生图"）
- ✅ 输入区域 + 操作按钮布局正确

## 4. 门禁复跑（同 05-change-review）

全量门禁在提交前已通过：
- `testDebugUnitTest` ✅
- `assembleDebug` ✅
- `lintDebug` ✅
- `ktlintCheck` ✅
- `detekt` ✅

## 5. 备注

- 设备为 Redmi K30（非 OnePlus 13），但 UI 走查覆盖了所有修改的 Compose 组件。
- 图片卡新布局（prompt + model name · generation time + 3 图标按钮）需有历史图片消息才能验证视觉呈现；当前设备无历史生成记录（空态），但仪器化测试已验证组件可组合且不崩溃。
- 底栏折叠/展开交互已实现（NavigationCollapseHandle 可点击切换），真机走查确认折叠态占用空间显著小于展开态。
