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
