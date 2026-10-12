package com.billscanner.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.billscanner.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a plain-text export to the app's private cache directory and hands
 * it to the system share sheet (email, Drive, Files, messaging apps, etc.)
 * via a FileProvider content:// URI. Nothing is uploaded anywhere by the
 * app itself — sharing (or not) is entirely the user's choice at that point.
 */
object ExportHelper {

    fun timestampedFileName(prefix: String, extension: String = "txt"): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        return "${prefix}_$stamp.$extension"
    }

    fun shareText(context: Context, fileName: String, content: String, chooserTitle: String) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, fileName)
        file.writeText(content)

        val uri = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
