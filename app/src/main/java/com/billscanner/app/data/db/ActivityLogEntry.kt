package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single entry in the on-device activity log: what was auto-extracted
 * from a scan, what the user corrected afterwards (and to what), bills
 * being deleted, and store corrections being learned. Purely a local,
 * human-readable audit trail — exportable as plain text so the user can
 * hand it to someone (including back to Claude) to explain what happened.
 */
@Entity(tableName = "activity_log")
data class ActivityLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: String,
    val summary: String,
    val detail: String = ""
)

object LogEventType {
    const val SCAN_EXTRACTED = "SCAN_EXTRACTED"
    const val BILL_SAVED = "BILL_SAVED"
    const val BILL_CORRECTED = "BILL_CORRECTED"
    const val BILL_EDITED = "BILL_EDITED"
    const val BILL_DELETED = "BILL_DELETED"
    const val STORE_LEARNED = "STORE_LEARNED"
    const val PHOTOS_MIGRATED = "PHOTOS_MIGRATED"
}
