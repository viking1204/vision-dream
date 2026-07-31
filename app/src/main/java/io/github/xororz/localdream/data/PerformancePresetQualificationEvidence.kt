package io.github.xororz.localdream.data

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Host harness 产出的资格候选。候选不是资格本身：Android 仅在同时核对
 * RunManifest 摘要、当前预设快照、模型摘要、运行时指纹和构建版本后才会落库。
 */
data class PerformancePresetQualificationCandidate(
    val runId: String,
    val qualificationLevel: PerformancePresetQualificationLevel,
    val presetSnapshotSha256: String,
    val modelId: String,
    val modelAssetSha256: String,
    val scenarioSetSha256: String,
    val runtimeFingerprint: String,
    val appBuild: String,
    val evidenceManifestSha256: String,
) {
    init {
        require(runId.isNotBlank() && modelId.isNotBlank() && appBuild.isNotBlank()) { "Qualification candidate is incomplete" }
        listOf(
            presetSnapshotSha256,
            modelAssetSha256,
            scenarioSetSha256,
            runtimeFingerprint,
            evidenceManifestSha256,
        ).forEach { require(it.matches(SHA256)) { "Qualification candidate digest is invalid" } }
    }

    fun context(): PresetQualificationContext = PresetQualificationContext(
        modelId = modelId,
        modelAssetSha256 = modelAssetSha256,
        runtimeFingerprint = runtimeFingerprint,
        scenarioSetSha256 = scenarioSetSha256,
        appBuild = appBuild,
        presetSnapshotSha256 = presetSnapshotSha256,
    )

    companion object {
        internal val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * 验证 `qualification-candidates.json` 与同次 `run-manifest.json` 的摘要关系。
 * 该入口不接受候选自行声明的资格，也不触发任何自动绑定。
 */
object PerformancePresetQualificationEvidence {
    private const val SCHEMA_VERSION = 1
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun parse(candidatesJson: String, runManifestJson: String): List<PerformancePresetQualificationCandidate> {
        val root = JSONObject(candidatesJson)
        require(root.keysSet() == setOf("schemaVersion", "candidates") && root.getInt("schemaVersion") == SCHEMA_VERSION) {
            "Qualification candidates schema is invalid"
        }
        val values = root.getJSONArray("candidates")
        require(values.length() > 0) { "Qualification candidates are empty" }
        val manifest = JSONObject(runManifestJson)
        val manifestSha256 = sha256(runManifestJson.toByteArray(Charsets.UTF_8))
        val parsed = (0 until values.length()).map { index ->
            parseCandidate(values.getJSONObject(index), manifestSha256).also { candidate ->
                requireManifestAcceptanceFacts(manifest, candidate)
            }
        }
        // The harness writes one candidate per GroupKey. Room deliberately
        // stores one active qualification per immutable identity instead.
        return parsed.distinctBy { candidate ->
            listOf(
                candidate.presetSnapshotSha256,
                candidate.modelId,
                candidate.modelAssetSha256,
                candidate.scenarioSetSha256,
                candidate.runtimeFingerprint,
                candidate.appBuild,
                candidate.qualificationLevel.name,
            )
        }
    }

    private fun parseCandidate(value: JSONObject, manifestSha256: String): PerformancePresetQualificationCandidate {
        val required = setOf(
            "runId",
            "qualificationLevel",
            "presetSnapshotSha256",
            "modelId",
            "modelAssetSha256",
            "scenarioSetSha256",
            "runtimeFingerprint",
            "appBuild",
            "evidenceManifestSha256",
            "groupKey",
        )
        require(value.keysSet() == required) { "Qualification candidate fields are invalid" }
        require(value.getJSONObject("groupKey").keysSet().containsAll(setOf("presetSnapshotSha256", "runtimeFingerprint"))) {
            "Qualification candidate group key is invalid"
        }
        val candidate = PerformancePresetQualificationCandidate(
            runId = value.getString("runId").trim(),
            qualificationLevel = PerformancePresetQualificationLevel.valueOf(value.getString("qualificationLevel")),
            presetSnapshotSha256 = value.getString("presetSnapshotSha256").lowercase(),
            modelId = value.getString("modelId").trim(),
            modelAssetSha256 = value.getString("modelAssetSha256").lowercase(),
            scenarioSetSha256 = value.getString("scenarioSetSha256").lowercase(),
            runtimeFingerprint = value.getString("runtimeFingerprint").lowercase(),
            appBuild = value.getString("appBuild").trim(),
            evidenceManifestSha256 = value.getString("evidenceManifestSha256").lowercase(),
        )
        require(candidate.evidenceManifestSha256 == manifestSha256) { "Qualification manifest digest mismatch" }
        require(value.getJSONObject("groupKey").getString("presetSnapshotSha256") == candidate.presetSnapshotSha256) {
            "Qualification candidate preset snapshot mismatch"
        }
        require(value.getJSONObject("groupKey").getString("runtimeFingerprint") == candidate.runtimeFingerprint) {
            "Qualification candidate runtime fingerprint mismatch"
        }
        return candidate
    }

    /**
     * An import is a promotion boundary, not merely a file-format boundary.
     * Both MCP and any future product surface must prove the candidate against
     * the complete run manifest before a Room qualification can be written.
     */
    private fun requireManifestAcceptanceFacts(manifest: JSONObject, candidate: PerformancePresetQualificationCandidate) {
        val required = setOf(
            "manifestVersion", "runId", "validationLevel", "startedAt", "harnessVersion", "scenarioDigests",
            "scenarioContracts", "presetSnapshotSha256", "appBuild", "androidVersion", "network", "battery",
            "screen", "ambientTemperatureC", "contextFingerprint", "runtimeProbe", "acceptanceEvidence",
            "adbTarget", "groupArtifacts", "replayable", "missingReplayFacts",
        )
        require(manifest.keysSet() == required && manifest.getInt("manifestVersion") == 2) { "Qualification manifest schema is invalid" }
        require(manifest.getString("runId") == candidate.runId && manifest.getString("appBuild") == candidate.appBuild) {
            "Qualification manifest run identity mismatch"
        }
        require(manifest.getString("validationLevel") == candidate.qualificationLevel.name) {
            "Qualification manifest validation level mismatch"
        }
        require(manifest.getString("presetSnapshotSha256") == candidate.presetSnapshotSha256) {
            "Qualification manifest preset snapshot mismatch"
        }
        require(manifest.getBoolean("replayable") && manifest.getJSONArray("missingReplayFacts").length() == 0) {
            "Qualification manifest is not replayable"
        }
        require(manifest.getJSONObject("acceptanceEvidence").hasAcceptanceEvidence()) {
            "Qualification manifest lacks frozen B0 or quality evidence"
        }
        require(manifest.getJSONObject("adbTarget").isPjz110Target()) { "Qualification manifest target identity is invalid" }
        val probe = manifest.getJSONObject("runtimeProbe")
        require(probe.isVerifiedTargetProbe()) { "Qualification manifest runtime probe is incomplete" }
        require(manifest.getString("contextFingerprint") == probe.getString("context_fingerprint")) {
            "Qualification manifest context fingerprint mismatch"
        }
        require(runtimeFingerprint(probe) == candidate.runtimeFingerprint) { "Qualification manifest runtime fingerprint mismatch" }
        val scenarioDigests = manifest.getJSONObject("scenarioDigests")
        require(scenarioDigests.hasScenarioSet(candidate.scenarioSetSha256)) { "Qualification manifest scenario set mismatch" }
        val groupKey = manifestGroupKey(candidate, manifest.getJSONArray("groupArtifacts"))
        require(scenarioDigests.keysSet().any { scenarioDigests.getString(it) == groupKey.getString("scenarioSha256") }) {
            "Qualification group scenario is absent"
        }
        require(
            manifest.getJSONObject("scenarioContracts").getJSONObject(groupKeyScenarioId(scenarioDigests, groupKey.getString("scenarioSha256")))
                .getJSONObject("modelMetadata").matchesCandidateModel(candidate),
        ) {
            "Qualification group model identity mismatch"
        }
    }

    private fun manifestGroupKey(
        candidate: PerformancePresetQualificationCandidate,
        groups: org.json.JSONArray,
    ): JSONObject {
        val expectedConclusion = when (candidate.qualificationLevel) {
            PerformancePresetQualificationLevel.TARGET_VALIDATED -> "TARGET_VALIDATED"
            PerformancePresetQualificationLevel.FINAL_VALIDATED -> "ACCEPTED_FOR_ONEPLUS13"
        }
        val matches = (0 until groups.length()).map { groups.getJSONObject(it) }.filter { group ->
            group.keysSet() == setOf("groupId", "groupKey", "artifactDirectory", "sampleCount", "conclusion") &&
                group.getJSONObject("groupKey").sameGroupIdentity(candidate) &&
                group.getString("conclusion") == expectedConclusion && group.getInt("sampleCount") >= requiredMeasurementCount(
                    candidate.qualificationLevel,
                    group.getJSONObject("groupKey").getString("coldState"),
                )
        }
        require(matches.size == 1) { "Qualification candidate has no accepted manifest group" }
        return matches.single().getJSONObject("groupKey")
    }

    private fun requiredMeasurementCount(level: PerformancePresetQualificationLevel, coldState: String): Int = when (level) {
        PerformancePresetQualificationLevel.FINAL_VALIDATED -> 100

        PerformancePresetQualificationLevel.TARGET_VALIDATED -> when (coldState) {
            "DEVICE_COLD", "PROCESS_COLD" -> 5
            "OS_CACHE_WARM", "CONTEXT_WARM" -> 30
            else -> throw IllegalArgumentException("Qualification group cold state is invalid")
        }
    }

    private fun JSONObject.hasAcceptanceEvidence(): Boolean = keysSet() == setOf("baselineId", "baselineSha256", "qualityEvidenceSha256") &&
        getString("baselineId").isNotBlank() && getString("baselineSha256").matches(SHA256) &&
        getString("qualityEvidenceSha256").matches(SHA256)

    private fun JSONObject.isPjz110Target(): Boolean = keysSet() == setOf("serial", "model", "soc", "boardPlatform", "abi") &&
        getString("serial").isNotBlank() && getString("model") == "PJZ110" &&
        getString("soc").equals("SM8750", ignoreCase = true) && getString("boardPlatform").isNotBlank() &&
        getString("abi") == "arm64-v8a"

    private fun JSONObject.isVerifiedTargetProbe(): Boolean {
        val required = setOf(
            "status", "device_model", "soc", "abi", "qairt_version", "htp_target", "context_fingerprint",
            "loaded_library_fingerprints", "native_ready", "rejection_reasons",
        )
        val libraries = getJSONObject("loaded_library_fingerprints")
        return keysSet() == required && getString("status") == "VERIFIED" && getString("device_model") == "PJZ110" &&
            getString("soc").equals("SM8750", ignoreCase = true) && getString("abi") == "arm64-v8a" &&
            getString("qairt_version") == "2.48.40" && getString("htp_target").equals("v79", ignoreCase = true) &&
            getString("context_fingerprint").matches(SHA256) && getBoolean("native_ready") &&
            getJSONArray("rejection_reasons").length() == 0 && libraries.length() > 0 &&
            libraries.keysSet().all { name -> name.isNotBlank() && libraries.getString(name).matches(SHA256) }
    }

    private fun JSONObject.hasScenarioSet(expectedSha256: String): Boolean {
        val values = keysSet().associateWith { getString(it) }
        require(values.isNotEmpty() && values.values.all { it.matches(SHA256) }) { "Qualification scenario digests are invalid" }
        val canonical = values.toSortedMap().entries.joinToString(",", prefix = "{", postfix = "}") { (id, digest) ->
            "${JSONObject.quote(id)}:${JSONObject.quote(digest)}"
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8)) == expectedSha256
    }

    private fun JSONObject.sameGroupIdentity(candidate: PerformancePresetQualificationCandidate): Boolean = keysSet() == setOf("scenarioSha256", "presetSnapshotSha256", "runtimeFingerprint", "coldState", "harnessVersion") &&
        getString("presetSnapshotSha256") == candidate.presetSnapshotSha256 &&
        getString("runtimeFingerprint") == candidate.runtimeFingerprint &&
        getString("scenarioSha256").matches(SHA256) && getString("coldState").isNotBlank() && getString("harnessVersion").isNotBlank()

    private fun groupKeyScenarioId(scenarios: JSONObject, scenarioSha256: String): String = scenarios.keysSet().singleOrNull {
        scenarios.getString(it) == scenarioSha256
    } ?: throw IllegalArgumentException("Qualification group scenario is ambiguous")

    private fun JSONObject.matchesCandidateModel(candidate: PerformancePresetQualificationCandidate): Boolean = keysSet() == setOf("selector", "assetSha256") && getString("selector") == candidate.modelId &&
        getString("assetSha256") == candidate.modelAssetSha256

    /** Mirrors Python's sorted json.dumps(probe_as_dict(probe)) fingerprint. */
    internal fun runtimeFingerprint(probe: JSONObject): String {
        val libraries = probe.getJSONObject("loaded_library_fingerprints").keysSet().sorted().joinToString(", ") { name ->
            "${JSONObject.quote(name)}: ${JSONObject.quote(probe.getJSONObject("loaded_library_fingerprints").getString(name))}"
        }
        val reasons = (0 until probe.getJSONArray("rejection_reasons").length()).joinToString(", ") { index ->
            JSONObject.quote(probe.getJSONArray("rejection_reasons").getString(index))
        }
        val payload = buildString {
            append("{\\\"abi\\\": ").append(JSONObject.quote(probe.getString("abi")))
            append(", \\\"context_fingerprint\\\": ").append(JSONObject.quote(probe.getString("context_fingerprint")))
            append(", \\\"device_model\\\": ").append(JSONObject.quote(probe.getString("device_model")))
            append(", \\\"htp_target\\\": ").append(JSONObject.quote(probe.getString("htp_target")))
            append(", \\\"loaded_library_fingerprints\\\": {").append(libraries).append("}")
            append(", \\\"native_ready\\\": ").append(probe.getBoolean("native_ready"))
            append(", \\\"qairt_version\\\": ").append(JSONObject.quote(probe.getString("qairt_version")))
            append(", \\\"rejection_reasons\\\": [").append(reasons).append("]")
            append(", \\\"soc\\\": ").append(JSONObject.quote(probe.getString("soc")))
            append(", \\\"status\\\": ").append(JSONObject.quote(probe.getString("status"))).append("}")
        }
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun JSONObject.keysSet(): Set<String> = keys().run {
        buildSet {
            while (hasNext()) add(next())
        }
    }
}
