package com.billscanner.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * The permission needed to QUERY the Gallery/MediaStore for photos this
     * app didn't create itself (i.e. to find the photo the external Camera
     * app just saved). This is DIFFERENT from being able to write our own
     * photos, which needs no runtime permission on API 29+.
     *
     * Without this, MediaStore queries either return zero rows or throw a
     * SecurityException on Android 10+ - which is why "find the photo I just
     * took" was silently failing.
     */
    fun galleryReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    fun hasGalleryReadPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, galleryReadPermission()) ==
            PackageManager.PERMISSION_GRANTED
    }
}
