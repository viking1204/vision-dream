package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetOriginTest {
    @Test
    fun `persisted values round trip and unknown values fail closed to local`() {
        AssetOrigin.entries.forEach { origin ->
            assertEquals(
                origin,
                AssetOrigin.fromPersistedValue(origin.persistedValue),
            )
        }
        assertEquals(AssetOrigin.LOCAL_APP, AssetOrigin.fromPersistedValue(null))
        assertEquals(
            AssetOrigin.LOCAL_APP,
            AssetOrigin.fromPersistedValue("future_source"),
        )
    }
}
