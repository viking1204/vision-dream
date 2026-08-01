package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreationDraftPersistenceTest {

    @Test
    fun roundTripsAllFields() {
        val draft = CreationDraft(
            prompt = "a quiet harbor at dawn",
            negativePrompt = "blurry, lowres",
            modelId = "model-123",
            mode = "IMG2IMG",
            width = 768,
            height = 768,
            steps = 25,
            cfg = 6.5f,
            seed = "42",
            scheduler = "euler",
        )

        val restored = CreationDraft.fromJson(draft.toJson())

        assertEquals(draft, restored)
        assertEquals("IMG2IMG", restored?.mode)
        assertEquals(768, restored?.width)
        assertEquals(6.5f, restored?.cfg)
        assertEquals("42", restored?.seed)
    }

    @Test
    fun nullModelIdIsPreserved() {
        val draft = CreationDraft(prompt = "hello", modelId = null)

        val restored = CreationDraft.fromJson(draft.toJson())

        assertEquals(null, restored?.modelId)
        assertEquals("hello", restored?.prompt)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(CreationDraft.fromJson("not json"))
        assertNull(CreationDraft.fromJson(""))
    }

    @Test
    fun missingFieldsUseDefaults() {
        val restored = CreationDraft.fromJson("""{"prompt":"only prompt"}""")

        assertEquals("only prompt", restored?.prompt)
        assertEquals("TXT2IMG", restored?.mode)
        assertEquals(512, restored?.width)
        assertEquals(7f, restored?.cfg)
    }
}
