package io.github.xororz.localdream.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "generation_history",
    indices = [
        Index(value = ["modelId", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["mode"]),
        Index(value = ["origin", "timestamp"]),
        Index(value = ["jobId"]),
        Index(value = ["presetId"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val modelId: String,
    val timestamp: Long,
    val imagePath: String,

    val width: Int,
    val height: Int,

    val mode: String,
    val denoiseStrength: Float?,

    val upscalerId: String?,

    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val prompt: String,
    val negativePrompt: String,
    val generationTime: String?,
    val scheduler: String,
    val runOnCpu: Boolean,
    val useOpenCL: Boolean,

    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,

    @ColumnInfo(defaultValue = "'local_app'")
    val origin: String = "local_app",

    @ColumnInfo(defaultValue = "'image/png'")
    val mimeType: String = "image/png",

    val requestId: String? = null,

    // 旧 history 不回填这些关联；v4 记录在 v5 migration 后保持 null。
    val jobId: String? = null,
    val presetId: String? = null,
    val presetRevision: Long? = null,
    val runtimeFingerprint: String? = null,
)
