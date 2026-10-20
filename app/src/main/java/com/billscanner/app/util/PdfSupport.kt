package com.billscanner.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * PDF bills (e.g. an Amazon invoice downloaded as a PDF).
 *
 * Uses Android's built-in [PdfRenderer], so no extra library is needed: each
 * page is drawn to a bitmap, which is then handled exactly like a photo
 * (the same OCR, the same parsing). A PDF bill is stored as a ".pdf" file in
 * private storage, and the extension is the only marker that it is a PDF.
 *
 * Works on files in the app's own private storage (file:// Uris), which is
 * where every imported PDF is copied first.
 */
object PdfSupport {

    /** An invoice can run to several pages; reading more than this only slows the import down. */
    const val MAX_OCR_PAGES = 6

    /** Tallest bitmap a page is ever rendered at, so an unusually long page cannot exhaust memory. */
    private const val MAX_RENDER_HEIGHT_PX = 6000

    fun isPdf(uriString: String?): Boolean {
        if (uriString.isNullOrEmpty()) return false
        return uriString.substringBefore('?').substringBefore('#').lowercase().endsWith(".pdf")
    }

    private fun openDescriptor(uriString: String): ParcelFileDescriptor? {
        val path = Uri.parse(uriString).path ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Number of pages, or 0 if the file is missing, damaged or password protected. */
    fun pageCount(uriString: String): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            val fd = openDescriptor(uriString) ?: return 0
            pfd = fd
            val r = PdfRenderer(fd)
            renderer = r
            r.pageCount
        } catch (e: Exception) {
            0
        } finally {
            try { renderer?.close() } catch (e: Exception) { /* ignore */ }
            try { pfd?.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Renders one page (0-based) about [targetWidthPx] pixels wide on a white
     * background (PdfRenderer draws onto transparent, which OCR would see as
     * black). Returns null if the page cannot be rendered. The caller owns
     * the returned bitmap.
     */
    fun renderPage(uriString: String, pageIndex: Int, targetWidthPx: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            val fd = openDescriptor(uriString) ?: return null
            pfd = fd
            val r = PdfRenderer(fd)
            renderer = r
            if (pageIndex < 0 || pageIndex >= r.pageCount) return null
            val p = r.openPage(pageIndex)
            page = p

            val scale = minOf(
                targetWidthPx.coerceAtLeast(1).toFloat() / p.width,
                MAX_RENDER_HEIGHT_PX.toFloat() / p.height
            )
            val width = (p.width * scale).toInt().coerceAtLeast(1)
            val height = (p.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            try { page?.close() } catch (e: Exception) { /* ignore */ }
            try { renderer?.close() } catch (e: Exception) { /* ignore */ }
            try { pfd?.close() } catch (e: Exception) { /* ignore */ }
        }
    }
}
