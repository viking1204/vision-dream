package io.github.xororz.localdream.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseFile: File
    private var roomDatabase: AppDatabase? = null

    @Before
    fun prepareDatabase() {
        databaseFile = context.getDatabasePath(DATABASE_NAME)
        context.deleteDatabase(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
    }

    @After
    fun cleanDatabase() {
        roomDatabase?.close()
        roomDatabase = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationFrom3To4PreservesHistoryAndCreatesPromptTemplates() {
        createVersion3Database()

        val migrated = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        roomDatabase = migrated

        migrated.openHelper.writableDatabase.query(
            """
            SELECT modelId, favorite, origin, mimeType, requestId
            FROM generation_history
            ORDER BY id
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("png-model", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("local_app", cursor.getString(2))
            assertEquals("image/png", cursor.getString(3))
            assertTrue(cursor.isNull(4))

            assertTrue(cursor.moveToNext())
            assertEquals("jpg-model", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("local_app", cursor.getString(2))
            assertEquals("image/jpeg", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }

        runBlocking {
            val prompt = PromptTemplateEntity(
                title = "Portrait",
                prompt = "portrait",
                negativePrompt = "blurry",
                createdAt = 10,
                updatedAt = 10,
                lastUsedAt = null,
            )
            val id = migrated.promptTemplateDao().insert(prompt)
            val loaded = migrated.promptTemplateDao().getById(id)
            assertEquals("Portrait", loaded?.title)
            assertEquals(0, loaded?.useCount)
            assertNull(loaded?.lastUsedAt)
        }
    }

    private fun createVersion3Database() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE generation_history (
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
                    useOpenCL INTEGER NOT NULL,
                    favorite INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX index_generation_history_modelId_timestamp " +
                    "ON generation_history (modelId, timestamp)",
            )
            db.execSQL(
                "CREATE INDEX index_generation_history_timestamp " +
                    "ON generation_history (timestamp)",
            )
            db.execSQL(
                "CREATE INDEX index_generation_history_mode " +
                    "ON generation_history (mode)",
            )
            insertHistory(
                db = db,
                modelId = "png-model",
                timestamp = 100,
                imagePath = "history/png-model/100.png",
                favorite = 1,
            )
            insertHistory(
                db = db,
                modelId = "jpg-model",
                timestamp = 200,
                imagePath = "history/jpg-model/200.jpg",
                favorite = 0,
            )
            db.version = 3
        }
    }

    private fun insertHistory(
        db: SQLiteDatabase,
        modelId: String,
        timestamp: Long,
        imagePath: String,
        favorite: Int,
    ) {
        db.execSQL(
            """
            INSERT INTO generation_history (
                modelId, timestamp, imagePath, width, height, mode,
                denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                negativePrompt, generationTime, scheduler, runOnCpu,
                useOpenCL, favorite
            ) VALUES (?, ?, ?, 512, 512, 'TXT2IMG', NULL, NULL, 20, 7.0,
                      123, 'prompt', '', '1s', 'dpm', 0, 0, ?)
            """.trimIndent(),
            arrayOf<Any>(modelId, timestamp, imagePath, favorite),
        )
    }

    companion object {
        private const val DATABASE_NAME = "migration-v3-v4.db"
    }
}
