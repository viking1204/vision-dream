package io.github.xororz.localdream.openai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiBackgroundAccessTest {
    @Test
    fun oplusFamilyUsesVendorBackgroundControl() {
        assertTrue(requiresVendorBackgroundControl("OnePlus"))
        assertTrue(requiresVendorBackgroundControl("OPPO"))
        assertTrue(requiresVendorBackgroundControl(" realme "))
        assertFalse(requiresVendorBackgroundControl("Google"))
        assertFalse(requiresVendorBackgroundControl("Samsung"))
    }
}
