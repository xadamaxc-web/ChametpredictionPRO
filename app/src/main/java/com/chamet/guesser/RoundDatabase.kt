package com.chamet.guesser

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Database(
    entities = [RoundEntity::class, RaceTrackEntity::class],
    version = 5,
    exportSchema = false
)
abstract class RoundDatabase : RoomDatabase() {
    abstract fun roundDao(): RoundDao
    abstract fun raceTrackDao(): RaceTrackDao

    companion object {
        private const val TAG = "RoundDatabase"
        private const val DB_NAME = "chamet_rounds.db"

        @Volatile private var INSTANCE: RoundDatabase? = null

        /**
         * v4 → v5: add Session-1 columns and the race_track child table.
         * All new columns are nullable / have defaults so existing rows stay intact.
         * SQL is also exposed for unit tests (see [MIGRATION_4_5_SQL]).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_4_5_SQL) {
                    db.execSQL(sql)
                }
            }
        }

        /** Ordered list of statements applied by [MIGRATION_4_5]. Kept public for tests. */
        val MIGRATION_4_5_SQL: List<String> = listOf(
            "ALTER TABLE rounds ADD COLUMN roundUuid TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE rounds ADD COLUMN revealedSlot INTEGER",
            "ALTER TABLE rounds ADD COLUMN visibleRoad TEXT",
            "ALTER TABLE rounds ADD COLUMN roadType1 TEXT",
            "ALTER TABLE rounds ADD COLUMN roadType2 TEXT",
            "ALTER TABLE rounds ADD COLUMN roadType3 TEXT",
            "ALTER TABLE rounds ADD COLUMN roadPx1 REAL",
            "ALTER TABLE rounds ADD COLUMN roadPx2 REAL",
            "ALTER TABLE rounds ADD COLUMN roadPx3 REAL",
            "ALTER TABLE rounds ADD COLUMN trackWidthPx REAL",
            "ALTER TABLE rounds ADD COLUMN poolTotalShown INTEGER",
            "ALTER TABLE rounds ADD COLUMN laneCars TEXT",
            "ALTER TABLE rounds ADD COLUMN finishOrder TEXT",
            "ALTER TABLE rounds ADD COLUMN modelWinner TEXT",
            "ALTER TABLE rounds ADD COLUMN modelTimes TEXT",
            "ALTER TABLE rounds ADD COLUMN pickJson TEXT",
            "ALTER TABLE rounds ADD COLUMN paramsVersion TEXT",
            "ALTER TABLE rounds ADD COLUMN winnerSource TEXT",
            "ALTER TABLE rounds ADD COLUMN roadSource TEXT",
            "ALTER TABLE rounds ADD COLUMN captureStage TEXT",
            "ALTER TABLE rounds ADD COLUMN synced INTEGER NOT NULL DEFAULT 0",
            """
            CREATE TABLE IF NOT EXISTS race_track (
                roundUuid TEXT NOT NULL,
                timeMs INTEGER NOT NULL,
                x1 REAL NOT NULL,
                x2 REAL NOT NULL,
                x3 REAL NOT NULL,
                PRIMARY KEY(roundUuid, timeMs)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_race_track_roundUuid ON race_track(roundUuid)"
        )

        fun get(context: Context): RoundDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): RoundDatabase {
            // Snapshot the on-disk DB before Room opens it and runs migrations.
            backupDatabaseFile(context)
            return Room.databaseBuilder(context, RoundDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_4_5)
                // No fallbackToDestructiveMigration — data must survive upgrades.
                .build()
        }

        /**
         * Copy chamet_rounds.db (and -wal/-shm if present) into the app Documents
         * folder with a timestamped name, so a bad migration never loses history.
         * Safe to call every open; skips when the DB file does not exist yet.
         */
        fun backupDatabaseFile(context: Context): File? {
            return try {
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists() || dbFile.length() == 0L) return null

                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                    ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dest = File(dir, "chamet_rounds_backup_$stamp.db")
                copyFile(dbFile, dest)
                // Best-effort WAL companions
                for (suffix in listOf("-wal", "-shm")) {
                    val side = File(dbFile.path + suffix)
                    if (side.exists()) {
                        copyFile(side, File(dest.path + suffix))
                    }
                }
                Log.i(TAG, "Pre-migration backup written to ${dest.absolutePath}")
                dest
            } catch (e: Exception) {
                Log.w(TAG, "Pre-migration backup failed: ${e.message}")
                null
            }
        }

        private fun copyFile(src: File, dest: File) {
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }

        /** Test helper: drop the singleton so the next [get] rebuilds. */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
