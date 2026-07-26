package io.github.xororz.localdream.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "prompt_templates",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["lastUsedAt"]),
    ],
)
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    @ColumnInfo(defaultValue = "0")
    val useCount: Int = 0,
)
