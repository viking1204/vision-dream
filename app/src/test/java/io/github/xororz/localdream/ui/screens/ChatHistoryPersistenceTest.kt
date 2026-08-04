package io.github.xororz.localdream.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryPersistenceTest {

    @Test
    fun `conversation round-trips through JSON`() {
        val imageFile = File.createTempFile("chat_hist", ".png")
        try {
            val original = listOf<ChatGenerationMessage>(
                ChatGenerationMessage.User(id = 1, prompt = "a cat on mars"),
                ChatGenerationMessage.Image(
                    id = 2,
                    file = imageFile,
                    fallbackBytes = null,
                    modelName = "sd15",
                    width = 512,
                    height = 512,
                    seed = 12345L,
                ),
                ChatGenerationMessage.Error(id = 3, message = "boom"),
            )

            val json = original.toChatHistoryJson()
            val restored = chatHistoryFromJson(json)

            assertEquals(original, restored)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `images whose asset file is missing are dropped on restore`() {
        val missing = File.createTempFile("chat_missing", ".png").also { it.delete() }
        val original = listOf<ChatGenerationMessage>(
            ChatGenerationMessage.User(id = 1, prompt = "keep me"),
            ChatGenerationMessage.Image(
                id = 2,
                file = missing,
                fallbackBytes = null,
                modelName = "sd15",
                width = 512,
                height = 512,
                seed = null,
            ),
        )

        val restored = chatHistoryFromJson(original.toChatHistoryJson())

        assertEquals(
            listOf(ChatGenerationMessage.User(id = 1, prompt = "keep me")),
            restored,
        )
    }

    @Test
    fun `generation time survives the JSON round-trip`() {
        val imageFile = File.createTempFile("chat_gt", ".png")
        try {
            val original = listOf<ChatGenerationMessage>(
                ChatGenerationMessage.Image(
                    id = 1,
                    file = imageFile,
                    fallbackBytes = null,
                    modelName = "sd15",
                    width = 512,
                    height = 512,
                    seed = 7L,
                    prompt = "a cat on mars",
                    generationTime = "12.3s",
                ),
            )

            val restored = chatHistoryFromJson(original.toChatHistoryJson())

            assertEquals(original, restored)
            assertEquals(
                "12.3s",
                (restored?.first() as ChatGenerationMessage.Image).generationTime,
            )
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `envelope written before generation timing still restores`() {
        val imageFile = File.createTempFile("chat_legacy", ".png")
        try {
            // Exactly what an older build produced: no "gt" key at all.
            val legacy = """
                [{"t":"i","f":"${imageFile.absolutePath}","mn":"sd15","w":512,"h":512,
                "s":7,"pr":"legacy prompt","np":"","st":20,"cf":7,"sc":"dpm"}]
            """.trimIndent().replace("\n", "")

            val restored = chatHistoryFromJson(legacy)
            val image = restored?.single() as ChatGenerationMessage.Image

            assertEquals("legacy prompt", image.prompt)
            assertEquals("", image.generationTime)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `empty conversation serializes to empty list`() {
        val json = emptyList<ChatGenerationMessage>().toChatHistoryJson()
        assertEquals("[]", json)
        assertEquals(emptyList<ChatGenerationMessage>(), chatHistoryFromJson(json))
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(chatHistoryFromJson("not json {"))
    }
}
