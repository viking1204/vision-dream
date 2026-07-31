package io.github.xororz.localdream.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.data.InMemoryPerformancePresetStore
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetBinding
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.data.PresetDeleteResult
import io.github.xororz.localdream.mcp.McpPresetStore
import io.github.xororz.localdream.ui.screens.PerformancePresetScreen
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformancePresetScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recommendedPresetIsAutomaticAndOverrideSwitchCanBeToggled() {
        val store = FakePresetStore()
        store.addBuiltIn("持续性能", "sustained_performance", VALID_CONFIG)
        store.create("Balanced", "balanced", VALID_CONFIG)
        render(store)

        composeRule.waitForText("自动推荐模式 · 持续性能")
        composeRule.onAllNodesWithContentDescription("编辑").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("删除").assertCountEquals(1)

        composeRule.onNodeWithTag("performance-preset-override-switch")
            .performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            store.binding(PerformancePresetBinding.DEFAULT)?.presetId ==
                PerformancePresetRepository.RECOMMENDED_DEFAULT_PRESET_ID
        }
        composeRule.waitForText("自选预设已启用")

        composeRule.onNodeWithTag("performance-preset-override-switch")
            .performTouchInput { click() }
        composeRule.waitUntil(5_000) {
            store.binding(PerformancePresetBinding.DEFAULT) == null
        }
        composeRule.waitForText("自动推荐模式 · 持续性能")
    }

    @Test
    fun builtInPresetCanBeOpenedForReadOnlyParameterInspection() {
        val store = FakePresetStore()
        val builtIn = store.addBuiltIn("极致性能", "extreme_performance", VALID_CONFIG)
        render(store)

        composeRule.waitForText("单张极速")
        composeRule.onNodeWithText("查看参数").performClick()
        composeRule.waitForText("内置预设 · 只读")
        composeRule.onNodeWithText("运行参数").fetchSemanticsNode()
        composeRule.onNodeWithText("CLIP CPU 线程").fetchSemanticsNode()
        composeRule.onAllNodesWithContentDescription("编辑").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("删除").assertCountEquals(0)
        composeRule.onNodeWithTag("performance-preset-detail-copy").performClick()
        composeRule.onNodeWithTag("performance-preset-name").fetchSemanticsNode()
        composeRule.onNodeWithTag("performance-preset-sdxl-low-ram").fetchSemanticsNode()
        composeRule.onAllNodesWithText("v1/v2 配置 JSON").assertCountEquals(0)
        composeRule.onAllNodesWithText("选择标识").assertCountEquals(0)
    }

    @Test
    fun userPresetCanBeCreatedAndEdited() {
        val store = FakePresetStore()
        render(store)

        composeRule.waitForText("自动推荐模式 · 持续性能")
        composeRule.onNodeWithTag("performance-preset-create").performClick()
        composeRule.onNodeWithTag("performance-preset-name").performTextReplacement("Fast")
        composeRule.onNodeWithTag("performance-preset-sdxl-low-ram").performClick()
        composeRule.onNodeWithText("保存").performClick()
        composeRule.waitForText("Fast")

        composeRule.onNodeWithContentDescription("编辑").performClick()
        composeRule.onNodeWithTag("performance-preset-name").performTextReplacement("Fast v2")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitForText("Fast v2")
        assertEquals("Fast v2", store.list().single { !it.isFallback }.name)
        assertEquals(
            true,
            io.github.xororz.localdream.data.PerformancePresetConfig
                .parse(store.list().single { !it.isFallback }.configJson)
                .engine
                ?.sdxlLowRam,
        )
    }

    @Test
    fun qualificationImportIsNotExposedThroughTheOrdinaryPresetEditor() {
        val store = FakePresetStore()
        render(store)

        composeRule.onAllNodesWithText("导入验收资格").assertCountEquals(0)
    }

    private fun render(store: FakePresetStore): SnackbarHostState {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snackbar = SnackbarHostState()
        composeRule.setContent {
            MaterialTheme {
                PerformancePresetScreen(
                    navController = NavController(context),
                    presetStore = store,
                    snackbarHostState = snackbar,
                )
            }
        }
        return snackbar
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForText(text: String) {
        waitUntil(5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakePresetStore : McpPresetStore {
        private val backingStore = InMemoryPerformancePresetStore()
        private val repository = PerformancePresetRepository(backingStore)
        private val bindings = ConcurrentHashMap<String, PerformancePresetBinding>()

        override fun list(): List<PerformancePreset> = repository.list()

        override fun get(id: String): PerformancePreset? = repository.get(id)

        override fun create(name: String, selector: String, configJson: String): PerformancePreset = repository.create(name, selector, configJson)

        override fun update(
            id: String,
            revision: Long,
            name: String,
            selector: String,
            configJson: String,
        ): PerformancePreset = repository.update(id, revision, name, selector, configJson)

        override fun delete(id: String): PresetDeleteResult {
            val rebound = bindings.filterValues { it.presetId == id }.keys.sorted()
            return repository.delete(id).let { result ->
                if (result.deleted) {
                    rebound.forEach { key ->
                        bindings[key] = PerformancePresetBinding(key, PerformancePresetRepository.RECOMMENDED_DEFAULT_PRESET_ID)
                    }
                }
                result.copy(reboundBindingKeys = rebound)
            }
        }

        override fun binding(bindingKey: String): PerformancePresetBinding? = bindings[bindingKey]

        override fun bind(bindingKey: String, presetId: String): PerformancePresetBinding = PerformancePresetBinding(bindingKey, presetId).also { bindings[bindingKey] = it }

        override fun unbind(bindingKey: String): Boolean = bindings.remove(bindingKey) != null

        override fun exportEnvelope(): String = error("Not used by this screen test")

        override fun importEnvelope(envelope: String): List<PerformancePreset> = error("Not used by this screen test")

        fun addBuiltIn(name: String, selector: String, configJson: String): PerformancePreset = PerformancePreset(
            id = if (selector == "sustained_performance") {
                PerformancePresetRepository.RECOMMENDED_DEFAULT_PRESET_ID
            } else {
                "built-in-$selector"
            },
            name = name,
            selector = selector,
            configJson = configJson,
            revision = 1,
            isBuiltIn = true,
        ).also(backingStore::save)
    }

    private companion object {
        const val VALID_CONFIG =
            "{\"schemaVersion\":1,\"engine\":{\"sdxlLowRam\":false,\"animaLowRam\":false,\"animaSequentialDit\":false}}"
    }
}
