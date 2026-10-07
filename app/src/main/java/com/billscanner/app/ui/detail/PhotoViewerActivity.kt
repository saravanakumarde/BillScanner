package com.billscanner.app.ui.detail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.R
import com.billscanner.app.ui.widget.ZoomableImageView
import com.billscanner.app.util.ImageRotation
import kotlinx.coroutines.launch

/**
 * Shows a bill's photo full-screen. Lets the user pinch/double-tap to zoom
 * in on faint or small print, and rotate the photo (rotation is written
 * back to the same file/MediaStore row, so it sticks). Whenever the user
 * rotates, RESULT_OK is returned so the caller (Review/Detail screen) can
 * refresh its own thumbnail, which would otherwise still show the old
 * orientation.
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
    private var imageUriString: String = ""
    private var wasRotated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finishWithResult() }

        imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI) ?: ""
        ivFullPhoto = findViewById(R.id.ivFullPhoto)
        rotateProgress = findViewById(R.id.rotateProgress)

        loadImage()

        // Zoomable image's own single-tap also closes the viewer, matching
        // the previous tap-anywhere-to-dismiss behavior when not zoomed in.
        ivFullPhoto.setOnClickListener { finishWithResult() }

        findViewById<ImageButton>(R.id.btnRotateLeft).setOnClickListener { rotate(-90f) }
        findViewById<ImageButton>(R.id.btnRotateRight).setOnClickListener { rotate(90f) }

        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    private fun loadImage() {
        if (imageUriString.isEmpty()) return
        try {
            ivFullPhoto.setImageURI(Uri.parse(imageUriString))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rotate(degrees: Float) {
        if (imageUriString.isEmpty()) return
        rotateProgress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ImageRotation.rotateInPlace(this@PhotoViewerActivity, imageUriString, degrees)
            }
            rotateProgress.visibility = android.view.View.GONE
            if (success) {
                wasRotated = true
                loadImage()
            } else {
                Toast.makeText(
                    this@PhotoViewerActivity,
                    "Could not rotate the photo.",
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
