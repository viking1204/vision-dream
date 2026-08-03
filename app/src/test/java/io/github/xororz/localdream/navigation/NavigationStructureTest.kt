package io.github.xororz.localdream.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun `studio exposes six unique top-level destinations`() {
        assertEquals(Screen.Workbench.route, StudioTopLevelRoutes.first())
        assertEquals(6, StudioTopLevelRoutes.size)
        assertEquals(StudioTopLevelRoutes.size, StudioTopLevelRoutes.toSet().size)
    }
}
