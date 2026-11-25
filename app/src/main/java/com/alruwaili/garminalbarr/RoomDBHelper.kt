package com.alruwaili.garminalbarr

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.alruwaili.garminalbarr.track.TrackDao
import com.alruwaili.garminalbarr.track.TrackEntity
import com.alruwaili.garminalbarr.track.TrackPointEntity

@Database(
    entities = [
        LocationEntity::class,
        TrackEntity::class,
        TrackPointEntity::class
    ],
    version = 5
)
abstract class RoomDBHelper: RoomDatabase() {
    abstract val locationDao: LocationDao
    abstract val trackDao: TrackDao

    companion object {
        @Volatile
        private var INSTANCE: RoomDBHelper? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_title ON locations(title)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_latitude_longitude ON locations(latitude, longitude)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE locations ADD COLUMN is_manual INTEGER DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tracks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `color_hex` TEXT NOT NULL DEFAULT '#E53935',
                        `start_time_millis` INTEGER NOT NULL,
                        `end_time_millis` INTEGER NOT NULL,
                        `total_distance_meters` REAL NOT NULL,
                        `duration_seconds` INTEGER NOT NULL,
                        `max_speed_mps` REAL NOT NULL DEFAULT 0.0,
                        `avg_speed_mps` REAL NOT NULL DEFAULT 0.0,
                        `min_altitude_meters` REAL,
                        `max_altitude_meters` REAL,
                        `is_visible_on_map` INTEGER NOT NULL DEFAULT 1,
                        `points_count` INTEGER NOT NULL DEFAULT 0,
                        `encoded_polyline` TEXT
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `track_points` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `track_id` INTEGER NOT NULL,
                        `seq_index` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `altitude` REAL,
                        `speed_mps` REAL,
                        `bearing_deg` REAL,
                        `timestamp_millis` INTEGER NOT NULL,
                        FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_track_id_seq_index` ON `track_points`(`track_id`, `seq_index`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_track_id` ON `track_points`(`track_id`)")
            }
        }

        fun getInstance(c: Context): RoomDBHelper{
            return INSTANCE ?: synchronized(this) {

                val instance = Room
                    .databaseBuilder(c, RoomDBHelper::class.java,"app_database")
                    .createFromAsset("locations.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON;")
                            db.execSQL("PRAGMA temp_store = MEMORY;")
                            db.execSQL("PRAGMA synchronous = NORMAL;")
                        }
                    })
                    .build()
                 INSTANCE= instance
                instance

            }
        }
    }
}