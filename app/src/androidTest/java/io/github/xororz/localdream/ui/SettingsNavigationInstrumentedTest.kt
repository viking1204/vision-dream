package io.github.xororz.localdream.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.MainActivity
import io.github.xororz.localdream.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingSettingsTabOpensIndependentSettingsScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.waitForIdle()

        // 底部导航栏的“设置”tab（首页态下唯一匹配“设置”文本）
        composeRule.onNodeWithText(context.getString(R.string.studio_nav_settings))
            .assertExists()
            .performClick()
        composeRule.waitForIdle()

        // 进入独立的 SettingsScreen：
        // 1) 设置页独有 section“下载源”显示（首页完全没有 => 强证明已进入设置页）
        composeRule.onNodeWithText(context.getString(R.string.download_source)).assertIsDisplayed()
        // 2) “设置”文本恰好 2 处：底部栏 tab + 独立页 TopAppBar 标题
        //    （若是 G3 旧实现“模型列表页 + 浮层”，顶部不会有独立“设置”标题）
        composeRule.onAllNodesWithText(context.getString(R.string.settings)).assertCountEquals(2)
    }
}
