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
    entities = [
        Bill::class, LineItem::class, Category::class, StoreMemory::class, ActivityLogEntry::class,
        CustomerCard::class, CustomerCardPhoto::class, RecurringExpense::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun storeMemoryDao(): StoreMemoryDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun customerCardDao(): CustomerCardDao
    abstract fun recurringExpenseDao(): RecurringExpenseDao

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

        // Adds the customer_cards and customer_card_photos tables only,
        // same non-destructive approach as the migrations above.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `customer_cards` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `customer_card_photos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cardId` INTEGER NOT NULL,
                        `imageUri` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`cardId`) REFERENCES `customer_cards`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_customer_card_photos_cardId` ON `customer_card_photos` (`cardId`)"
                )
            }
        }

        // Adds the recurring_expenses table only (fixed monthly expenses and
        // EMIs). Same non-destructive approach as the migrations above.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recurring_expenses` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `kind` TEXT NOT NULL,
                        `dayOfMonth` INTEGER NOT NULL,
                        `startYear` INTEGER NOT NULL,
                        `startMonth` INTEGER NOT NULL,
                        `totalInstallments` INTEGER,
                        `notes` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
