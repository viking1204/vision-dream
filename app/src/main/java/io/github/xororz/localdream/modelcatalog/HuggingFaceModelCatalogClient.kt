package io.github.xororz.localdream.modelcatalog

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class HuggingFaceModelCatalogClient(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val repositoryBaseUrl = validateBaseUrl(baseUrl)

    suspend fun search(
        keyword: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<HuggingFaceModelRepository> = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        require(query.isNotEmpty()) { "Search keyword must not be blank" }
        require(query.length <= MAX_QUERY_LENGTH) { "Search keyword is too long" }
        require(limit in 1..MAX_SEARCH_LIMIT) { "Search limit must be between 1 and $MAX_SEARCH_LIMIT" }

        val request = Request.Builder()
            .url(buildSearchUrl(query, limit))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HuggingFaceCatalogException("Model search failed with HTTP ${response.code}")
            }
            val body = response.body ?: throw HuggingFaceCatalogException("Model search returned no body")
            HuggingFaceCatalogJsonParser.parseRepositories(body.source().readUtf8Limited(MAX_RESPONSE_BYTES))
        }
    }

    suspend fun searchCompatible(
        keyword: String,
        evaluator: ModelCompatibilityEvaluator = ModelCompatibilityEvaluator(),
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<ModelCatalogSearchResult> = search(keyword, limit)
        .flatMap { repository ->
            evaluator.evaluate(repository).artifacts.mapNotNull { artifact ->
                toSearchResult(repository, artifact)
            }
        }

    fun downloadUrl(artifact: CompatibleModelArtifact): String {
        val revision = artifact.repositorySha?.takeIf { SAFE_REVISION.matches(it) } ?: DEFAULT_REVISION
        val artifactPath = artifact.file.path
        require(
            artifact.kind != CatalogArtifactKind.LOCAL_DREAM_DIRECTORY &&
                artifact.file.isRootFile &&
                artifactPath != "." &&
                artifactPath != ".." &&
                artifactPath.none(::isControlCharacter),
        ) {
            "Unsafe artifact path"
        }
        return repositoryBaseUrl.newBuilder()
            .addRepositoryPath(artifact.repositoryId)
            .addPathSegment("resolve")
            .addPathSegment(revision)
            .addPathSegment(artifactPath)
            .build()
            .toString()
    }

    internal fun directoryDownloadUrl(
        repositoryId: String,
        revision: String,
        sourcePath: String,
    ): String {
        require(SAFE_REVISION.matches(revision)) {
            "Directory downloads require a commit revision"
        }
        require(isSafeRepositoryPath(sourcePath)) { "Unsafe directory artifact path" }
        return repositoryBaseUrl.newBuilder()
            .addRepositoryPath(repositoryId)
            .addPathSegment("resolve")
            .addPathSegment(revision)
            .addArtifactPath(sourcePath)
            .build()
            .toString()
    }

    private fun toSearchResult(
        repository: HuggingFaceModelRepository,
        artifact: CompatibleModelArtifact,
    ): ModelCatalogSearchResult? {
        val classification = ModelContentClassifier.classify(
            repository,
            artifact.sourcePrefix.ifBlank { artifact.file.path },
        )
        val revision = repository.sha?.takeIf(SAFE_REVISION::matches)
        val manifest = if (artifact.kind == CatalogArtifactKind.LOCAL_DREAM_DIRECTORY) {
            val pinnedRevision = revision ?: return null
            CatalogDownloadManifest(
                repositoryId = repository.id,
                revision = pinnedRevision,
                backendType = artifact.backendType ?: return null,
                files = artifact.directoryFiles.map { file ->
                    CatalogDownloadFile(
                        sourcePath = file.path,
                        targetName = file.path.substringAfterLast('/'),
                        downloadUrl = directoryDownloadUrl(
                            repositoryId = repository.id,
                            revision = pinnedRevision,
                            sourcePath = file.path,
                        ),
                        sizeBytes = file.sizeBytes,
                        sha256 = file.lfsSha256,
                    )
                },
            )
        } else {
            null
        }
        val sizeBytes = if (manifest != null) {
            manifest.declaredTotalBytes
        } else {
            artifact.file.sizeBytes
        }
        return ModelCatalogSearchResult(
            repositoryId = repository.id,
            localModelId = artifact.localModelId,
            displayName = artifact.displayName,
            artifactFileName = if (manifest != null) {
                artifact.sourcePrefix.ifBlank { repository.id.substringAfterLast('/') } + "/"
            } else {
                artifact.file.path
            },
            downloadUrl = manifest?.files?.firstOrNull()?.downloadUrl ?: downloadUrl(artifact),
            artifactKind = artifact.kind,
            backendHint = artifact.backendHint,
            backendType = artifact.backendType,
            hardwareTarget = artifact.hardwareTarget,
            sizeBytes = sizeBytes,
            lastModified = repository.lastModified,
            sha256 = if (manifest == null) artifact.file.lfsSha256 else null,
            downloadManifest = manifest,
            contentRating = classification.rating,
            ratingSource = classification.source,
            ratingEvidence = classification.evidence,
            repositoryRevision = revision,
        )
    }

    internal fun buildSearchUrl(keyword: String, limit: Int): HttpUrl = repositoryBaseUrl.newBuilder()
        .addPathSegment("api")
        .addPathSegment("models")
        .addQueryParameter("search", keyword)
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("full", "true")
        .addQueryParameter("config", "true")
        .addQueryParameter("cardData", "true")
        .build()

    private fun HttpUrl.Builder.addRepositoryPath(repositoryId: String): HttpUrl.Builder {
        val segments = repositoryId.split('/')
        val validSegments = segments.all {
            SAFE_REPOSITORY_SEGMENT.matches(it) && it != "." && it != ".."
        }
        require(segments.size in 1..2 && validSegments) { "Unsafe repository id" }
        segments.forEach(::addPathSegment)
        return this
    }

    private fun HttpUrl.Builder.addArtifactPath(path: String): HttpUrl.Builder {
        path.split('/').forEach(::addPathSegment)
        return this
    }

    private fun isSafeRepositoryPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.endsWith('/') || '\\' in path) {
            return false
        }
        if (path.any(::isControlCharacter)) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun validateBaseUrl(baseUrl: String): HttpUrl {
        val parsed = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid model repository URL")
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "Model repository URL must use HTTP or HTTPS"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Model repository URL must not contain a query or fragment"
        }
        return parsed
    }

    private fun okio.BufferedSource.readUtf8Limited(maxBytes: Long): String {
        if (request(maxBytes + 1) && buffer.size > maxBytes) {
            throw HuggingFaceCatalogException("Model search response is too large")
        }
        return readUtf8()
    }

    private fun isControlCharacter(character: Char): Boolean = character.isISOControl()

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 30
        const val MAX_SEARCH_LIMIT = 50
        const val MAX_QUERY_LENGTH = 100
        const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_REVISION = "main"
        const val USER_AGENT = "vision-dream-model-catalog/1"
        val SAFE_REPOSITORY_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        val SAFE_REVISION = Regex("[A-Fa-f0-9]{7,64}")
    }
}

class HuggingFaceCatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object HuggingFaceCatalogJsonParser {
    fun parseRepositories(rawJson: String): List<HuggingFaceModelRepository> {
        val trimmed = rawJson.trim()
        val entries = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)

            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("items")
                    ?: root.optJSONArray("models")
                    ?: root.optJSONArray("data")
                    ?: throw HuggingFaceCatalogException("Unsupported model search response")
            }

            else -> throw HuggingFaceCatalogException("Invalid model search response")
        }
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                parseRepository(entry)?.let(::add)
            }
        }
    }

    private fun parseRepository(json: JSONObject): HuggingFaceModelRepository? {
        val id = json.optString("id").ifBlank { json.optString("modelId") }.trim()
        if (id.isBlank()) return null
        val cardData = json.optJSONObject("cardData")
        val config = json.optJSONObject("config")
        val cardMetadata = cardData?.let(::toMetadataMap).orEmpty()
        val tags = buildSet {
            addAll(stringValues(json.opt("tags")))
            addAll(stringValues(cardData?.opt("tags")))
        }
        val baseModels = buildSet {
            addAll(stringValues(json.opt("base_model")))
            addAll(stringValues(cardData?.opt("base_model")))
        }
        val formats = buildSet {
            addAll(stringValues(cardData?.opt("format")))
            addAll(stringValues(cardData?.opt("formats")))
        }
        return HuggingFaceModelRepository(
            id = id,
            author = json.nullableString("author"),
            sha = json.nullableString("sha"),
            pipelineTag = json.nullableString("pipeline_tag")
                ?: cardData?.nullableString("pipeline_tag"),
            libraryName = json.nullableString("library_name")
                ?: cardData?.nullableString("library_name"),
            configClassName = config?.optJSONObject("diffusers")?.nullableString("_class_name")
                ?: config?.nullableString("_class_name"),
            tags = tags,
            baseModels = baseModels,
            modelType = cardData?.nullableString("model_type"),
            formats = formats,
            cardMetadata = cardMetadata,
            files = parseFiles(json.optJSONArray("siblings")),
            downloads = json.nullableLong("downloads"),
            likes = json.nullableLong("likes"),
            lastModified = json.nullableString("lastModified"),
            isPrivate = json.truthy("private"),
            isGated = json.truthy("gated"),
            isDisabled = json.truthy("disabled"),
            declaredNsfw = json.nullableBoolean("nsfw")
                ?: cardData?.nullableBoolean("nsfw"),
        )
    }

    private fun parseFiles(siblings: JSONArray?): List<HuggingFaceModelFile> {
        if (siblings == null) return emptyList()
        return buildList {
            for (index in 0 until siblings.length()) {
                val item = siblings.optJSONObject(index) ?: continue
                val path = item.optString("rfilename").ifBlank { item.optString("path") }
                if (path.isBlank()) continue
                val lfs = item.optJSONObject("lfs")
                add(
                    HuggingFaceModelFile(
                        path = path,
                        sizeBytes = item.nullableLong("size") ?: lfs?.nullableLong("size"),
                        lfsSha256 = lfs?.nullableString("sha256")
                            ?: lfs?.nullableString("oid")?.removePrefix("sha256:"),
                    ),
                )
            }
        }
    }

    private fun toMetadataMap(json: JSONObject): Map<String, Set<String>> = buildMap {
        for (key in json.keys()) {
            val values = stringValues(json.opt(key))
            if (values.isNotEmpty()) put(key, values)
        }
    }

    private fun stringValues(value: Any?): Set<String> = when (value) {
        null,
        JSONObject.NULL,
        -> emptySet()

        is String -> setOf(value)

        is Number,
        is Boolean,
        -> setOf(value.toString())

        is JSONArray -> buildSet {
            for (index in 0 until value.length()) {
                addAll(stringValues(value.opt(index)))
            }
        }

        is JSONObject -> buildSet {
            for (key in value.keys()) {
                addAll(stringValues(value.opt(key)))
            }
        }

        else -> emptySet()
    }

    private fun JSONObject.nullableString(key: String): String? = optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }

    private fun JSONObject.nullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.truthy(key: String): Boolean {
        if (!has(key) || isNull(key)) return false
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.lowercase(Locale.ROOT) !in setOf("", "false", "none", "null", "0")
            else -> true
        }
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Boolean -> value

            is Number -> value.toInt() != 0

            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "true", "yes", "1", "on" -> true
                "false", "no", "0", "off" -> false
                else -> null
            }

            else -> null
        }
    }
}
