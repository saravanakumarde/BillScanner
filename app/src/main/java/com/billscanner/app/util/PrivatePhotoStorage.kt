package com.billscanner.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Bill photos are kept in the app's own private storage
 * (getExternalFilesDir — app-specific, no permissions needed, invisible to
 * the Gallery/Photos app and to any other app) rather than the shared
 * Gallery. That means deleting a photo from the Gallery's BillScanner album
 * can no longer break a bill in the app, since the app isn't relying on
 * that copy anymore.
 *
 * Trade-off, by design: these files are NOT visible in Gallery/Photos and
 * are NOT swept up by things like Google Photos auto-backup. They also do
 * NOT survive a full uninstall of the app (app updates are fine — this
 * directory persists across those, same as the database).
 */
object PrivatePhotoStorage {

    private fun photosDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "bill_photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copies [sourceUri]'s bytes into app-private storage. Returns a
     * file:// Uri for the new copy, or null if the source couldn't be read.
     * Does not touch or delete the source — that's the caller's job, once
     * it has confirmed the copy succeeded.
     */
    fun copyIntoPrivateStorage(context: Context, sourceUri: Uri): Uri? {
        return try {
            val destFile = File(photosDir(context), "bill_${System.currentTimeMillis()}.jpg")
            val input = context.contentResolver.openInputStream(sourceUri) ?: return null
            input.use { inStream ->
                destFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
            }
            if (destFile.length() == 0L) {
                destFile.delete()
                return null
            }
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isPrivateUri(uriString: String): Boolean = uriString.startsWith("file://")

    /** Deletes a private-storage photo file directly — no permissions dance needed, it's the app's own file. */
    fun deletePrivatePhoto(uriString: String): Boolean {
        if (!isPrivateUri(uriString)) return false
        return try {
            Uri.parse(uriString).path?.let { File(it).delete() } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
