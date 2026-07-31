package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetConfigTest {
    @Test
    fun `v2 encoder round trips every editable field`() {
        val engine = PerformancePresetEngineConfig(
            sdxlLowRam = true,
            animaLowRam = false,
            animaSequentialDit = true,
            cpuClipThreads = 6,
            htpPowerMode = HtpPowerMode.ADJUST_UP_DOWN,
            htpDynamicPartitioning = HtpDynamicPartitioning.ENABLED,
        )

        val encoded = PerformancePresetConfig.encodeV2(engine)

        assertEquals(PresetConfigParseStatus.SUPPORTED, PerformancePresetConfig.parse(encoded).status)
        assertEquals(engine, PerformancePresetConfig.parse(encoded).engine)
    }

    @Test
    fun parseAcceptsOnlyTheExactV1EngineSchema() {
        val parsed = PerformancePresetConfig.parse(validConfig())

        assertEquals(PresetConfigParseStatus.SUPPORTED, parsed.status)
        assertEquals(true, parsed.engine?.sdxlLowRam)
        assertEquals(false, parsed.engine?.animaLowRam)
        assertEquals(true, parsed.engine?.animaSequentialDit)
        assertEquals(null, parsed.engine?.cpuClipThreads)
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
            PerformancePresetConfig.parse("{\"schemaVersion\":3,\"engine\":{\"sdxlLowRam\":true,\"animaLowRam\":false,\"animaSequentialDit\":true}}").status,
        )
        assertEquals(PresetConfigParseStatus.INVALID, PerformancePresetConfig.parse("not json").status)
        assertTrue(PerformancePresetConfig.parse(validConfig()).isSupported)
    }

    @Test
    fun parseAcceptsExactV2AndPreservesItsNativeLaunchOptions() {
        val parsed = PerformancePresetConfig.parse(
            """{"schemaVersion":2,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":true,"cpuClipThreads":6,"htpPowerMode":"ADJUST_UP_DOWN","htpDynamicPartitioning":"ENABLED"}}""",
        )

        assertEquals(PresetConfigParseStatus.SUPPORTED, parsed.status)
        assertEquals(6, parsed.engine?.cpuClipThreads)
        assertEquals(HtpPowerMode.ADJUST_UP_DOWN, parsed.engine?.htpPowerMode)
        assertEquals(HtpDynamicPartitioning.ENABLED, parsed.engine?.htpDynamicPartitioning)
    }

    @Test
    fun parseRejectsV2UnknownKeysInvalidEnumsAndOutOfRangeThreads() {
        assertEquals(
            PresetConfigParseStatus.INVALID,
            PerformancePresetConfig.parse(validV2Config().replace("\"cpuClipThreads\":4", "\"cpuClipThreads\":0")).status,
        )
        assertEquals(
            PresetConfigParseStatus.INVALID,
            PerformancePresetConfig.parse(validV2Config().replace("\"PERFORMANCE\"", "\"TURBO\"")).status,
        )
        assertEquals(
            PresetConfigParseStatus.INVALID,
            PerformancePresetConfig.parse(validV2Config().replace("\"AUTO\"", "\"AUTO\",\"extra\":true")).status,
        )
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

    private fun validV2Config() = """{"schemaVersion":2,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":true,"cpuClipThreads":4,"htpPowerMode":"PERFORMANCE","htpDynamicPartitioning":"AUTO"}}"""
}
