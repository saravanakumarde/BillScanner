package com.billscanner.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.ActivityLogEntry
import com.billscanner.app.data.db.LogEventType
import com.billscanner.app.parser.ParsedReceipt
import com.billscanner.app.ui.list.BillListActivity
import com.billscanner.app.ui.list.ListFilter
import com.billscanner.app.ui.review.ReviewActivity
import com.billscanner.app.ui.scan.ScanActivity
import com.billscanner.app.ui.stats.CategoryStatsActivity
import com.billscanner.app.ui.stats.StoreStatsActivity
import com.billscanner.app.util.AppPreferences
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.PrivatePhotoStorage
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp

    // Fires after the one-time batch "delete these old Gallery photos?"
    // prompt from migratePhotosIfNeeded(). No action needed either way —
    // each bill's own private copy is already in place regardless.
    private val batchDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Toolbar>(R.id.toolbar).let { setSupportActionBar(it) }

        findViewById<FloatingActionButton>(R.id.fabScan).setOnClickListener {
            showSourceChooserDialog()
        }

        findViewById<MaterialCardView>(R.id.cardAllBills).setOnClickListener {
            BillListActivity.start(this, ListFilter.All)
        }
        findViewById<MaterialCardView>(R.id.cardThisWeek).setOnClickListener {
            BillListActivity.start(this, ListFilter.ThisWeek)
        }
        findViewById<MaterialCardView>(R.id.cardThisMonth).setOnClickListener {
            BillListActivity.start(this, ListFilter.ThisMonth)
        }
        findViewById<MaterialCardView>(R.id.cardByCategory).setOnClickListener {
            startActivity(Intent(this, CategoryStatsActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardByStore).setOnClickListener {
            startActivity(Intent(this, StoreStatsActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardItemPrices).setOnClickListener {
            startActivity(Intent(this, com.billscanner.app.ui.items.ItemPriceListActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, com.billscanner.app.ui.settings.SettingsActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.cardActivityLog).setOnClickListener {
            startActivity(Intent(this, com.billscanner.app.ui.log.ActivityLogActivity::class.java))
        }

        app.database.billDao().observeGrandTotal().observe(this) { total ->
            findViewById<TextView>(R.id.tvGrandTotal).text =
                CurrencyFormat.format(total ?: 0.0, "EUR")
        }

        migratePhotosIfNeeded()
    }

    /**
     * One-time move of every existing bill's photo out of the shared
     * Gallery BillScanner album and into the app's own private storage,
     * then removes the old Gallery copies so nothing is left duplicated.
     * Runs once (tracked via AppPreferences) and does nothing on repeat
     * launches, or if every bill is already on private storage.
     */
    private fun migratePhotosIfNeeded() {
        if (AppPreferences.arePhotosMigrated(this)) return

        lifecycleScope.launch {
            val dao = app.database.billDao()
            val legacyBills = dao.getAllBillsSuspend().filter {
                it.imageUri.isNotEmpty() && !PrivatePhotoStorage.isPrivateUri(it.imageUri)
            }

            if (legacyBills.isEmpty()) {
                AppPreferences.setPhotosMigrated(this@MainActivity, true)
                return@launch
            }

            Toast.makeText(
                this@MainActivity,
                "Moving ${legacyBills.size} bill photo(s) into the app...",
                Toast.LENGTH_SHORT
            ).show()

            val oldUrisToDelete = mutableListOf<Uri>()
            var migratedCount = 0

            for (bill in legacyBills) {
                val sourceUri = try { Uri.parse(bill.imageUri) } catch (e: Exception) { null } ?: continue
                val privateUri = PrivatePhotoStorage.copyIntoPrivateStorage(this@MainActivity, sourceUri)
                if (privateUri != null) {
                    dao.updateBill(bill.copy(imageUri = privateUri.toString()))
                    oldUrisToDelete.add(sourceUri)
                    migratedCount++
                }
            }

            if (migratedCount > 0) {
                app.database.activityLogDao().insert(
                    ActivityLogEntry(
                        eventType = LogEventType.PHOTOS_MIGRATED,
                        summary = "Migrated $migratedCount bill photo(s) into app storage",
                        detail = "Moved from the Gallery BillScanner album into the app's private " +
                            "storage, and removed from the Gallery, so deleting the Gallery album " +
                            "no longer affects saved bills."
                    )
                )
            }

            if (oldUrisToDelete.isNotEmpty()) {
                requestBatchDeleteOldPhotos(oldUrisToDelete)
            }

            AppPreferences.setPhotosMigrated(this@MainActivity, true)
        }
    }

    private fun requestBatchDeleteOldPhotos(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // One single system confirmation for all of them at once,
            // rather than prompting per-photo.
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                batchDeleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // No batch API before Android 11; best-effort per file, silently
            // skipping any that need a confirmation we can't easily chain here.
            uris.forEach { uri ->
                try { contentResolver.delete(uri, null, null) } catch (e: Exception) { /* best effort */ }
            }
        }
    }

    private fun showSourceChooserDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_choose_source, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_source_title)
            .setView(view)
            .create()

        view.findViewById<android.widget.LinearLayout>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            ScanActivity.start(this, ScanActivity.SOURCE_CAMERA)
        }
        view.findViewById<android.widget.LinearLayout>(R.id.optionGallery).setOnClickListener {
            dialog.dismiss()
            ScanActivity.start(this, ScanActivity.SOURCE_GALLERY)
        }
        view.findViewById<android.widget.LinearLayout>(R.id.optionManual).setOnClickListener {
            dialog.dismiss()
            startManualEntry()
        }

        dialog.show()
    }

    /**
     * Skips the camera/OCR flow entirely and opens ReviewActivity with a
     * blank receipt and no photo, for when the user has no bill photo to
     * scan (e.g. a lost paper receipt) but still wants to log the bill.
     */
    private fun startManualEntry() {
        ReviewActivity.start(
            context = this,
            imageUri = "",
            rawOcrText = "",
            parsed = ParsedReceipt(
                storeName = null,
                dateMillis = null,
                dateWasFound = false,
                total = null,
                currency = "EUR",
                language = "unknown",
                items = emptyList()
            )
        )
    }
}
