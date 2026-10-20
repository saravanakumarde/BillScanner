package com.billscanner.app.util

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView

/**
 * One place that shows a bill's stored file in an ImageView, whether it is a
 * photo or a PDF. A photo is loaded from its Uri; a PDF shows its first page.
 *
 * PDF first pages are cached (a PDF file never changes once imported) so
 * scrolling the bill list does not re-render them every time.
 */
object BillImageLoader {

    private val pdfPageCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** [pdfWidthPx] is only used for PDFs: how wide to render the first page. */
    fun load(imageView: ImageView, uriString: String, pdfWidthPx: Int) {
        if (uriString.isEmpty()) {
            imageView.setImageDrawable(null)
            return
        }
        try {
            if (PdfSupport.isPdf(uriString)) {
                val key = "$uriString@$pdfWidthPx"
                val bitmap = pdfPageCache.get(key)
                    ?: PdfSupport.renderPage(uriString, 0, pdfWidthPx)?.also { pdfPageCache.put(key, it) }
                imageView.setImageBitmap(bitmap) // null simply clears the view
            } else {
                // Clear first: ImageView can treat an unchanged Uri as a no-op
                // and keep showing a stale (e.g. pre-rotation) picture.
                imageView.setImageURI(null)
                imageView.setImageURI(Uri.parse(uriString))
            }
        } catch (e: Exception) {
            imageView.setImageDrawable(null)
        }
    }
}
