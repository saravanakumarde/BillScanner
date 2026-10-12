package com.billscanner.app.util

import android.content.Context

enum class ScanDetectionMode {
    AUTOMATIC,  // detect the new photo on return to the app (onResume)
    MANUAL      // user taps "Find my photo" explicitly
}

object AppPreferences {
    private const val PREFS_NAME = "bill_scanner_prefs"
    private const val KEY_SCAN_MODE = "scan_detection_mode"
    private const val KEY_DELETE_PHOTO_ON_BILL_DELETE = "delete_photo_on_bill_delete"
    private const val KEY_DIRECT_CAPTURE_MODE = "direct_capture_mode"

    fun getScanDetectionMode(context: Context): ScanDetectionMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_SCAN_MODE, ScanDetectionMode.AUTOMATIC.name)
        return try {
            ScanDetectionMode.valueOf(value ?: ScanDetectionMode.AUTOMATIC.name)
        } catch (e: IllegalArgumentException) {
            ScanDetectionMode.AUTOMATIC
        }
    }

    fun setScanDetectionMode(context: Context, mode: ScanDetectionMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SCAN_MODE, mode.name).apply()
    }

    /**
     * Whether deleting a bill in the app should also delete its photo from
     * the Pictures/BillScanner album. Defaults to false so existing
     * behavior (photo kept) doesn't change for anyone until they opt in.
     */
    fun getDeletePhotoOnBillDelete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DELETE_PHOTO_ON_BILL_DELETE, false)
    }

    fun setDeletePhotoOnBillDelete(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DELETE_PHOTO_ON_BILL_DELETE, enabled).apply()
    }

    // One-time flag: have existing bills' photos been moved from the shared
    // Gallery album into the app's own private storage yet? See
    // PrivatePhotoStorage and MainActivity.migratePhotosIfNeeded().
    private const val KEY_PHOTOS_MIGRATED = "photos_migrated_to_private_v1"

    fun arePhotosMigrated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PHOTOS_MIGRATED, false)
    }

    fun setPhotosMigrated(context: Context, migrated: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PHOTOS_MIGRATED, migrated).apply()
    }

    /**
     * Whether to use the plain, standard camera-capture intent (which lets
     * us tell it to save directly into Pictures/BillScanner — no find/move
     * step afterward) instead of launching the Xiaomi Camera app's own
     * package directly (which unlocks its "Document" auto-crop/perspective-
     * correction mode, but can't reliably be told where to save, hence the
     * find-and-move workaround). Off by default to keep the better scan
     * quality most people would prefer; on trades that for a simpler,
     * more direct save.
     */
    fun getDirectCaptureMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DIRECT_CAPTURE_MODE, false)
    }

    fun setDirectCaptureMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DIRECT_CAPTURE_MODE, enabled).apply()
    }
}
