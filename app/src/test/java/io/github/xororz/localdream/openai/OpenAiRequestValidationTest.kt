package io.github.xororz.localdream.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiRequestValidationTest {
    @Test
    fun binarySelectsRawImageResponse() {
        assertEquals(ImageResponseFormat.BINARY, parseResponseFormat("binary"))
    }

    @Test
    fun absentAndUrlSelectTemporaryUrlResponse() {
        assertEquals(ImageResponseFormat.URL, parseResponseFormat(null))
        assertEquals(ImageResponseFormat.URL, parseResponseFormat("url"))
    }

    @Test
    fun b64JsonSelectsJsonResponse() {
        assertEquals(ImageResponseFormat.B64_JSON, parseResponseFormat("b64_json"))
    }

    @Test
    fun unknownResponseFormatIsRejected() {
        val error = assertThrows(OpenAiRequestException::class.java) {
            parseResponseFormat("jpeg")
        }

        assertEquals(400, error.statusCode)
        assertEquals("response_format", error.parameter)
        assertEquals("unsupported_parameter", error.code)
    }

    @Test
    fun parsesTavoAndOpenAiDimensionSeparators() {
        assertEquals(1024 to 1024, parseSize("1024x1024"))
        assertEquals(1024 to 1024, parseSize("1024*1024"))
        assertEquals(1024 to 1024, parseSize("1024×1024"))
    }
}
