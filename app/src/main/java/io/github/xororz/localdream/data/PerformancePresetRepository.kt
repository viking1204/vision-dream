package io.github.xororz.localdream.data

import java.util.UUID

/**
 * 用户可选的推理性能配置。revision 用于将每次受理的推理固定到可追溯版本。
 */
data class PerformancePreset(
    val id: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
    val isFallback: Boolean = false,
)

data class PresetSnapshot(
    val presetId: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
)

data class PresetImport(
    val name: String,
    val selector: String,
    val configJson: String,
)

interface PerformancePresetStore {
    fun all(): List<PerformancePreset>
    fun get(id: String): PerformancePreset?
    fun getByName(name: String): PerformancePreset?
    fun save(preset: PerformancePreset)
    fun delete(id: String): Boolean
}

/**
 * 性能预设领域规则。持久层适配器必须在单个 Room transaction 内调用这些写操作。
 */
class PerformancePresetRepository(private val store: PerformancePresetStore) {
    init {
        if (store.get(COMPATIBILITY_FALLBACK_ID) == null) {
            store.save(
                PerformancePreset(
                    id = COMPATIBILITY_FALLBACK_ID,
                    name = "Compatibility fallback",
                    selector = "COMPATIBILITY_FALLBACK",
                    configJson = "{}",
                    revision = 1,
                    isFallback = true,
                ),
            )
        }
    }

    fun get(id: String): PerformancePreset? = synchronized(this) { store.get(id) }

    /**
     * Returns product-owned preset metadata only. Callers must still create an
     * immutable snapshot before they accept an inference request.
     */
    fun list(): List<PerformancePreset> = synchronized(this) { store.all() }

    fun create(name: String, selector: String, configJson: String): PerformancePreset = synchronized(this) {
        validate(name, selector, configJson)
        require(store.getByName(name.trim()) == null) { "Preset name already exists" }
        PerformancePreset(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            selector = selector.trim(),
            configJson = configJson.trim(),
            revision = 1,
        ).also(store::save)
    }

    fun update(
        id: String,
        expectedRevision: Long,
        name: String,
        selector: String,
        configJson: String,
    ): PerformancePreset = synchronized(this) {
        validate(name, selector, configJson)
        val current = requireNotNull(store.get(id)) { "Preset not found" }
        require(!current.isFallback) { "Compatibility fallback cannot be modified" }
        require(current.revision == expectedRevision) { "Preset revision conflict" }
        val sameName = store.getByName(name.trim())
        require(sameName == null || sameName.id == id) { "Preset name already exists" }
        current.copy(
            name = name.trim(),
            selector = selector.trim(),
            configJson = configJson.trim(),
            revision = current.revision + 1,
        ).also(store::save)
    }

    fun delete(id: String): Boolean = synchronized(this) {
        val preset = store.get(id) ?: return false
        if (preset.isFallback) return false
        store.delete(id)
    }

    fun snapshot(id: String): PresetSnapshot = synchronized(this) {
        store.get(id)?.let(::snapshotOf) ?: error("Preset not found")
    }

    fun import(items: List<PresetImport>): List<PerformancePreset> = synchronized(this) {
        items.forEach { validate(it.name, it.selector, it.configJson) }
        items.map { item ->
            val uniqueName = uniqueImportName(item.name.trim())
            create(uniqueName, item.selector, item.configJson)
        }
    }

    private fun uniqueImportName(name: String): String {
        if (store.getByName(name) == null) return name
        var suffix = 2
        while (store.getByName("$name ($suffix)") != null) suffix++
        return "$name ($suffix)"
    }

    private fun validate(name: String, selector: String, configJson: String) {
        require(name.trim().isNotEmpty()) { "Preset name is required" }
        require(selector.trim().matches(SELECTOR)) { "Preset selector is invalid" }
        val normalizedJson = configJson.trim()
        require(normalizedJson.startsWith('{') && normalizedJson.endsWith('}')) {
            "Preset config must be a JSON object"
        }
    }

    private fun snapshotOf(preset: PerformancePreset) = PresetSnapshot(
        presetId = preset.id,
        name = preset.name,
        selector = preset.selector,
        configJson = preset.configJson,
        revision = preset.revision,
    )

    companion object {
        const val COMPATIBILITY_FALLBACK_ID = "00000000-0000-4000-8000-000000000000"
        private val SELECTOR = Regex("[A-Za-z0-9_.-]{1,80}")
    }
}

/** 仅供 JVM 规则测试和不接入 Room 的调用方使用。 */
class InMemoryPerformancePresetStore : PerformancePresetStore {
    private val values = LinkedHashMap<String, PerformancePreset>()

    override fun all(): List<PerformancePreset> = values.values.toList()

    override fun get(id: String): PerformancePreset? = values[id]

    override fun getByName(name: String): PerformancePreset? = values.values.firstOrNull { it.name == name }

    override fun save(preset: PerformancePreset) {
        values[preset.id] = preset
    }

    override fun delete(id: String): Boolean = values.remove(id) != null
}
