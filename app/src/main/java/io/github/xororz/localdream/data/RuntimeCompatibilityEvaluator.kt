package io.github.xororz.localdream.data

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable contract embedded with the APK runtime. The runtime file list is
 * validated before native startup so a partially copied or mixed SDK cannot
 * reach QNN with an apparently valid model directory.
 */
data class RuntimeManifest(
    val qairtVersion: String,
    val buildId: String,
    val core: RuntimeManifestFile,
    val runtimeFiles: List<RuntimeManifestFile>,
) {
    companion object {
        fun fromJsonString(rawJson: String): RuntimeManifest {
            val json = JSONObject(rawJson)
            require(json.optInt("schemaVersion", -1) == 1) { "Unsupported runtime manifest schema" }
            val qairt = json.getJSONObject("qairt")
            val runtime = json.getJSONArray("packagedRuntime")
            return RuntimeManifest(
                qairtVersion = qairt.getString("version"),
                buildId = qairt.getString("buildId"),
                core = json.getJSONObject("precompiledCore").toRuntimeManifestFile(),
                runtimeFiles = runtime.toRuntimeManifestFiles(),
            )
        }
    }
}

data class RuntimeManifestFile(
    val name: String,
    val sha256: String,
)

enum class RuntimeCompatibilityRejection {
    MANIFEST_INVALID,
    CORE_MISSING,
    CORE_DIGEST_MISMATCH,
    RUNTIME_LIBRARY_MISSING,
    RUNTIME_LIBRARY_DIGEST_MISMATCH,
    QAIRT_VERSION_MISMATCH,
    ABI_MISMATCH,
    HTP_TARGET_MISMATCH,
    CONTEXT_FINGERPRINT_MISMATCH,
}

data class RuntimeCompatibilityResult(
    val rejections: Set<RuntimeCompatibilityRejection>,
    val requiresCompatibilityFallback: Boolean,
) {
    val isCompatible: Boolean get() = rejections.isEmpty()
}

/**
 * Checks the data available immediately before ProcessBuilder starts native
 * inference. It has no Android dependency so VM-09 can cover each rejection
 * deterministically on the JVM.
 */
class RuntimeCompatibilityEvaluator {
    fun evaluate(
        manifestJson: String?,
        runtimeDirectory: File,
        coreFile: File,
        metadata: ModelMetadata?,
        deviceAbi: String,
        htpTarget: String,
        contextFingerprint: String?,
    ): RuntimeCompatibilityResult {
        val manifest = manifestJson?.let { rawJson ->
            runCatching { RuntimeManifest.fromJsonString(rawJson) }.getOrNull()
        }
            ?: return RuntimeCompatibilityResult(
                rejections = setOf(RuntimeCompatibilityRejection.MANIFEST_INVALID),
                requiresCompatibilityFallback = false,
            )
        val rejections = linkedSetOf<RuntimeCompatibilityRejection>()
        verify(coreFile, manifest.core, isCore = true)?.let(rejections::add)
        manifest.runtimeFiles.forEach { file ->
            verify(File(runtimeDirectory, file.name), file, isCore = false)?.let(rejections::add)
        }

        val compatibility = metadata?.runtimeCompatibility
        if (compatibility == null) {
            // Pre-v2 metadata has no runtime build evidence. It may run only
            // through the conservative compatibility fallback, never through
            // a target-performance preset.
            return RuntimeCompatibilityResult(
                rejections = rejections,
                requiresCompatibilityFallback = true,
            )
        } else {
            if (compatibility.qairtVersion != manifest.qairtVersion) {
                rejections += RuntimeCompatibilityRejection.QAIRT_VERSION_MISMATCH
            }
            if (compatibility.abi != deviceAbi) {
                rejections += RuntimeCompatibilityRejection.ABI_MISMATCH
            }
            if (!compatibility.htpTarget.equals(htpTarget, ignoreCase = true)) {
                rejections += RuntimeCompatibilityRejection.HTP_TARGET_MISMATCH
            }
            if (contextFingerprint == null || compatibility.contextFingerprint != contextFingerprint) {
                rejections += RuntimeCompatibilityRejection.CONTEXT_FINGERPRINT_MISMATCH
            }
        }
        return RuntimeCompatibilityResult(
            rejections = rejections,
            requiresCompatibilityFallback = false,
        )
    }

    private fun verify(
        file: File,
        expected: RuntimeManifestFile,
        isCore: Boolean,
    ): RuntimeCompatibilityRejection? = when {
        !file.isFile -> if (isCore) {
            RuntimeCompatibilityRejection.CORE_MISSING
        } else {
            RuntimeCompatibilityRejection.RUNTIME_LIBRARY_MISSING
        }

        sha256(file) != expected.sha256 -> if (isCore) {
            RuntimeCompatibilityRejection.CORE_DIGEST_MISMATCH
        } else {
            RuntimeCompatibilityRejection.RUNTIME_LIBRARY_DIGEST_MISMATCH
        }

        else -> null
    }

    companion object {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .also { digest -> file.inputStream().use { input -> input.copyTo(DigestOutputStream(digest)) } }
            .digest()
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }
}

private class DigestOutputStream(private val digest: MessageDigest) : java.io.OutputStream() {
    override fun write(value: Int) {
        digest.update(value.toByte())
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        digest.update(buffer, offset, length)
    }
}

private fun JSONObject.toRuntimeManifestFile(): RuntimeManifestFile {
    val name = getString("name")
    val sha256 = getString("sha256").lowercase(Locale.ROOT)
    require(name.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid runtime manifest file" }
    return RuntimeManifestFile(name, sha256)
}

private fun JSONArray.toRuntimeManifestFiles(): List<RuntimeManifestFile> = buildList {
    for (index in 0 until length()) add(getJSONObject(index).toRuntimeManifestFile())
}.also { files -> require(files.map(RuntimeManifestFile::name).toSet().size == files.size) { "Duplicate runtime file" } }
