package io.github.xororz.localdream.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryEntity
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Immutable
data class HistoryItem(
    val id: Long,
    val modelId: String,
    val imageFile: File,
    val params: GenerationParameters,
    val timestamp: Long,
    val mode: GenerationMode,
    val upscalerId: String?,
    val favorite: Boolean = false,
    val origin: AssetOrigin = AssetOrigin.LOCAL_APP,
    val mimeType: String = "image/png",
    val requestId: String? = null,
) {
    companion object {
        fun fromEntity(filesDir: File, e: HistoryEntity): HistoryItem {
            val imageFile = File(filesDir, e.imagePath)
            val mode = GenerationMode.fromString(e.mode)
            return HistoryItem(
                id = e.id,
                modelId = e.modelId,
                imageFile = imageFile,
                timestamp = e.timestamp,
                mode = mode,
                upscalerId = e.upscalerId,
                favorite = e.favorite,
                origin = AssetOrigin.fromPersistedValue(e.origin),
                mimeType = e.mimeType,
                requestId = e.requestId,
                params = GenerationParameters(
                    steps = e.steps,
                    cfg = e.cfg,
                    seed = e.seed,
                    prompt = e.prompt,
                    negativePrompt = e.negativePrompt,
                    generationTime = e.generationTime,
                    width = e.width,
                    height = e.height,
                    runOnCpu = e.runOnCpu,
                    denoiseStrength = e.denoiseStrength ?: 0.6f,
                    useOpenCL = e.useOpenCL,
                    scheduler = e.scheduler,
                    mode = mode,
                ),
            )
        }
    }
}

// Keep id batches under SQLite's host-parameter limit (999 on older API levels).
private const val SQLITE_IN_CHUNK = 900

internal class AssetPersistenceQueue(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun <T> submit(block: suspend () -> T): Deferred<T> = scope.async {
        block()
    }

    internal fun cancel() {
        scope.cancel()
    }
}

