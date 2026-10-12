package com.billscanner.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.billscanner.app.BuildConfig
import com.billscanner.app.data.db.Bill
import com.billscanner.app.data.db.Category
import com.billscanner.app.data.db.LineItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports every bill (photo + all entered data) to the phone, for a future
 * companion HTML app to import. Two things are produced from one pass over
 * the data:
 *
 *  1. A folder that just sits on the phone (Pictures/BillScannerExport for
 *     the renamed photos, Downloads/BillScannerExport for export.json) —
 *     written via MediaStore, the same mechanism [GalleryStorage] already
 *     uses elsewhere in this app, so no new permission model is needed.
 *  2. A single .zip of the same content, handed to the system share sheet
 *     (email, Drive, Files, etc.) — the HTML importer's most likely input,
 *     since a zip is one file instead of a scattered folder.
 *
 * Nothing is uploaded anywhere by the app itself.
 *
 * Image naming: "YYYYMMDD_HHMM_StoreName_Amount.jpg" (per spec). A
 * same-minute/store/amount collision (two bills at the same shop, same
 * minute, same total) gets "_2", "_3", ... appended before the extension.
 */
object DataExporter {

    private const val RELATIVE_IMAGE_DIR = "Pictures/BillScannerExport"
    private const val RELATIVE_DOWNLOAD_DIR = "Download/BillScannerExport"
    private const val JSON_FILE_NAME = "export.json"
    private const val SCHEMA_VERSION = 1

    data class ExportResult(
        val billCount: Int,
        val imageCount: Int,
        val zipFile: File,
        val skippedPhotos: Int
    )

    /**
     * Does the full export: reads the DB, copies/renames photos, writes
     * export.json, saves everything into shared storage, and builds the
     * zip for sharing. Runs entirely on whatever dispatcher the caller is
     * already on — callers should invoke this from a background thread
     * (e.g. Dispatchers.IO via a coroutine), since it does a lot of file
     * I/O and should not run on the main thread.
     */
    fun exportAll(
        context: Context,
        bills: List<Bill>,
        itemsByBillId: Map<Long, List<LineItem>>,
        categories: List<Category>
    ): ExportResult {
        val categoryById = categories.associateBy { it.id }
        val usedNames = mutableSetOf<String>()
        var skippedPhotos = 0

        // Stage everything in the app's own cache first (always writable,
        // no MediaStore dance needed) — then copy the staged files out to
        // shared storage and into the zip. Keeps the "where do these bytes
        // come from" logic in one place instead of duplicated per destination.
        val stagingDir = File(context.cacheDir, "export_staging").apply {
            deleteRecursively()
            mkdirs()
        }

        val billEntries = JSONArray()
        val stagedImageFiles = mutableListOf<File>()

        for (bill in bills) {
            val category = categoryById[bill.categoryId]
            val imageFileName = uniqueImageFileName(bill, usedNames)

            val stagedImage = if (imageFileName != null) {
                copyBillPhotoToStaging(context, bill, stagingDir, imageFileName)
            } else {
                null
            }
            if (imageFileName != null && stagedImage == null) skippedPhotos++
            if (stagedImage != null) stagedImageFiles.add(stagedImage)

            billEntries.put(buildBillJson(bill, itemsByBillId[bill.id].orEmpty(), category, stagedImage?.name))
        }

        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("appId", BuildConfig.APPLICATION_ID)
            put("categories", JSONArray().apply {
                categories.forEach { put(categoryJson(it)) }
            })
            put("bills", billEntries)
        }
        val jsonFile = File(stagingDir, JSON_FILE_NAME)
        jsonFile.writeText(root.toString(2))

        // Copy staged content into shared storage so it "just sits there"
        // on the phone, independent of the zip/share step below.
        saveImagesToSharedStorage(context, stagedImageFiles)
        saveJsonToSharedStorage(context, jsonFile)

        val zipFile = buildZip(context, stagedImageFiles, jsonFile)

