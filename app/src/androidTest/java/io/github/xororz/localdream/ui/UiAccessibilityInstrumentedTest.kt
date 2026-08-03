package io.github.xororz.localdream.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.GenerationTask
import io.github.xororz.localdream.data.GenerationTaskStatus
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.ui.components.GenerationParamsDialog
import io.github.xororz.localdream.ui.components.GenerationQueueBar
import io.github.xororz.localdream.ui.components.GenerationQueueSheet
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.ui.screens.GenerationParameters
import io.github.xororz.localdream.ui.screens.ModelRunHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun generationParamsDialogExposesPositiveAndNegativePromptCopyButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                GenerationParamsDialog(
                    title = "Generation parameters",
                    params = previewParameters(),
                    modelId = "dream-shaper-8",
                    displayMode = GenerationMode.TXT2IMG,
                    showImg2imgButton = false,
                    onCopyPrompt = {},
                    onCopyNegativePrompt = {},
                    onShare = {},
                    onSendToImg2img = {},
                    onReproduce = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // UX-23: distinct, correctly-labelled copy actions for the positive and
        // negative prompts are exposed to assistive technology. (The dialog is
        // hosted in a separate window, so coordinate-based clicks are unreliable
        // here; presence + accessibility label is the meaningful check for the
        // exposed affordance.)
        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_copy_positive_prompt))
            .assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_copy_negative_prompt))
            .assertExists()
    }

    @Test
    fun zoomableImageOverlayExposesZoomInButtonWhenEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                ZoomableImageOverlay(
                    bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888),
                    onDismiss = {},
                    showScaleIndicator = true,
                    zoomInEnabled = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_zoom_in))
            .assertExists()
            .performClick()
    }

    @Test
    fun historyEmptyStateShowsGoToCreateCta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var createCount = 0

        val emptyFlow = Pager(PagingConfig(pageSize = 20)) {
            object : PagingSource<Int, HistoryItem>() {
                override suspend fun load(params: LoadParams<Int>): androidx.paging.PagingSource.LoadResult<Int, HistoryItem> = androidx.paging.PagingSource.LoadResult.Page(emptyList(), null, null)

                override fun getRefreshKey(state: PagingState<Int, HistoryItem>): Int? = null
            }
        }.flow

        composeRule.setContent {
            MaterialTheme {
                val pagedItems = emptyFlow.collectAsLazyPagingItems()
                ModelRunHistoryPage(
                    historyFilter = HistoryFilter(),
                    currentModelId = null,
                    pagedItems = pagedItems,
                    totalCount = 0,
                    isSelectionMode = false,
                    selectedIds = emptySet(),
                    isBatchSaving = false,
                    onFilterChange = {},
                    onShowFilterSheet = {},
                    onItemClick = {},
                    onItemLongClick = {},
                    onExitSelection = {},
                    onToggleSelectAll = {},
                    onBatchSave = {},
                    onBatchDelete = {},
                    onGoCreate = { createCount++ },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.asset_go_create))
            .assertExists()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, createCount)
        }
    }

    @Test
    fun generationParamsDialogExposesSetAsModelDefaultButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                GenerationParamsDialog(
                    title = "Generation parameters",
                    params = previewParameters(),
                    modelId = "dream-shaper-8",
                    displayMode = GenerationMode.TXT2IMG,
                    showImg2imgButton = false,
                    onSetAsModelDefaults = {},
                    onShare = {},
                    onSendToImg2img = {},
                    onReproduce = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // AC-06/AC-07: the "use these settings as this model's defaults" affordance
        // is present and carries a human-readable label for assistive technology.
        composeRule.onNodeWithText(context.getString(R.string.asset_set_model_defaults))
            .assertExists()
    }

    @Test
    fun generationQueueBarExposesOpenPanel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openCount = 0

        composeRule.setContent {
            MaterialTheme {
                GenerationQueueBar(
                    pendingCount = 2,
                    runningModelName = "dream-shaper-8",
                    onOpenPanel = { openCount++ },
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()

        // AC-07: the always-on queue summary exposes a labelled icon and opens the
        // full panel on activation.
        composeRule.onNodeWithContentDescription(context.getString(R.string.generation_queue_title))
            .assertExists()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, openCount)
        }
    }

    @Test
    fun generationQueueSheetExposesReorderAndRemoveButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val tasks = listOf(
            sampleTask("t1", "dream-shaper-8", "DreamShaper", "a cat on a rooftop"),
        )

        composeRule.setContent {
            MaterialTheme {
                GenerationQueueSheet(
                    tasks = tasks,
                    smartSortEnabled = false,
                    onSmartSortChange = {},
                    onRemove = {},
                    onMove = { _, _ -> },
                    onClear = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // AC-07: manual reordering uses explicit up/down buttons (reachable with
        // TalkBack / switch access) instead of a drag handle, plus a remove action.
        // A single queued task exposes one of each control.
        composeRule.onNodeWithContentDescription(context.getString(R.string.generation_queue_move_up))
            .assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.generation_queue_move_down))
            .assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.generation_queue_remove))
            .assertExists()
    }

    private fun sampleTask(id: String, modelId: String, modelName: String, prompt: String) = GenerationTask(
        id = id,
        modelId = modelId,
        modelName = modelName,
        prompt = prompt,
    )

    private fun previewParameters() = GenerationParameters(
        steps = 20,
        cfg = 7.5f,
        seed = 1234567890L,
        prompt = "a serene mountain lake at sunrise, highly detailed",
        negativePrompt = "blurry, low quality, watermark",
        generationTime = "3.4s",
        width = 512,
        height = 512,
        runOnCpu = true,
        scheduler = "dpm",
        mode = GenerationMode.TXT2IMG,
    )
}
