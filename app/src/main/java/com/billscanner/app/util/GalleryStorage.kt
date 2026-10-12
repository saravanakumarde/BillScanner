package com.billscanner.app.util

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves scanned receipt photos into the shared Gallery (Pictures/BillScanner),
 * NOT app-private storage — required so photos show up in the phone's normal
 * Gallery/Photos app like any other picture.
 */
object GalleryStorage {

    private const val RELATIVE_DIR = "Pictures/BillScanner"

    /**
     * Finds the most recently added photo in the device's MediaStore
     * (any album/folder — since we can't control where the Xiaomi Camera
     * app saves when launched directly). Used to pick up the photo the
     * user just took in the system Camera app, including its Documents
     * scan mode. Only looks at photos added in the last few minutes so we
     * don't accidentally grab something unrelated to this scan session.
     */
    fun findMostRecentPhoto(context: Context, sinceMillis: Long): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        // DATE_ADDED has only second-level precision, so subtract a generous
        // margin to avoid missing a photo due to clock/rounding differences
        // between our timestamp and what the camera app's write is recorded as.
        val sinceSeconds = (sinceMillis / 1000) - 10
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(sinceSeconds.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                android.util.Log.d("GalleryStorage", "findMostRecentPhoto: query returned ${cursor.count} rows (since=$sinceSeconds)")
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    android.util.Log.d("GalleryStorage", "findMostRecentPhoto: found id=$id name=$name")
                    return android.content.ContentUris.withAppendedId(collection, id)
                }
            } ?: android.util.Log.w("GalleryStorage", "findMostRecentPhoto: query() returned null cursor")
        } catch (e: SecurityException) {
            android.util.Log.e("GalleryStorage", "findMostRecentPhoto: missing gallery read permission", e)
        } catch (e: Exception) {
            android.util.Log.e("GalleryStorage", "findMostRecentPhoto: query failed", e)
        }
        return null
    }

    /**
     * Creates an empty, "pending" entry in the shared Gallery and returns its
     * content:// URI, WITHOUT writing any bytes yet. Hand this URI to a
     * camera intent (MediaStore.ACTION_IMAGE_CAPTURE with EXTRA_OUTPUT) so
     * the camera app writes the photo directly into the Gallery. Call
     * [finalizePending] afterwards to mark it visible, or [deletePending]
     * if the user cancels the capture.
     *
     * Used by ScanActivity's opt-in "direct capture" mode (Settings), which
     * trades the Xiaomi Camera app's Document auto-crop mode (only reachable
     * by launching that app directly, which doesn't reliably honor
     * EXTRA_OUTPUT) for saving straight into Pictures/BillScanner with no
     * find-and-move step afterward.
     */
    fun createPendingImageUri(context: Context): Uri? {
        val fileName = "BILL_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "BillScanner"
                )
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.Images.Media.DATA, java.io.File(dir, fileName).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return resolver.insert(collection, values)
    }

    /** Marks a pending entry (from [createPendingImageUri]) as complete/visible. */
    fun finalizePending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
    }

    /** Removes a pending entry if the user backed out of the camera without taking a photo. */
    fun deletePending(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Moves a photo (found elsewhere in the Gallery, e.g. the Camera app's
     * default roll) into our Pictures/BillScanner folder, so all bill scans
     * end up organized together regardless of where the camera app that
     * took them normally saves. Returns the new URI (which may differ from
     * the input URI), or the original URI if the move could not be
     * performed (e.g. on very old Android versions where this is best-effort).
     */
    fun moveIntoAppFolder(context: Context, sourceUri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return sourceUri
        }
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            val rows = context.contentResolver.update(sourceUri, values, null, null)
            android.util.Log.d("GalleryStorage", "moveIntoAppFolder: updated $rows row(s) for $sourceUri")
            sourceUri
        } catch (e: Exception) {
            android.util.Log.e("GalleryStorage", "moveIntoAppFolder: failed for $sourceUri", e)
            sourceUri
        }
    }

    /**
     * Copies an arbitrary picked image (from the Photo Picker / gallery
     * chooser, which may return a URI from anywhere - another app's cache,
     * cloud-backed storage, etc.) into our own Pictures/BillScanner folder
     * as a standalone JPEG. Used for the "Choose from Gallery" flow, where
     * (unlike the camera flow) we already have a definite source URI and
     * don't need to search MediaStore for it.
     */
    fun copyIntoAppFolder(context: Context, sourceUri: Uri): Uri? {
        return try {
            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            } ?: return null
            saveBitmap(context, bitmap)
        } catch (e: Exception) {
            android.util.Log.e("GalleryStorage", "copyIntoAppFolder failed for $sourceUri", e)
            null
        }
    }

    /**
     * Best-effort delete of the original picked photo after it has been
     * copied into our BillScanner album, so "Choose from Gallery" ends up
     * moving the photo instead of leaving a duplicate behind.
     *
     * Deleting a MediaStore row that another app owns is restricted on
     * modern Android, so this is layered:
     *  - API 30+: a direct delete usually throws a RecoverableSecurityException;
     *    on Android 11+ we instead ask for one via [MediaStore.createDeleteRequest],
     *    which shows the system's "Allow Bill Scanner to delete this photo?"
     *    confirmation. Call this from an ActivityResultLauncher registered
     *    with StartIntentSenderForResult.
     *  - API 29: a direct delete throws RecoverableSecurityException carrying
     *    its own IntentSender for the same kind of confirmation.
     *  - Below API 29 (legacy storage) or when we already own the row: the
     *    direct delete just succeeds immediately.
     *
     * Returns an IntentSender to launch for user confirmation, or null if
     * the delete already succeeded (or is not possible) and nothing further
     * needs to happen. Never throws.
     */
    fun deleteOriginalOrGetConfirmationRequest(context: Context, uri: Uri): IntentSender? {
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                android.util.Log.d("GalleryStorage", "deleteOriginal: deleted $uri immediately")
                return null
            }
        } catch (e: RecoverableSecurityException) {
            return e.userAction.actionIntent.intentSender
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                } catch (inner: Exception) {
                    android.util.Log.w("GalleryStorage", "deleteOriginal: createDeleteRequest failed for $uri", inner)
                    null
                }
            }
            android.util.Log.w("GalleryStorage", "deleteOriginal: no permission to delete $uri", e)
        } catch (e: Exception) {
            android.util.Log.w("GalleryStorage", "deleteOriginal: failed for $uri", e)
        }
        return null
    }

    fun saveBitmap(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "BILL_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "BillScanner"
                )
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.Images.Media.DATA, java.io.File(dir, fileName).absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }
}
