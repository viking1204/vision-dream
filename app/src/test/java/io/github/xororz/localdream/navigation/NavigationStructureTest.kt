package io.github.xororz.localdream.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun `studio exposes five unique creation-first destinations`() {
        assertEquals(Screen.Workbench.route, StudioTopLevelRoutes.first())
        assertEquals(5, StudioTopLevelRoutes.size)
        assertEquals(StudioTopLevelRoutes.size, StudioTopLevelRoutes.toSet().size)
    }
}
