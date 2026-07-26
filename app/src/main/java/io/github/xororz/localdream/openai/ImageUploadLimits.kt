package io.github.xororz.localdream.openai

internal data class ImageUploadLimits(
    val maxEdge: Int,
    val maxPixels: Long,
) {
    init {
        require(maxEdge > 0)
        require(maxPixels > 0)
    }

    fun accepts(width: Int, height: Int): Boolean = width > 0 &&
        height > 0 &&
        width <= maxEdge &&
        height <= maxEdge &&
        width.toLong() * height.toLong() <= maxPixels
}

internal val EDIT_IMAGE_LIMITS = ImageUploadLimits(
    maxEdge = 8_192,
    maxPixels = 16_777_216L,
)

internal val UPSCALE_IMAGE_LIMITS = ImageUploadLimits(
    maxEdge = 8_192,
    maxPixels = 1_048_576L,
)
