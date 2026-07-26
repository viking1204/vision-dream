package io.github.xororz.localdream.openai

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail as junitFail
import org.junit.Test

class OpenAiMultipartParserTest {
    private val parser = OpenAiMultipartParser()

    @Test
    fun parsesImageMaskAndTextFieldsWithoutSplittingFalseBoundaryBytes() {
        val boundary = "vision-boundary-123"
        val falseBoundary = "\r\n--$boundary-not-real".ascii()
        val imageBytes = PNG_SIGNATURE + byteArrayOf(1, 2) + falseBoundary + byteArrayOf(3, 4)
        val maskBytes = PNG_SIGNATURE + byteArrayOf(5, 6)
        val body = multipart(
            boundary = boundary,
            parts = listOf(
                textPart("prompt", "一只猫", contentType = "text/plain; charset=\"UTF-8\""),
                filePart("image", "source.png", "image/png", imageBytes),
                textPart("model", "installed-model"),
                filePart("mask", "mask.png", null, maskBytes),
            ),
        )

        val result = parser.parse("multipart/form-data; boundary=$boundary", body)

        assertEquals("image", result.image.fieldName)
        assertEquals("source.png", result.image.fileName)
        assertEquals("image/png", result.image.contentType)
        assertArrayEquals(imageBytes, result.image.bytes)
        assertEquals("mask.png", result.mask?.fileName)
        assertEquals("image/png", result.mask?.contentType)
        assertArrayEquals(maskBytes, result.mask?.bytes)
        assertEquals(mapOf("prompt" to "一只猫", "model" to "installed-model"), result.fields)
    }

    @Test
    fun acceptsQuotedBoundaryImageArrayAndOctetStream() {
        val boundary = "----client-boundary"
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val body = multipart(
            boundary = boundary,
            parts = listOf(
                filePart("image[]", "source.jpg", "application/octet-stream", jpegBytes),
            ),
            finalCrlf = false,
        )

        val result = parser.parse("Multipart/Form-Data; boundary=\"$boundary\"", body)

        assertEquals("image[]", result.image.fieldName)
        assertEquals("image/jpeg", result.image.contentType)
        assertNull(result.mask)
        assertEquals(emptyMap<String, String>(), result.fields)
    }

    @Test
    fun rejectsMissingWrongOrInvalidMultipartContentType() {
        assertParseFailure(MultipartParseException.Reason.INVALID_CONTENT_TYPE) {
            parser.parse(null, byteArrayOf())
        }
        assertParseFailure(MultipartParseException.Reason.INVALID_CONTENT_TYPE) {
            parser.parse("application/json", byteArrayOf())
        }
        assertParseFailure(MultipartParseException.Reason.MISSING_BOUNDARY) {
            parser.parse("multipart/form-data", byteArrayOf())
        }
        assertParseFailure(MultipartParseException.Reason.INVALID_BOUNDARY) {
            parser.parse("multipart/form-data; boundary=\"bad boundary\"", byteArrayOf())
        }
    }

