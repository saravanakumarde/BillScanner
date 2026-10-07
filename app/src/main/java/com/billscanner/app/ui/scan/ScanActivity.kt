package com.billscanner.app.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.R
import com.billscanner.app.ocr.ImagePreprocessor
import com.billscanner.app.ocr.ReceiptOcr
import com.billscanner.app.parser.ReceiptParser
import com.billscanner.app.ui.review.ReviewActivity
import com.billscanner.app.util.AppPreferences
import com.billscanner.app.util.GalleryStorage
import com.billscanner.app.util.ScanDetectionMode
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Launches the Xiaomi/HyperOS Camera app DIRECTLY (by package name), rather
 * than through the generic ACTION_IMAGE_CAPTURE intent, so special modes
 * like "Documents" (auto-crop + perspective correction) are available.
 *
 * Because a directly-launched camera app doesn't reliably return a result
 * or honor EXTRA_OUTPUT, we instead look up the most recently added photo
 * in the device's Gallery afterwards, move it into our own
 * Pictures/BillScanner folder, and ask the user to confirm it before OCR.
 *
 * Two ways to trigger that "look for the new photo" step, switchable in
 * Settings, since which one is reliable can vary by phone/OEM:
 *  - AUTOMATIC: triggered from onResume() when we return to this screen
 *  - MANUAL: user explicitly taps "Find my photo"
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        private const val XIAOMI_CAMERA_PACKAGE = "com.android.camera"
        private const val EXTRA_SOURCE = "source"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_GALLERY = "gallery"

        fun start(context: android.content.Context, source: String) {
            val intent = Intent(context, ScanActivity::class.java)
            intent.putExtra(EXTRA_SOURCE, source)
            context.startActivity(intent)
        }
    }

    private lateinit var launchingOverlay: LinearLayout
    private lateinit var waitingOverlay: LinearLayout
    private lateinit var permissionOverlay: LinearLayout
    private lateinit var processingOverlay: LinearLayout
    private lateinit var confirmOverlay: LinearLayout
    private lateinit var ivConfirmPreview: com.billscanner.app.ui.widget.ZoomableImageView

    private var scanStartedAtMillis: Long = 0L
    private var awaitingCameraReturn = false
    private var candidateUri: Uri? = null
    private var detectionMode: ScanDetectionMode = ScanDetectionMode.AUTOMATIC
    private var source: String = SOURCE_CAMERA

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestGalleryPermissionThenLaunch() else showPermissionOverlay()
    }

    private val requestGalleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Without permission to read your Gallery, Bill Scanner can't find or use your photos. You can grant it in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (source == SOURCE_GALLERY) launchGalleryPicker() else launchCamera()
    }

    private val cameraAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        awaitingCameraReturn = true
    }

    // Used only when the "direct capture" setting is on. TakePicture()
    // saves straight into the Uri we hand it (created up-front in
    // Pictures/BillScanner), so there's no find-and-move step afterward —
    // but it launches the plain system camera UI, not the Xiaomi Camera
    // app's Document auto-crop mode.
    private var pendingDirectCaptureUri: Uri? = null
    private val directCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingDirectCaptureUri
        pendingDirectCaptureUri = null
        if (success && uri != null) {
            GalleryStorage.finalizePending(this, uri)
            candidateUri = uri
            try {
                ivConfirmPreview.setImageURI(uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            launchingOverlay.visibility = android.view.View.GONE
            confirmOverlay.visibility = android.view.View.VISIBLE
        } else {
            if (uri != null) GalleryStorage.deletePending(this, uri)
            finish()
        }
    }

    // Fires after the system asks the user to confirm deleting the original
    // picked photo (see requestDeleteOriginal()). We don't act on the
    // result either way — the copy in our BillScanner album already exists
    // and is already on screen, so this is purely tidy-up.
    private val deleteOriginalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* no-op */ }

    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Copy the picked image into our own BillScanner album for
            // consistency with camera-captured bills...
            val organizedUri = GalleryStorage.copyIntoAppFolder(this, uri) ?: uri
            candidateUri = organizedUri
            // ...then remove the original so this ends up as a MOVE rather
            // than leaving a duplicate sitting in whichever album it came
            // from. Best-effort: some Android versions require the user to
            // confirm the deletion, and the system Photo Picker (Android
            // 13+) can hand us a read-only reference we're not allowed to
            // delete at all — in either case the copy we already made is
            // kept regardless.
            if (organizedUri != uri) {
                requestDeleteOriginal(uri)
            }
            try {
                ivConfirmPreview.setImageURI(organizedUri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            launchingOverlay.visibility = android.view.View.GONE
            confirmOverlay.visibility = android.view.View.VISIBLE
        } else {
            finish()
        }
    }

    private fun requestDeleteOriginal(sourceUri: Uri) {
        try {
            val confirmationRequest =
                GalleryStorage.deleteOriginalOrGetConfirmationRequest(this, sourceUri)
            if (confirmationRequest != null) {
                deleteOriginalLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(confirmationRequest).build()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        launchingOverlay = findViewById(R.id.launchingOverlay)
        waitingOverlay = findViewById(R.id.waitingOverlay)
        permissionOverlay = findViewById(R.id.permissionOverlay)
        processingOverlay = findViewById(R.id.processingOverlay)
        confirmOverlay = findViewById(R.id.confirmOverlay)
        ivConfirmPreview = findViewById(R.id.ivConfirmPreview)

        detectionMode = AppPreferences.getScanDetectionMode(this)
        source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_CAMERA

        findViewById<MaterialButton>(R.id.btnGrantPermission).setOnClickListener {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        findViewById<MaterialButton>(R.id.btnRetake).setOnClickListener {
            confirmOverlay.visibility = android.view.View.GONE
            candidateUri = null
            if (source == SOURCE_GALLERY) launchGalleryPicker() else launchCamera()
        }
        findViewById<MaterialButton>(R.id.btnUsePhoto).setOnClickListener {
            candidateUri?.let { processImage(it) }
        }
        findViewById<MaterialButton>(R.id.btnFindPhoto).setOnClickListener {
            lookForNewPhoto()
        }
        findViewById<android.widget.ImageButton>(R.id.btnConfirmRotateLeft).setOnClickListener {
            rotateCandidate(-90f)
        }
        findViewById<android.widget.ImageButton>(R.id.btnConfirmRotateRight).setOnClickListener {
            rotateCandidate(90f)
        }

        startPermissionChain()
    }

    /** Requests whichever permissions the chosen source needs, then proceeds. */
    private fun startPermissionChain() {
        if (source == SOURCE_GALLERY) {
            // GetContent() (the modern Photo Picker on Android 13+) doesn't
            // require a runtime permission on newer Android, but request the
            // gallery-read permission anyway for older versions where it does.
            if (!com.billscanner.app.util.PermissionHelper.hasGalleryReadPermission(this) &&
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                requestGalleryPermissionLauncher.launch(com.billscanner.app.util.PermissionHelper.galleryReadPermission())
                return
            }
            launchGalleryPicker()
            return
        }

        if (!hasCameraPermission()) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (!com.billscanner.app.util.PermissionHelper.hasGalleryReadPermission(this)) {
            requestGalleryPermissionThenLaunch()
            return
        }
        launchCamera()
    }

    private fun launchGalleryPicker() {
        launchingOverlay.visibility = android.view.View.VISIBLE
        galleryPickerLauncher.launch("image/*")
    }

    private fun requestGalleryPermissionThenLaunch() {
        requestGalleryPermissionLauncher.launch(com.billscanner.app.util.PermissionHelper.galleryReadPermission())
    }

    override fun onResume() {
        super.onResume()
        if (awaitingCameraReturn && detectionMode == ScanDetectionMode.AUTOMATIC) {
            awaitingCameraReturn = false
            lookForNewPhoto()
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionOverlay() {
        permissionOverlay.visibility = android.view.View.VISIBLE
        launchingOverlay.visibility = android.view.View.GONE
        waitingOverlay.visibility = android.view.View.GONE
    }

    private fun launchCamera() {
        permissionOverlay.visibility = android.view.View.GONE
        waitingOverlay.visibility = android.view.View.GONE
        launchingOverlay.visibility = android.view.View.VISIBLE
        scanStartedAtMillis = System.currentTimeMillis()

        if (AppPreferences.getDirectCaptureMode(this)) {
            val uri = GalleryStorage.createPendingImageUri(this)
            if (uri == null) {
                Toast.makeText(this, "Could not prepare a photo slot in your Gallery.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            pendingDirectCaptureUri = uri
            directCaptureLauncher.launch(uri)
            return
        }

        val directIntent = packageManager.getLaunchIntentForPackage(XIAOMI_CAMERA_PACKAGE)

        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            cameraAppLauncher.launch(directIntent)
            afterLaunch()
            return
        }

        val fallbackIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (fallbackIntent.resolveActivity(packageManager) != null) {
            cameraAppLauncher.launch(fallbackIntent)
            afterLaunch()
        } else {
            Toast.makeText(this, "No camera app found on this device.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun afterLaunch() {
        // In MANUAL mode, show the "waiting" screen with the Find button
        // right away (we don't know when/if onResume alone would be trusted).
        // In AUTOMATIC mode this overlay is skipped entirely - onResume()
        // does the detection transparently.
        if (detectionMode == ScanDetectionMode.MANUAL) {
            launchingOverlay.visibility = android.view.View.GONE
            waitingOverlay.visibility = android.view.View.VISIBLE
        }
    }

    private fun lookForNewPhoto() {
        launchingOverlay.visibility = android.view.View.GONE
        waitingOverlay.visibility = android.view.View.GONE

        if (!com.billscanner.app.util.PermissionHelper.hasGalleryReadPermission(this)) {
            Toast.makeText(
                this,
                "Bill Scanner needs permission to read your Gallery to find the photo. Please grant it and try again.",
                Toast.LENGTH_LONG
            ).show()
            requestGalleryPermissionThenLaunch()
            return
        }

        val found = GalleryStorage.findMostRecentPhoto(this, scanStartedAtMillis)
        if (found == null) {
            Toast.makeText(this, "No new photo detected yet. Take the photo, then try again.", Toast.LENGTH_LONG).show()
            if (detectionMode == ScanDetectionMode.MANUAL) {
                waitingOverlay.visibility = android.view.View.VISIBLE
            } else {
                finish()
            }
            return
        }

        // Move it into our own folder so all scans end up organized
        // together, regardless of where the camera app saved it.
        val organizedUri = GalleryStorage.moveIntoAppFolder(this, found)
        candidateUri = organizedUri

        try {
            ivConfirmPreview.setImageURI(organizedUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        confirmOverlay.visibility = android.view.View.VISIBLE
    }

    private fun rotateCandidate(degrees: Float) {
        val uri = candidateUri ?: return
        val progress = findViewById<android.widget.ProgressBar>(R.id.confirmRotateProgress)
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.billscanner.app.util.ImageRotation.rotateInPlace(this@ScanActivity, uri.toString(), degrees)
            }
            progress.visibility = android.view.View.GONE
            if (success) {
                try {
                    ivConfirmPreview.setImageURI(uri)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this@ScanActivity, "Could not rotate the photo.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processImage(sourceUri: Uri) {
        confirmOverlay.visibility = android.view.View.GONE
        processingOverlay.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            // Copy into the app's own private storage first, so this bill's
            // photo no longer depends on the shared Gallery album at all —
            // deleting it from Gallery afterward can't break the bill.
            val privateUri = com.billscanner.app.util.PrivatePhotoStorage
                .copyIntoPrivateStorage(this@ScanActivity, sourceUri)
            val imageUri = privateUri ?: sourceUri
            if (privateUri != null) {
                // We have our own copy now; remove the shared-album one so
                // it doesn't sit there as an orphaned duplicate. Same
                // best-effort pattern as the gallery-pick "move" — some
                // Android versions ask the user to confirm.
                requestDeleteOriginal(sourceUri)
            }

            val bitmap: Bitmap? = try {
                contentResolver.openInputStream(imageUri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }

            if (bitmap == null) {
                Toast.makeText(this@ScanActivity, "Could not read the captured photo.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val ocr = ReceiptOcr()
            val processedForOcr = ImagePreprocessor.forOcr(bitmap)
            val ocrResult = ocr.recognize(processedForOcr)
            val parsed = ReceiptParser().parse(ocrResult.rawText)

            ReviewActivity.start(
                context = this@ScanActivity,
                imageUri = imageUri.toString(),
                rawOcrText = ocrResult.rawText,
                parsed = parsed
            )
            finish()
        }
    }
}
