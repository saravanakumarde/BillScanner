package com.billscanner.app.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.ActivityLogEntry
import com.billscanner.app.data.db.Category
import com.billscanner.app.data.db.LogEventType
import com.billscanner.app.ui.review.ReviewActivity
import com.billscanner.app.util.AppPreferences
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.DateFormat
import com.billscanner.app.util.GalleryStorage
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class BillDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BILL_ID = "billId"

        fun start(context: Context, billId: Long) {
            val intent = Intent(context, BillDetailActivity::class.java)
            intent.putExtra(EXTRA_BILL_ID, billId)
            context.startActivity(intent)
        }
    }

    private val app get() = application as BillScannerApp
    private var billId: Long = -1L
    private var currentImageUri: String = ""

    // Result of the system's "allow Bill Scanner to delete this photo?"
    // confirmation, shown when the delete-photo setting is on. Either way
    // the bill row itself is already gone by the time this fires.
    private val deletePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* no-op: best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_detail)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        billId = intent.getLongExtra(EXTRA_BILL_ID, -1L)
        if (billId == -1L) {
            finish()
            return
        }

        loadBill()

        findViewById<ImageView>(R.id.ivPhoto).setOnClickListener {
            PhotoViewerActivity.start(this, currentImageUri)
        }

        findViewById<MaterialButton>(R.id.btnEdit).setOnClickListener {
            ReviewActivity.startEdit(this, billId)
        }

        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            confirmDelete()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case the bill was just edited (category, items, total,
        // etc. may have changed while ReviewActivity was in edit mode).
        loadBill()
    }

    private fun loadBill() {
        lifecycleScope.launch {
            val dao = app.database.billDao()
            val bill = dao.getBillById(billId) ?: run { finish(); return@launch }
            val items = dao.getLineItemsForBill(billId)
            val category: Category? = bill.categoryId.let { app.database.categoryDao().getById(it) }

            currentImageUri = bill.imageUri

            findViewById<Toolbar>(R.id.toolbar).title = bill.storeNameEnglish

            val ivPhoto = findViewById<ImageView>(R.id.ivPhoto)
            if (bill.imageUri.isNotEmpty()) {
                ivPhoto.visibility = android.view.View.VISIBLE
                try {
                    ivPhoto.setImageURI(Uri.parse(bill.imageUri))
                } catch (e: Exception) { /* ignore */ }
            } else {
                // Manually-entered bill with no photo — hide the image
                // slot entirely rather than showing an empty/blank tile.
                ivPhoto.visibility = android.view.View.GONE
            }

            findViewById<TextView>(R.id.tvStoreEnglish).text = bill.storeNameEnglish
            findViewById<TextView>(R.id.tvStoreOriginal).text =
                if (bill.storeNameOriginal != bill.storeNameEnglish) {
                    "${getString(R.string.original_text)}: ${bill.storeNameOriginal}"
                } else ""

            var dateText = DateFormat.display(bill.billDateMillis)
            if (bill.dateWasGuessed) dateText += "  (estimated)"
            findViewById<TextView>(R.id.tvDate).text = dateText

            val chip = findViewById<TextView>(R.id.tvCategoryChip)
            chip.text = category?.name ?: "Other"
            val bg = (chip.background ?: androidx.core.content.ContextCompat.getDrawable(
                this@BillDetailActivity, R.drawable.bg_category_chip
            ))?.mutate()
            if (bg != null) {
                try {
                    DrawableCompat.setTint(bg, android.graphics.Color.parseColor(category?.colorHex ?: "#607D8B"))
                } catch (e: Exception) {
                    DrawableCompat.setTint(bg, android.graphics.Color.parseColor("#607D8B"))
                }
                chip.background = bg
            }

            findViewById<TextView>(R.id.tvTotal).text = CurrencyFormat.format(bill.totalAmount, bill.currency)

            val notesContainer = findViewById<LinearLayout>(R.id.notesContainer)
            if (bill.notes.isNotBlank()) {
                notesContainer.visibility = android.view.View.VISIBLE
                findViewById<TextView>(R.id.tvNotes).text = bill.notes
            } else {
                notesContainer.visibility = android.view.View.GONE
            }

            val container = findViewById<LinearLayout>(R.id.lineItemsContainer)
            container.removeAllViews()
            if (items.isEmpty()) {
                val tv = TextView(this@BillDetailActivity)
                tv.text = "No individual items recorded"
                tv.setTextColor(getColor(R.color.text_secondary))
                container.addView(tv)
            }
            items.forEach { item ->
                val row = layoutInflater.inflate(R.layout.item_line_item_view, container, false)
                row.findViewById<TextView>(R.id.tvName).text = item.nameEnglish
                row.findViewById<TextView>(R.id.tvOriginal).text =
                    if (item.nameOriginal != item.nameEnglish) item.nameOriginal else ""
                row.findViewById<TextView>(R.id.tvPrice).text =
                    CurrencyFormat.format(item.totalPrice, bill.currency)
                container.addView(row)
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete_bill)
            .setPositiveButton(R.string.delete) { _, _ -> deleteBill() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteBill() {
        lifecycleScope.launch {
            val dao = app.database.billDao()
            val bill = dao.getBillById(billId) ?: return@launch
            val shouldDeletePhoto = AppPreferences.getDeletePhotoOnBillDelete(this@BillDetailActivity)
            dao.deleteBill(bill)

            app.database.activityLogDao().insert(
                ActivityLogEntry(
                    eventType = LogEventType.BILL_DELETED,
                    summary = "Deleted bill: ${bill.storeNameEnglish}",
                    detail = "Date: ${DateFormat.display(bill.billDateMillis)}, " +
                        "Total: ${CurrencyFormat.format(bill.totalAmount, bill.currency)}" +
                        if (shouldDeletePhoto) ", photo also deleted" else ""
                )
            )

            if (shouldDeletePhoto && bill.imageUri.isNotEmpty()) {
                deletePhotoFromAlbum(bill.imageUri)
            }

            finish()
        }
    }

    /**
     * Best-effort deletion of the bill's photo, only called when the "also
     * delete photo" setting is on. Bills saved after the private-storage
     * change just need a direct file delete — no permissions dance. Any
     * older bill whose photo hasn't been migrated yet (imageUri still a
     * content:// MediaStore URI) falls back to the original confirm-to-
     * delete flow.
     */
    private fun deletePhotoFromAlbum(imageUriString: String) {
        if (com.billscanner.app.util.PrivatePhotoStorage.isPrivateUri(imageUriString)) {
            com.billscanner.app.util.PrivatePhotoStorage.deletePrivatePhoto(imageUriString)
            return
        }
        try {
            val uri = Uri.parse(imageUriString)
            val confirmationRequest = GalleryStorage.deleteOriginalOrGetConfirmationRequest(this, uri)
            if (confirmationRequest != null) {
                deletePhotoLauncher.launch(IntentSenderRequest.Builder(confirmationRequest).build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
