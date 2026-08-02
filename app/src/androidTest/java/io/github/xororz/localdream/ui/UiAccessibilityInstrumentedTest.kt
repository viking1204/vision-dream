package io.github.xororz.localdream.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.ui.components.GenerationParamsDialog
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.ui.screens.GenerationParameters
import io.github.xororz.localdream.ui.screens.ModelRunHistoryPage
import kotlinx.coroutines.flow.flowOf
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
        var positiveCopyCount = 0
        var negativeCopyCount = 0

        composeRule.setContent {
            MaterialTheme {
                GenerationParamsDialog(
                    title = "Generation parameters",
                    params = previewParameters(),
                    modelId = "dream-shaper-8",
                    displayMode = GenerationMode.TXT2IMG,
                    showImg2imgButton = false,
                    onCopyPrompt = { positiveCopyCount++ },
                    onCopyNegativePrompt = { negativeCopyCount++ },
                    onShare = {},
                    onSendToImg2img = {},
                    onReproduce = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_copy_positive_prompt))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_copy_negative_prompt))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, positiveCopyCount)
            assertEquals(1, negativeCopyCount)
        }
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

        composeRule.onNodeWithContentDescription(context.getString(R.string.asset_zoom_in))
            .assertIsDisplayed()
            .performClick()
    }

    @Test
    fun historyEmptyStateShowsGoToCreateCta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var createCount = 0

        composeRule.setContent {
            MaterialTheme {
                val pagedItems = flowOf(PagingData.empty<HistoryItem>()).collectAsLazyPagingItems()
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

        composeRule.onNodeWithText(context.getString(R.string.asset_go_create))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, createCount)
        }
    }

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
