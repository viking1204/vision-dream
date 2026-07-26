package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetLayoutModeTest {
    @Test
    fun persistedModesRoundTrip() {
        AssetLayoutMode.entries.forEach { mode ->
            assertEquals(mode, AssetLayoutMode.fromPersisted(mode.name))
        }
    }

    @Test
    fun unknownPersistedModeFallsBackToWaterfall() {
        assertEquals(AssetLayoutMode.WATERFALL, AssetLayoutMode.fromPersisted("UNKNOWN"))
        assertEquals(AssetLayoutMode.WATERFALL, AssetLayoutMode.fromPersisted(null))
    }
}
