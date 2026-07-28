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
            config = NativeBackendLaunchConfig(
                modelId = "test-model",
                backendType = backendType,
                width = 512,
                height = 512,
                listenOnAll = false,
                imageInputEnabled = imageInputEnabled,
                sdxlLowRam = sdxlLowRam,
                animaLowRam = true,
                animaSequentialDit = false,
            ),
            usesUnifiedCli = usesUnifiedCli,
        )
    }
}
