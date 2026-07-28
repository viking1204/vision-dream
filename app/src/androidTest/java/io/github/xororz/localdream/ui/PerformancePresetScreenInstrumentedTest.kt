package io.github.xororz.localdream.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformancePresetScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fallbackIsReadOnlyAndUserPresetCanBeBoundThenDeleted() {
        val store = FakePresetStore()
        val preset = store.create("Balanced", "balanced", VALID_CONFIG)
        render(store)

        composeRule.waitForText("Compatibility fallback")
        composeRule.onNodeWithText("Compatibility fallback：只读、不可删除").fetchSemanticsNode()
        composeRule.onAllNodesWithContentDescription("编辑").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("删除").assertCountEquals(1)

        composeRule.onNodeWithText("设为默认").performClick()
        composeRule.waitUntil(5_000) {
            store.binding(PerformancePresetBinding.DEFAULT)?.presetId == preset.id
        }

        composeRule.onNodeWithContentDescription("删除").performClick()
        composeRule.onNodeWithText("删除 Balanced？").fetchSemanticsNode()
        composeRule.onNodeWithText("删除").performClick()

        composeRule.waitUntil(5_000) { store.get(preset.id) == null }
        composeRule.waitForText("已删除；已回退：DEFAULT")
        composeRule.onNodeWithText("已删除；已回退：DEFAULT").fetchSemanticsNode()
        assertEquals(
            PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID,
            store.binding(PerformancePresetBinding.DEFAULT)?.presetId,
        )
    }

    @Test
    fun userPresetCanBeCreatedAndEdited() {
        val store = FakePresetStore()
        render(store)

        composeRule.waitForText("Compatibility fallback")
        composeRule.onNodeWithTag("performance-preset-create").performClick()
        composeRule.onNodeWithTag("performance-preset-name").performTextReplacement("Fast")
        composeRule.onNodeWithTag("performance-preset-selector").performTextReplacement("fast")
        composeRule.onNodeWithTag("performance-preset-config").performTextReplacement(VALID_CONFIG)
        composeRule.onNodeWithText("保存").performClick()
        composeRule.waitForText("Fast")

        composeRule.onNodeWithContentDescription("编辑").performClick()
        composeRule.onNodeWithTag("performance-preset-name").performTextReplacement("Fast v2")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitForText("Fast v2")
        assertEquals("Fast v2", store.list().single { !it.isFallback }.name)
    }

    private fun render(store: FakePresetStore) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                PerformancePresetScreen(
                    navController = NavController(context),
                    presetStore = store,
                )
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForText(text: String) {
        waitUntil(5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakePresetStore : McpPresetStore {
        private val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())

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

        override fun delete(id: String): PresetDeleteResult = repository.delete(id)

        override fun binding(bindingKey: String): PerformancePresetBinding? = repository.binding(bindingKey)

        override fun bind(bindingKey: String, presetId: String): PerformancePresetBinding = repository.bind(bindingKey, presetId)

        override fun exportEnvelope(): String = error("Not used by this screen test")

        override fun importEnvelope(envelope: String): List<PerformancePreset> = error("Not used by this screen test")
    }

    private companion object {
        const val VALID_CONFIG =
            "{\"schemaVersion\":1,\"engine\":{\"sdxlLowRam\":false,\"animaLowRam\":false,\"animaSequentialDit\":false}}"
    }
}
