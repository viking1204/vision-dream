package io.github.xororz.localdream.data

import java.security.MessageDigest

/** 真机验收工件授予的预设资格层级。 */
enum class PerformancePresetQualificationLevel {
    TARGET_VALIDATED,
    FINAL_VALIDATED,
}

/**
 * 自动绑定必须精确匹配的不可变运行环境。显式用户选择不需要该资格，仍可作为探索运行。
 */
data class PresetQualificationContext(
    val modelId: String,
    val modelAssetSha256: String,
    val runtimeFingerprint: String,
    val scenarioSetSha256: String,
    val appBuild: String,
    val presetSnapshotSha256: String,
) {
    init {
        require(modelId.isNotBlank() && appBuild.isNotBlank()) { "Qualification identity is incomplete" }
        listOf(modelAssetSha256, runtimeFingerprint, scenarioSetSha256, presetSnapshotSha256).forEach {
            require(it.matches(SHA256)) { "Qualification digest is invalid" }
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * `performance_preset_qualifications` 的领域投影。资格只来自可审计目标机工件；撤销后
 * 永远不能被 DEFAULT 或 MODEL 自动绑定再次使用。
 */
data class PerformancePresetQualification(
    val id: String,
    val presetId: String,
    val presetRevision: Long,
    val presetSnapshotSha256: String,
    val modelId: String,
    val modelAssetSha256: String,
    val scenarioSetSha256: String,
    val runtimeFingerprint: String,
    val appBuild: String,
    val qualificationLevel: PerformancePresetQualificationLevel,
    val evidenceManifestSha256: String,
    val createdAt: Long,
    val revokedAt: Long? = null,
) {
    val isActive: Boolean
        get() = revokedAt == null

    fun matches(preset: PerformancePreset, context: PresetQualificationContext): Boolean = isActive && presetId == preset.id && presetRevision == preset.revision &&
        presetSnapshotSha256 == context.presetSnapshotSha256 && modelId == context.modelId &&
        modelAssetSha256 == context.modelAssetSha256 && runtimeFingerprint == context.runtimeFingerprint &&
        scenarioSetSha256 == context.scenarioSetSha256 && appBuild == context.appBuild

    companion object {
        fun snapshotSha256(preset: PerformancePreset): String = sha256(
            listOf(preset.id, preset.revision.toString(), preset.name, preset.selector, preset.configJson).joinToString("\u0000"),
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

interface PerformancePresetQualificationStore {
    fun all(): List<PerformancePresetQualification>
    fun save(qualification: PerformancePresetQualification)
    fun revokeForPreset(presetId: String, revokedAt: Long = System.currentTimeMillis())

    fun hasActiveTargetQualification(preset: PerformancePreset, context: PresetQualificationContext): Boolean = all().any { qualification ->
        qualification.qualificationLevel in TARGET_LEVELS && qualification.matches(preset, context)
    }

    private companion object {
        val TARGET_LEVELS = setOf(
            PerformancePresetQualificationLevel.TARGET_VALIDATED,
            PerformancePresetQualificationLevel.FINAL_VALIDATED,
        )
    }
}

class PresetNotTargetValidatedException : IllegalArgumentException("PRESET_NOT_TARGET_VALIDATED")

/** 仅供 JVM 规则测试使用的资格存储。 */
class InMemoryPerformancePresetQualificationStore : PerformancePresetQualificationStore {
    private val values = LinkedHashMap<String, PerformancePresetQualification>()

    override fun all(): List<PerformancePresetQualification> = values.values.toList()

    override fun save(qualification: PerformancePresetQualification) {
        values[qualification.id] = qualification
    }

    override fun revokeForPreset(presetId: String, revokedAt: Long) {
        values.entries.filter { it.value.presetId == presetId && it.value.isActive }.forEach { entry ->
            entry.setValue(entry.value.copy(revokedAt = revokedAt))
        }
    }
}
