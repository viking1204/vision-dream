package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCompatibilityEvaluatorTest {
    private val evaluator = ModelCompatibilityEvaluator()

    @Test
    fun qnnArtifactNameIsAcceptedWithoutRepositoryMetadata() {
        val result = evaluator.evaluate(
            repository(
                id = "community/mobile-model",
                files = listOf("DreamShaperV8_qnn2.28_8gen2.zip"),
            ),
        )

        assertTrue(result.isCompatible)
        assertEquals(CatalogBackendHint.QNN_NPU, result.artifacts.single().backendHint)
        assertEquals("sd15npu", result.artifacts.single().backendType)
        assertEquals("8gen2", result.artifacts.single().hardwareTarget)
    }

    @Test
    fun markedRepositoryStillRequiresAnExplicitSupportedBackend() {
        val result = evaluator.evaluate(
            repository(
                id = "community/mobile-model",
                tags = setOf("local-dream"),
                files = listOf("portrait.zip", "nested/other.zip"),
            ),
        )

        assertFalse(result.isCompatible)
    }

    @Test
    fun markedCpuRepositoryAcceptsOnlyItsRootArchive() {
        val result = evaluator.evaluate(
            repository(
                id = "community/mobile-model",
                tags = setOf("local-dream"),
                modelType = "sd15cpu",
                files = listOf("portrait.zip", "nested/other.zip"),
            ),
        )

        assertEquals(listOf("portrait.zip"), result.artifacts.map { it.file.path })
        assertEquals("sd15cpu", result.artifacts.single().backendType)
    }

    @Test
    fun genericNpuArchiveWithoutHardwareTargetIsRejected() {
        val result = evaluator.evaluate(
            repository(
                id = "community/mobile-model",
                tags = setOf("local-dream"),
                modelType = "sd15npu",
                files = listOf("portrait.zip"),
            ),
        )

        assertFalse(result.isCompatible)
    }

    @Test
    fun officialSdxlArchiveExposesBackendType() {
        val result = evaluator.evaluate(
            repository(
                id = "xororz/sdxl-qnn",
                files = listOf("Portrait_qnn2.28_8gen3.zip"),
            ),
        )

        assertEquals("sdxl", result.artifacts.single().backendType)
    }

    @Test
    fun genericZipIsRejected() {
        val result = evaluator.evaluate(
            repository(
                id = "community/random",
                files = listOf("model.zip"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.NOT_LOCAL_DREAM_ARCHIVE in result.rejections)
    }

    @Test
    fun explicitSd15SingleCheckpointIsAcceptedForConversion() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf("portrait-v2.safetensors"),
            ),
        )

        assertTrue(result.isCompatible)
        assertEquals(CatalogArtifactKind.SD15_SAFETENSORS, result.artifacts.single().kind)
        assertEquals("sd15cpu", result.artifacts.single().backendType)
        assertEquals("portrait_v2", result.artifacts.single().localModelId)
    }

    @Test
    fun checkpointWithoutExplicitBaseModelIsRejected() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                tags = setOf("stable-diffusion"),
                files = listOf("portrait.safetensors"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.MISSING_EXPLICIT_SD15_BASE in result.rejections)
    }

    @Test
    fun sdxlMetadataCannotMasqueradeAsSd15() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait-xl",
                baseModels = setOf("stabilityai/stable-diffusion-xl-base-1.0"),
                files = listOf("portrait.safetensors"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY in result.rejections)
    }

    @Test
    fun conflictingSdxlTagOverridesClaimedSd15Base() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                tags = setOf("stable-diffusion-xl"),
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf("portrait.safetensors"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY in result.rejections)
    }

    @Test
    fun loraIsNotAcceptedAsFullSd15Checkpoint() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait-lora",
                tags = setOf("lora"),
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf("portrait.safetensors"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY in result.rejections)
    }

    @Test
    fun diffusersLayoutIsRejectedEvenWithRootCheckpoint() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                libraryName = "diffusers",
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf(
                    "portrait.safetensors",
                    "unet/diffusion_pytorch_model.safetensors",
                ),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.DIFFUSERS_LAYOUT in result.rejections)
        assertTrue(CompatibilityRejection.NESTED_CHECKPOINT in result.rejections)
    }

    @Test
    fun inpaintingCheckpointIsRejected() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait-inpainting",
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf("portrait-inpainting.safetensors"),
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.INPAINTING_MODEL in result.rejections)
    }

    @Test
    fun shardedOrMultipleCheckpointsAreRejected() {
        val sharded = evaluator.evaluate(
            repository(
                id = "artist/sharded",
                baseModels = setOf("runwayml/stable-diffusion-v1-5"),
                files = listOf(
                    "model-00001-of-00002.safetensors",
                    "model-00002-of-00002.safetensors",
                    "model.safetensors.index.json",
                ),
            ),
        )

        assertFalse(sharded.isCompatible)
        assertTrue(CompatibilityRejection.SHARDED_CHECKPOINT in sharded.rejections)
        assertTrue(CompatibilityRejection.MULTIPLE_CHECKPOINTS in sharded.rejections)
    }

    @Test
    fun gatedRepositoryIsRejectedBeforeArtifactInspection() {
        val result = evaluator.evaluate(
            repository(
                id = "artist/private-model",
                gated = true,
                files = listOf("Model_qnn2.28_min.zip"),
            ),
        )

        assertEquals(setOf(CompatibilityRejection.ACCESS_RESTRICTED), result.rejections)
    }

    @Test
    fun completeLooseCpuDirectoryIsAccepted() {
        val files = io.github.xororz.localdream.data.ModelFileLayouts.sd15Cpu.requiredFiles
            .map { "portrait/$it" }
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                files = files,
            ),
        )

        val artifact = result.artifacts.single()
        assertEquals(CatalogArtifactKind.LOCAL_DREAM_DIRECTORY, artifact.kind)
        assertEquals("sd15cpu", artifact.backendType)
        assertEquals("portrait", artifact.localModelId)
        assertEquals(files.toSet(), artifact.directoryFiles.map { it.path }.toSet())
    }

    @Test
    fun looseNpuDirectoryRequiresHardwareTarget() {
        val files = io.github.xororz.localdream.data.ModelFileLayouts.sd15Npu.requiredFiles
            .map { "portrait/$it" }
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                files = files,
            ),
        )

        assertFalse(result.isCompatible)
        assertTrue(CompatibilityRejection.MISSING_HARDWARE_TARGET in result.rejections)
    }

    @Test
    fun looseNpuDirectoryReadsHardwareTargetFromFolderName() {
        val files = io.github.xororz.localdream.data.ModelFileLayouts.sd15Npu.requiredFiles
            .map { "portrait_8gen2/$it" }
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                files = files,
            ),
        )

        assertEquals("8gen2", result.artifacts.single().hardwareTarget)
    }

    @Test
    fun directoryTargetTakesPriorityOverRepositoryWideTargetTags() {
        val files = io.github.xororz.localdream.data.ModelFileLayouts.sd15Npu.requiredFiles
            .map { "portrait_8gen2/$it" }
        val result = evaluator.evaluate(
            repository(
                id = "artist/portrait",
                tags = setOf("8gen1", "8gen2"),
                files = files,
            ),
        )

        assertEquals("8gen2", result.artifacts.single().hardwareTarget)
    }

    private fun repository(
        id: String,
        files: List<String>,
        tags: Set<String> = emptySet(),
        baseModels: Set<String> = emptySet(),
        libraryName: String? = null,
        modelType: String? = null,
        gated: Boolean = false,
    ): HuggingFaceModelRepository = HuggingFaceModelRepository(
        id = id,
        tags = tags,
        baseModels = baseModels,
        libraryName = libraryName,
        modelType = modelType,
        files = files.map(::HuggingFaceModelFile),
        isGated = gated,
    )
}
