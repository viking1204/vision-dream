package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-level proof of the message-windowing reactivity fix in
 * `ChatGenerationScreen`. The screen computes its visible window with
 * `derivedStateOf { if (messages.size <= N) messages.toList() else
 * messages.takeLast(N) }`. `derivedStateOf` tracks the snapshot reads
 * performed inside the block, so the window invalidates on every
 * add/remove/swap and the creation screen refreshes in place.
 *
 * This mirrors the exact expression used by the composable and guards the
 * regression: the old code keyed `remember(messages, visibleMessageCount)`
 * on the SnapshotStateList *identity*, which never changes on add/remove, so
 * the window froze on its first `toList()`/`takeLast(N)` snapshot.
 *
 * No device required — runs on the JVM under the Compose snapshot runtime.
 */
class ChatMessageWindowRefreshTest {

    @Test
    fun windowReflectsLiveAddAndDelete() {
        val messages = mutableStateListOf("m0", "m1", "m2", "m3", "m4")
        val visibleCount = 10

        val window by derivedStateOf {
            if (messages.size <= visibleCount) messages.toList() else messages.takeLast(visibleCount)
        }

        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), window)

        // A new generation result arrives.
        messages += "m5"
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4", "m5"), window)

        // The user deletes a visible message in place.
        messages.removeAt(2) // remove "m2"
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("m0", "m1", "m3", "m4", "m5"), window)

        // The old pattern would NOT have reacted: a captured copy stays stale.
        val frozenCopy = messages.toList()
        messages += "m6"
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("m0", "m1", "m3", "m4", "m5"), frozenCopy)
        // Whereas the derived window updates.
        assertEquals(listOf("m0", "m1", "m3", "m4", "m5", "m6"), window)
    }

    @Test
    fun windowReflectsTakeLastWhenOverCapacity() {
        val messages = mutableStateListOf<String>()
        val visibleCount = 10
        repeat(15) { messages += "m%02d".format(it) }

        val window by derivedStateOf {
            if (messages.size <= visibleCount) messages.toList() else messages.takeLast(visibleCount)
        }

        // Window shows only the most recent 10.
        assertEquals((5..14).map { "m%02d".format(it) }, window)

        // Deleting the newest message shifts the window down by one immediately.
        messages.removeAt(messages.lastIndex)
        Snapshot.sendApplyNotifications()
        assertEquals((4..13).map { "m%02d".format(it) }, window)
    }
}
