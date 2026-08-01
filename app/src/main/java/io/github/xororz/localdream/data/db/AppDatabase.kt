package io.github.xororz.localdream.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HistoryEntity::class,
        PromptTemplateEntity::class,
        PerformancePresetEntity::class,
        PerformancePresetBindingEntity::class,
        PerformancePresetQualificationEntity::class,
        InferenceJobEntity::class,
        PresetSnapshotEntity::class,
        McpClientGrantEntity::class,
        McpAuditEventEntity::class,
        PromptSampleSeedEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun promptTemplateDao(): PromptTemplateDao
    abstract fun performancePresetDao(): PerformancePresetDao
    abstract fun performancePresetBindingDao(): PerformancePresetBindingDao
    abstract fun performancePresetQualificationDao(): PerformancePresetQualificationDao
    abstract fun inferenceJobDao(): InferenceJobDao
    abstract fun mcpClientGrantDao(): McpClientGrantDao
    abstract fun mcpAuditEventDao(): McpAuditEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 -> v2: drop generationTimeMs column (SQLite < 3.35 doesn't support DROP COLUMN
        // directly, so recreate the table). All other columns and indices unchanged.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE generation_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modelId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        imagePath TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        denoiseStrength REAL,
                        upscalerId TEXT,
                        steps INTEGER NOT NULL,
                        cfg REAL NOT NULL,
                        seed INTEGER,
                        prompt TEXT NOT NULL,
                        negativePrompt TEXT NOT NULL,
                        generationTime TEXT,
                        scheduler TEXT NOT NULL,
                        runOnCpu INTEGER NOT NULL,
                        useOpenCL INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO generation_history_new
                    (id, modelId, timestamp, imagePath, width, height, mode,
                     denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                     negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL)
                    SELECT id, modelId, timestamp, imagePath, width, height, mode,
                           denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                           negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL
                    FROM generation_history
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE generation_history")
                db.execSQL("ALTER TABLE generation_history_new RENAME TO generation_history")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_modelId_timestamp ON generation_history (modelId, timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_timestamp ON generation_history (timestamp)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_history_mode ON generation_history (mode)")
            }
        }

        // v2 -> v3: add the favorite flag.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE generation_history " +
                        "ADD COLUMN origin TEXT NOT NULL DEFAULT 'local_app'",
                )
                db.execSQL(
                    "ALTER TABLE generation_history " +
                        "ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'image/png'",
                )
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN requestId TEXT",
                )
                db.execSQL(
                    "UPDATE generation_history SET mimeType = 'image/jpeg' " +
                        "WHERE LOWER(imagePath) LIKE '%.jpg' OR LOWER(imagePath) LIKE '%.jpeg'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_origin_timestamp " +
                        "ON generation_history (origin, timestamp)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        negativePrompt TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER,
                        useCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_templates_updatedAt " +
                        "ON prompt_templates (updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_templates_lastUsedAt " +
                        "ON prompt_templates (lastUsedAt)",
                )
            }
        }

        /**
         * v4 -> v5 only adds independent preset, Job and MCP structures. Existing history
         * remains readable and its new optional associations intentionally stay null.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN jobId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN presetId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN presetRevision INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN runtimeFingerprint TEXT",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_jobId ON generation_history (jobId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_presetId ON generation_history (presetId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS performance_presets (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        selector TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        isFallback INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_performance_presets_name ON performance_presets (name)",
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO performance_presets
                    (id, name, selector, configJson, revision, isFallback, createdAt, updatedAt)
                    VALUES ('00000000-0000-4000-8000-000000000000', 'Compatibility fallback',
                            'COMPATIBILITY_FALLBACK', '{}', 1, 1, 0, 0)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS inference_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        ownerId TEXT NOT NULL,
                        presetId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_inference_jobs_ownerId_createdAt ON inference_jobs (ownerId, createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_inference_jobs_status_updatedAt ON inference_jobs (status, updatedAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS preset_snapshots (
                        jobId TEXT NOT NULL PRIMARY KEY,
                        presetId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        selector TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        revision INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_client_grants (
                        id TEXT NOT NULL PRIMARY KEY,
                        clientId TEXT NOT NULL,
                        tokenAlias TEXT NOT NULL,
                        tokenGeneration INTEGER NOT NULL,
                        scopesJson TEXT NOT NULL,
                        lanAllowed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        revokedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_mcp_client_grants_clientId ON mcp_client_grants (clientId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_audit_events (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        clientId TEXT NOT NULL,
                        transport TEXT NOT NULL,
                        sessionHash TEXT,
                        method TEXT NOT NULL,
                        scopeSnapshot TEXT NOT NULL,
                        risk TEXT NOT NULL,
                        parameterDigest TEXT NOT NULL,
                        jobId TEXT,
                        outcomeCode TEXT NOT NULL,
                        durationMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mcp_audit_events_timestamp ON mcp_audit_events (timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mcp_audit_events_clientId_timestamp ON mcp_audit_events (clientId, timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mcp_audit_events_jobId ON mcp_audit_events (jobId)",
                )
            }
        }

        /** v5 -> v6 adds future-request preset bindings without rewriting historical snapshots. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS performance_preset_bindings (
                        bindingKey TEXT NOT NULL PRIMARY KEY,
                        presetId TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_performance_preset_bindings_presetId " +
                        "ON performance_preset_bindings (presetId)",
                )
            }
        }

        /**
         * v6 -> v7 persists only audited preset qualification facts. Existing
         * presets, bindings, jobs and history remain byte-for-byte untouched;
         * old automatic bindings deliberately have no qualification and are
         * therefore rejected by the v7 automatic-binding gate.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS performance_preset_qualifications (
                        id TEXT NOT NULL PRIMARY KEY,
                        presetId TEXT NOT NULL,
                        presetRevision INTEGER NOT NULL,
                        presetSnapshotSha256 TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        modelAssetSha256 TEXT NOT NULL,
                        scenarioSetSha256 TEXT NOT NULL,
                        runtimeFingerprint TEXT NOT NULL,
                        appBuild TEXT NOT NULL,
                        qualificationLevel TEXT NOT NULL,
                        evidenceManifestSha256 TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        revokedAt INTEGER,
                        FOREIGN KEY(presetId) REFERENCES performance_presets(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_performance_preset_qualifications_presetId_revokedAt " +
                        "ON performance_preset_qualifications (presetId, revokedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_performance_preset_qualifications_modelId_modelAssetSha256_runtimeFingerprint_scenarioSetSha256 " +
                        "ON performance_preset_qualifications (modelId, modelAssetSha256, runtimeFingerprint, scenarioSetSha256)",
                )
                createPerformancePresetQualificationGuards(db)
            }
        }

        /**
         * v7 -> v8 adds the product-ownership flag without rewriting existing
         * custom presets. The immutable catalog is seeded idempotently so a
         * partially created database can recover on the next open.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE performance_presets ADD COLUMN isBuiltIn INTEGER NOT NULL DEFAULT 0",
                )
                seedBuiltInPerformancePresets(db)
            }
        }

        /**
         * v8 used one unique namespace for product-owned and user-owned names.
         * That made a newly shipped built-in disappear silently when an older
         * custom preset already used the same display name. Stable IDs are the
         * real identity; repository rules continue to reject new user-name
         * duplicates after this index is removed.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_performance_presets_name")
                seedBuiltInPerformancePresets(db)
            }
        }

        /**
         * v10 turns generated prompt samples into normal editable rows. Model
         * and sampling columns keep picker behavior intact, while the separate
         * seed marker prevents a user-deleted sample from reappearing.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prompt_templates ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE prompt_templates ADD COLUMN sampleKey TEXT")
                db.execSQL("ALTER TABLE prompt_templates ADD COLUMN steps INTEGER")
                db.execSQL("ALTER TABLE prompt_templates ADD COLUMN cfg REAL")
                db.execSQL("ALTER TABLE prompt_templates ADD COLUMN scheduler TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_templates_modelId " +
                        "ON prompt_templates (modelId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_prompt_templates_sampleKey " +
                        "ON prompt_templates (sampleKey)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_sample_seed_models (
                        modelId TEXT NOT NULL PRIMARY KEY,
                        seededAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * These rows are product defaults, not user content. Their stable IDs
         * let a persisted job snapshot name the same preset across upgrades.
         */
        private fun seedBuiltInPerformancePresets(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO performance_presets
                (id, name, selector, configJson, revision, isFallback, isBuiltIn, createdAt, updatedAt)
                VALUES ('$COMPATIBILITY_FALLBACK_ID', 'Compatibility fallback',
                        'COMPATIBILITY_FALLBACK', '{}', 1, 1, 1, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                "UPDATE performance_presets SET isBuiltIn = 1 WHERE id = '$COMPATIBILITY_FALLBACK_ID'",
            )
            BUILT_IN_PERFORMANCE_PRESETS.forEach { preset ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO performance_presets
                    (id, name, selector, configJson, revision, isFallback, isBuiltIn, createdAt, updatedAt)
                    VALUES ('${preset.id}', '${preset.name}', '${preset.selector}', '${preset.configJson}',
                            1, 0, 1, 0, 0)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Room does not model partial indices. Triggers preserve the required
         * "one active qualification per immutable identity" invariant without
         * adding an index that makes Room reject an otherwise valid migration.
         */
        private fun createPerformancePresetQualificationGuards(db: SupportSQLiteDatabase) {
            val duplicateIdentityPredicate =
                """
                presetSnapshotSha256 = NEW.presetSnapshotSha256
                AND modelAssetSha256 = NEW.modelAssetSha256
                AND runtimeFingerprint = NEW.runtimeFingerprint
                AND scenarioSetSha256 = NEW.scenarioSetSha256
                AND qualificationLevel = NEW.qualificationLevel
                AND revokedAt IS NULL
                """.trimIndent()
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS reject_duplicate_active_preset_qualification_insert
                BEFORE INSERT ON performance_preset_qualifications
                WHEN NEW.revokedAt IS NULL AND EXISTS (
                    SELECT 1
                    FROM performance_preset_qualifications
                    WHERE $duplicateIdentityPredicate
                )
                BEGIN
                    SELECT RAISE(ABORT, 'duplicate active performance preset qualification');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS reject_duplicate_active_preset_qualification_update
                BEFORE UPDATE OF presetSnapshotSha256, modelAssetSha256, runtimeFingerprint,
                    scenarioSetSha256, qualificationLevel, revokedAt
                ON performance_preset_qualifications
                WHEN NEW.revokedAt IS NULL AND EXISTS (
                    SELECT 1
                    FROM performance_preset_qualifications
                    WHERE $duplicateIdentityPredicate
                      AND id <> OLD.id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'duplicate active performance preset qualification');
                END
                """.trimIndent(),
            )
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "local_dream.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            seedBuiltInPerformancePresets(db)
                            createPerformancePresetQualificationGuards(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // Repairs a partial built-in seed and v7 databases created
                            // by builds that predate the qualification guards.
                            seedBuiltInPerformancePresets(db)
                            createPerformancePresetQualificationGuards(db)
                        }
                    },
                )
                // No destructive fallback: a future schema bump without a
                // matching migration should fail loudly at open time rather
                // than silently dropping the user's whole generation history.
                // Add a Migration for every version increment instead.
                .build()
                .also { INSTANCE = it }
        }

        private const val COMPATIBILITY_FALLBACK_ID = "00000000-0000-4000-8000-000000000000"

        private data class BuiltInPerformancePreset(
            val id: String,
            val name: String,
            val selector: String,
            val configJson: String,
        )

        private val BUILT_IN_PERFORMANCE_PRESETS = listOf(
            BuiltInPerformancePreset(
                id = "10000000-0000-4000-8000-000000000001",
                name = "省内存",
                selector = "memory_saver",
                configJson = "{\"schemaVersion\":2,\"engine\":{\"sdxlLowRam\":true,\"animaLowRam\":true,\"animaSequentialDit\":true,\"cpuClipThreads\":2,\"htpPowerMode\":\"POWER_SAVER\",\"htpDynamicPartitioning\":\"AUTO\"}}",
            ),
            BuiltInPerformancePreset(
                id = "10000000-0000-4000-8000-000000000002",
                name = "均衡",
                selector = "balanced",
                configJson = "{\"schemaVersion\":2,\"engine\":{\"sdxlLowRam\":false,\"animaLowRam\":false,\"animaSequentialDit\":false,\"cpuClipThreads\":4,\"htpPowerMode\":\"ADJUST_UP_DOWN\",\"htpDynamicPartitioning\":\"AUTO\"}}",
            ),
            BuiltInPerformancePreset(
                id = "10000000-0000-4000-8000-000000000003",
                name = "极致性能",
                selector = "extreme_performance",
                configJson = "{\"schemaVersion\":2,\"engine\":{\"sdxlLowRam\":false,\"animaLowRam\":false,\"animaSequentialDit\":false,\"cpuClipThreads\":8,\"htpPowerMode\":\"PERFORMANCE\",\"htpDynamicPartitioning\":\"ENABLED\"}}",
            ),
            BuiltInPerformancePreset(
                id = "10000000-0000-4000-8000-000000000004",
                name = "持续性能",
                selector = "sustained_performance",
                configJson = "{\"schemaVersion\":2,\"engine\":{\"sdxlLowRam\":true,\"animaLowRam\":true,\"animaSequentialDit\":true,\"cpuClipThreads\":8,\"htpPowerMode\":\"PERFORMANCE\",\"htpDynamicPartitioning\":\"ENABLED\"}}",
            ),
        )
    }
}
