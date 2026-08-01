package io.github.xororz.localdream.modelcatalog

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRepositorySearchMergerTest {
    // ---------- SearchResultMerger: sort order ----------

    @Test
    fun mergeSortsExactModelIdMatchAheadOfPrefixAndRelevance() {
        val query = "portrait"
        val results = listOf(
            // Relevance tier: neither localId nor displayName matches the query.
            result(localModelId = "anime-portrait-pack", displayName = "Anime Set"),
            // Exact match on localModelId.
            result(localModelId = "portrait", displayName = "Portrait Model"),
            // Prefix match via displayName.
            result(localModelId = "pp_portrait_v2", displayName = "Portrait Plus"),
            // Prefix match via localModelId.
            result(localModelId = "portrait-plus", displayName = "PP"),
            // Relevance tier: contains the word as a substring only.
            result(localModelId = "landscapes", displayName = "Backdrop Pack"),
        )

        val merged = SearchResultMerger.merge(
            query,
            listOf(RepositorySearchOutcome.Success(repositoryConfigId = "repo-1", results = results)),
        )

        // Tier 0: exact. Tier 1 (prefix): insertion order pp_portrait_v2 then
        // portrait-plus. Tier 2 (relevance): insertion order anime-portrait-pack
        // then landscapes.
        assertEquals(
            listOf("portrait", "pp_portrait_v2", "portrait-plus", "anime-portrait-pack", "landscapes"),
            merged.results.map { it.localModelId },
        )
        assertEquals(MultiRepositorySearchStatus.SUCCESS, merged.status)
        assertTrue(merged.perRepositoryErrors.isEmpty())
    }

    @Test
    fun mergePreservesOriginalRelevanceOrderWithinATier() {
        val relevanceTier = listOf(
            result(localModelId = "rel-1", displayName = "Rel One"),
            result(localModelId = "rel-2", displayName = "Rel Two"),
            result(localModelId = "rel-3", displayName = "Rel Three"),
        )

        val merged = SearchResultMerger.merge(
            "portrait",
            listOf(RepositorySearchOutcome.Success(repositoryConfigId = "r", results = relevanceTier)),
        )

        assertEquals(
            listOf("rel-1", "rel-2", "rel-3"),
            merged.results.map { it.localModelId },
        )
    }

    @Test
    fun mergeStablyInterleavesRepositoriesKeepingBuiltInFirstInRelevanceTier() {
        val outcomes = listOf(
            RepositorySearchOutcome.Success(
                repositoryConfigId = null,
                results = listOf(result(localModelId = "builtin-rel", displayName = "Builtin")),
            ),
            RepositorySearchOutcome.Success(
                repositoryConfigId = "custom-a",
                results = listOf(result(localModelId = "custom-a-rel", displayName = "A")),
            ),
            RepositorySearchOutcome.Success(
                repositoryConfigId = "custom-b",
                results = listOf(result(localModelId = "custom-b-rel", displayName = "B")),
            ),
        )

        val merged = SearchResultMerger.merge("portrait", outcomes)

        // All relevance tier; stable sort preserves outcome insertion order.
        assertEquals(
            listOf("builtin-rel", "custom-a-rel", "custom-b-rel"),
            merged.results.map { it.localModelId },
        )
    }

    @Test
    fun mergeRanksExactMatchFromCustomRepoAheadOfRelevanceMatchFromBuiltin() {
        val outcomes = listOf(
            RepositorySearchOutcome.Success(
                repositoryConfigId = null,
                results = listOf(result(localModelId = "builtin-other", displayName = "Builtin")),
            ),
            RepositorySearchOutcome.Success(
                repositoryConfigId = "custom-a",
                results = listOf(result(localModelId = "portrait", displayName = "Custom Exact")),
            ),
        )

        val merged = SearchResultMerger.merge("portrait", outcomes)

        assertEquals(
            listOf("portrait", "builtin-other"),
            merged.results.map { it.localModelId },
        )
    }

    @Test
    fun mergeIsCaseInsensitiveForExactAndPrefixTiers() {
        val merged = SearchResultMerger.merge(
            "Portrait",
            listOf(
                RepositorySearchOutcome.Success(
                    repositoryConfigId = "r",
                    results = listOf(
                        result(localModelId = "PORTRAIT", displayName = "Caps Exact"),
                        result(localModelId = "Portrait-Pro", displayName = "Caps Prefix"),
                        result(localModelId = "other", displayName = "portrait backdrops"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("PORTRAIT", "Portrait-Pro", "other"),
            merged.results.map { it.localModelId },
        )
    }

    // ---------- SearchResultMerger: failure isolation ----------

    @Test
    fun mergeRecordsFailureWithoutDroppingSuccessfulRepositories() {
        val failure = IOException("repository offline")
        val outcomes = listOf(
            RepositorySearchOutcome.Success(
                repositoryConfigId = null,
                results = listOf(result(localModelId = "portrait", displayName = "Builtin")),
            ),
            RepositorySearchOutcome.Failure(repositoryConfigId = "broken", error = failure),
        )

        val merged = SearchResultMerger.merge("portrait", outcomes)

        assertEquals(MultiRepositorySearchStatus.PARTIAL_FAILURE, merged.status)
        assertEquals(1, merged.results.size)
        assertEquals("portrait", merged.results.first().localModelId)
        assertEquals(failure, merged.perRepositoryErrors["broken"])
    }

    @Test
    fun mergeReturnsAllFailedWhenEveryRepositoryFails() {
        val outcomes = listOf(
            RepositorySearchOutcome.Failure(repositoryConfigId = null, error = IOException("builtin down")),
            RepositorySearchOutcome.Failure(repositoryConfigId = "custom-a", error = IOException("a down")),
        )

        val merged = SearchResultMerger.merge("portrait", outcomes)

        assertEquals(MultiRepositorySearchStatus.ALL_FAILED, merged.status)
        assertTrue(merged.results.isEmpty())
        assertEquals(2, merged.perRepositoryErrors.size)
    }

    @Test
    fun mergeReturnsNoRepositoriesWhenOutcomeListIsEmpty() {
        val merged = SearchResultMerger.merge("portrait", emptyList())

        assertEquals(MultiRepositorySearchStatus.NO_REPOSITORIES, merged.status)
        assertTrue(merged.results.isEmpty())
        assertTrue(merged.perRepositoryErrors.isEmpty())
    }

    @Test
    fun mergeReportsSuccessOnlyWhenNoFailuresOccurredEvenWithEmptyResultLists() {
        val outcomes = listOf(
            RepositorySearchOutcome.Success(repositoryConfigId = null, results = emptyList()),
            RepositorySearchOutcome.Success(repositoryConfigId = "custom-a", results = emptyList()),
        )

        val merged = SearchResultMerger.merge("portrait", outcomes)

        assertEquals(MultiRepositorySearchStatus.SUCCESS, merged.status)
        assertTrue(merged.results.isEmpty())
        assertTrue(merged.perRepositoryErrors.isEmpty())
    }

    @Test
    fun mergeTagsResultsWithTheirRepositoryConfigId() {
        val merged = SearchResultMerger.merge(
            "portrait",
            listOf(
                RepositorySearchOutcome.Success(
                    repositoryConfigId = "custom-a",
                    results = listOf(
                        result(localModelId = "portrait", displayName = "A", repositoryConfigId = "custom-a"),
                    ),
                ),
            ),
        )

        assertEquals("custom-a", merged.results.first().repositoryConfigId)
    }

    // ---------- MultiRepositorySearchClient: failure isolation ----------

    @Test
    fun clientIsolatesFailingBuiltInAndStillReturnsCustomResults() = runBlocking {
        val builtInClient = HuggingFaceModelCatalogClient(
            baseUrl = "https://example.test",
            client = failingOkHttpClient("builtin unavailable"),
        )
        val okConfig = RepositoryConfig(id = "ok", name = "OK", baseUrl = "https://ok.test")
        val client = MultiRepositorySearchClient(
            builtInClient = builtInClient,
            customRepositories = listOf(okConfig),
            customSearcher = { config, _, _ ->
                when (config.id) {
                    "ok" -> listOf(
                        result(localModelId = "portrait", displayName = "OK Portrait", repositoryConfigId = config.id),
                    )
                    else -> emptyList()
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val merged = client.searchCompatible("portrait")

        assertEquals(MultiRepositorySearchStatus.PARTIAL_FAILURE, merged.status)
        assertEquals(1, merged.results.size)
        assertEquals("portrait", merged.results.first().localModelId)
        assertEquals("ok", merged.results.first().repositoryConfigId)
        assertTrue(merged.perRepositoryErrors.containsKey(null))
    }

    @Test
    fun clientIsolatesFailingCustomRepositoryWithoutBlockingOthers() = runBlocking {
        // Built-in is wired to fail so the test stays hermetic; the focus is
        // that a throwing custom searcher does not cancel its siblings.
        val builtInClient = HuggingFaceModelCatalogClient(
            baseUrl = "https://example.test",
            client = failingOkHttpClient("builtin unavailable"),
        )
        val brokenConfig = RepositoryConfig(id = "broken", name = "Broken", baseUrl = "https://broken.test")
        val okConfig = RepositoryConfig(id = "ok", name = "OK", baseUrl = "https://ok.test")
        val client = MultiRepositorySearchClient(
            builtInClient = builtInClient,
            customRepositories = listOf(brokenConfig, okConfig),
            customSearcher = { config, _, _ ->
                when (config.id) {
                    "broken" -> throw IOException("broken searcher blew up")
                    "ok" -> listOf(
                        result(localModelId = "portrait-ok", displayName = "OK", repositoryConfigId = config.id),
                    )
                    else -> emptyList()
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val merged = client.searchCompatible("portrait")

        assertEquals(MultiRepositorySearchStatus.PARTIAL_FAILURE, merged.status)
        assertEquals(listOf("portrait-ok"), merged.results.map { it.localModelId })
        assertTrue(merged.perRepositoryErrors.containsKey("broken"))
        assertTrue(merged.perRepositoryErrors.containsKey(null))
    }

    @Test
    fun clientReturnsAllFailedWhenEveryRepositoryThrows() = runBlocking {
        val builtInClient = HuggingFaceModelCatalogClient(
            baseUrl = "https://example.test",
            client = failingOkHttpClient("builtin unavailable"),
        )
        val brokenConfig = RepositoryConfig(id = "broken", name = "Broken", baseUrl = "https://broken.test")
        val client = MultiRepositorySearchClient(
            builtInClient = builtInClient,
            customRepositories = listOf(brokenConfig),
            customSearcher = { _, _, _ -> throw IOException("custom blew up") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val merged = client.searchCompatible("portrait")

        assertEquals(MultiRepositorySearchStatus.ALL_FAILED, merged.status)
        assertTrue(merged.results.isEmpty())
        assertEquals(2, merged.perRepositoryErrors.size)
    }

    @Test
    fun clientSkipsDisabledCustomRepositories() = runBlocking {
        val builtInClient = HuggingFaceModelCatalogClient(
            baseUrl = "https://example.test",
            client = failingOkHttpClient("builtin unavailable"),
        )
        val disabledConfig = RepositoryConfig(
            id = "disabled",
            name = "Disabled",
            baseUrl = "https://disabled.test",
            enabled = false,
        )
        var searcherCalls = 0
        val client = MultiRepositorySearchClient(
            builtInClient = builtInClient,
            customRepositories = listOf(disabledConfig),
            customSearcher = { _, _, _ ->
                searcherCalls++
                emptyList()
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        client.searchCompatible("portrait")

        assertEquals(0, searcherCalls)
    }

    @Test
    fun clientStampsRepositoryConfigIdOntoCustomResults() = runBlocking {
        val builtInClient = HuggingFaceModelCatalogClient(
            baseUrl = "https://example.test",
            client = failingOkHttpClient("builtin unavailable"),
        )
        val okConfig = RepositoryConfig(id = "ok", name = "OK", baseUrl = "https://ok.test")
        val client = MultiRepositorySearchClient(
            builtInClient = builtInClient,
            customRepositories = listOf(okConfig),
            customSearcher = { config, _, _ ->
                listOf(
                    result(localModelId = "portrait", displayName = "OK", repositoryConfigId = "should-be-overwritten"),
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val merged = client.searchCompatible("portrait")

        assertEquals("ok", merged.results.first().repositoryConfigId)
    }

    private fun result(
        localModelId: String,
        displayName: String,
        repositoryConfigId: String? = null,
    ): ModelCatalogSearchResult = ModelCatalogSearchResult(
        repositoryId = "owner/$localModelId",
        localModelId = localModelId,
        displayName = displayName,
        artifactFileName = "$localModelId.zip",
        downloadUrl = "https://example.test/$localModelId.zip",
        artifactKind = CatalogArtifactKind.LOCAL_DREAM_ZIP,
        backendHint = CatalogBackendHint.PREPACKAGED,
        backendType = "sd15cpu",
        hardwareTarget = null,
        sizeBytes = null,
        lastModified = null,
        repositoryConfigId = repositoryConfigId,
    )

    /**
     * Returns an [OkHttpClient] whose every call throws before reaching the
     * network, so the built-in [HuggingFaceModelCatalogClient] fails
     * deterministically and hermetically.
     */
    private fun failingOkHttpClient(message: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { _ -> throw IOException(message) }
            .build()
}
