package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof that the creation (chat) screen restores and renders a
 * previously persisted conversation after a cold start. This does NOT require
 * model inference to succeed, so it runs on devices whose CPU cannot load the
 * bundled MNN model (e.g. OnePlus 6).
 *
 * The screen is hosted in a lightweight ComponentActivity so the seed and the
 * screen run in the SAME process and share the DataStore instance (no
 * cross-process staleness). We inject LocalActivityResultRegistryOwner and
 * LocalContext so rememberLauncherForActivityResult and the screen's
 * GenerationPreferences resolve.
 */
@RunWith(AndroidJUnit4::class)
class ChatHistoryRestoreInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seededChatHistoryIsRestoredAndRendered() {
        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        // A real on-disk image file so the Image message survives restore
        // (chatHistoryFromJson drops images whose file no longer exists).
        val imageFile = File(appContext.filesDir, "seed_chat_history_image.png").apply {
            if (!exists()) {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, outputStream())
            }
        }

        val seedPrompt = "SEED_PROMPT_RESTORE_TEST_42"
        val seedError = "SEED_ERROR_MSG_99"

        val json = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("t", "u")
                    put("p", seedPrompt)
                },
            )
            put(
                JSONObject().apply {
                    put("t", "i")
                    put("f", imageFile.absolutePath)
                    put("mn", "SEED_MODEL_X")
                    put("w", 512)
                    put("h", 512)
                    put("s", 1234567890L)
                },
            )
            put(
                JSONObject().apply {
                    put("t", "e")
                    put("m", seedError)
                },
            )
        }.toString()

        // Seed using the exact same DataStore the screen reads. Because the
        // composable runs in the same (test) process, this shares the DataStore
        // instance and file with the screen under test.
        runBlocking { GenerationPreferences(appContext).saveChatHistoryJson(json) }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides appContext,
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                androidx.compose.material3.MaterialTheme {
                    ChatGenerationScreen(navController = rememberNavController())
                }
            }
        }

        // Restore is an async LaunchedEffect (DataStore read); poll until the
        // seeded prompt is rendered, then assert the whole conversation.
        var restored = false
        repeat(40) {
            try {
                composeRule.onNodeWithText(seedPrompt).assertExists()
                restored = true
                return@repeat
            } catch (_: AssertionError) {
                Thread.sleep(200)
            }
        }
        check(restored) { "Seeded creation history was not restored and rendered." }

        // The whole conversation (prompt + error) restored.
        composeRule.onNodeWithText(seedPrompt).assertExists()
        composeRule.onNodeWithText(seedError).assertExists()

        // The generated image is restored too, but RevealableImage keeps it
        // concealed behind a tap-to-reveal gate by default. Reveal it like a
        // real user would, then assert the image is actually rendered.
        composeRule
            .onNodeWithContentDescription(appContext.getString(R.string.reveal_image))
            .performClick()
        var imageRevealed = false
        repeat(20) {
            try {
                composeRule
                    .onNodeWithContentDescription(
                        appContext.getString(R.string.chat_generation_generated_image),
                    ).assertExists()
                imageRevealed = true
                return@repeat
            } catch (_: AssertionError) {
                Thread.sleep(100)
            }
        }
        check(imageRevealed) { "Restored generated image was not revealed/rendered." }
    }
}
