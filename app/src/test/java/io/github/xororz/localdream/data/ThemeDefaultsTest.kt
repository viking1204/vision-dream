package io.github.xororz.localdream.data

import io.github.xororz.localdream.ui.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeDefaultsTest {
    @Test
    fun `new installs use the vision palette instead of dynamic legacy colors`() {
        val state = ThemeState()

        assertFalse(state.dynamicColor)
        assertEquals(ThemePreset.VISION, state.preset)
    }
}
