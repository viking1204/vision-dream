package io.github.xororz.localdream.mcp

import android.content.Context
import io.github.xororz.localdream.openai.InstalledModelCatalog
import kotlinx.coroutines.runBlocking

/**
 * MCP-facing view of a validated installed model. It intentionally excludes
 * filesystem paths and implementation objects so a read scope cannot become a
 * local file-discovery capability.
 */
data class McpInstalledModel(
    val id: String,
    val name: String,
    val kind: String,
    val backendType: String,
    val generationSize: Int,
    val supportsImageInput: Boolean,
)

interface McpInstalledModelCatalog {
    fun all(): List<McpInstalledModel>

    fun find(id: String): McpInstalledModel? = all().firstOrNull { it.id == id }

    object Unavailable : McpInstalledModelCatalog {
        override fun all(): List<McpInstalledModel> = emptyList()
    }
}

/** Adapts the product's runtime-validated catalog without broadening MCP access. */
class AndroidMcpInstalledModelCatalog(context: Context) : McpInstalledModelCatalog {
    private val catalog = InstalledModelCatalog(context.applicationContext)

    override fun all(): List<McpInstalledModel> = runBlocking {
        catalog.all().map { entry ->
            McpInstalledModel(
                id = entry.id,
                name = entry.name,
                kind = entry.kind.name.lowercase(),
                backendType = entry.backendType,
                generationSize = entry.generationSize,
                supportsImageInput = entry.supportsImageInput,
            )
        }
    }
}
