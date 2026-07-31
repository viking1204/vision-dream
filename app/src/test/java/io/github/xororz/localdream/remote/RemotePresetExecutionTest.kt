package io.github.xororz.localdream.remote

import io.github.xororz.localdream.data.HtpDynamicPartitioning
import io.github.xororz.localdream.data.HtpPowerMode
import io.github.xororz.localdream.data.PerformancePresetEngineConfig
import io.github.xororz.localdream.data.PresetSnapshot
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.remoteBackendExtras
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePresetExecutionTest {
    @Test
    fun `round trip preserves an immutable v1 snapshot and its engine values`() {
        val expected = RemotePresetExecution(
            snapshot = snapshot(validConfig()),
            engineConfig = engineConfig(),
        )

        assertEquals(expected, RemotePresetExecution.fromJson(expected.toJson()))
    }

    @Test
    fun `rejects v1 snapshot whose transmitted engine differs from its config`() {
        val body = RemotePresetExecution(snapshot(validConfig()), engineConfig()).toJson()
        body.getJSONObject("engine").put("sdxl_low_ram", false)

        assertNull(RemotePresetExecution.fromJson(body))
    }

    @Test
    fun `round trip preserves all immutable v2 engine fields`() {
        val expected = RemotePresetExecution(
            snapshot = snapshot(validV2Config()),
            engineConfig = PerformancePresetEngineConfig(
                sdxlLowRam = true,
                animaLowRam = false,
                animaSequentialDit = true,
                cpuClipThreads = 6,
                htpPowerMode = HtpPowerMode.POWER_SAVER,
                htpDynamicPartitioning = HtpDynamicPartitioning.ENABLED,
            ),
        )

        val body = expected.toJson()
        assertEquals(
            setOf(
                "sdxl_low_ram",
                "anima_low_ram",
                "anima_sequential_dit",
                "cpu_clip_threads",
                "htp_power_mode",
                "htp_dynamic_partitioning",
            ),
            body.getJSONObject("engine").keys().asSequence().toSet(),
        )
        assertEquals(expected, RemotePresetExecution.fromJson(body))
    }

    @Test
    fun `rejects v2 snapshot when any required engine field is missing or differs`() {
        val expected = RemotePresetExecution(
            snapshot = snapshot(validV2Config()),
            engineConfig = PerformancePresetEngineConfig(
                sdxlLowRam = true,
                animaLowRam = false,
                animaSequentialDit = true,
                cpuClipThreads = 6,
                htpPowerMode = HtpPowerMode.POWER_SAVER,
                htpDynamicPartitioning = HtpDynamicPartitioning.ENABLED,
            ),
        )

        val missing = expected.toJson()
        missing.getJSONObject("engine").remove("htp_power_mode")
        assertNull(RemotePresetExecution.fromJson(missing))

        val mismatched = expected.toJson()
        mismatched.getJSONObject("engine").put("cpu_clip_threads", 4)
        assertNull(RemotePresetExecution.fromJson(mismatched))
    }

    @Test
    fun `v2 engine maps every native value to its BackendService extra`() {
        val extras = PerformancePresetEngineConfig(
            sdxlLowRam = true,
            animaLowRam = false,
            animaSequentialDit = true,
            cpuClipThreads = 6,
            htpPowerMode = HtpPowerMode.POWER_SAVER,
            htpDynamicPartitioning = HtpDynamicPartitioning.ENABLED,
        ).remoteBackendExtras()

        assertEquals(
            mapOf(
                BackendService.EXTRA_SDXL_LOW_RAM to true,
                BackendService.EXTRA_ANIMA_LOW_RAM to false,
                BackendService.EXTRA_ANIMA_SEQUENTIAL_DIT to true,
                BackendService.EXTRA_CPU_CLIP_THREADS to 6,
                BackendService.EXTRA_HTP_POWER_MODE to "POWER_SAVER",
                BackendService.EXTRA_HTP_DYNAMIC_PARTITIONING to "ENABLED",
            ),
            extras,
        )
    }

    @Test
    fun `legacy snapshot requires explicitly captured engine values`() {
        val execution = RemotePresetExecution(snapshot("{}"), engineConfig())
        val parsed = RemotePresetExecution.fromJson(execution.toJson())

        assertEquals(execution, parsed)
        assertNull(
            RemotePresetExecution.fromJson(
                JSONObject().put(
                    "preset_snapshot",
                    execution.toJson().getJSONObject("preset_snapshot"),
                ),
            ),
        )
    }

    @Test
    fun `select request keeps optional snapshot separate from model identity`() {
        val execution = RemotePresetExecution(snapshot(validConfig()), engineConfig())
        val body = RemoteApiClient("127.0.0.1")
            .selectRequestBody("model-a", 1024, 1024, execution)

        assertEquals("model-a", body.getString("model_id"))
        assertTrue(body.has("preset_snapshot"))
        assertEquals(7L, body.getJSONObject("preset_snapshot").getLong("revision"))
    }

    private fun snapshot(configJson: String) = PresetSnapshot(
        presetId = "preset-1",
        name = "Balanced",
        selector = "DEFAULT",
        configJson = configJson,
        revision = 7,
    )

    private fun engineConfig() = PerformancePresetEngineConfig(
        sdxlLowRam = true,
        animaLowRam = false,
        animaSequentialDit = true,
    )

    private fun validConfig() = """
        {"schemaVersion":1,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":true}}
    """.trimIndent()

    private fun validV2Config() = """
        {"schemaVersion":2,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":true,"cpuClipThreads":6,"htpPowerMode":"POWER_SAVER","htpDynamicPartitioning":"ENABLED"}}
    """.trimIndent()
}
