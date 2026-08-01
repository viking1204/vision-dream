package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Records models whose editable prompt samples have already been created. */
@Entity(tableName = "prompt_sample_seed_models")
data class PromptSampleSeedEntity(
    @PrimaryKey
    val modelId: String,
    val seededAt: Long,
)
