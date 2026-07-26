package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelFileLayouts
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class CatalogDownloadFile(
    val sourcePath: String,
    val targetName: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/**
 * Immutable, commit-pinned plan for installing an unpacked model repository.
 */
data class CatalogDownloadManifest(
    val repositoryId: String,
    val revision: String,
    val backendType: String,
    val files: List<CatalogDownloadFile>,
) {
    init {
        require(SAFE_REPOSITORY_ID.matches(repositoryId)) { "Unsafe repository id" }
        require(SAFE_REVISION.matches(revision)) { "Directory downloads require a commit revision" }
        require(files.size in 1..MAX_FILES) { "Invalid directory file count" }
        require(files.map { it.targetName }.distinct().size == files.size) {
            "Directory manifest contains duplicate target files"
        }
        require(
            files
                .map { it.sourcePath.substringBeforeLast('/', "") }
                .distinct()
                .size == 1,
        ) {
            "Directory manifest must describe one repository directory"
        }

        val layout = requireNotNull(ModelFileLayouts.forBackend(backendType)) {
            "Unsupported directory backend"
        }
        val targetNames = files.mapTo(mutableSetOf()) { file ->
            validateFile(file)
            file.targetName
        }
        require(layout.isComplete(targetNames)) { "Directory manifest is incomplete" }
        require(targetNames.all(layout::accepts)) { "Directory manifest contains unsupported files" }

        var declaredBytes = 0L
        files.mapNotNull { it.sizeBytes }.forEach { size ->
            require(size >= 0L) { "Directory manifest contains an invalid file size" }
            require(declaredBytes <= MAX_DECLARED_BYTES - size) {
                "Directory model is too large"
            }
            declaredBytes += size
        }
    }

    val declaredTotalBytes: Long?
        get() = if (files.all { it.sizeBytes != null }) {
            files.sumOf { requireNotNull(it.sizeBytes) }
        } else {
            null
        }

    fun toJsonString(): String = JSONObject().apply {
        put(KEY_REPOSITORY_ID, repositoryId)
        put(KEY_REVISION, revision)
        put(KEY_BACKEND_TYPE, backendType)
        put(
            KEY_FILES,
            JSONArray().apply {
                files.forEach { file ->
                    put(
                        JSONObject().apply {
                            put(KEY_SOURCE_PATH, file.sourcePath)
                            put(KEY_TARGET_NAME, file.targetName)
                            put(KEY_DOWNLOAD_URL, file.downloadUrl)
                            file.sizeBytes?.let { put(KEY_SIZE_BYTES, it) }
                            file.sha256?.let { put(KEY_SHA256, it) }
                        },
                    )
                }
            },
        )
    }.toString()

    private fun validateFile(file: CatalogDownloadFile) {
        require(file.sourcePath.length <= MAX_SOURCE_PATH_LENGTH) { "Directory source path is too long" }
        require(isSafeRepositoryPath(file.sourcePath)) { "Unsafe directory source path" }
        require(file.sourcePath.count { it == '/' } <= 1) {
            "Directory source path is nested too deeply"
        }
        require(
            file.targetName.isNotBlank() &&
                file.targetName != "." &&
                file.targetName != ".." &&
                '/' !in file.targetName &&
                '\\' !in file.targetName &&
                file.targetName.none(Char::isISOControl),
        ) {
            "Unsafe directory target name"
        }
        require(file.sourcePath.substringAfterLast('/') == file.targetName) {
            "Directory target name must match its source file"
        }
        file.sha256?.let {
            require(SAFE_SHA256.matches(it)) { "Invalid directory file checksum" }
        }
        val uri = runCatching { URI(file.downloadUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "Unsafe directory download URL"
        }
    }

    companion object {
        const val MAX_FILES = 64
        const val MAX_DECLARED_BYTES = CatalogArtifactDownloadLimits.MAX_DOWNLOAD_BYTES
        const val MAX_JSON_LENGTH = 256 * 1024

        private const val MAX_SOURCE_PATH_LENGTH = 512
        private const val KEY_REPOSITORY_ID = "repository_id"
        private const val KEY_REVISION = "revision"
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_FILES = "files"
        private const val KEY_SOURCE_PATH = "source_path"
        private const val KEY_TARGET_NAME = "target_name"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_SIZE_BYTES = "size_bytes"
        private const val KEY_SHA256 = "sha256"

        private val SAFE_REPOSITORY_ID =
            Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,95}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,95})?""")
        private val SAFE_REVISION = Regex("[A-Fa-f0-9]{7,64}")
        private val SAFE_SHA256 = Regex("[A-Fa-f0-9]{64}")

        fun fromJsonString(rawJson: String): CatalogDownloadManifest {
            require(rawJson.length <= MAX_JSON_LENGTH) { "Directory manifest is too large" }
            val json = JSONObject(rawJson)
            val jsonFiles = json.optJSONArray(KEY_FILES)
                ?: throw IllegalArgumentException("Directory manifest has no files")
            val files = buildList {
                for (index in 0 until jsonFiles.length()) {
                    val file = jsonFiles.optJSONObject(index)
                        ?: throw IllegalArgumentException("Invalid directory file entry")
                    add(
                        CatalogDownloadFile(
                            sourcePath = file.getString(KEY_SOURCE_PATH),
                            targetName = file.getString(KEY_TARGET_NAME),
                            downloadUrl = file.getString(KEY_DOWNLOAD_URL),
                            sizeBytes = file.optLongOrNull(KEY_SIZE_BYTES),
                            sha256 = file.optString(KEY_SHA256).trim().takeIf(String::isNotEmpty),
                        ),
                    )
                }
            }
            return CatalogDownloadManifest(
                repositoryId = json.getString(KEY_REPOSITORY_ID),
                revision = json.getString(KEY_REVISION),
                backendType = json.getString(KEY_BACKEND_TYPE),
                files = files,
            )
        }

        private fun isSafeRepositoryPath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.endsWith('/') || '\\' in path) {
                return false
            }
            if (path.any(Char::isISOControl)) return false
            val segments = path.split('/')
            return segments.all { it.isNotBlank() && it != "." && it != ".." }
        }

        private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) {
            when (val value = opt(key)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        } else {
            null
        }
    }
}
