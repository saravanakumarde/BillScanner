package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A store the user has manually corrected at least once. [anchorText] holds
 * a handful of lines pulled from that receipt's raw OCR text which are
 * expected to reappear, verbatim, on every future receipt from the exact
 * same shop location — typically its address and/or phone number, since
 * those don't change from visit to visit (unlike the date, total, or items).
 * Matching a new receipt against this is just "do enough of these lines
 * show up again", no ML involved.
 */
@Entity(tableName = "store_memory")
data class StoreMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val anchorText: String,
    val correctedStoreName: String,
    val categoryId: Long? = null,
    val timesConfirmed: Int = 1,
    val lastUsedMillis: Long = System.currentTimeMillis()
)
