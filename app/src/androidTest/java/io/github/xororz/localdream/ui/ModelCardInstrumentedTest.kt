package io.github.xororz.localdream.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.ui.screens.ModelCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun modelIdIsVisibleAndCopyDoesNotOpenTheModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var copyCount = 0
        var openCount = 0
        val model = Model(
            id = "portrait_model_v1",
            name = "Portrait Model V1",
            description = "Portrait model",
            baseUrl = "",
            isDownloaded = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ModelCard(
                    model = model,
                    isSelected = false,
                    isSelectionMode = false,
                    onClick = { openCount++ },
                    onLongClick = {},
                    onCopyIdClick = { copyCount++ },
                )
            }
        }

        composeRule.onNodeWithText(model.id).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_model_id))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("copy-model-id-${model.id}").performClick()

        composeRule.runOnIdle {
            assertEquals(1, copyCount)
            assertEquals(0, openCount)
        }
    }
}
