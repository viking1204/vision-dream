package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelFileLayout
import io.github.xororz.localdream.data.ModelFileLayouts
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Hard limits shared by catalog ZIP and checkpoint downloads. */
internal object CatalogArtifactDownloadLimits {
    const val MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L * 1024L

    fun maximumBytes(expectedSizeBytes: Long?): Long {
        require(expectedSizeBytes == null || expectedSizeBytes in 1..MAX_DOWNLOAD_BYTES) {
            "Catalog artifact size is invalid or exceeds the download limit"
        }
        return expectedSizeBytes ?: MAX_DOWNLOAD_BYTES
    }
}

/**
 * Flat ZIP extraction with limits applied to every entry and every inflated byte.
 * Ignored and directory entries are drained explicitly so ZipInputStream cannot
 * perform unbounded work while implicitly advancing to the next entry.
 */
internal object BoundedModelZipExtractor {
    const val MAX_ENTRIES = 256
    const val MAX_EXTRACTED_BYTES = CatalogArtifactDownloadLimits.MAX_DOWNLOAD_BYTES

    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    ) {
        init {
            require(maxEntries > 0)
            require(maxExtractedBytes > 0L)
        }
    }

    suspend fun extractFlat(
        zipStream: InputStream,
        destination: File,
        limits: Limits = Limits(),
        onExtractedBytes: (Long) -> Unit = {},
    ) {
        var entryCount = 0
        var extractedBytes = 0L
        val extractedNames = mutableSetOf<String>()
        val coroutineContext = currentCoroutineContext()

        ZipInputStream(zipStream.buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Archive contains too many entries"
                }

                val fileName = entry.name.substringAfterLast('/')
                val shouldExtract = !entry.isDirectory &&
                    fileName.isNotEmpty() &&
                    !fileName.startsWith(".") &&
                    !entry.name.split('/').contains("__MACOSX")
                val output = if (shouldExtract) {
                    require(extractedNames.add(fileName)) {
                        "Archive contains duplicate '$fileName'"
                    }
                    BufferedOutputStream(File(destination, fileName).outputStream())
                } else {
                    null
                }

                try {
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        coroutineContext.ensureActive()
                        if (count < 0) break
                        require(extractedBytes <= limits.maxExtractedBytes - count.toLong()) {
                            "Extracted model is too large"
                        }
                        extractedBytes += count
                        output?.write(buffer, 0, count)
                        onExtractedBytes(extractedBytes)
                    }
                    input.closeEntry()
                } finally {
                    output?.close()
                }
                entry = input.nextEntry
            }
        }
    }

    private const val COPY_BUFFER_BYTES = 32 * 1024
}

/** Detects a model layout only when every required file contains data. */
internal object PreparedModelValidator {
    fun detectCompleteLayout(directory: File): ModelFileLayout? {
        val fileNames = directory.listFiles()
            ?.filter(File::isFile)
            ?.mapTo(mutableSetOf(), File::getName)
            .orEmpty()
        val layout = ModelFileLayouts.detect(fileNames) ?: return null
        return layout.takeIf {
            it.requiredFiles.all { name ->
                val file = File(directory, name)
                file.isFile && file.length() > 0L
            }
        }
    }
}
