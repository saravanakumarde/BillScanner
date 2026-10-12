package com.billscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.File

/**
 * Rotates a bill photo (by ±90°) and writes the result back to wherever the
 * photo already lives, so the rotation "sticks" the same way for a fresh
 * scan, an already-saved bill, or a manually-picked gallery photo — no new
 * file, no dangling old copy, no extra column needed on [Bill].
 *
 * Two storage shapes exist in this app (see PrivatePhotoStorage / GalleryStorage):
 *  - file:// — our own app-private storage. Rewritten directly via File I/O.
 *  - content:// — a MediaStore row we own (Pictures/BillScanner). Rewritten
 *    via the ContentResolver, which is all that's allowed for a MediaStore
 *    row even when the app owns it.
 */
object ImageRotation {

    /**
     * Rotates the image at [uriString] by [degrees] (typically ±90) and
     * overwrites it in place. Returns true on success; the caller should
     * re-load the ImageView from the same Uri afterward (Bitmap/Drawable
     * caching, not the Uri itself, is the only thing that can go stale).
     */
    fun rotateInPlace(context: Context, uriString: String, degrees: Float): Boolean {
        if (uriString.isEmpty()) return false
        return try {
            val uri = Uri.parse(uriString)
            val original = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return false

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true
            )
            if (rotated !== original) original.recycle()

            val wrote = if (PrivatePhotoStorage.isPrivateUri(uriString)) {
                writeToFile(uri, rotated)
            } else {
                writeToContentUri(context, uri, rotated)
            }
            rotated.recycle()
            wrote
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writeToFile(uri: Uri, bitmap: Bitmap): Boolean {
        val path = uri.path ?: return false
        val file = File(path)
        return try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writeToContentUri(context: Context, uri: Uri, bitmap: Bitmap): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } ?: return false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
