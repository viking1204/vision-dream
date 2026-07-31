package io.github.xororz.localdream.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformancePresetQualificationEvidenceTest {
    @Test
    fun acceptsOnlyCandidatesBoundToTheExactRunManifestAndDeduplicatesGroupRows() {
        val manifest = validManifest()
        val digest = PerformancePresetQualificationEvidence.sha256(manifest.toByteArray())
        val candidate = candidateJson(digest, runtimeFingerprint = manifestRuntimeFingerprint())
        val candidates = PerformancePresetQualificationEvidence.parse(
            "{\"schemaVersion\":1,\"candidates\":[$candidate,$candidate]}",
            manifest,
        )

        assertEquals(1, candidates.size)
        assertEquals("model-a", candidates.single().modelId)
        assertEquals(PerformancePresetQualificationLevel.TARGET_VALIDATED, candidates.single().qualificationLevel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCandidatesWhoseManifestDigestDoesNotMatchTheImportedManifest() {
        PerformancePresetQualificationEvidence.parse(
            "{\"schemaVersion\":1,\"candidates\":[${candidateJson("b".repeat(64))}]}",
            "{\"runId\":\"target-run\"}",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCandidateWhoseGroupKeyDoesNotMatchItsRuntimeIdentity() {
        val manifest = validManifest()
        val digest = PerformancePresetQualificationEvidence.sha256(manifest.toByteArray())
        PerformancePresetQualificationEvidence.parse(
            "{\"schemaVersion\":1,\"candidates\":[${candidateJson(digest, "e".repeat(64), manifestRuntimeFingerprint())}]}",
            manifest,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsACompleteCandidateWhenTheManifestDoesNotProveTargetAcceptance() {
        val manifest = validManifest(validationLevel = "EXPLORATORY")
        val digest = PerformancePresetQualificationEvidence.sha256(manifest.toByteArray())
        PerformancePresetQualificationEvidence.parse(
            "{\"schemaVersion\":1,\"candidates\":[${candidateJson(digest, runtimeFingerprint = manifestRuntimeFingerprint())}]}",
            manifest,
        )
    }

    private fun candidateJson(
        manifestDigest: String,
        groupRuntimeFingerprint: String = manifestRuntimeFingerprint(),
        runtimeFingerprint: String = manifestRuntimeFingerprint(),
    ): String {
        val preset = "a".repeat(64)
        val runtime = runtimeFingerprint
        return """
            {"runId":"target-run","qualificationLevel":"TARGET_VALIDATED","presetSnapshotSha256":"$preset","modelId":"model-a","modelAssetSha256":"c${"c".repeat(63)}","scenarioSetSha256":"${scenarioSetSha256()}","runtimeFingerprint":"$runtime","appBuild":"1.0","evidenceManifestSha256":"$manifestDigest","groupKey":{"scenarioSha256":"${scenarioSha256()}","presetSnapshotSha256":"$preset","runtimeFingerprint":"$groupRuntimeFingerprint","coldState":"PROCESS_COLD","harnessVersion":"1"}}
        """.trimIndent()
    }

    private fun validManifest(validationLevel: String = "TARGET_VALIDATED"): String {
        val probe = probeJson()
        val scenarioSha = scenarioSha256()
        val manifest = JSONObject()
            .put("manifestVersion", 2)
            .put("runId", "target-run")
            .put("validationLevel", validationLevel)
            .put("startedAt", "2026-07-30T00:00:00Z")
            .put("harnessVersion", "1")
            .put("scenarioDigests", JSONObject().put("W1", scenarioSha))
            .put(
                "scenarioContracts",
                JSONObject().put(
                    "W1",
                    JSONObject()
                        .put("scenarioVersion", 1).put("fixtures", JSONObject())
                        .put("modelMetadata", JSONObject().put("selector", "model-a").put("assetSha256", "c${"c".repeat(63)}"))
                        .put("coldState", "PROCESS_COLD"),
                ),
            )
            .put("presetSnapshotSha256", "a".repeat(64))
            .put("appBuild", "1.0")
            .put("androidVersion", "16")
            .put("network", JSONObject().put("transport", "wifi"))
            .put("battery", JSONObject().put("level", 90))
            .put("screen", JSONObject().put("state", "on"))
            .put("ambientTemperatureC", 25)
            .put("contextFingerprint", "f".repeat(64))
            .put("runtimeProbe", probe)
            .put("acceptanceEvidence", JSONObject().put("baselineId", "b0-1").put("baselineSha256", "d".repeat(64)).put("qualityEvidenceSha256", "e".repeat(64)))
            .put("adbTarget", JSONObject().put("serial", "3B15C4018L500000").put("model", "PJZ110").put("soc", "SM8750").put("boardPlatform", "sun").put("abi", "arm64-v8a"))
            .put(
                "groupArtifacts",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("groupId", "group-1").put("groupKey", JSONObject().put("scenarioSha256", scenarioSha).put("presetSnapshotSha256", "a".repeat(64)).put("runtimeFingerprint", manifestRuntimeFingerprint()).put("coldState", "PROCESS_COLD").put("harnessVersion", "1"))
                        .put("artifactDirectory", "groups/group-1").put("sampleCount", 5)
                        .put("conclusion", if (validationLevel == "TARGET_VALIDATED") "TARGET_VALIDATED" else "NOT_ACCEPTED_FOR_ONEPLUS13"),
                ),
            )
            .put("replayable", true)
            .put("missingReplayFacts", org.json.JSONArray())
        return manifest.toString()
    }

    private fun probeJson(): JSONObject = JSONObject()
        .put("status", "VERIFIED").put("device_model", "PJZ110").put("soc", "SM8750")
        .put("abi", "arm64-v8a").put("qairt_version", "2.48.40").put("htp_target", "v79")
        .put("context_fingerprint", "f".repeat(64))
        .put("loaded_library_fingerprints", JSONObject().put("libQnnHtp.so", "b".repeat(64)))
        .put("native_ready", true).put("rejection_reasons", org.json.JSONArray())

    private fun manifestRuntimeFingerprint(): String = PerformancePresetQualificationEvidence.runtimeFingerprint(probeJson())

    private fun scenarioSha256(): String = "d".repeat(64)

    private fun scenarioSetSha256(): String = PerformancePresetQualificationEvidence.sha256(
        "{\"W1\":\"${scenarioSha256()}\"}".toByteArray(),
    )
}
