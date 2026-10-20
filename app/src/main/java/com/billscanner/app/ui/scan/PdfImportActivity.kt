package com.billscanner.app.ui.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.R
import com.billscanner.app.ocr.ImagePreprocessor
import com.billscanner.app.ocr.ReceiptOcr
import com.billscanner.app.parser.ReceiptParser
import com.billscanner.app.ui.review.ReviewActivity
import com.billscanner.app.util.PdfSupport
import com.billscanner.app.util.PrivatePhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports a PDF as a bill (e.g. an invoice PDF downloaded from Amazon).
 *
 * Two ways in, same result:
 *  - the "Choose PDF" option of the add-bill dialog (no Uri given, so the
 *    system file picker opens first), or
 *  - another app's Share menu (Gmail, a browser, the Amazon app, Files...),
 *    which hands this activity the PDF directly.
 *
 * The PDF is copied into private storage, each page (up to
 * [PdfSupport.MAX_OCR_PAGES]) is rendered to an image and run through the
 * same OCR as a photo, and the combined text goes through the normal parser
 * and into the usual Review screen. The original PDF is what gets stored
 * with the bill, not a picture of it.
 */
class PdfImportActivity : AppCompatActivity() {

    companion object {
        /** Width each page is rendered at before OCR; wide enough for small invoice print. */
        private const val OCR_PAGE_WIDTH_PX = 1800

        fun start(context: Context) {
            context.startActivity(Intent(context, PdfImportActivity::class.java))
        }
    }

    private lateinit var tvStatus: TextView

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importPdf(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_import)
        tvStatus = findViewById(R.id.tvPdfImportStatus)
        tvStatus.setText(R.string.pdf_import_reading)

        val shared = sharedPdfUri(intent)
        if (shared != null) importPdf(shared) else pickPdfLauncher.launch(arrayOf("application/pdf"))
    }

    private fun sharedPdfUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    private fun fail(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun importPdf(source: Uri) {
        lifecycleScope.launch {
            val privateUri = withContext(Dispatchers.IO) {
                PrivatePhotoStorage.copyPdfIntoPrivateStorage(this@PdfImportActivity, source)
            }
            if (privateUri == null) { fail(R.string.pdf_import_failed); return@launch }
            val pdfUriString = privateUri.toString()

            val totalPages = withContext(Dispatchers.IO) { PdfSupport.pageCount(pdfUriString) }
            if (totalPages == 0) {
                // Unreadable (damaged / password protected): don't keep a useless copy.
                PrivatePhotoStorage.deletePrivatePhoto(pdfUriString)
                fail(R.string.pdf_import_failed)
                return@launch
            }

            val pagesToRead = minOf(totalPages, PdfSupport.MAX_OCR_PAGES)
            val ocr = ReceiptOcr()
            val allText = StringBuilder()

            for (pageIndex in 0 until pagesToRead) {
                tvStatus.text = getString(R.string.pdf_import_page_format, pageIndex + 1, pagesToRead)

                val page = withContext(Dispatchers.Default) {
                    PdfSupport.renderPage(pdfUriString, pageIndex, OCR_PAGE_WIDTH_PX)
                } ?: continue

                val processed = ImagePreprocessor.forOcr(page)
                val pageText = try {
                    ocr.recognize(processed).rawText
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                if (processed !== page) processed.recycle()
                page.recycle()

                if (pageText.isNotBlank()) {
                    if (allText.isNotEmpty()) allText.append('\n')
                    allText.append(pageText)
                }
            }

            val rawText = allText.toString()
            ReviewActivity.start(
                context = this@PdfImportActivity,
                imageUri = pdfUriString,
                rawOcrText = rawText,
                parsed = ReceiptParser().parse(rawText)
            )
            finish()
        }
    }
}
