package io.github.xororz.localdream.openai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackendClientInstrumentedTest {
    @Test
    fun nativeUpscaleJpegIsNormalizedToPngForThePublicApi() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            ByteArrayOutputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            source.recycle()
        }

        val image = normalizeUpscaledImageForResponse(jpeg)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, dimensions)

        assertEquals("image/png", image.mimeType)
        assertTrue(image.bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
        assertEquals(3, dimensions.outWidth)
        assertEquals(2, dimensions.outHeight)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
    }
}
