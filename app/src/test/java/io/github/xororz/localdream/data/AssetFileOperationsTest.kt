package io.github.xororz.localdream.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFileOperationsTest {
    @Test
    fun `detects supported encoded image formats`() {
        val png = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
            0x01,
        )
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01)

        assertEquals(EncodedImageFormat.PNG, EncodedImageFormat.detect(png))
        assertEquals(EncodedImageFormat.JPEG, EncodedImageFormat.detect(jpeg))
        assertEquals(
            EncodedImageFormat.JPEG,
            EncodedImageFormat.fromMimeType("image/jpg; charset=binary"),
        )
        assertNull(EncodedImageFormat.detect(byteArrayOf(1, 2, 3)))
        assertNull(EncodedImageFormat.fromMimeType("image/webp"))
    }

    @Test
    fun `atomic write publishes complete destination and removes part file`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.png")
            val bytes = byteArrayOf(1, 2, 3, 4)

            val result = AssetFileOperations.writeAtomically(destination) {
                it.write(bytes)
                true
            }

            assertTrue(result)
            assertArrayEquals(bytes, destination.readBytes())
            assertFalse(File(directory, "asset.png.part").exists())
        }
    }

    @Test
    fun `failed writer leaves neither destination nor part file`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.png")

            val result = AssetFileOperations.writeAtomically(destination) {
                it.write(byteArrayOf(1, 2, 3))
                false
            }

            assertFalse(result)
            assertFalse(destination.exists())
            assertFalse(File(directory, "asset.png.part").exists())
        }
    }

    @Test
    fun `delete reports whether the file is actually absent`() {
        withTempDirectory { directory ->
            val image = File(directory, "asset.png").apply { writeBytes(byteArrayOf(1)) }
            val nonEmptyDirectory = File(directory, "not-an-image").apply {
                mkdirs()
                File(this, "child").writeText("data")
            }

            assertTrue(AssetFileOperations.deleteIfPresent(image))
            assertFalse(image.exists())
            assertTrue(AssetFileOperations.deleteIfPresent(image))
            assertFalse(AssetFileOperations.deleteIfPresent(nonEmptyDirectory))
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("vision-dream-assets").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
