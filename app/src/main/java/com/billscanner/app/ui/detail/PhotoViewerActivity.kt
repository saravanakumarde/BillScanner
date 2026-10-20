package com.billscanner.app.ui.detail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.R
import com.billscanner.app.ui.widget.ZoomableImageView
import com.billscanner.app.util.ImageRotation
import com.billscanner.app.util.PdfSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a bill's file full-screen. Pinch/double-tap zooms in on faint or
 * small print.
 *
 *  - Photo: can also be rotated (rotation is written back to the same
 *    file/MediaStore row, so it sticks). Whenever the user rotates,
 *    RESULT_OK is returned so the caller (Review/Detail screen) can refresh
 *    its own thumbnail, which would otherwise still show the old orientation.
 *  - PDF: shown one page at a time (zoomable), with previous/next page
 *    buttons when it has more than one page. Rotate is hidden: a PDF page is
 *    never stored sideways, so there is nothing to fix.
 */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IMAGE_URI = "imageUri"

        /** Simple fire-and-forget viewer; the caller's onResume() naturally
         *  picks up any rotation made here since it reloads the bill/image. */
        fun start(context: Context, imageUri: String) {
            if (imageUri.isEmpty()) return
            val intent = Intent(context, PhotoViewerActivity::class.java)
            intent.putExtra(EXTRA_IMAGE_URI, imageUri)
            context.startActivity(intent)
        }
    }

    private lateinit var ivFullPhoto: ZoomableImageView
    private lateinit var rotateProgress: ProgressBar
    private lateinit var rotateControls: View
    private lateinit var pdfNav: View
    private lateinit var tvPdfPage: TextView
    private lateinit var btnPrevPage: ImageButton
    private lateinit var btnNextPage: ImageButton

    private var imageUriString: String = ""
    private var wasRotated = false

    private var isPdf = false
    private var pdfPageCount = 0
    private var pdfPageIndex = 0
    private var pdfRenderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finishWithResult() }

        imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI) ?: ""
        ivFullPhoto = findViewById(R.id.ivFullPhoto)
        rotateProgress = findViewById(R.id.rotateProgress)
        rotateControls = findViewById(R.id.rotateControls)
        pdfNav = findViewById(R.id.pdfNav)
        tvPdfPage = findViewById(R.id.tvPdfPage)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)

        // Zoomable image's own single-tap also closes the viewer, matching
        // the previous tap-anywhere-to-dismiss behavior when not zoomed in.
        ivFullPhoto.setOnClickListener { finishWithResult() }

        findViewById<ImageButton>(R.id.btnRotateLeft).setOnClickListener { rotate(-90f) }
        findViewById<ImageButton>(R.id.btnRotateRight).setOnClickListener { rotate(90f) }
        btnPrevPage.setOnClickListener { showPdfPage(pdfPageIndex - 1) }
        btnNextPage.setOnClickListener { showPdfPage(pdfPageIndex + 1) }

        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        isPdf = PdfSupport.isPdf(imageUriString)
        if (isPdf) {
            rotateControls.visibility = View.GONE
            openPdf()
        } else {
            loadImage()
        }
    }

    private fun openPdf() {
        lifecycleScope.launch {
            pdfPageCount = withContext(Dispatchers.IO) { PdfSupport.pageCount(imageUriString) }
            if (pdfPageCount == 0) {
                Toast.makeText(this@PhotoViewerActivity, R.string.pdf_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            showPdfPage(0)
        }
    }

    private fun showPdfPage(index: Int) {
        if (index < 0 || index >= pdfPageCount) return
        // A quick second tap on next/previous replaces the render still running.
        pdfRenderJob?.cancel()
        rotateProgress.visibility = View.VISIBLE
        pdfRenderJob = lifecycleScope.launch {
            // About twice the screen width: sharp enough to zoom into small print.
            val width = (resources.displayMetrics.widthPixels * 2).coerceIn(1200, 2400)
            val bitmap = withContext(Dispatchers.Default) {
                PdfSupport.renderPage(imageUriString, index, width)
            }
            rotateProgress.visibility = View.GONE
            if (bitmap == null) {
                Toast.makeText(this@PhotoViewerActivity, R.string.pdf_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            ivFullPhoto.setImageBitmap(bitmap)
            pdfPageIndex = index
            updatePdfNav()
        }
    }

    private fun updatePdfNav() {
        if (pdfPageCount <= 1) {
            pdfNav.visibility = View.GONE
            return
        }
        pdfNav.visibility = View.VISIBLE
        tvPdfPage.text = getString(R.string.pdf_page_indicator_format, pdfPageIndex + 1, pdfPageCount)
        btnPrevPage.alpha = if (pdfPageIndex > 0) 1f else 0.3f
        btnNextPage.alpha = if (pdfPageIndex < pdfPageCount - 1) 1f else 0.3f
    }

    private fun loadImage() {
        if (imageUriString.isEmpty()) return
        try {
            // ImageView treats "same Uri as already shown" as a no-op on
            // many Android versions, so after a rotation overwrites the
            // same file/MediaStore row in place, calling setImageURI again
            // with that identical Uri can silently keep showing the old
            // (pre-rotation) pixels on screen even though the right bytes
            // are now on disk. Clearing first forces a real reload.
            ivFullPhoto.setImageURI(null)
            ivFullPhoto.setImageURI(Uri.parse(imageUriString))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rotate(degrees: Float) {
        if (imageUriString.isEmpty() || isPdf) return
        rotateProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ImageRotation.rotateInPlace(this@PhotoViewerActivity, imageUriString, degrees)
            }
            rotateProgress.visibility = View.GONE
            if (success) {
                wasRotated = true
                loadImage()
            } else {
                Toast.makeText(
                    this@PhotoViewerActivity,
                    R.string.could_not_rotate_photo,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun finishWithResult() {
        setResult(if (wasRotated) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
