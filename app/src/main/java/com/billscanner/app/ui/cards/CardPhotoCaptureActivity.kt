package com.billscanner.app.ui.cards

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.R
import com.billscanner.app.ui.widget.ZoomableImageView
import com.billscanner.app.util.GalleryStorage
import com.billscanner.app.util.ImageRotation
import com.billscanner.app.util.PrivatePhotoStorage
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures or picks ONE photo for a customer card — front, back, or an
 * additional card. Deliberately much simpler than [com.billscanner.app.ui.scan.ScanActivity]:
 * no OCR, no "find the most recent Gallery photo" workaround, since cards
 * don't need the Xiaomi Camera app's Document auto-crop mode that workaround
 * exists for. A plain [ActivityResultContracts.TakePicture] writes straight
 * into a Uri this activity already owns, so there's nothing to search for
 * afterward.
 *
 * On success, returns [RESULT_OK] with the new photo's private-storage Uri
 * string in the result Intent (see [EXTRA_RESULT_URI]); the caller inserts
 * it as a [com.billscanner.app.data.db.CustomerCardPhoto] itself, since this
 * activity has no opinion on which card or what sort order it belongs to.
 */
class CardPhotoCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_GALLERY = "gallery"
        const val EXTRA_RESULT_URI = "resultUri"

        /** Builds the launch Intent; callers that need the result (e.g. the new photo's Uri) launch this via an ActivityResultLauncher instead of calling [start]. */
        fun buildIntent(context: Context, source: String): Intent =
            Intent(context, CardPhotoCaptureActivity::class.java).putExtra(EXTRA_SOURCE, source)

        /** Fire-and-forget launch for callers that don't need a result back. */
        fun start(context: Context, source: String) {
            context.startActivity(buildIntent(context, source))
        }
    }

    private lateinit var ivPreview: ZoomableImageView
    private lateinit var confirmOverlay: android.view.View
    private lateinit var loadingOverlay: android.view.View
    private var source: String = SOURCE_CAMERA
    private var candidateUri: Uri? = null
    private var pendingCaptureUri: Uri? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else {
            Toast.makeText(this, R.string.card_camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            GalleryStorage.finalizePending(this, uri)
            showCandidate(uri)
        } else {
            if (uri != null) GalleryStorage.deletePending(this, uri)
            finish()
        }
    }

    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // The picked Uri may be read-only (e.g. the system Photo
            // Picker on Android 13+ hands back a reference we don't own),
            // so rotating it directly before the user confirms could fail.
            // Copy into our own Gallery folder first — the same working
            // copy ScanActivity uses — so rotate-before-save is reliable;
            // this gets superseded by the private-storage copy on save anyway.
            loadingOverlay.visibility = android.view.View.VISIBLE
            lifecycleScope.launch {
                val ownedUri = withContext(Dispatchers.IO) {
                    GalleryStorage.copyIntoAppFolder(this@CardPhotoCaptureActivity, uri)
                } ?: uri
                loadingOverlay.visibility = android.view.View.GONE
                showCandidate(ownedUri)
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_photo_capture)

        ivPreview = findViewById(R.id.ivCardPhotoPreview)
        confirmOverlay = findViewById(R.id.cardConfirmOverlay)
        loadingOverlay = findViewById(R.id.cardLoadingOverlay)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_CAMERA

        findViewById<MaterialButton>(R.id.btnCardRetake).setOnClickListener {
            confirmOverlay.visibility = android.view.View.GONE
            candidateUri = null
            launchSource()
        }
        findViewById<MaterialButton>(R.id.btnCardUsePhoto).setOnClickListener {
            candidateUri?.let { savePhoto(it) }
        }
        findViewById<ImageButton>(R.id.btnCardRotateLeft).setOnClickListener { rotateCandidate(-90f) }
        findViewById<ImageButton>(R.id.btnCardRotateRight).setOnClickListener { rotateCandidate(90f) }

        launchSource()
    }

    private fun launchSource() {
        if (source == SOURCE_GALLERY) {
            galleryPickerLauncher.launch("image/*")
            return
        }
        if (hasCameraPermission()) {
            launchCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun launchCamera() {
        val uri = GalleryStorage.createPendingImageUri(this)
        if (uri == null) {
            Toast.makeText(this, R.string.card_photo_slot_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pendingCaptureUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun showCandidate(uri: Uri) {
        candidateUri = uri
        try {
            ivPreview.setImageURI(uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        confirmOverlay.visibility = android.view.View.VISIBLE
    }

    private fun rotateCandidate(degrees: Float) {
        val uri = candidateUri ?: return
        val progress = findViewById<ProgressBar>(R.id.cardRotateProgress)
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ImageRotation.rotateInPlace(this@CardPhotoCaptureActivity, uri.toString(), degrees)
            }
            progress.visibility = android.view.View.GONE
            if (success) {
                try {
                    // Same Uri as before rotating — force a real reload
                    // rather than risk ImageView treating it as a no-op.
                    ivPreview.setImageURI(null)
                    ivPreview.setImageURI(uri)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this@CardPhotoCaptureActivity, R.string.could_not_rotate_photo, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun savePhoto(sourceUri: Uri) {
        confirmOverlay.visibility = android.view.View.GONE
        loadingOverlay.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            val privateUri = withContext(Dispatchers.IO) {
                PrivatePhotoStorage.copyIntoPrivateStorage(this@CardPhotoCaptureActivity, sourceUri)
            }
            if (privateUri == null) {
                Toast.makeText(this@CardPhotoCaptureActivity, R.string.could_not_read_photo, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            // The capture/pick source (a MediaStore row we created for
            // camera capture, or whatever the gallery picker returned) is
            // no longer needed once our own private copy exists — same
            // "copy then clean up the original" approach ScanActivity uses
            // for bills, kept best-effort here too.
            if (source == SOURCE_CAMERA) {
                try { contentResolver.delete(sourceUri, null, null) } catch (e: Exception) { /* best effort */ }
            }

            val result = Intent()
            result.putExtra(EXTRA_RESULT_URI, privateUri.toString())
            setResult(RESULT_OK, result)
            finish()
        }
    }
}
