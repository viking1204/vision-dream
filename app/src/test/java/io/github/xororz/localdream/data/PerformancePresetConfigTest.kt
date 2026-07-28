package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetConfigTest {
    @Test
    fun parseAcceptsOnlyTheExactV1EngineSchema() {
        val parsed = PerformancePresetConfig.parse(validConfig())

        assertEquals(PresetConfigParseStatus.SUPPORTED, parsed.status)
        assertEquals(true, parsed.engine?.sdxlLowRam)
        assertEquals(false, parsed.engine?.animaLowRam)
        assertEquals(true, parsed.engine?.animaSequentialDit)
    }

    @Test
    fun parseClassifiesOnlyEmptyObjectAsLegacyCompatibility() {
        assertEquals(PresetConfigParseStatus.LEGACY_COMPATIBILITY, PerformancePresetConfig.parse("{}").status)
        assertEquals(PresetConfigParseStatus.INVALID, PerformancePresetConfig.parse("{ }").status)
    }

    @Test
    fun parseRejectsUnknownFieldsMissingFieldsAndNonBooleans() {
        assertEquals(PresetConfigParseStatus.INVALID, PerformancePresetConfig.parse(validConfig().replace("}", ",\"extra\":true}")).status)
        assertEquals(
            PresetConfigParseStatus.INVALID,
            PerformancePresetConfig.parse("{\"schemaVersion\":1,\"engine\":{\"sdxlLowRam\":true,\"animaLowRam\":false}}").status,
        )
        assertEquals(
            PresetConfigParseStatus.INVALID,
            PerformancePresetConfig.parse("{\"schemaVersion\":1,\"engine\":{\"sdxlLowRam\":\"true\",\"animaLowRam\":false,\"animaSequentialDit\":true}}").status,
        )
        assertFalse(PerformancePresetConfig.parse(validConfig()).engine == null)
    }

    @Test
    fun parseSeparatesUnsupportedVersionsFromMalformedJson() {
        assertEquals(
            PresetConfigParseStatus.UNSUPPORTED_VERSION,
            PerformancePresetConfig.parse("{\"schemaVersion\":2,\"engine\":{\"sdxlLowRam\":true,\"animaLowRam\":false,\"animaSequentialDit\":true}}").status,
        )
        assertEquals(PresetConfigParseStatus.INVALID, PerformancePresetConfig.parse("not json").status)
        assertTrue(PerformancePresetConfig.parse(validConfig()).isSupported)
    }

    @Test
    fun compatibilityFallbackHasNoEngineOverrideButRemainsExecutable() {
        assertEquals(
            null,
            PerformancePresetConfig.parse("{}").requireExecutableSnapshot(isCompatibilityFallback = true),
        )
        try {
            PerformancePresetConfig.parse("{}").requireExecutableSnapshot(isCompatibilityFallback = false)
            throw AssertionError("a user preset must not execute legacy config")
        } catch (_: IllegalArgumentException) {
            // Expected: only the immutable compatibility fallback retains legacy execution.
        }
    }

    private fun validConfig() = """
        {"schemaVersion":1,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":true}}
    """.trimIndent()
}
