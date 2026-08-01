package io.github.xororz.localdream.modelcatalog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryConfigSerializationTest {
    @Test
    fun singleConfigRoundTripsThroughJson() {
        val original = RepositoryConfig(
            id = "mirror-hf",
            name = "HF Mirror",
            baseUrl = "https://hf-mirror.com/",
            enabled = false,
            type = RepositoryType.HUGGINGFACE,
        )

        val restored = RepositoryConfig.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun serializeDeserializeRoundTripsAList() {
        val list = listOf(
            RepositoryConfig(
                id = "default",
                name = "Default",
                baseUrl = "https://huggingface.co",
                type = RepositoryType.HUGGINGFACE,
            ),
            RepositoryConfig(
                id = "json-index",
                name = "Local JSON",
                baseUrl = "https://example.test/index.json",
                enabled = false,
                type = RepositoryType.JSON_INDEX,
            ),
            RepositoryConfig(
                id = "dir-store",
                name = "Directory Store",
                baseUrl = "https://example.test/models",
                type = RepositoryType.DIRECTORY,
            ),
        )

        val restored = RepositoryConfig.deserializeList(RepositoryConfig.serializeList(list))

        assertEquals(list, restored)
    }

    @Test
    fun serializeEmptyListProducesEmptyJsonArray() {
        val serialized = RepositoryConfig.serializeList(emptyList())

        assertEquals("[]", serialized)
        assertTrue(RepositoryConfig.deserializeList(serialized).isEmpty())
    }

    @Test
    fun deserializeEmptyArrayReturnsEmptyList() {
        assertTrue(RepositoryConfig.deserializeList("[]").isEmpty())
    }

    @Test
    fun fromJsonAppliesDefaultsForMissingOptionalFields() {
        val json = JSONObject().apply {
            put("id", "minimal")
            put("name", "Minimal")
            put("baseUrl", "https://example.test")
        }

        val restored = RepositoryConfig.fromJson(json)

        assertEquals("minimal", restored.id)
        assertEquals("Minimal", restored.name)
        assertEquals("https://example.test", restored.baseUrl)
        assertTrue(restored.enabled)
        assertEquals(RepositoryType.HUGGINGFACE, restored.type)
    }

    @Test
    fun fromJsonFallsBackToHuggingFaceForUnknownType() {
        val json = JSONObject().apply {
            put("id", "unknown")
            put("name", "Unknown")
            put("baseUrl", "https://example.test")
            put("type", "TOTALLY_UNKNOWN")
        }

        val restored = RepositoryConfig.fromJson(json)

        assertEquals(RepositoryType.HUGGINGFACE, restored.type)
    }

    @Test
    fun fromJsonRespectsExplicitDisabledAndJsonIndexType() {
        val json = JSONObject().apply {
            put("id", "json-idx")
            put("name", "JSON")
            put("baseUrl", "https://example.test/index.json")
            put("enabled", false)
            put("type", "JSON_INDEX")
        }

        val restored = RepositoryConfig.fromJson(json)

        assertEquals(false, restored.enabled)
        assertEquals(RepositoryType.JSON_INDEX, restored.type)
    }

    @Test
    fun serializeListEmitsValidJsonArrayShape() {
        val list = listOf(
            RepositoryConfig(id = "a", name = "A", baseUrl = "https://a.test"),
        )

        val parsed = JSONArray(RepositoryConfig.serializeList(list))

        assertEquals(1, parsed.length())
        assertEquals("a", parsed.getJSONObject(0).getString("id"))
        assertEquals("HUGGINGFACE", parsed.getJSONObject(0).getString("type"))
    }

    @Test
    fun allRepositoryTypeValuesRoundTrip() {
        RepositoryType.values().forEach { type ->
            val original = RepositoryConfig(
                id = type.name,
                name = type.name,
                baseUrl = "https://example.test",
                type = type,
            )

            val restored = RepositoryConfig.fromJson(original.toJson())

            assertEquals(type, restored.type)
        }
    }
}
