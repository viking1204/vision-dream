package io.github.xororz.localdream.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParamSharePromptPairTest {
    @Test
    fun `prompt pair clipboard text round trips both fields`() {
        val clipboardText = ParamShare.buildPromptPairClipboardText(
            prompt = "portrait, cinematic light",
            negativePrompt = "blurry, low quality",
        )

        assertEquals(
            PromptPairInput(
                prompt = "portrait, cinematic light",
                negativePrompt = "blurry, low quality",
            ),
            ParamShare.tryDecodePromptPairPaste(clipboardText),
        )
    }

    @Test
    fun `base64 prompt pair clipboard text is accepted`() {
        val clipboardText = ParamShare.buildPromptPairClipboardText(
            prompt = "portrait",
            negativePrompt = "",
            useBase64 = true,
        )

        assertEquals(
            PromptPairInput(prompt = "portrait", negativePrompt = ""),
            ParamShare.tryDecodePromptPairPaste(clipboardText),
        )
    }

    @Test
    fun `shared prompt pair replaces a selected negative field`() {
        val clipboardText = ParamShare.buildPromptPairClipboardText(
            prompt = "new positive",
            negativePrompt = "new negative",
        )

        assertEquals(
            PromptPairInput(prompt = "new positive", negativePrompt = "new negative"),
            ParamShare.tryDecodePromptPairEdit(
                currentText = "old negative",
                selectionStart = 0,
                selectionEnd = "old negative".length,
                candidate = clipboardText,
            ),
        )
    }

    @Test
    fun `pair pasted at cursor of non-empty prompt is still decoded`() {
        val clipboardText = ParamShare.buildPromptPairClipboardText(
            prompt = "new positive",
            negativePrompt = "new negative",
        )

        assertEquals(
            PromptPairInput(prompt = "new positive", negativePrompt = "new negative"),
            ParamShare.tryDecodePromptPairEdit(
                currentText = "old positive",
                selectionStart = 4,
                selectionEnd = 4,
                candidate = "old $clipboardText positive",
            ),
        )
    }

    @Test
    fun `pair replacing selected brace wrapped text is decoded without trimming payload`() {
        val clipboardText = ParamShare.buildPromptPairClipboardText(
            prompt = "new positive",
            negativePrompt = "new negative",
        )

        assertEquals(
            PromptPairInput(prompt = "new positive", negativePrompt = "new negative"),
            ParamShare.tryDecodePromptPairEdit(
                currentText = "{old}",
                selectionStart = 0,
                selectionEnd = 5,
                candidate = clipboardText,
            ),
        )
    }

    @Test
    fun `ordinary text edit is not decoded as a shared pair`() {
        assertNull(
            ParamShare.tryDecodePromptPairEdit(
                currentText = "old positive",
                selectionStart = 0,
                selectionEnd = "old positive".length,
                candidate = "typed positive",
            ),
        )
    }

    @Test
    fun `prompt only payload is not treated as an atomic pair paste`() {
        val promptOnly = """{"_localdream_params":true,"v":1,"prompt":"new"}"""

        assertNull(ParamShare.tryDecodePromptPairPaste(promptOnly))
    }
}
