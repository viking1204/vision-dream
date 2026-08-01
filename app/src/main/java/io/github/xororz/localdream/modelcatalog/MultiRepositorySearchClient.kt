package io.github.xororz.localdream.modelcatalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Status of a multi-repository search after every enabled repository has been
 * consulted (or failed).
 */
enum class MultiRepositorySearchStatus {
    /** Every queried repository returned a result. */
    SUCCESS,

    /** At least one repository succeeded and at least one failed. */
    PARTIAL_FAILURE,

    /** Every queried repository failed. */
    ALL_FAILED,

    /** No repositories were available to query. */
    NO_REPOSITORIES,
}

/**
 * Per-repository outcome of a parallel search. Failures are captured here
 * rather than thrown so a single broken repository cannot sink the whole query.
 */
sealed class RepositorySearchOutcome {
    abstract val repositoryConfigId: String?

    data class Success(
        override val repositoryConfigId: String?,
        val results: List<ModelCatalogSearchResult>,
    ) : RepositorySearchOutcome()

    data class Failure(
        override val repositoryConfigId: String?,
        val error: Throwable,
    ) : RepositorySearchOutcome()
}

/**
 * Aggregated result of searching across multiple repositories.
 */
data class MultiRepositorySearchResult(
    val results: List<ModelCatalogSearchResult>,
    val perRepositoryErrors: Map<String?, Throwable>,
    val status: MultiRepositorySearchStatus,
) {
    val isSuccess: Boolean
        get() = status == MultiRepositorySearchStatus.SUCCESS

    val hasResults: Boolean
        get() = results.isNotEmpty()
}

/**
 * Pure merge logic for combining per-repository search outcomes into a single
 * ranked list. Extracted so ranking and failure handling can be tested without
 * spinning up coroutines or network clients.
 */
object SearchResultMerger {
    /**
     * Merge [outcomes] honoring the following rank order, applied as a stable
     * sort so the original per-repository relevance ordering is preserved
     * within each tier:
     *  1. [ModelCatalogSearchResult.localModelId] exactly matches the query
     *     (case-insensitive).
     *  2. [ModelCatalogSearchResult.localModelId] or
     *     [ModelCatalogSearchResult.displayName] starts with the query
     *     (case-insensitive).
     *  3. Everything else, in original relevance order.
     *
     * Failures are recorded in [MultiRepositorySearchResult.perRepositoryErrors]
     * and do not affect the merged result list.
     */
    fun merge(
        query: String,
        outcomes: List<RepositorySearchOutcome>,
    ): MultiRepositorySearchResult {
        val lowerQuery = query.trim().lowercase()
        val errors = LinkedHashMap<String?, Throwable>()
        val flattened = ArrayList<ModelCatalogSearchResult>()
        for (outcome in outcomes) {
            when (outcome) {
                is RepositorySearchOutcome.Success -> flattened.addAll(outcome.results)
                is RepositorySearchOutcome.Failure -> errors[outcome.repositoryConfigId] = outcome.error
            }
        }
        val ranked = flattened.sortedBy { result -> tierOf(result, lowerQuery) }
        val status = when {
            outcomes.isEmpty() -> MultiRepositorySearchStatus.NO_REPOSITORIES
            errors.size == outcomes.size -> MultiRepositorySearchStatus.ALL_FAILED
            errors.isEmpty() -> MultiRepositorySearchStatus.SUCCESS
            else -> MultiRepositorySearchStatus.PARTIAL_FAILURE
        }
        return MultiRepositorySearchResult(
            results = ranked,
            perRepositoryErrors = errors,
            status = status,
        )
    }

    private fun tierOf(result: ModelCatalogSearchResult, lowerQuery: String): Int {
        if (lowerQuery.isEmpty()) return TIER_RELEVANCE
        val localId = result.localModelId.lowercase()
        if (localId == lowerQuery) return TIER_EXACT_MATCH
        val displayName = result.displayName.lowercase()
        if (localId.startsWith(lowerQuery) || displayName.startsWith(lowerQuery)) {
            return TIER_PREFIX_MATCH
        }
        return TIER_RELEVANCE
    }

    private const val TIER_EXACT_MATCH = 0
    private const val TIER_PREFIX_MATCH = 1
    private const val TIER_RELEVANCE = 2
}

/**
 * Searches the built-in Hugging Face catalog and any enabled custom
 * [RepositoryConfig]s in parallel, then merges the results via
 * [SearchResultMerger].
 *
 * Each repository is queried independently; a failure in one repository is
 * captured as a [RepositorySearchOutcome.Failure] and never propagates to the
 * caller or cancels sibling searches.
 *
 * [customSearcher] is injectable so callers (and tests) can plug in a
 * non-network backend per custom repository. It defaults to constructing a
 * [HuggingFaceModelCatalogClient] from each config's [RepositoryConfig.baseUrl].
 */
class MultiRepositorySearchClient(
    private val builtInClient: HuggingFaceModelCatalogClient,
    private val customRepositories: List<RepositoryConfig>,
    private val customSearcher: suspend (RepositoryConfig, String, Int) -> List<ModelCatalogSearchResult> = ::defaultCustomSearcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun searchCompatible(
        keyword: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): MultiRepositorySearchResult = withContext(ioDispatcher) {
        coroutineScope {
            val enabledCustom = customRepositories.filter { it.enabled }

            val builtInDeferred = async {
                safeSearch(BUILT_IN_REPOSITORY_CONFIG_ID) {
                    builtInClient
                        .searchCompatible(keyword, limit = limit)
                        .map { result -> result.copy(repositoryConfigId = BUILT_IN_REPOSITORY_CONFIG_ID) }
                }
            }
            val customDeferreds = enabledCustom.map { config ->
                async {
                    safeSearch(config.id) {
                        customSearcher(config, keyword, limit)
                            .map { result -> result.copy(repositoryConfigId = config.id) }
                    }
                }
            }

            val outcomes = buildList {
                add(builtInDeferred.await())
                customDeferreds.forEach { add(it.await()) }
            }

            SearchResultMerger.merge(keyword, outcomes)
        }
    }

    /**
     * Runs [block] and wraps its outcome, isolating non-cancellation failures
     * into [RepositorySearchOutcome.Failure] so they cannot tear down the
     * surrounding [coroutineScope].
     */
    private suspend fun safeSearch(
        repositoryConfigId: String?,
        block: suspend () -> List<ModelCatalogSearchResult>,
    ): RepositorySearchOutcome = try {
        RepositorySearchOutcome.Success(repositoryConfigId, block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        RepositorySearchOutcome.Failure(repositoryConfigId, failure)
    }

    companion object {
        /** [repositoryConfigId] assigned to results sourced from [builtInClient]. */
        val BUILT_IN_REPOSITORY_CONFIG_ID: String? = null

        const val DEFAULT_SEARCH_LIMIT = 30

        private suspend fun defaultCustomSearcher(
            config: RepositoryConfig,
            keyword: String,
            limit: Int,
        ): List<ModelCatalogSearchResult> =
            HuggingFaceModelCatalogClient(config.baseUrl).searchCompatible(keyword, limit = limit)
    }
}
