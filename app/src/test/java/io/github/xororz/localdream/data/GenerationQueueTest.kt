package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationQueueTest {

    private fun task(
        id: String,
        modelId: String,
        mode: String = "TXT2IMG",
        status: GenerationTaskStatus = GenerationTaskStatus.QUEUED,
    ) = GenerationTask(
        id = id,
        modelId = modelId,
        modelName = "name-$modelId",
        prompt = "prompt-$id",
        negativePrompt = "neg-$id",
        mode = mode,
        width = 640,
        height = 512,
        steps = 24,
        cfg = 6.5f,
        seed = 42L,
        scheduler = "euler",
        status = status,
        createdAt = 1_000L,
    )

    @Test
    fun `json round trip preserves every field`() {
        val original = listOf(task("a", "sd15"), task("b", "sdxl"))

        val restored = GenerationQueueCodec.fromJson(GenerationQueueCodec.toJson(original))

        assertEquals(original, restored)
    }

    @Test
    fun `null seed survives the round trip`() {
        val original = listOf(task("a", "sd15").copy(seed = null))

        val restored = GenerationQueueCodec.fromJson(GenerationQueueCodec.toJson(original))

        assertNull(restored.single().seed)
    }

    @Test
    fun `image based tasks are not persisted`() {
        val original = listOf(
            task("a", "sd15"),
            task("b", "sd15", mode = "IMG2IMG"),
            task("c", "sd15", mode = "INPAINT"),
        )

        val restored = GenerationQueueCodec.fromJson(GenerationQueueCodec.toJson(original))

        assertEquals(listOf("a"), restored.map { it.id })
    }

    @Test
    fun `corrupt payload decodes to an empty queue`() {
        assertTrue(GenerationQueueCodec.fromJson("not json").isEmpty())
        assertTrue(GenerationQueueCodec.fromJson("").isEmpty())
        assertTrue(GenerationQueueCodec.fromJson("[]").isEmpty())
    }

    @Test
    fun `entries missing an id or model are skipped`() {
        val raw = """[{"prompt":"orphan"},{"id":"a","prompt":"no model"}]"""

        assertTrue(GenerationQueueCodec.fromJson(raw).isEmpty())
    }

    @Test
    fun `clustering groups tasks by model in first submission order`() {
        val queue = listOf(
            task("1", "sd15"),
            task("2", "sdxl"),
            task("3", "sd15"),
            task("4", "anima"),
            task("5", "sdxl"),
        )

        val clustered = GenerationQueueSorter.clusterByModel(queue)

        assertEquals(listOf("1", "3", "2", "5", "4"), clustered.map { it.id })
    }

    @Test
    fun `clustering keeps the running task at the head`() {
        val queue = listOf(
            task("1", "sdxl", status = GenerationTaskStatus.RUNNING),
            task("2", "sd15"),
            task("3", "sdxl"),
            task("4", "sd15"),
        )

        val clustered = GenerationQueueSorter.clusterByModel(queue)

        assertEquals(listOf("1", "2", "4", "3"), clustered.map { it.id })
    }

    @Test
    fun `clustering reduces the number of model switches`() {
        val queue = listOf(
            task("1", "sd15"),
            task("2", "sdxl"),
            task("3", "sd15"),
            task("4", "sdxl"),
        )

        val before = GenerationQueueSorter.modelSwitchCount(queue)
        val after = GenerationQueueSorter.modelSwitchCount(
            GenerationQueueSorter.clusterByModel(queue),
        )

        assertEquals(3, before)
        assertEquals(1, after)
    }

    @Test
    fun `clustering an already grouped queue is a no-op`() {
        val queue = listOf(task("1", "sd15"), task("2", "sd15"), task("3", "sdxl"))

        assertEquals(queue, GenerationQueueSorter.clusterByModel(queue))
    }

    @Test
    fun `unknown status falls back to queued`() {
        assertEquals(GenerationTaskStatus.QUEUED, GenerationTaskStatus.fromKey("BOGUS"))
        assertEquals(GenerationTaskStatus.RUNNING, GenerationTaskStatus.fromKey("RUNNING"))
    }
}
