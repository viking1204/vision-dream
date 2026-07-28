package io.github.xororz.localdream.remote

import io.github.xororz.localdream.data.PerformancePresetEngineConfig
import io.github.xororz.localdream.data.PresetSnapshot
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
}
