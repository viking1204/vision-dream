package io.github.xororz.localdream.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * Owns every filesystem location used for installed and in-progress models.
 *
 * Model runtimes require real filesystem paths, so the repository deliberately
 * uses a stable public directory instead of app-private storage or document
 * URIs. Callers must never silently fall back to a private directory when the
 * required all-files permission is missing.
 */
object ModelStorage {
    const val PUBLIC_DIRECTORY_NAME = "VisionDream"
    const val MODELS_DIRECTORY_NAME = "models"

    data class MigrationReport(
        val copied: Int,
        val alreadyPresent: Int,
        val failed: Int,
    )

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun publicRoot(): File = File(
        Environment.getExternalStorageDirectory(),
        PUBLIC_DIRECTORY_NAME,
    )

    fun publicModelsDir(): File = File(publicRoot(), MODELS_DIRECTORY_NAME)

    fun requireModelsDir(context: Context): File {
        requireAccess(context)
        return publicModelsDir().also { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "无法创建模型目录：${directory.absolutePath}"
            }
        }
    }

    fun requireStagingDir(context: Context): File {
        requireAccess(context)
        return File(publicRoot(), ".staging").also { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "无法创建模型暂存目录：${directory.absolutePath}"
            }
        }
    }

    fun legacyPrivateModelsDir(context: Context): File = File(
        context.filesDir,
        MODELS_DIRECTORY_NAME,
    )

    /**
     * Copies models previously installed by this package to the public
     * repository. The private source is intentionally retained as a recovery
     * copy; existing public entries are never overwritten.
     */
    fun migrateLegacyModels(context: Context): MigrationReport {
        val target = requireModelsDir(context)
        val migrationRoot = File(publicRoot(), ".migration")
        return migrateLegacyEntries(
            source = legacyPrivateModelsDir(context),
            target = target,
            migrationRoot = migrationRoot,
        )
    }

    internal fun migrateLegacyEntries(
        source: File,
        target: File,
        migrationRoot: File,
    ): MigrationReport {
        val entries = source.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (entries.isEmpty()) {
            return MigrationReport(copied = 0, alreadyPresent = 0, failed = 0)
        }
        check(target.isDirectory || target.mkdirs()) {
            "无法创建模型目录：${target.absolutePath}"
        }
        check(migrationRoot.isDirectory || migrationRoot.mkdirs()) {
            "无法创建迁移暂存目录：${migrationRoot.absolutePath}"
        }

        var copied = 0
        var alreadyPresent = 0
        var failed = 0
        entries.forEach { entry ->
            val destination = File(target, entry.name)
            if (destination.exists()) {
                alreadyPresent++
                return@forEach
            }

            val staging = File(migrationRoot, "${entry.name}-${UUID.randomUUID()}")
            try {
                val copySucceeded = entry.copyRecursively(staging, overwrite = false)
                if (!copySucceeded || !sameTree(entry, staging) || !staging.renameTo(destination)) {
                    failed++
                } else {
                    copied++
                }
            } catch (_: Exception) {
                failed++
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        migrationRoot.delete()
        return MigrationReport(copied, alreadyPresent, failed)
    }

    private fun sameTree(source: File, copy: File): Boolean {
        val sourceFiles = source.walkTopDown().filter { it.isFile }.toList()
        val copiedFiles = copy.walkTopDown().filter { it.isFile }.toList()
        if (sourceFiles.size != copiedFiles.size) return false

        val sourceRoot = source.toPath()
        val copiedByPath = copiedFiles.associateBy {
            copy.toPath().relativize(it.toPath()).toString()
        }
        return sourceFiles.all { original ->
            val relativePath = sourceRoot.relativize(original.toPath()).toString()
            copiedByPath[relativePath]?.length() == original.length()
        }
    }

    private fun requireAccess(context: Context) {
        check(hasAccess(context)) {
            "Vision Dream 需要存储管理权限才能访问 ${publicModelsDir().absolutePath}"
        }
    }
}