    @Test
    fun enforcesBodyFileAndTextSizeLimitsSeparately() {
        val bodyLimited = OpenAiMultipartParser(
            MultipartLimits(
                maxBodyBytes = 10,
                maxFileBytes = 10,
                maxTextFieldBytes = 10,
                maxHeaderBytes = 32,
                maxParts = 2,
            ),
        )
        assertParseFailure(MultipartParseException.Reason.BODY_TOO_LARGE) {
            bodyLimited.parse("multipart/form-data; boundary=b", ByteArray(11))
        }

        val partLimited = OpenAiMultipartParser(
            MultipartLimits(
                maxBodyBytes = 1_024,
                maxFileBytes = PNG_SIGNATURE.size,
                maxTextFieldBytes = 3,
                maxHeaderBytes = 256,
                maxParts = 4,
            ),
        )
        assertParseFailure(MultipartParseException.Reason.FILE_TOO_LARGE) {
            partLimited.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(filePart("image", "image.png", "image/png", PNG_SIGNATURE + 1)),
                ),
            )
        }
        assertParseFailure(MultipartParseException.Reason.TEXT_FIELD_TOO_LARGE) {
            partLimited.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        textPart("prompt", "four"),
                        filePart("image", "image.png", "image/png", PNG_SIGNATURE),
                    ),
                ),
            )
        }
    }

    @Test
    fun requiresExactlyOneImageAndUniqueFields() {
        val image = filePart("image", "image.png", "image/png", PNG_SIGNATURE)
        assertParseFailure(MultipartParseException.Reason.MISSING_IMAGE) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart("b", listOf(textPart("prompt", "none"))),
            )
        }
        assertParseFailure(MultipartParseException.Reason.DUPLICATE_FIELD) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        image,
                        filePart("image[]", "other.png", "image/png", PNG_SIGNATURE),
                    ),
                ),
            )
        }
        assertParseFailure(MultipartParseException.Reason.DUPLICATE_FIELD) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(textPart("prompt", "first"), textPart("prompt", "second"), image),
                ),
            )
        }
    }

    @Test
    fun rejectsUnsupportedDisguisedAndInvalidImages() {
        assertParseFailure(MultipartParseException.Reason.UNSUPPORTED_IMAGE_FORMAT) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(filePart("image", "image.gif", "image/gif", "GIF89a".ascii())),
                ),
            )
        }
        assertParseFailure(MultipartParseException.Reason.CONTENT_TYPE_MISMATCH) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(filePart("image", "image.jpg", "image/jpeg", PNG_SIGNATURE)),
                ),
            )
        }
        assertParseFailure(MultipartParseException.Reason.UNSUPPORTED_IMAGE_FORMAT) {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        filePart("image", "image.jpg", "image/jpeg", jpeg),
                        filePart("mask", "mask.jpg", "image/jpeg", jpeg),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidUtf8AndUnexpectedFileFields() {
        assertParseFailure(MultipartParseException.Reason.INVALID_TEXT_ENCODING) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        TestPart(
                            headers = listOf("""Content-Disposition: form-data; name="prompt""""),
                            bytes = byteArrayOf(0xC3.toByte(), 0x28),
                        ),
                        filePart("image", "image.png", "image/png", PNG_SIGNATURE),
                    ),
                ),
            )
        }
        assertParseFailure(MultipartParseException.Reason.UNSUPPORTED_FILE_FIELD) {
            parser.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        filePart("metadata", "metadata.json", "application/json", "{}".ascii()),
                        filePart("image", "image.png", "image/png", PNG_SIGNATURE),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsMalformedFramingOversizedHeadersAndTooManyParts() {
        assertParseFailure(MultipartParseException.Reason.MALFORMED_BODY) {
            parser.parse("multipart/form-data; boundary=b", "not-multipart".ascii())
        }

        val withTrailingData = multipart(
            "b",
            listOf(filePart("image", "image.png", "image/png", PNG_SIGNATURE)),
        ) + "junk".ascii()
        assertParseFailure(MultipartParseException.Reason.MALFORMED_BODY) {
            parser.parse("multipart/form-data; boundary=b", withTrailingData)
        }

        val headerLimited = OpenAiMultipartParser(
            MultipartLimits(
                maxBodyBytes = 1_024,
                maxFileBytes = 128,
                maxTextFieldBytes = 128,
                maxHeaderBytes = 20,
                maxParts = 2,
            ),
        )
        assertParseFailure(MultipartParseException.Reason.HEADER_TOO_LARGE) {
            headerLimited.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(filePart("image", "image.png", "image/png", PNG_SIGNATURE)),
                ),
            )
        }

        val partLimited = OpenAiMultipartParser(
            MultipartLimits(
                maxBodyBytes = 1_024,
                maxFileBytes = 128,
                maxTextFieldBytes = 128,
                maxHeaderBytes = 256,
                maxParts = 1,
            ),
        )
        assertParseFailure(MultipartParseException.Reason.TOO_MANY_PARTS) {
            partLimited.parse(
                "multipart/form-data; boundary=b",
                multipart(
                    "b",
                    listOf(
                        filePart("image", "image.png", "image/png", PNG_SIGNATURE),
                        textPart("prompt", "extra"),
                    ),
                ),
            )
        }
    }

    private fun assertParseFailure(
        reason: MultipartParseException.Reason,
        operation: () -> Unit,
    ) {
        try {
            operation()
            junitFail("Expected multipart parse failure: $reason")
        } catch (error: MultipartParseException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun filePart(
        fieldName: String,
        fileName: String,
        contentType: String?,
        bytes: ByteArray,
    ): TestPart {
        val headers = mutableListOf(
            """Content-Disposition: form-data; name="$fieldName"; filename="$fileName"""",
        )
        contentType?.let { headers += "Content-Type: $it" }
        return TestPart(headers = headers, bytes = bytes)
    }

    private fun textPart(
        fieldName: String,
        value: String,
        contentType: String? = null,
    ): TestPart {
        val headers = mutableListOf(
            """Content-Disposition: form-data; name="$fieldName"""",
        )
        contentType?.let { headers += "Content-Type: $it" }
        return TestPart(headers = headers, bytes = value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun multipart(
        boundary: String,
        parts: List<TestPart>,
        finalCrlf: Boolean = true,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach { part ->
            output.write("--$boundary\r\n".ascii())
            part.headers.forEach { header ->
                output.write("$header\r\n".ascii())
            }
            output.write("\r\n".ascii())
            output.write(part.bytes)
            output.write("\r\n".ascii())
        }
        output.write("--$boundary--".ascii())
        if (finalCrlf) output.write("\r\n".ascii())
        return output.toByteArray()
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private data class TestPart(
        val headers: List<String>,
        val bytes: ByteArray,
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
