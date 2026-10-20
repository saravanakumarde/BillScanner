package com.billscanner.app.ui.cards

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.CustomerCardPhoto
import com.billscanner.app.util.ImageRotation
import com.billscanner.app.util.PrivatePhotoStorage
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows one customer card's photos as swipeable full-screen pages, with rotate/delete per photo and add-another-photo/delete-card actions. */
class CustomerCardDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CARD_ID = "cardId"
        fun start(context: Context, cardId: Long) {
            val intent = Intent(context, CustomerCardDetailActivity::class.java)
            intent.putExtra(EXTRA_CARD_ID, cardId)
            context.startActivity(intent)
        }
    }

    private val app get() = application as BillScannerApp
    private var cardId: Long = -1
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: CardPhotoPagerAdapter
    private lateinit var tvPageIndicator: TextView
    private var currentPhotos: List<CustomerCardPhoto> = emptyList()

    private val addPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getStringExtra(CardPhotoCaptureActivity.EXTRA_RESULT_URI)
        if (result.resultCode == RESULT_OK && uri != null) {
            lifecycleScope.launch {
                app.database.customerCardDao().appendPhoto(cardId, uri)
                reloadPhotos(scrollToEnd = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_card_detail)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1)
        if (cardId == -1L) { finish(); return }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        pager = findViewById(R.id.cardPhotoPager)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        pagerAdapter = CardPhotoPagerAdapter(
            onRotate = { photo, degrees, onDone -> rotatePhoto(photo, degrees, onDone) },
            onDelete = { photo -> confirmDeletePhoto(photo) }
        )
        pager.adapter = pagerAdapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageIndicator(position)
        })

        findViewById<MaterialButton>(R.id.btnAddAnotherPhoto).setOnClickListener {
            showSourceChooser()
        }
        findViewById<MaterialButton>(R.id.btnDeleteCard).setOnClickListener {
            confirmDeleteCard()
        }

        loadCard()
    }

    private fun loadCard() {
        lifecycleScope.launch {
            val dao = app.database.customerCardDao()
            val card = dao.getCardById(cardId)
            if (card == null) { finish(); return@launch }
            supportActionBar?.title = card.storeName
            val tvNotes = findViewById<TextView>(R.id.tvCardNotes)
            if (card.notes.isNotBlank()) {
                tvNotes.text = card.notes
                tvNotes.visibility = android.view.View.VISIBLE
            }
            reloadPhotos(scrollToEnd = false)
        }
    }

    private fun reloadPhotos(scrollToEnd: Boolean) {
        lifecycleScope.launch {
            val photos = app.database.customerCardDao().getPhotosForCard(cardId)
            currentPhotos = photos
            pagerAdapter.submitList(photos)
            if (photos.isEmpty()) {
                // Last photo of the card was just deleted — nothing left to show.
                finish()
                return@launch
            }
            if (scrollToEnd) pager.setCurrentItem(photos.size - 1, false)
            updatePageIndicator(pager.currentItem)
        }
    }

    private fun updatePageIndicator(position: Int) {
        val total = currentPhotos.size
        tvPageIndicator.text = if (total <= 1) "" else getString(
            R.string.card_page_indicator_format, (position + 1).coerceIn(1, total), total
        )
    }

    private fun rotatePhoto(photo: CustomerCardPhoto, degrees: Float, onDone: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ImageRotation.rotateInPlace(this@CustomerCardDetailActivity, photo.imageUri, degrees)
            }
            if (!success) {
                Toast.makeText(this@CustomerCardDetailActivity, R.string.could_not_rotate_photo, Toast.LENGTH_SHORT).show()
            }
            onDone(success)
        }
    }

    private fun confirmDeletePhoto(photo: CustomerCardPhoto) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_photo)
            .setMessage(R.string.delete_photo_confirm)
            .setPositiveButton(R.string.delete_photo) { _, _ -> deletePhoto(photo) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePhoto(photo: CustomerCardPhoto) {
        lifecycleScope.launch {
            app.database.customerCardDao().deletePhoto(photo)
            withContext(Dispatchers.IO) { deletePhotoFile(photo.imageUri) }
            reloadPhotos(scrollToEnd = false)
        }
    }

    private fun confirmDeleteCard() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_card)
            .setMessage(R.string.delete_card_confirm)
            .setPositiveButton(R.string.delete_card) { _, _ -> deleteCard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteCard() {
        lifecycleScope.launch {
            val dao = app.database.customerCardDao()
            val card = dao.getCardById(cardId) ?: return@launch finish()
            val photos = dao.deleteCardWithPhotos(card)
            withContext(Dispatchers.IO) {
                photos.forEach { deletePhotoFile(it.imageUri) }
            }
            finish()
        }
    }

    private fun deletePhotoFile(imageUri: String) {
        if (imageUri.isEmpty()) return
        try {
            if (PrivatePhotoStorage.isPrivateUri(imageUri)) {
                android.net.Uri.parse(imageUri).path?.let { File(it).delete() }
            } else {
                contentResolver.delete(android.net.Uri.parse(imageUri), null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showSourceChooser() {
        val view = layoutInflater.inflate(R.layout.dialog_choose_source, null)
        view.findViewById<android.view.View>(R.id.optionManual).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.dividerBeforeManual).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.optionPdf).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.dividerBeforePdf).visibility = android.view.View.GONE

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_card_source_title)
            .setView(view)
            .create()

        view.findViewById<android.widget.LinearLayout>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            addPhotoLauncher.launch(CardPhotoCaptureActivity.buildIntent(this, CardPhotoCaptureActivity.SOURCE_CAMERA))
        }
        view.findViewById<android.widget.LinearLayout>(R.id.optionGallery).setOnClickListener {
            dialog.dismiss()
            addPhotoLauncher.launch(CardPhotoCaptureActivity.buildIntent(this, CardPhotoCaptureActivity.SOURCE_GALLERY))
        }
        dialog.show()
    }
}
