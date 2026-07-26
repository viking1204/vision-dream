package io.github.xororz.localdream.data

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal enum class EncodedImageFormat(
    val mimeType: String,
    val extension: String,
) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    ;

    companion object {
        fun detect(bytes: ByteArray): EncodedImageFormat? = when {
            bytes.hasPrefix(PNG_SIGNATURE) -> PNG
            bytes.hasPrefix(JPEG_SIGNATURE) -> JPEG
            else -> null
        }

        fun fromMimeType(value: String): EncodedImageFormat? = when (
            value.substringBefore(';').trim().lowercase()
        ) {
            PNG.mimeType -> PNG
            JPEG.mimeType, "image/jpg" -> JPEG
            else -> null
        }

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        private val JPEG_SIGNATURE = byteArrayOf(
            0xff.toByte(),
            0xd8.toByte(),
            0xff.toByte(),
        )

        private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean = size >= prefix.size &&
            prefix.indices.all { this[it] == prefix[it] }
    }
}

/**
 * Filesystem operations shared by bitmap and already-encoded asset writes.
 */
internal object AssetFileOperations {
    fun writeAtomically(
        destination: File,
        writer: (OutputStream) -> Boolean,
    ): Boolean {
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return false
        }
        val part = File(destination.parentFile, "${destination.name}.part")
        if (part.exists() && !part.delete()) return false

        return try {
            val written = FileOutputStream(part).use { output ->
                val success = writer(output)
                if (success) output.fd.sync()
                success
            }
            if (!written || destination.exists()) {
                false
            } else {
                part.renameTo(destination)
            }
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()
}
