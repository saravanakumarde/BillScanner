package com.billscanner.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Bill::class, LineItem::class, Category::class, StoreMemory::class, ActivityLogEntry::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun storeMemoryDao(): StoreMemoryDao
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Adds the store_memory table only — existing bills, items, and
        // categories are left untouched. Deliberately NOT a destructive
        // migration: wiping data on an app update is exactly the problem
        // this app's signing/versioning was fixed to avoid.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `store_memory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `anchorText` TEXT NOT NULL,
                        `correctedStoreName` TEXT NOT NULL,
                        `categoryId` INTEGER,
                        `timesConfirmed` INTEGER NOT NULL,
                        `lastUsedMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Adds the activity_log table only, same non-destructive approach.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `activity_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `detail` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "billscanner.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed default categories on first creation.
                        scope.launch(Dispatchers.IO) {
                            val instance = INSTANCE ?: return@launch
                            val dao = instance.categoryDao()
                            dao.insertAll(
                                DefaultCategories.SEED.map { (name, color) ->
                                    Category(name = name, colorHex = color, isDefault = true)
                                }
                            )
                        }
                    }
                }).build().also { INSTANCE = it }
            }
        }
    }
}