class HistoryManager(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.historyDao()
    private val filesDir: File = context.filesDir

    private fun getHistoryDir(modelId: String): File {
        val dir = File(filesDir, "history/$modelId")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Could not create history directory for $modelId")
        }
        return dir
    }

    suspend fun saveGeneratedImage(
        modelId: String,
        bitmap: Bitmap,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
        origin: AssetOrigin = AssetOrigin.LOCAL_APP,
        requestId: String? = null,
    ): HistoryItem? {
        // Upscaled and ultrafixed images are 4x-class resolutions; store
        // them as JPEG (PNG would be tens of MB and seconds to encode).
        val format = if (upscalerId != null || mode == GenerationMode.ULTRAFIX) {
            EncodedImageFormat.JPEG
        } else {
            EncodedImageFormat.PNG
        }
        return saveImageAsset(
            modelId = modelId,
            params = params,
            mode = mode,
            upscalerId = upscalerId,
            origin = origin,
            requestId = requestId,
            format = format,
        ) { output ->
            bitmap.compress(
                if (format == EncodedImageFormat.JPEG) {
                    Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.PNG
                },
                if (format == EncodedImageFormat.JPEG) 95 else 100,
                output,
            )
        }
    }

    /**
     * Starts an asset write in the process-wide persistence scope.
     *
     * The returned deferred is not a child of a screen's Compose scope, so
     * leaving the generation page cannot cancel an image that was already
     * published as complete. A visible screen may await it to update selection
     * state; cancellation of that awaiter does not cancel the write.
     */
    fun enqueueGeneratedImageSave(
        modelId: String,
        bitmap: Bitmap,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
        origin: AssetOrigin = AssetOrigin.LOCAL_APP,
        requestId: String? = null,
    ): Deferred<HistoryItem?> = assetPersistenceQueue.submit {
        saveGeneratedImage(
            modelId = modelId,
            bitmap = bitmap,
            params = params,
            mode = mode,
            upscalerId = upscalerId,
            origin = origin,
            requestId = requestId,
        )
    }

    /**
     * Saves an image that is already encoded, without decoding and recompressing it.
     *
     * This is the persistence entry point for the OpenAI-compatible gateway and
     * any other caller that already owns PNG/JPEG response bytes.
     */
    suspend fun saveEncodedImage(
        modelId: String,
        encodedImage: ByteArray,
        mimeType: String,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
        origin: AssetOrigin = AssetOrigin.LOCAL_APP,
        requestId: String? = null,
    ): HistoryItem? {
        val declaredFormat = EncodedImageFormat.fromMimeType(mimeType)
        val detectedFormat = EncodedImageFormat.detect(encodedImage)
        if (declaredFormat == null || detectedFormat == null || declaredFormat != detectedFormat) {
            Log.e(
                TAG,
                "Refusing asset with unsupported or mismatched image type: $mimeType",
            )
            return null
        }
        return saveImageAsset(
            modelId = modelId,
            params = params,
            mode = mode,
            upscalerId = upscalerId,
            origin = origin,
            requestId = requestId,
            format = detectedFormat,
        ) { output ->
            output.write(encodedImage)
            true
        }
    }

    fun enqueueEncodedImageSave(
        modelId: String,
        encodedImage: ByteArray,
        mimeType: String,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
        origin: AssetOrigin = AssetOrigin.LOCAL_APP,
        requestId: String? = null,
    ): Deferred<HistoryItem?> = assetPersistenceQueue.submit {
        saveEncodedImage(
            modelId = modelId,
            encodedImage = encodedImage,
            mimeType = mimeType,
            params = params,
            mode = mode,
            upscalerId = upscalerId,
            origin = origin,
            requestId = requestId,
        )
    }

    private suspend fun saveImageAsset(
        modelId: String,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String?,
        origin: AssetOrigin,
        requestId: String?,
        format: EncodedImageFormat,
        writer: (OutputStream) -> Boolean,
    ): HistoryItem? = withContext(Dispatchers.IO) {
        var imageFile: File? = null
        try {
            val timestamp = nextAvailableTimestamp(modelId, format.extension)
            val historyDir = getHistoryDir(modelId)
            imageFile = File(historyDir, "$timestamp.${format.extension}")
            if (!AssetFileOperations.writeAtomically(imageFile, writer)) {
                Log.e(TAG, "Failed to write image asset ${imageFile.absolutePath}")
                return@withContext null
            }

            val entity = HistoryEntity(
                modelId = modelId,
                timestamp = timestamp,
                imagePath = "history/$modelId/${imageFile.name}",
                width = params.width,
                height = params.height,
                mode = mode.name,
                denoiseStrength = if (mode == GenerationMode.IMG2IMG ||
                    mode == GenerationMode.INPAINT ||
                    mode == GenerationMode.ULTRAFIX
                ) {
                    params.denoiseStrength
                } else {
                    null
                },
                upscalerId = upscalerId,
                steps = params.steps,
                cfg = params.cfg,
                seed = params.seed,
                prompt = params.prompt,
                negativePrompt = params.negativePrompt,
                generationTime = params.generationTime,
                scheduler = params.scheduler,
                runOnCpu = params.runOnCpu,
                useOpenCL = params.useOpenCL,
                origin = origin.persistedValue,
                mimeType = format.mimeType,
                requestId = requestId?.trim()?.takeIf { it.isNotEmpty() },
            )
            val id = try {
                dao.insert(entity)
            } catch (e: Exception) {
                imageFile.delete()
                throw e
            }
            HistoryItem.fromEntity(filesDir, entity.copy(id = id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image asset", e)
            null
        }
    }

    private suspend fun nextAvailableTimestamp(
        modelId: String,
        extension: String,
    ): Long {
        val historyDir = getHistoryDir(modelId)
        var timestamp = System.currentTimeMillis()
        while (
            File(historyDir, "$timestamp.$extension").exists() ||
            dao.countByKey(modelId, timestamp) > 0
        ) {
            timestamp++
        }
        return timestamp
    }

    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.setFavorite(id, favorite) > 0
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to update favorite", e)
            false
        }
    }

    suspend fun loadHistoryForModel(modelId: String): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            val filter = HistoryFilter(modelIds = setOf(modelId))
            dao.queryOnce(filter.toSqlQuery())
                .map { HistoryItem.fromEntity(filesDir, it) }
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to load history", e)
            emptyList()
        }
    }

    fun observe(filter: HistoryFilter): Flow<List<HistoryItem>> = dao.query(filter.toSqlQuery()).map { entities ->
        entities.map { HistoryItem.fromEntity(filesDir, it) }
    }

    // Paged grid feed. pageSize 60 keeps roughly three screens of 2-column
    // thumbnails resident; placeholders are off so the grid never renders empty
    // slots (the list simply grows as pages load).
    fun pager(filter: HistoryFilter): Flow<PagingData<HistoryItem>> = Pager(
        config = PagingConfig(pageSize = 60, enablePlaceholders = false),
        pagingSourceFactory = { dao.queryPaged(filter.toSqlQuery()) },
    ).flow.map { data -> data.map { HistoryItem.fromEntity(filesDir, it) } }

    fun observeCount(filter: HistoryFilter): Flow<Int> = dao.queryCount(filter.toCountQuery())

    // Newest matches first, capped. Backs the result-page thumbnail strip.
    fun observeRecent(filter: HistoryFilter, limit: Int): Flow<List<HistoryItem>> = dao.query(filter.toRecentQuery(limit)).map { entities ->
        entities.map { HistoryItem.fromEntity(filesDir, it) }
    }

    fun observeFavorite(id: Long): Flow<Boolean?> = dao.observeFavorite(id)

    // Every id matching the filter, in display order. Used by select-all.
    suspend fun queryIds(filter: HistoryFilter): List<Long> = withContext(Dispatchers.IO) {
        try {
            dao.queryIds(filter.toIdQuery())
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to query ids", e)
            emptyList()
        }
    }

    // Resolves a selection (ids) back to items for batch save/delete. Returned
    // in the requested id order so callers can rely on it.
    suspend fun getItems(ids: Collection<Long>): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            // Chunk the IN clause so large select-all sets stay under SQLite's
            // host-parameter limit (999 on older API levels).
            val byId = ids.toList()
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { dao.getByIds(it) }
                .associateBy { it.id }
            ids.mapNotNull { byId[it]?.let { e -> HistoryItem.fromEntity(filesDir, e) } }
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to load items", e)
            emptyList()
        }
    }

    fun observeKnownModelIds(): Flow<List<String>> = dao.observeKnownModelIds()
    fun observeKnownSchedulers(): Flow<List<String>> = dao.observeKnownSchedulers()
    fun observeKnownSizes(): Flow<List<String>> = dao.observeKnownSizes()

    suspend fun deleteHistoryItem(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!AssetFileOperations.deleteIfPresent(item.imageFile)) {
                Log.w(TAG, "Could not delete image file ${item.imageFile.absolutePath}")
                return@withContext false
            }
            dao.deleteById(item.id) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete history item", e)
            false
        }
    }

    // Delete files first, and only remove rows whose file is now absent. This
    // makes every item counted as successful satisfy both halves of "delete the
    // asset". If the Room transaction itself fails, a retry removes the rows
    // whose files are already gone.
    suspend fun deleteHistoryItems(items: List<HistoryItem>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        try {
            val deletableIds = items
                .distinctBy { it.id }
                .filter { item ->
                    AssetFileOperations.deleteIfPresent(item.imageFile).also { deleted ->
                        if (!deleted) {
                            Log.w(TAG, "Could not delete image file ${item.imageFile.absolutePath}")
                        }
                    }
                }
                .map { it.id }
            if (deletableIds.isEmpty()) return@withContext 0

            db.withTransaction {
                var deletedRows = 0
                deletableIds.chunked(SQLITE_IN_CHUNK).forEach { ids ->
                    deletedRows += dao.deleteByIds(ids)
                }
                deletedRows
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete history items", e)
            0
        }
    }

    // Move a model's history (image files + DB rows) to a new id. The DB path
    // rewrite mirrors the directory move so saved thumbnails keep resolving.
    suspend fun renameModel(oldId: String, newId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldDir = File(filesDir, "history/$oldId")
            if (oldDir.exists()) {
                val newDir = File(filesDir, "history/$newId")
                newDir.parentFile?.mkdirs()
                if (newDir.exists()) {
                    oldDir.listFiles()?.forEach { file ->
                        file.renameTo(File(newDir, file.name))
                    }
                    oldDir.delete()
                } else {
                    oldDir.renameTo(newDir)
                }
            }
            dao.renameModelId(oldId, newId)
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to rename model history", e)
            false
        }
    }

    suspend fun clearHistoryForModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteAllForModel(modelId)
            File(filesDir, "history/$modelId").deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to clear history", e)
            false
        }
    }

    companion object {
        private const val TAG = "HistoryManager"
        private val assetPersistenceQueue = AssetPersistenceQueue()
    }
}
