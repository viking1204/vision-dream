package io.github.xororz.localdream.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xororz.localdream.data.GenerationPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the creation-screen message windowing bug:
 *
 * The visible message window used to be computed with
 * `remember(messages, visibleMessageCount)`, keyed on the *identity* of the
 * SnapshotStateList the session owns. That identity never changes when a
 * message is appended or removed, so once the conversation grew past
 * MAX_VISIBLE_MESSAGES the windowed view froze on its first `takeLast(N)`
 * snapshot. New results and deletions only surfaced after leaving and
 * re-entering the screen — exactly the symptom reported by the user.
 *
 * The fix switches the window to `derivedStateOf`, which tracks the snapshot
 * reads performed inside the block, so the window invalidates on every
 * add/remove/swap.
 *
 * This test seeds FEWER than MAX_VISIBLE_MESSAGES messages so the window in
 * both the old and new code is the full (<= 10) list, then mutates the live
 * session and asserts the window updates in place. The old code froze a
 * `toList()` copy at first composition, so an appended message would never
 * appear and a deleted message would never leave — this test fails against
 * the unfixed code and passes with the derivedStateOf fix. No model
 * inference and no navigation are required.
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageWindowRefreshInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun seedHistory(count: Int) {
        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val json = JSONArray().apply {
            for (i in 0 until count) {
                put(
                    JSONObject().apply {
                        put("t", "u")
                        put("p", "MSG_%02d".format(i))
                    },
                )
            }
        }.toString()
        runBlocking { GenerationPreferences(appContext).saveChatHistoryJson(json) }
    }

    private fun clearSession() {
        // Safe before any composition: no readers exist yet.
        ChatGenerationSession.historyRestoredState.value = false
        ChatGenerationSession.messages.clear()
    }

    private fun setScreen() {
        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
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
    }

    private fun waitUntilSessionSize(size: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ChatGenerationSession.messages.size == size) return
            Thread.sleep(100)
        }
        check(ChatGenerationSession.messages.size == size) {
            "Session did not reach $size messages (got ${ChatGenerationSession.messages.size})."
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 4000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithText(text).assertExists()
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            }
        }
        composeRule.onNodeWithText(text).assertExists()
    }

    private fun waitForGone(text: String, timeoutMs: Long = 4000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val exists = runCatching { composeRule.onNodeWithText(text).assertExists() }.isSuccess
            if (!exists) return
            Thread.sleep(100)
        }
        composeRule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun windowReflectsLiveAddAndDeleteInPlace() {
        // 5 messages -> window is the full list in both old and new code.
        seedHistory(5)
        clearSession()
        setScreen()

        waitUntilSessionSize(5)
        // All five are rendered after restore.
        waitForText("MSG_00")
        waitForText("MSG_02")
        waitForText("MSG_04")

        // ---- DELETE: remove a visible message on the live session ----
        var targetId = -1L
        composeRule.runOnUiThread {
            targetId = ChatGenerationSession.messages.first {
                it is ChatGenerationMessage.User && it.prompt == "MSG_02"
            }.id
        }
        check(targetId != -1L) { "Could not locate MSG_02 in the session." }
        composeRule.runOnUiThread {
            ChatGenerationSession.messages.removeAll { it.id == targetId }
        }
        // Fix: the window recomputes and MSG_02 disappears at once.
        // Old code: the frozen `toList()` copy still contains MSG_02.
        waitForGone("MSG_02")
        // The neighbours remain.
        waitForText("MSG_01")
        waitForText("MSG_03")

        // ---- ADD: append a new message on the live session ----
        composeRule.runOnUiThread {
            ChatGenerationSession.messages += ChatGenerationMessage.User(
                999L,
                "MSG_NEW_APPEND_XYZ",
            )
        }
        // Fix: the window recomputes and the new message appears at once.
        // Old code: the frozen copy never gains the new message.
        waitForText("MSG_NEW_APPEND_XYZ")

        // Remove it again to leave the conversation clean.
        composeRule.runOnUiThread {
            ChatGenerationSession.messages.removeAll {
                it is ChatGenerationMessage.User && it.prompt == "MSG_NEW_APPEND_XYZ"
            }
        }
        waitForGone("MSG_NEW_APPEND_XYZ")
    }
}
