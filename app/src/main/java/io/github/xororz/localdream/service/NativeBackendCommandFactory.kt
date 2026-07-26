package io.github.xororz.localdream.service

import android.util.Log
import java.io.File

internal data class NativeBackendLaunchConfig(
    val modelId: String,
    val backendType: String,
    val width: Int,
    val height: Int,
    val listenOnAll: Boolean,
    val imageInputEnabled: Boolean,
    val sdxlLowRam: Boolean,
    val animaLowRam: Boolean,
    val animaSequentialDit: Boolean,
)

/**
 * Builds native backend commands for both the current unified CLI and the
 * legacy prebuilt core bundled with existing releases.
 */
internal object NativeBackendCommandFactory {
    private const val TAG = "BackendService"

    fun build(
        executableFile: File,
        modelsDir: File,
        runtimeDir: File,
        config: NativeBackendLaunchConfig,
        usesUnifiedCli: Boolean,
    ): MutableList<String> = if (usesUnifiedCli) {
        buildUnified(executableFile, modelsDir, runtimeDir, config)
    } else {
        buildLegacy(executableFile, modelsDir, runtimeDir, config)
    }

    private fun buildUnified(
        executableFile: File,
        modelsDir: File,
        runtimeDir: File,
        config: NativeBackendLaunchConfig,
    ): MutableList<String> {
        if (config.backendType == BackendService.BACKEND_TYPE_UPSCALER) {
            return mutableListOf(
                executableFile.absolutePath,
                "--upscaler_mode",
                "--lib_dir",
                runtimeDir.absolutePath,
                "--port",
                "8081",
            )
        }
        val command = mutableListOf(
            executableFile.absolutePath,
            "--type",
            config.backendType,
            "--model_dir",
            modelsDir.absolutePath,
            "--port",
            "8081",
        )
        if (config.backendType != "sd15cpu") {
            command += listOf("--lib_dir", runtimeDir.absolutePath)
        }
        if (!config.imageInputEnabled) {
            command += "--no_img2img"
        }
        appendCommonOptions(command, modelsDir, config, unified = true)
        return command
    }

    private fun buildLegacy(
        executableFile: File,
        modelsDir: File,
        runtimeDir: File,
        config: NativeBackendLaunchConfig,
    ): MutableList<String> {
        if (config.backendType == BackendService.BACKEND_TYPE_UPSCALER) {
            return mutableListOf(
                executableFile.absolutePath,
                "--upscaler_mode",
                "--backend",
                File(runtimeDir, "libQnnHtp.so").absolutePath,
                "--system_library",
                File(runtimeDir, "libQnnSystem.so").absolutePath,
                "--port",
                "8081",
            )
        }
        require(config.backendType != "anima") {
            "Bundled native core does not support Anima"
        }

        val isCpu = config.backendType == "sd15cpu"
        val useCpuClip = isCpu ||
            (!File(modelsDir, "clip.bin").isFile && File(modelsDir, "clip.mnn").isFile)
        val extension = if (isCpu) "mnn" else "bin"
        val command = mutableListOf(
            executableFile.absolutePath,
            "--clip",
            File(modelsDir, if (useCpuClip) "clip.mnn" else "clip.bin").absolutePath,
            "--unet",
            File(modelsDir, "unet.$extension").absolutePath,
            "--vae_decoder",
            File(modelsDir, "vae_decoder.$extension").absolutePath,
            "--tokenizer",
            File(modelsDir, "tokenizer.json").absolutePath,
            "--port",
            "8081",
            "--text_embedding_size",
            if (config.modelId == "sd21") "1024" else "768",
        )
        if (isCpu) {
            command += "--cpu"
        } else {
            command += listOf(
                "--backend",
                File(runtimeDir, "libQnnHtp.so").absolutePath,
                "--system_library",
                File(runtimeDir, "libQnnSystem.so").absolutePath,
            )
        }
        if (config.imageInputEnabled) {
            command += listOf(
                "--vae_encoder",
                File(modelsDir, "vae_encoder.$extension").absolutePath,
            )
        }
        if (useCpuClip && !isCpu) {
            command += "--use_cpu_clip"
        }
        appendCommonOptions(command, modelsDir, config, unified = false)
        if (config.listenOnAll) {
            Log.w(TAG, "Legacy native core cannot expose port 8081 on the LAN")
        }
        return command
    }

    private fun appendCommonOptions(
        command: MutableList<String>,
        modelsDir: File,
        config: NativeBackendLaunchConfig,
        unified: Boolean,
    ) {
        if (File(modelsDir, "V_PRED").exists()) {
            command += "--use_v_pred"
        }
        if (config.backendType == "sd15npu" &&
            (config.width != 512 || config.height != 512)
        ) {
            val squarePatch = File(modelsDir, "${config.width}.patch")
            val rectangularPatch = File(modelsDir, "${config.width}x${config.height}.patch")
            val patchFile = if (config.width == config.height && squarePatch.isFile) {
                squarePatch
            } else {
                rectangularPatch
            }
            if (patchFile.isFile) {
                command += listOf("--patch", patchFile.absolutePath)
            } else if (unified) {
                Log.w(TAG, "Patch file not found: ${patchFile.absolutePath}; using 512×512")
            }
        }
        if (config.backendType == "sdxl" &&
            config.sdxlLowRam
        ) {
            command += if (unified) "--lowram" else "--sdxl"
            if (!unified) {
                command += "--lowram"
            }
        } else if (!unified && config.backendType == "sdxl") {
            command += "--sdxl"
        }
        if (unified && config.backendType == "anima" &&
            config.animaLowRam
        ) {
            command += "--lowram"
            if (config.animaSequentialDit) {
                command += "--anima_seq_dit"
            }
        }
        if (unified && config.listenOnAll) {
            command += "--listen_all"
        }
    }
}
