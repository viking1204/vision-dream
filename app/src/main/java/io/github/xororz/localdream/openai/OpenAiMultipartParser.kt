package io.github.xororz.localdream.openai

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class MultipartLimits(
    val maxBodyBytes: Int = 25 * 1024 * 1024,
    val maxFileBytes: Int = 20 * 1024 * 1024,
    val maxTextFieldBytes: Int = 64 * 1024,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxParts: Int = 32,
) {
    init {
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxTextFieldBytes > 0) { "maxTextFieldBytes must be positive" }
        require(maxHeaderBytes > 0) { "maxHeaderBytes must be positive" }
        require(maxParts > 0) { "maxParts must be positive" }
        require(maxFileBytes <= maxBodyBytes) { "maxFileBytes must not exceed maxBodyBytes" }
    }
}

data class MultipartFilePart(
    val fieldName: String,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

data class OpenAiMultipartForm(
    val image: MultipartFilePart,
    val mask: MultipartFilePart?,
    val fields: Map<String, String>,
)

class MultipartParseException(
    val reason: Reason,
    message: String,
) : IllegalArgumentException(message) {
    enum class Reason {
        INVALID_CONTENT_TYPE,
        MISSING_BOUNDARY,
        INVALID_BOUNDARY,
        BODY_TOO_LARGE,
        MALFORMED_BODY,
        TOO_MANY_PARTS,
        HEADER_TOO_LARGE,
        INVALID_HEADER,
        INVALID_CONTENT_DISPOSITION,
        DUPLICATE_FIELD,
        UNSUPPORTED_FILE_FIELD,
        FILE_TOO_LARGE,
        TEXT_FIELD_TOO_LARGE,
        INVALID_TEXT_ENCODING,
        MISSING_IMAGE,
        EMPTY_IMAGE,
        UNSUPPORTED_IMAGE_FORMAT,
        CONTENT_TYPE_MISMATCH,
    }
}

/**
 * Binary-safe parser for OpenAI image edit and upscale multipart requests.
 *
 * One `image` or `image[]` file is required. A PNG `mask` is optional, and all
 * remaining parts must be unique UTF-8 text fields.
 */
class OpenAiMultipartParser(
    private val limits: MultipartLimits = MultipartLimits(),
) {
    fun parse(
        contentType: String?,
        body: ByteArray,
    ): OpenAiMultipartForm {
        if (body.size > limits.maxBodyBytes) {
            fail(
                MultipartParseException.Reason.BODY_TOO_LARGE,
                "Multipart body exceeds ${limits.maxBodyBytes} bytes",
            )
        }
        val boundary = parseBoundary(contentType)
        val parts = parseParts(boundary, body)

        var image: MultipartFilePart? = null
        var mask: MultipartFilePart? = null
        val fields = linkedMapOf<String, String>()

        parts.forEach { part ->
            val disposition = parseParameterizedHeader(
                part.headers["content-disposition"],
                MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                "Content-Disposition",
            )
            if (!disposition.value.equals("form-data", ignoreCase = true)) {
                fail(
                    MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                    "Content-Disposition must be form-data",
                )
            }
            val fieldName = disposition.parameters["name"]
                ?.takeIf(String::isNotBlank)
                ?: fail(
                    MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                    "Multipart part is missing a non-empty name",
                )
            validateDispositionValue(fieldName, "field name")
            val fileName = disposition.parameters["filename"]
            fileName?.let {
                if (it.isBlank()) {
                    fail(
                        MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                        "Multipart file name must not be blank",
                    )
                }
                validateDispositionValue(it, "file name")
            }

            when (fieldName) {
                IMAGE_FIELD, IMAGE_ARRAY_FIELD -> {
                    if (image != null) {
                        fail(
                            MultipartParseException.Reason.DUPLICATE_FIELD,
                            "Only one image or image[] part is allowed",
                        )
                    }
                    image = parseImagePart(part, fieldName, fileName, maskOnly = false)
                }

                MASK_FIELD -> {
                    if (mask != null) {
                        fail(
                            MultipartParseException.Reason.DUPLICATE_FIELD,
                            "Only one mask part is allowed",
                        )
                    }
                    mask = parseImagePart(part, fieldName, fileName, maskOnly = true)
                }

                else -> {
                    if (fileName != null) {
                        fail(
                            MultipartParseException.Reason.UNSUPPORTED_FILE_FIELD,
                            "Unsupported file field: $fieldName",
                        )
                    }
                    if (fields.containsKey(fieldName)) {
                        fail(
                            MultipartParseException.Reason.DUPLICATE_FIELD,
                            "Duplicate text field: $fieldName",
                        )
                    }
                    fields[fieldName] = parseTextPart(part, fieldName)
                }
            }
        }

        return OpenAiMultipartForm(
            image = image ?: fail(
                MultipartParseException.Reason.MISSING_IMAGE,
                "Multipart request must contain one image or image[] part",
            ),
            mask = mask,
            fields = fields.toMap(),
        )
    }

    private fun parseBoundary(contentType: String?): String {
        val parsed = parseParameterizedHeader(
            contentType,
            MultipartParseException.Reason.INVALID_CONTENT_TYPE,
            "Content-Type",
        )
        if (!parsed.value.equals(MULTIPART_FORM_DATA, ignoreCase = true)) {
            fail(
                MultipartParseException.Reason.INVALID_CONTENT_TYPE,
                "Content-Type must be multipart/form-data",
            )
        }
        val boundary = parsed.parameters["boundary"]
            ?: fail(
                MultipartParseException.Reason.MISSING_BOUNDARY,
                "Content-Type is missing the multipart boundary",
            )
        if (!BOUNDARY_PATTERN.matches(boundary)) {
            fail(
                MultipartParseException.Reason.INVALID_BOUNDARY,
                "Multipart boundary contains unsupported characters or length",
            )
        }
        return boundary
    }

    private fun parseParts(
        boundary: String,
        body: ByteArray,
    ): List<RawPart> {
        val delimiter = "--$boundary".toByteArray(StandardCharsets.US_ASCII)
        val boundaryMarker = CRLF + delimiter
        if (!body.matchesAt(0, delimiter)) {
            fail(
                MultipartParseException.Reason.MALFORMED_BODY,
                "Multipart body does not start with the declared boundary",
            )
        }

        var cursor = delimiter.size
        if (body.matchesAt(cursor, FINAL_SUFFIX)) {
            validateFinalBoundary(body, cursor + FINAL_SUFFIX.size)
            return emptyList()
        }
        if (!body.matchesAt(cursor, CRLF)) {
            fail(
                MultipartParseException.Reason.MALFORMED_BODY,
                "Multipart opening boundary must end with CRLF",
            )
        }
        cursor += CRLF.size

        val parts = mutableListOf<RawPart>()
        while (true) {
            if (parts.size >= limits.maxParts) {
                fail(
                    MultipartParseException.Reason.TOO_MANY_PARTS,
                    "Multipart request exceeds ${limits.maxParts} parts",
                )
            }

            val headerEnd = findHeaderEnd(body, cursor)
            val headerBlock = body.copyOfRange(cursor, headerEnd).toString(StandardCharsets.ISO_8859_1)
            val headers = parsePartHeaders(headerBlock)
            val contentStart = headerEnd + HEADER_SEPARATOR.size
            val boundaryStart = findNextBoundary(body, boundaryMarker, contentStart)
            if (boundaryStart < 0) {
                fail(
                    MultipartParseException.Reason.MALFORMED_BODY,
                    "Multipart part is missing a closing boundary",
                )
            }
            parts += RawPart(
                headers = headers,
                bytes = body.copyOfRange(contentStart, boundaryStart),
            )

            cursor = boundaryStart + boundaryMarker.size
            when {
                body.matchesAt(cursor, FINAL_SUFFIX) -> {
                    validateFinalBoundary(body, cursor + FINAL_SUFFIX.size)
                    return parts
                }

                body.matchesAt(cursor, CRLF) -> cursor += CRLF.size

                else -> fail(
                    MultipartParseException.Reason.MALFORMED_BODY,
                    "Multipart boundary has an invalid suffix",
                )
            }
        }
    }

    private fun findHeaderEnd(
        body: ByteArray,
        start: Int,
    ): Int {
        val latestAllowedEnd = start + limits.maxHeaderBytes
        val found = body.indexOf(HEADER_SEPARATOR, start)
        if (found < 0) {
            val reason = if (body.size - start > limits.maxHeaderBytes) {
                MultipartParseException.Reason.HEADER_TOO_LARGE
            } else {
                MultipartParseException.Reason.MALFORMED_BODY
            }
            fail(reason, "Multipart part has no complete header block")
        }
        if (found > latestAllowedEnd) {
            fail(
                MultipartParseException.Reason.HEADER_TOO_LARGE,
                "Multipart part headers exceed ${limits.maxHeaderBytes} bytes",
            )
        }
        return found
    }

    private fun parsePartHeaders(headerBlock: String): Map<String, String> {
        if (headerBlock.isEmpty()) {
            fail(
                MultipartParseException.Reason.INVALID_HEADER,
                "Multipart part must contain headers",
            )
        }
        val headers = linkedMapOf<String, String>()
        headerBlock.split("\r\n").forEach { line ->
            if (line.startsWith(' ') || line.startsWith('\t')) {
                fail(
                    MultipartParseException.Reason.INVALID_HEADER,
                    "Folded multipart headers are not supported",
                )
            }
            val separator = line.indexOf(':')
            if (separator <= 0) {
                fail(
                    MultipartParseException.Reason.INVALID_HEADER,
                    "Malformed multipart header",
                )
            }
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME_PATTERN.matches(name) || value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                fail(
                    MultipartParseException.Reason.INVALID_HEADER,
                    "Multipart header contains invalid characters",
                )
            }
            if (headers.put(name, value) != null) {
                fail(
                    MultipartParseException.Reason.INVALID_HEADER,
                    "Duplicate multipart header: $name",
                )
            }
        }
        if (!headers.containsKey("content-disposition")) {
            fail(
                MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                "Multipart part is missing Content-Disposition",
            )
        }
        if (headers.containsKey("content-transfer-encoding")) {
            fail(
                MultipartParseException.Reason.INVALID_HEADER,
                "Content-Transfer-Encoding is not supported",
            )
        }
        return headers
    }

    private fun parseImagePart(
        part: RawPart,
        fieldName: String,
        fileName: String?,
        maskOnly: Boolean,
    ): MultipartFilePart {
        val requiredFileName = fileName ?: fail(
            MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
            "$fieldName must be sent as a file part",
        )
        if (part.bytes.isEmpty()) {
            fail(
                MultipartParseException.Reason.EMPTY_IMAGE,
                "$fieldName must not be empty",
            )
        }
        if (part.bytes.size > limits.maxFileBytes) {
            fail(
                MultipartParseException.Reason.FILE_TOO_LARGE,
                "$fieldName exceeds ${limits.maxFileBytes} bytes",
            )
        }

        val detectedType = detectImageContentType(part.bytes)
            ?: fail(
                MultipartParseException.Reason.UNSUPPORTED_IMAGE_FORMAT,
                "$fieldName must be a PNG, JPEG, or WebP image",
            )
        if (maskOnly && detectedType != IMAGE_PNG) {
            fail(
                MultipartParseException.Reason.UNSUPPORTED_IMAGE_FORMAT,
                "mask must be a PNG image",
            )
        }
        val declaredType = part.headers["content-type"]?.let { value ->
            parseParameterizedHeader(
                value,
                MultipartParseException.Reason.INVALID_CONTENT_TYPE,
                "$fieldName Content-Type",
            ).value.lowercase(Locale.ROOT)
        }
        val normalizedDeclaredType = normalizeImageContentType(declaredType)
        if (
            normalizedDeclaredType != null &&
            normalizedDeclaredType != APPLICATION_OCTET_STREAM &&
            normalizedDeclaredType != detectedType
        ) {
            fail(
                MultipartParseException.Reason.CONTENT_TYPE_MISMATCH,
                "$fieldName Content-Type does not match its bytes",
            )
        }
        if (declaredType != null && normalizedDeclaredType == null) {
            fail(
                MultipartParseException.Reason.UNSUPPORTED_IMAGE_FORMAT,
                "$fieldName has unsupported Content-Type: $declaredType",
            )
        }

        return MultipartFilePart(
            fieldName = fieldName,
            fileName = requiredFileName,
            contentType = detectedType,
            bytes = part.bytes,
        )
    }

    private fun parseTextPart(
        part: RawPart,
        fieldName: String,
    ): String {
        if (part.bytes.size > limits.maxTextFieldBytes) {
            fail(
                MultipartParseException.Reason.TEXT_FIELD_TOO_LARGE,
                "$fieldName exceeds ${limits.maxTextFieldBytes} bytes",
            )
        }
        part.headers["content-type"]?.let { value ->
            val parsed = parseParameterizedHeader(
                value,
                MultipartParseException.Reason.INVALID_CONTENT_TYPE,
                "$fieldName Content-Type",
            )
            if (!parsed.value.equals(TEXT_PLAIN, ignoreCase = true)) {
                fail(
                    MultipartParseException.Reason.INVALID_CONTENT_TYPE,
                    "$fieldName must use text/plain",
                )
            }
            val charset = parsed.parameters["charset"]
            if (charset != null && !charset.equals("utf-8", ignoreCase = true)) {
                fail(
                    MultipartParseException.Reason.INVALID_TEXT_ENCODING,
                    "$fieldName must use UTF-8",
                )
            }
        }

        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(part.bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail(
                MultipartParseException.Reason.INVALID_TEXT_ENCODING,
                "$fieldName contains invalid UTF-8",
            )
        }
    }

    private fun parseParameterizedHeader(
        rawValue: String?,
        failureReason: MultipartParseException.Reason,
        label: String,
    ): ParsedHeader {
        if (rawValue.isNullOrBlank()) {
            fail(failureReason, "$label is missing")
        }
        val tokens = splitHeaderTokens(rawValue, failureReason, label)
        val value = tokens.first().trim()
        if (value.isEmpty()) {
            fail(failureReason, "$label has no value")
        }
        val parameters = linkedMapOf<String, String>()
        tokens.drop(1).forEach { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) {
                fail(failureReason, "$label contains a malformed parameter")
            }
            val name = token.substring(0, separator).trim().lowercase(Locale.ROOT)
            val rawParameterValue = token.substring(separator + 1).trim()
            if (!HEADER_PARAMETER_NAME_PATTERN.matches(name) || rawParameterValue.isEmpty()) {
                fail(failureReason, "$label contains a malformed parameter")
            }
            val parameterValue = decodeHeaderParameter(rawParameterValue, failureReason, label)
            if (parameters.put(name, parameterValue) != null) {
                fail(failureReason, "$label contains duplicate parameter: $name")
            }
        }
        return ParsedHeader(value = value, parameters = parameters)
    }

    private fun splitHeaderTokens(
        value: String,
        failureReason: MultipartParseException.Reason,
        label: String,
    ): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        value.forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }

                quoted && character == '\\' -> {
                    current.append(character)
                    escaped = true
                }

                character == '"' -> {
                    current.append(character)
                    quoted = !quoted
                }

                character == ';' && !quoted -> {
                    tokens += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
        }
        if (quoted || escaped) {
            fail(failureReason, "$label contains an unterminated quoted value")
        }
        tokens += current.toString()
        return tokens
    }

    private fun decodeHeaderParameter(
        value: String,
        failureReason: MultipartParseException.Reason,
        label: String,
    ): String {
        if (!value.startsWith('"')) {
            if (value.contains('"')) {
                fail(failureReason, "$label contains an invalid quoted value")
            }
            return value
        }
        if (value.length < 2 || !value.endsWith('"')) {
            fail(failureReason, "$label contains an unterminated quoted value")
        }

        val decoded = StringBuilder()
        var index = 1
        val end = value.lastIndex
        while (index < end) {
            val character = value[index]
            when {
                character == '"' -> fail(failureReason, "$label contains an unescaped quote")

                character == '\\' -> {
                    index += 1
                    if (index >= end) {
                        fail(failureReason, "$label contains an incomplete escape")
                    }
                    decoded.append(value[index])
                }

                else -> decoded.append(character)
            }
            index += 1
        }
        return decoded.toString()
    }

    private fun findNextBoundary(
        body: ByteArray,
        marker: ByteArray,
        start: Int,
    ): Int {
        var candidate = body.indexOf(marker, start)
        while (candidate >= 0) {
            val suffixStart = candidate + marker.size
            if (body.matchesAt(suffixStart, FINAL_SUFFIX) || body.matchesAt(suffixStart, CRLF)) {
                return candidate
            }
            candidate = body.indexOf(marker, candidate + 1)
        }
        return -1
    }

    private fun validateFinalBoundary(
        body: ByteArray,
        suffixEnd: Int,
    ) {
        val end = if (body.matchesAt(suffixEnd, CRLF)) suffixEnd + CRLF.size else suffixEnd
        if (end != body.size) {
            fail(
                MultipartParseException.Reason.MALFORMED_BODY,
                "Multipart body contains data after the final boundary",
            )
        }
    }

    private fun validateDispositionValue(
        value: String,
        label: String,
    ) {
        if (value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            fail(
                MultipartParseException.Reason.INVALID_CONTENT_DISPOSITION,
                "Multipart $label contains invalid characters",
            )
        }
    }

    private fun detectImageContentType(bytes: ByteArray): String? = when {
        bytes.matchesAt(0, PNG_SIGNATURE) -> IMAGE_PNG

        bytes.size >= JPEG_SIGNATURE.size && bytes.matchesAt(0, JPEG_SIGNATURE) -> IMAGE_JPEG

        bytes.size >= WEBP_MINIMUM_BYTES &&
            bytes.matchesAt(0, RIFF_SIGNATURE) &&
            bytes.matchesAt(WEBP_MARKER_OFFSET, WEBP_SIGNATURE) -> IMAGE_WEBP

        else -> null
    }

    private fun normalizeImageContentType(value: String?): String? = when (value) {
        null -> null
        IMAGE_PNG -> IMAGE_PNG
        IMAGE_JPEG, IMAGE_JPG -> IMAGE_JPEG
        IMAGE_WEBP -> IMAGE_WEBP
        APPLICATION_OCTET_STREAM -> APPLICATION_OCTET_STREAM
        else -> null
    }

    private fun ByteArray.indexOf(
        needle: ByteArray,
        start: Int,
    ): Int {
        if (needle.isEmpty()) return start.coerceAtMost(size)
        val lastStart = size - needle.size
        var index = start.coerceAtLeast(0)
        while (index <= lastStart) {
            if (matchesAt(index, needle)) return index
            index += 1
        }
        return -1
    }

    private fun ByteArray.matchesAt(
        offset: Int,
        expected: ByteArray,
    ): Boolean {
        if (offset < 0 || offset > size - expected.size) return false
        expected.indices.forEach { index ->
            if (this[offset + index] != expected[index]) return false
        }
        return true
    }

    private data class ParsedHeader(
        val value: String,
        val parameters: Map<String, String>,
    )

    private data class RawPart(
        val headers: Map<String, String>,
        val bytes: ByteArray,
    )

    private companion object {
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val TEXT_PLAIN = "text/plain"
        const val APPLICATION_OCTET_STREAM = "application/octet-stream"
        const val IMAGE_PNG = "image/png"
        const val IMAGE_JPEG = "image/jpeg"
        const val IMAGE_JPG = "image/jpg"
        const val IMAGE_WEBP = "image/webp"
        const val IMAGE_FIELD = "image"
        const val IMAGE_ARRAY_FIELD = "image[]"
        const val MASK_FIELD = "mask"
        const val WEBP_MINIMUM_BYTES = 12
        const val WEBP_MARKER_OFFSET = 8

        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        val HEADER_SEPARATOR = CRLF + CRLF
        val FINAL_SUFFIX = byteArrayOf('-'.code.toByte(), '-'.code.toByte())
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
        val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val RIFF_SIGNATURE = "RIFF".toByteArray(StandardCharsets.US_ASCII)
        val WEBP_SIGNATURE = "WEBP".toByteArray(StandardCharsets.US_ASCII)
        val BOUNDARY_PATTERN = Regex("^[0-9A-Za-z'()+_,./:=?-]{1,70}$")
        val HEADER_NAME_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val HEADER_PARAMETER_NAME_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        fun fail(
            reason: MultipartParseException.Reason,
            message: String,
        ): Nothing = throw MultipartParseException(reason, message)
    }
}