        return ExportResult(
            billCount = bills.size,
            imageCount = stagedImageFiles.size,
            zipFile = zipFile,
            skippedPhotos = skippedPhotos
        )
    }

    /** Hands the already-built zip to the system share sheet. Call after [exportAll]. */
    fun shareZip(context: Context, zipFile: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    // -----------------------------------------------------------------
    // Filename building
    // -----------------------------------------------------------------

    /**
     * "YYYYMMDD_HHMM_StoreName_Amount", collision-safe within this export
     * run via a small "_2", "_3", ... suffix (per spec). [usedNames] is
     * shared across the whole export call so two different bills never
     * produce the same final file name even if their base name matches.
     *
     * SimpleDateFormat is created fresh here (not cached) since it isn't
     * thread-safe and this is cheap to do once per bill.
     */
    private fun uniqueImageFileName(bill: Bill, usedNames: MutableSet<String>): String? {
        if (bill.imageUri.isEmpty()) return null // manually-entered bill with no photo
        val dateFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val datePart = dateFmt.format(Date(bill.billDateMillis))
        val storePart = sanitizeForFileName(bill.storeNameEnglish.ifBlank { "UnknownStore" })
        val amountPart = formatAmountForFileName(bill.totalAmount)
        val base = "${datePart}_${storePart}_$amountPart"

        var candidate = "$base.jpg"
        var counter = 2
        while (!usedNames.add(candidate)) {
            candidate = "${base}_$counter.jpg"
            counter++
        }
        return candidate
    }

    /** "13,83" / "13.83" -> "13_83"; keeps two decimal places, no currency symbol (currency is in the JSON). */
    private fun formatAmountForFileName(amount: Double): String {
        val formatted = String.format(Locale.US, "%.2f", amount)
        return formatted.replace(".", "_")
    }

    /**
     * Strips characters that are unsafe or awkward in file names on
     * Android's filesystem (and in a zip entry name), collapses whitespace
     * to single underscores, and caps length so a long OCR'd store name
     * can't produce a path-length problem.
     */
    private fun sanitizeForFileName(raw: String): String {
        val cleaned = raw
            .trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""_{2,}"""), "_")
        val safe = if (cleaned.isBlank()) "UnknownStore" else cleaned
        return safe.take(40)
    }

    // -----------------------------------------------------------------
    // Photo staging
    // -----------------------------------------------------------------

    /**
     * Copies one bill's photo (from either app-private file:// storage or a
     * legacy content:// Uri — see [PrivatePhotoStorage]'s doc comment on
     * why both can occur) into [stagingDir] under [targetFileName]. Returns
     * null (and leaves no partial file behind) if the photo can't be read,
     * e.g. it was deleted outside the app.
     */
    private fun copyBillPhotoToStaging(context: Context, bill: Bill, stagingDir: File, targetFileName: String): File? {
        val sourceUri = Uri.parse(bill.imageUri)
        val destFile = File(stagingDir, targetFileName)
        return try {
            val input = if (PrivatePhotoStorage.isPrivateUri(bill.imageUri)) {
                sourceUri.path?.let { File(it).takeIf { f -> f.exists() }?.inputStream() }
            } else {
                context.contentResolver.openInputStream(sourceUri)
            } ?: return null

            input.use { inStream ->
                destFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
            }
            if (destFile.length() == 0L) {
                destFile.delete()
                null
            } else {
                destFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            destFile.delete()
            null
        }
    }

    // -----------------------------------------------------------------
    // JSON building
    // -----------------------------------------------------------------

    private fun categoryJson(category: Category): JSONObject = JSONObject().apply {
        put("id", category.id)
        put("name", category.name)
        put("colorHex", category.colorHex)
    }

    private fun buildBillJson(bill: Bill, items: List<LineItem>, category: Category?, imageFileName: String?): JSONObject {
        return JSONObject().apply {
            put("id", bill.id)
            put("storeName", bill.storeNameEnglish)
            put("storeNameOriginal", bill.storeNameOriginal)
            put("billDateMillis", bill.billDateMillis)
            put("billDateIso", isoDate(bill.billDateMillis))
            put("dateWasGuessed", bill.dateWasGuessed)
            put("totalAmount", bill.totalAmount)
            put("currency", bill.currency)
            put("categoryId", bill.categoryId)
            put("categoryName", category?.name ?: JSONObject.NULL)
            put("notes", bill.notes)
            put("createdAtMillis", bill.createdAtMillis)
            put("imageFileName", imageFileName ?: JSONObject.NULL)
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("nameOriginal", item.nameOriginal)
                        put("nameEnglish", item.nameEnglish)
                        put("quantity", item.quantity)
                        put("unitPrice", item.unitPrice ?: JSONObject.NULL)
                        put("totalPrice", item.totalPrice)
                        put("categoryId", item.categoryId ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    private fun isoDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    // -----------------------------------------------------------------
    // Shared storage (the "just sits there on the phone" half)
    // -----------------------------------------------------------------

    private fun saveImagesToSharedStorage(context: Context, images: List<File>) {
        for (image in images) {
            try {
                writeImageToMediaStore(context, image)
            } catch (e: Exception) {
                e.printStackTrace()
                // One failed image shouldn't abort the rest of the export.
            }
        }
    }

    private fun writeImageToMediaStore(context: Context, source: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_IMAGE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BillScannerExport")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, source.name)
            source.copyTo(destFile, overwrite = true)
            // Legacy devices need a manual MediaStore insert for the file to
            // be visible to other apps (Files, Gallery) right away.
            values.put(MediaStore.Images.Media.DATA, destFile.absolutePath)
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    private fun saveJsonToSharedStorage(context: Context, jsonFile: File) {
        try {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, JSON_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DOWNLOAD_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out -> jsonFile.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BillScannerExport")
                if (!dir.exists()) dir.mkdirs()
                val destFile = File(dir, JSON_FILE_NAME)
                jsonFile.copyTo(destFile, overwrite = true)
                // Not a MediaStore.Images insert (this isn't an image), so
                // without a scan the file can exist but stay invisible to
                // file browsers until the next full media scan.
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(destFile.absolutePath), arrayOf("application/json"), null
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -----------------------------------------------------------------
    // Zip (the "share sheet" half)
    // -----------------------------------------------------------------

    private fun buildZip(context: Context, images: List<File>, jsonFile: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(exportsDir, "bill_scanner_export_$stamp.zip")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(JSON_FILE_NAME))
            jsonFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            for (image in images) {
                zos.putNextEntry(ZipEntry("images/${image.name}"))
                image.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }
}
