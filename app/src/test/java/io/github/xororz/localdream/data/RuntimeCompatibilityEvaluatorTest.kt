package io.github.xororz.localdream.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeCompatibilityEvaluatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun vm09CompleteRuntimeAndModelContractAllowsStartup() {
        val fixture = fixture()

        val result = evaluator().evaluate(
            manifestJson = fixture.manifestJson,
            runtimeDirectory = fixture.runtimeDirectory,
            coreFile = fixture.coreFile,
            attestation = fixture.attestation,
            deviceAbi = "arm64-v8a",
            htpTarget = "v79",
            contextFingerprint = fixture.contextFingerprint,
        )

        assertTrue(result.isCompatible)
    }

    @Test
    fun vm09RejectsMissingMixedDigestAbiHtpAndContextBeforeStartup() {
        val fixture = fixture()
        fixture.runtimeFile.delete()
        fixture.coreFile.writeText("mixed core")
        val incompatibleAttestation = fixture.attestation.copy(
            qairtVersion = "2.44.0",
            abi = "armeabi-v7a",
            htpTarget = "v75",
            contextFingerprint = "b".repeat(64),
        )

        val result = evaluator().evaluate(
            manifestJson = fixture.manifestJson,
            runtimeDirectory = fixture.runtimeDirectory,
            coreFile = fixture.coreFile,
            attestation = incompatibleAttestation,
            deviceAbi = "arm64-v8a",
            htpTarget = "v79",
            contextFingerprint = fixture.contextFingerprint,
        )

        assertFalse(result.isCompatible)
        assertTrue(RuntimeCompatibilityRejection.CORE_DIGEST_MISMATCH in result.rejections)
        assertTrue(RuntimeCompatibilityRejection.RUNTIME_LIBRARY_MISSING in result.rejections)
        assertTrue(RuntimeCompatibilityRejection.QAIRT_VERSION_MISMATCH in result.rejections)
        assertTrue(RuntimeCompatibilityRejection.ABI_MISMATCH in result.rejections)
        assertTrue(RuntimeCompatibilityRejection.HTP_TARGET_MISMATCH in result.rejections)
        assertTrue(RuntimeCompatibilityRejection.CONTEXT_FINGERPRINT_MISMATCH in result.rejections)
    }

    @Test
    fun vm09AllowsUnknownLegacyModelMetadataOnlyAsCompatibilityFallback() {
        val fixture = fixture()

        val result = evaluator().evaluate(
            manifestJson = fixture.manifestJson,
            runtimeDirectory = fixture.runtimeDirectory,
            coreFile = fixture.coreFile,
            attestation = null,
            deviceAbi = "arm64-v8a",
            htpTarget = "v79",
            contextFingerprint = fixture.contextFingerprint,
        )

        assertTrue(result.isCompatible)
        assertTrue(result.requiresCompatibilityFallback)
    }

    @Test
    fun vm09RequiresSuccessfulNativeAttestationForImportedRuntimeMetadata() {
        val fixture = fixture()
        val result = evaluator().evaluate(
            manifestJson = fixture.manifestJson,
            runtimeDirectory = fixture.runtimeDirectory,
            coreFile = fixture.coreFile,
            attestation = null,
            deviceAbi = "arm64-v8a",
            htpTarget = "v79",
            contextFingerprint = fixture.contextFingerprint,
        )

        assertTrue(result.isCompatible)
        assertTrue(result.requiresCompatibilityFallback)
    }

    @Test
    fun vm09RejectsAttestationWithoutMappedV79HostLibraries() {
        val fixture = fixture()
        val missingV79 = fixture.attestation.copy(
            loadedLibraryFingerprints = mapOf("libQnnSystem.so" to "a".repeat(64)),
        )

        val result = evaluator().evaluate(
            manifestJson = fixture.manifestJson,
            runtimeDirectory = fixture.runtimeDirectory,
            coreFile = fixture.coreFile,
            attestation = missingV79,
            deviceAbi = "arm64-v8a",
            htpTarget = "v79",
            contextFingerprint = fixture.contextFingerprint,
        )

        assertFalse(result.isCompatible)
        assertTrue(RuntimeCompatibilityRejection.CONTEXT_FINGERPRINT_MISMATCH in result.rejections)
    }

    private fun evaluator() = RuntimeCompatibilityEvaluator()

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val core = File(root, "libstable_diffusion_core.so").apply { writeText("core") }
        val runtimeDirectory = File(root, "runtime").apply {
            check(mkdirs())
        }
        val htp = File(runtimeDirectory, "libQnnHtp.so").apply { writeText("htp") }
        val runtime = File(runtimeDirectory, "libQnnHtpV79.so").apply { writeText("runtime") }
        val stub = File(runtimeDirectory, "libQnnHtpV79Stub.so").apply { writeText("stub") }
        val contextFingerprint = RuntimeCompatibilityEvaluator.sha256(
            File(root, "unet.bin").apply { writeText("context") },
        )
        val manifestJson = """
            {
              "schemaVersion": 1,
              "qairt": { "version": "2.48.40", "buildId": "260702151143" },
              "precompiledCore": { "name": "${core.name}", "sha256": "${RuntimeCompatibilityEvaluator.sha256(core)}" },
              "packagedRuntime": [
                { "name": "${htp.name}", "sha256": "${RuntimeCompatibilityEvaluator.sha256(htp)}" },
                { "name": "${runtime.name}", "sha256": "${RuntimeCompatibilityEvaluator.sha256(runtime)}" },
                { "name": "${stub.name}", "sha256": "${RuntimeCompatibilityEvaluator.sha256(stub)}" }
              ]
            }
        """.trimIndent()
        return Fixture(
            manifestJson = manifestJson,
            runtimeDirectory = runtimeDirectory,
            runtimeFile = runtime,
            coreFile = core,
            contextFingerprint = contextFingerprint,
            attestation = NativeRuntimeAttestation(
                deviceModel = "PJZ110",
                soc = "SM8750",
                qairtVersion = "2.48.40",
                abi = "arm64-v8a",
                htpTarget = "v79",
                contextFingerprint = contextFingerprint,
                loadedLibraryFingerprints = mapOf(
                    htp.name to RuntimeCompatibilityEvaluator.sha256(htp),
                    runtime.name to RuntimeCompatibilityEvaluator.sha256(runtime),
                    stub.name to RuntimeCompatibilityEvaluator.sha256(stub),
                ),
                observedAtEpochMillis = 1L,
            ),
        )
    }

    private data class Fixture(
        val manifestJson: String,
        val runtimeDirectory: File,
        val runtimeFile: File,
        val coreFile: File,
        val contextFingerprint: String,
        val attestation: NativeRuntimeAttestation,
    )
}
