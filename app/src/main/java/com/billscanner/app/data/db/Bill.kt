package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One scanned receipt/bill.
 *
 * [imageUri] is a "file://" path into the app's own private storage
 * (see PrivatePhotoStorage) — chosen specifically so deleting a photo from
 * the shared Gallery album can't break a saved bill. Bills saved before
 * that change may briefly hold a legacy "content://" MediaStore URI until
 * the one-time migration (MainActivity.migratePhotosIfNeeded) moves them
 * over.
 *
 * [storeNameOriginal] / [storeNameEnglish] keep both the as-scanned text and the
 * translated/normalized English text, since the user wants to see both.
 * Same pattern is used on LineItem.
 */
@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val imageUri: String,

    val storeNameOriginal: String,
    val storeNameEnglish: String,

    // Epoch millis, UTC. Date parsed from the receipt if found, else scan time.
    val billDateMillis: Long,
    val dateWasGuessed: Boolean,

    val totalAmount: Double,
    val currency: String,        // e.g. "EUR", "GBP" - detected or defaulted
    val detectedLanguage: String, // "de", "en", or "unknown"

    val categoryId: Long,

    // Full raw OCR text kept for re-parsing / debugging / manual correction.
    val rawOcrText: String,

    val createdAtMillis: Long,
    val notes: String = ""
)
