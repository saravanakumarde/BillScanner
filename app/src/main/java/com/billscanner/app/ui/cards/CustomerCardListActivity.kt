package com.billscanner.app.ui.cards

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.CustomerCard
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/**
 * Lists every saved customer/loyalty card. Tapping a card opens
 * [CustomerCardDetailActivity]. The "+" FAB starts a new card: camera or
 * gallery picks one photo (via [CardPhotoCaptureActivity]), the user names
 * the store, and the card is created with that first photo already attached.
 */
class CustomerCardListActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CustomerCardListActivity::class.java))
        }
    }

    private val app get() = application as BillScannerApp
    private lateinit var adapter: CustomerCardListAdapter
    private lateinit var tvEmpty: TextView

    // Holds the new card's store name between "name it" and "photo captured"
    // steps of the add-card flow, since CardPhotoCaptureActivity is a
    // separate Activity and the flow is: dialog -> name prompt -> capture ->
    // (here) create the card + its first photo once the capture returns.
    private var pendingNewCardStoreName: String? = null

    private val addPhotoForNewCardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val storeName = pendingNewCardStoreName
        pendingNewCardStoreName = null
        val uri = result.data?.getStringExtra(CardPhotoCaptureActivity.EXTRA_RESULT_URI)
        if (result.resultCode == RESULT_OK && uri != null && storeName != null) {
            createCardWithFirstPhoto(storeName, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_cards)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvEmpty)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CustomerCardListAdapter { card ->
            CustomerCardDetailActivity.start(this, card.id)
        }
        recyclerView.adapter = adapter

        app.database.customerCardDao().observeAllCards().observe(this) { cards ->
            adapter.submitList(cards)
            tvEmpty.visibility = if (cards.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        findViewById<FloatingActionButton>(R.id.fabAddCard).setOnClickListener {
            promptNewCardName()
        }
    }

    /** Asks for the store name first, THEN launches the camera/gallery — so the capture flow always ends with a complete card, never an unnamed one. */
    private fun promptNewCardName() {
        val input = android.widget.EditText(this)
        input.hint = getString(R.string.new_card_store_name_hint)
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_customer_card)
            .setView(input)
            .setPositiveButton(R.string.save_card) { _, _ ->
                val storeName = input.text.toString().trim()
                if (storeName.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.card_store_name_required, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingNewCardStoreName = storeName
                showSourceChooserForNewCard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSourceChooserForNewCard() {
        val view = layoutInflater.inflate(R.layout.dialog_choose_source, null)
        // This dialog is shared with the bill-scan flow, which also offers
        // a "manual entry, no photo" option; a card always has at least one
        // photo (it IS a photo of the card), so that option is hidden here.
        view.findViewById<android.view.View>(R.id.optionManual).visibility = android.view.View.GONE
        view.findViewById<android.view.View>(R.id.dividerBeforeManual).visibility = android.view.View.GONE

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_card_source_title)
            .setView(view)
            .create()

        view.findViewById<android.widget.LinearLayout>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            addPhotoForNewCardLauncher.launch(
                CardPhotoCaptureActivity.buildIntent(this, CardPhotoCaptureActivity.SOURCE_CAMERA)
            )
        }
        view.findViewById<android.widget.LinearLayout>(R.id.optionGallery).setOnClickListener {
            dialog.dismiss()
            addPhotoForNewCardLauncher.launch(
                CardPhotoCaptureActivity.buildIntent(this, CardPhotoCaptureActivity.SOURCE_GALLERY)
            )
        }

        dialog.show()
    }

    private fun createCardWithFirstPhoto(storeName: String, imageUri: String) {
        lifecycleScope.launch {
            val dao = app.database.customerCardDao()
            val cardId = dao.insertCard(CustomerCard(storeName = storeName))
            dao.appendPhoto(cardId, imageUri)
        }
    }
}
