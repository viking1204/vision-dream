package io.github.xororz.localdream.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBackendCommandFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy upscaler includes both QNN runtime libraries`() {
        val command = build(
            backendType = BackendService.BACKEND_TYPE_UPSCALER,
            usesUnifiedCli = false,
        )

        assertTrue(command.contains("--upscaler_mode"))
        assertEquals("libQnnHtp.so", File(command[command.indexOf("--backend") + 1]).name)
        assertEquals(
            "libQnnSystem.so",
            File(command[command.indexOf("--system_library") + 1]).name,
        )
        assertFalse(command.contains("--lib_dir"))
    }

    @Test
    fun `legacy SDXL uses legacy file arguments and low ram mode`() {
        val modelsDir = temporaryFolder.newFolder("sdxl")
        File(modelsDir, "clip.mnn").createNewFile()

        val command = build(
            backendType = "sdxl",
            usesUnifiedCli = false,
            modelsDir = modelsDir,
        )

        assertTrue(command.containsAll(listOf("--clip", "--unet", "--vae_decoder", "--sdxl")))
        assertTrue(command.contains("--use_cpu_clip"))
        assertTrue(command.contains("--lowram"))
        assertFalse(command.contains("--type"))
    }

    @Test
    fun `unified SD15 disables missing image input and uses model directory`() {
        val modelsDir = temporaryFolder.newFolder("sd15")
        val command = build(
            backendType = "sd15npu",
            usesUnifiedCli = true,
            modelsDir = modelsDir,
            imageInputEnabled = false,
        )

        assertEquals("sd15npu", command[command.indexOf("--type") + 1])
        assertEquals(modelsDir.absolutePath, command[command.indexOf("--model_dir") + 1])
        assertTrue(command.containsAll(listOf("--lib_dir", "--no_img2img")))
        assertFalse(command.contains("--backend"))
    }

    @Test
    fun `snapshot engine flags control the native command field by field`() {
        val modelsDir = temporaryFolder.newFolder("snapshot-sdxl")
        val command = build(
            backendType = "sdxl",
            usesUnifiedCli = true,
            modelsDir = modelsDir,
            sdxlLowRam = false,
        )

        assertFalse(command.contains("--lowram"))
    }

    @Test
    fun `v2 options map to native CPU CLIP and HTP command arguments`() {
        val command = NativeBackendCommandFactory.build(
            executableFile = File("/native/libstable_diffusion_core.so"),
            modelsDir = temporaryFolder.newFolder("v2-sdxl"),
            runtimeDir = temporaryFolder.newFolder("v2-runtime"),
            config = launchConfig(
                backendType = "sdxl",
                cpuClipThreads = 6,
                htpPowerMode = io.github.xororz.localdream.data.HtpPowerMode.POWER_SAVER,
                htpDynamicPartitioning = io.github.xororz.localdream.data.HtpDynamicPartitioning.ENABLED,
            ),
            usesUnifiedCli = true,
        )

        assertEquals("6", command[command.indexOf("--cpu_clip_threads") + 1])
        assertEquals("power_saver", command[command.indexOf("--htp_power_mode") + 1])
        assertEquals("enabled", command[command.indexOf("--htp_dynamic_partitioning") + 1])
    }

    @Test
    fun `portable preset omits CPU CLIP override for Anima`() {
        val command = NativeBackendCommandFactory.build(
            executableFile = File("/native/libstable_diffusion_core.so"),
            modelsDir = temporaryFolder.newFolder("v2-anima"),
            runtimeDir = temporaryFolder.newFolder("v2-anima-runtime"),
            config = launchConfig(
                backendType = "anima",
                cpuClipThreads = 8,
                htpPowerMode = io.github.xororz.localdream.data.HtpPowerMode.PERFORMANCE,
                htpDynamicPartitioning = io.github.xororz.localdream.data.HtpDynamicPartitioning.ENABLED,
            ),
            usesUnifiedCli = true,
        )

        assertFalse(command.contains("--cpu_clip_threads"))
        assertEquals("performance", command[command.indexOf("--htp_power_mode") + 1])
        assertEquals("enabled", command[command.indexOf("--htp_dynamic_partitioning") + 1])
    }

    @Test
    fun `portable preset omits HTP overrides for CPU backend`() {
        val command = NativeBackendCommandFactory.build(
            executableFile = File("/native/libstable_diffusion_core.so"),
            modelsDir = temporaryFolder.newFolder("v2-cpu"),
            runtimeDir = temporaryFolder.newFolder("v2-cpu-runtime"),
            config = launchConfig(
                backendType = "sd15cpu",
                cpuClipThreads = 8,
                htpPowerMode = io.github.xororz.localdream.data.HtpPowerMode.POWER_SAVER,
                htpDynamicPartitioning = io.github.xororz.localdream.data.HtpDynamicPartitioning.ENABLED,
            ),
            usesUnifiedCli = true,
        )

        assertEquals("8", command[command.indexOf("--cpu_clip_threads") + 1])
        assertFalse(command.contains("--htp_power_mode"))
        assertFalse(command.contains("--htp_dynamic_partitioning"))
    }

    private fun build(
        backendType: String,
        usesUnifiedCli: Boolean,
        modelsDir: File = temporaryFolder.newFolder("models-${System.nanoTime()}"),
        imageInputEnabled: Boolean = true,
        sdxlLowRam: Boolean = true,
    ): MutableList<String> {
        val runtimeDir = temporaryFolder.newFolder("runtime-${System.nanoTime()}")
        return NativeBackendCommandFactory.build(
            executableFile = File("/native/libstable_diffusion_core.so"),
            modelsDir = modelsDir,
            runtimeDir = runtimeDir,
            config = launchConfig(
                backendType = backendType,
                imageInputEnabled = imageInputEnabled,
                sdxlLowRam = sdxlLowRam,
            ),
            usesUnifiedCli = usesUnifiedCli,
        )
    }

    private fun launchConfig(
        backendType: String,
        sdxlLowRam: Boolean = true,
        imageInputEnabled: Boolean = true,
        cpuClipThreads: Int? = null,
        htpPowerMode: io.github.xororz.localdream.data.HtpPowerMode? = null,
        htpDynamicPartitioning: io.github.xororz.localdream.data.HtpDynamicPartitioning? = null,
    ) = NativeBackendLaunchConfig(
        modelId = "test-model",
        backendType = backendType,
        width = 512,
        height = 512,
        listenOnAll = false,
        imageInputEnabled = imageInputEnabled,
        sdxlLowRam = sdxlLowRam,
        animaLowRam = true,
        animaSequentialDit = false,
        cpuClipThreads = cpuClipThreads,
        htpPowerMode = htpPowerMode,
        htpDynamicPartitioning = htpDynamicPartitioning,
    )
}
