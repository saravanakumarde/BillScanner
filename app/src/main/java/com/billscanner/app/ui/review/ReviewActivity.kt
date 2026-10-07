package com.billscanner.app.ui.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.ActivityLogEntry
import com.billscanner.app.data.db.Bill
import com.billscanner.app.data.db.Category
import com.billscanner.app.data.db.LineItem
import com.billscanner.app.data.db.LogEventType
import com.billscanner.app.data.db.StoreMemory
import com.billscanner.app.data.dict.OfflineDictionaryTranslator
import com.billscanner.app.parser.CategoryMatcher
import com.billscanner.app.parser.ParsedItem
import com.billscanner.app.parser.ParsedReceipt
import com.billscanner.app.parser.template.ReceiptTemplateStore
import com.billscanner.app.parser.template.TemplateReceiptMatcher
import com.billscanner.app.ui.detail.PhotoViewerActivity
import com.billscanner.app.ui.MainActivity
import com.billscanner.app.util.StoreMemoryEngine
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Holds the last parsed receipt + raw OCR text in memory so it can cross the
 * Activity boundary without needing every field to be Parcelable/Serializable
 * (ParsedReceipt/ParsedItem are plain data classes used elsewhere too).
 * Cleared once consumed by ReviewActivity.onCreate.
 */
private object PendingReceiptHolder {
    var parsed: ParsedReceipt? = null
    var rawOcrText: String = ""
}

class ReviewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IMAGE_URI = "imageUri"
        private const val EXTRA_BILL_ID = "billId"

        /** New-scan flow: review a freshly OCR'd receipt before first save. */
        fun start(context: Context, imageUri: String, rawOcrText: String, parsed: ParsedReceipt) {
            PendingReceiptHolder.parsed = parsed
            PendingReceiptHolder.rawOcrText = rawOcrText
            val intent = Intent(context, ReviewActivity::class.java)
            intent.putExtra(EXTRA_IMAGE_URI, imageUri)
            context.startActivity(intent)
        }

        /**
         * Edit flow: re-opens this same screen pre-filled with an already
         * saved bill's data (store, date, category, total, items), so the
         * user can change the category, add/remove items, fix the total,
         * etc. after the fact — not just at the moment of scanning.
         */
        fun startEdit(context: Context, billId: Long) {
            val intent = Intent(context, ReviewActivity::class.java)
            intent.putExtra(EXTRA_BILL_ID, billId)
            context.startActivity(intent)
        }
    }

    private val app get() = application as BillScannerApp
    private val translator = OfflineDictionaryTranslator()

    private lateinit var etStoreEnglish: AutoCompleteTextView
    private lateinit var tvStoreOriginal: TextView
    private lateinit var templateMatchBanner: LinearLayout
    private lateinit var tvTemplateMatchTitle: TextView
    private lateinit var tvCompareToggle: TextView
    private lateinit var tvGenericComparison: TextView
    private lateinit var etDate: EditText
    private lateinit var spinnerCategory: AutoCompleteTextView
    private lateinit var etTotal: EditText
    private lateinit var etNotes: EditText
    private lateinit var lineItemsContainer: LinearLayout
    private lateinit var ivThumbnail: ImageView
    private lateinit var tvNoPhoto: TextView

    private var editingBillId: Long = -1L
    private var isEditMode: Boolean = false

    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var categories: List<Category> = emptyList()
    private var selectedCategoryId: Long = -1L
    private var imageUriString: String = ""
    private var detectedLanguage: String = "unknown"
    private var storeOriginalText: String = ""
    private var currency: String = "EUR"
    private var dateWasGuessed: Boolean = true
    private var createdAtMillis: Long = System.currentTimeMillis()
    private var rawOcrText: String = ""

    // Baseline of what was shown to the user automatically (parser guess,
    // or a learned store-memory override if one matched) BEFORE any manual
    // edits. Compared against the final saved values so we only "learn"
    // when the user actually corrected something, not on every save.
    private var initialAutoStoreName: String = ""
    private var initialAutoCategoryId: Long = -1L
    private var initialAutoDateMillis: Long = -1L
    private var initialAutoTotal: Double? = null
    private var matchedStoreMemoryId: Long? = null
    private val DATE_LOG_FMT = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    // Set when a known-store template (see parser.template package) matched
    // this receipt. When non-null, the fields on screen were auto-filled
    // from the template's extraction rather than the generic parser, and
    // genericComparisonReceipt holds what the generic parser found on its
    // own — shown small, for comparison, per the user's request to be able
    // to check the new template mechanism against the existing one.
    private var matchedTemplateId: String? = null
    private var genericComparisonReceipt: ParsedReceipt? = null

    // Each row keeps references to its edit fields for save-time collection.
    private data class ItemRow(
        val original: String,
        var englishView: EditText,
        var priceView: EditText
    )
    private val itemRows = mutableListOf<ItemRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etStoreEnglish = findViewById(R.id.etStoreEnglish)
        tvStoreOriginal = findViewById(R.id.tvStoreOriginal)
        templateMatchBanner = findViewById(R.id.templateMatchBanner)
        tvTemplateMatchTitle = findViewById(R.id.tvTemplateMatchTitle)
        tvCompareToggle = findViewById(R.id.tvCompareToggle)
        tvGenericComparison = findViewById(R.id.tvGenericComparison)
        tvCompareToggle.setOnClickListener {
            val showing = tvGenericComparison.visibility == android.view.View.VISIBLE
            tvGenericComparison.visibility = if (showing) android.view.View.GONE else android.view.View.VISIBLE
            tvCompareToggle.setText(
                if (showing) R.string.compare_with_generic_parser else R.string.compare_with_generic_parser_hide
            )
        }
        etDate = findViewById(R.id.etDate)
        spinnerCategory = findViewById(R.id.spinnerCategory)
        etTotal = findViewById(R.id.etTotal)
        etNotes = findViewById(R.id.etNotes)
        lineItemsContainer = findViewById(R.id.lineItemsContainer)
        ivThumbnail = findViewById(R.id.ivThumbnail)
        tvNoPhoto = findViewById(R.id.tvNoPhoto)

        editingBillId = intent.getLongExtra(EXTRA_BILL_ID, -1L)
        isEditMode = editingBillId != -1L

        if (isEditMode) {
            toolbar.title = getString(R.string.edit_bill)
            findViewById<MaterialButton>(R.id.btnSave).text = getString(R.string.save_changes)
        }

        etDate.setOnClickListener { showDatePicker() }

        etStoreEnglish.setOnClickListener {
            if (etStoreEnglish.text.isNullOrEmpty()) etStoreEnglish.showDropDown()
        }

        findViewById<MaterialButton>(R.id.btnAddItem).setOnClickListener {
            addBlankItemRow()
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveBill() }

        // Tapping the thumbnail opens the full photo viewer, where the
        // user can pinch-zoom in on faint print and rotate the photo if
        // it's sideways/upside-down. onResume() picks up any rotation.
        ivThumbnail.setOnClickListener {
            PhotoViewerActivity.start(this, imageUriString)
        }

        if (isEditMode) {
            loadCategoriesThenPopulateForEdit()
        } else {
            imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI) ?: ""
            showThumbnail()
            // Runs first and fully synchronously (it's cheap: in-memory
            // regex matching plus reading one small bundled JSON asset) so
            // matchedTemplateId/genericComparisonReceipt are already set
            // before loadCategoriesThenPopulate's async logExtraction()
            // reads them — not relying on coroutine scheduling order.
            populateFromParsedReceipt()
            loadCategoriesThenPopulate()
        }

        populateStoreNameDropdown()
    }

    /**
     * Lets the user pick a previously-used store name from a dropdown
     * instead of always retyping it when OCR gets it wrong, while still
     * allowing free typing for a store that hasn't been seen before.
     */
    private fun populateStoreNameDropdown() {
        lifecycleScope.launch {
            val existingNames = try {
                app.database.billDao().getDistinctStoreNames()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            if (existingNames.isEmpty()) return@launch
            val adapter = ArrayAdapter(this@ReviewActivity, android.R.layout.simple_list_item_1, existingNames)
            etStoreEnglish.setAdapter(adapter)
            etStoreEnglish.threshold = 1
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a rotation made in the full photo viewer, which writes
        // the rotated image back to the same Uri in place.
        if (imageUriString.isNotEmpty()) showThumbnail()
    }

    private fun showThumbnail() {
        if (imageUriString.isNotEmpty()) {
            ivThumbnail.visibility = android.view.View.VISIBLE
            tvNoPhoto.visibility = android.view.View.GONE
            try {
                ivThumbnail.setImageURI(Uri.parse(imageUriString))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Manual entry with no bill photo (e.g. a lost receipt) — hide
            // the thumbnail slot instead of showing an empty placeholder.
            ivThumbnail.visibility = android.view.View.GONE
            tvNoPhoto.visibility = android.view.View.VISIBLE
        }
    }

    // ---- New-scan flow ----

    private fun loadCategoriesThenPopulate() {
        lifecycleScope.launch {
            categories = app.database.categoryDao().getAll()
            populateCategoryDropdown()

            // A matched template (see populateFromParsedReceipt, which runs
            // before this) already put its own, more reliable store name
            // into etStoreEnglish/storeOriginalText — use THAT for the
            // category guess, not the generic parser's separate guess, and
            // don't let a learned StoreMemory correction below overwrite a
            // template's store name (a verified template match is stronger
            // evidence than one remembered correction).
            val usedTemplate = matchedTemplateId != null
            val parsed = PendingReceiptHolder.parsed
            val guessName = CategoryMatcher.guessCategory(
                storeName = if (usedTemplate) storeOriginalText else parsed?.storeName,
                itemNames = parsed?.items?.map { it.nameOriginal } ?: emptyList()
            )
            val guessedCategory = categories.firstOrNull { it.name.equals(guessName, ignoreCase = true) }
                ?: categories.firstOrNull { it.name.equals("Other", ignoreCase = true) }
                ?: categories.firstOrNull()

            // A learned correction for this exact shop (matched by stable
            // anchor text — address/phone lines — from a receipt the user
            // corrected before) takes priority over both the raw OCR guess
            // and the generic keyword-based category guess — UNLESS a
            // template already matched, in which case the template's store
            // name is kept, and only the learned correction's CATEGORY (not
            // store name) is still allowed to apply on top of it.
            val memories = app.database.storeMemoryDao().getAll()
            val match = StoreMemoryEngine.findMatch(PendingReceiptHolder.rawOcrText, memories)

            if (match != null && !usedTemplate) {
                matchedStoreMemoryId = match.id
                storeOriginalText = match.correctedStoreName
                etStoreEnglish.setText(match.correctedStoreName)
                // We now know the real name, so there's nothing useful left
                // to show as a separate "original OCR text" line.
                tvStoreOriginal.text = ""

                val learnedCategory = match.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
                (learnedCategory ?: guessedCategory)?.let {
                    selectedCategoryId = it.id
                    spinnerCategory.setText(it.name, false)
                }
            } else if (match != null) {
                // Template already set the store name/text; only take the
                // learned category on top, if any.
                matchedStoreMemoryId = match.id
                val learnedCategory = match.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
                (learnedCategory ?: guessedCategory)?.let {
                    selectedCategoryId = it.id
                    spinnerCategory.setText(it.name, false)
                }
            } else {
                guessedCategory?.let {
                    selectedCategoryId = it.id
                    spinnerCategory.setText(it.name, false)
                }
            }

            // Snapshot what's on screen now — before the user has touched
            // anything — as the baseline for detecting a correction on save.
            initialAutoStoreName = etStoreEnglish.text.toString().trim()
            initialAutoCategoryId = selectedCategoryId
            initialAutoDateMillis = selectedDateMillis
            initialAutoTotal = etTotal.text.toString().trim().replace(",", ".").toDoubleOrNull()

            // Only claim "from learned store" when StoreMemory actually
            // supplied the store NAME (the !usedTemplate branch above) —
            // when a template matched, the store name came from the
            // template even if StoreMemory also contributed a category.
            logExtraction(usedLearnedStore = match != null && !usedTemplate)
        }
    }

    private suspend fun logExtraction(usedLearnedStore: Boolean) {
        val itemCount = itemRows.count { it.englishView.text.toString().isNotBlank() }
        val categoryName = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "?"
        val summary = "Scanned: ${initialAutoStoreName.ifBlank { "(no store detected)" }}"
        val detail = buildString {
            append("Date: ${if (dateWasGuessed) "not found on receipt, defaulted" else DATE_LOG_FMT.format(Date(selectedDateMillis))}\n")
            append("Total: ${initialAutoTotal?.let { String.format(Locale.US, "%.2f", it) } ?: "not found"} $currency\n")
            append("Category: $categoryName${if (usedLearnedStore) " (from learned store)" else " (guessed)"}\n")
            append("Items detected: $itemCount\n")
            val templateId = matchedTemplateId
            if (templateId != null) {
                append("Extraction mechanism: TEMPLATE ($templateId)\n")
                genericComparisonReceipt?.let { generic ->
                    append("Generic parser (comparison only, not used): ")
                    append("store=${generic.storeName ?: "?"}, ")
                    append("total=${generic.total?.let { String.format(Locale.US, "%.2f", it) } ?: "?"}, ")
                    append("items=${generic.items.size}")
                }
            } else {
                append("Extraction mechanism: GENERIC (no template matched)")
            }
        }
        app.database.activityLogDao().insert(
            ActivityLogEntry(eventType = LogEventType.SCAN_EXTRACTED, summary = summary, detail = detail)
        )
    }

    /**
     * Shows/hides the green "Recognized as X" banner and builds the
     * collapsed-by-default text comparing what the generic parser would
     * have produced on its own, so the user can spot-check the new
     * template mechanism against the existing one on real receipts.
     */
    private fun updateTemplateComparisonUi(matchedTemplateName: String?) {
        if (matchedTemplateName == null) {
            templateMatchBanner.visibility = android.view.View.GONE
            return
        }
        templateMatchBanner.visibility = android.view.View.VISIBLE
        tvTemplateMatchTitle.text = getString(R.string.template_matched_format, matchedTemplateName)
        tvGenericComparison.visibility = android.view.View.GONE
        tvCompareToggle.setText(R.string.compare_with_generic_parser)

        val generic = genericComparisonReceipt
        tvGenericComparison.text = if (generic == null) "" else buildString {
            append("Generic parser found:\n")
            append("Store: ${generic.storeName ?: "(not found)"}\n")
            append("Total: ${generic.total?.let { String.format(Locale.US, "%.2f", it) } ?: "(not found)"}\n")
            append("Items: ${generic.items.size}")
            if (generic.items.isNotEmpty()) {
                append(" (")
                append(generic.items.joinToString(", ") { it.nameOriginal })
                append(")")
            }
        }
    }

    private fun populateFromParsedReceipt() {
        val genericParsed = PendingReceiptHolder.parsed ?: ParsedReceipt(
            storeName = null, dateMillis = null, dateWasFound = false,
            total = null, currency = "EUR", language = "unknown", items = emptyList()
        )

        // "First mechanism": check known-store templates (see
        // parser.template package) before falling back to the generic
        // parser's own guess. The generic result is always kept too, as
        // genericComparisonReceipt, so the Review screen can show it
        // alongside — this is the comparison the user asked for, to check
        // the two mechanisms against each other on real receipts.
        val ocrText = PendingReceiptHolder.rawOcrText
        val templates = ReceiptTemplateStore.loadAll(this)
        val matchedTemplate = TemplateReceiptMatcher.findMatch(ocrText, templates)

        val parsed: ParsedReceipt
        if (matchedTemplate != null) {
            val templateResult = TemplateReceiptMatcher.extract(ocrText, matchedTemplate)
            parsed = templateResult.toParsedReceipt()
            matchedTemplateId = matchedTemplate.id
            genericComparisonReceipt = genericParsed
        } else {
            parsed = genericParsed
            matchedTemplateId = null
            genericComparisonReceipt = null
        }
        updateTemplateComparisonUi(matchedTemplate?.displayName)

        detectedLanguage = parsed.language
        currency = parsed.currency
        dateWasGuessed = !parsed.dateWasFound
        rawOcrText = PendingReceiptHolder.rawOcrText

        storeOriginalText = parsed.storeName ?: ""
        val storeEnglish = if (storeOriginalText.isNotEmpty()) {
            translator.translate(storeOriginalText, detectedLanguage)
        } else ""
        etStoreEnglish.setText(storeEnglish)
        tvStoreOriginal.text = if (storeOriginalText.isNotEmpty() && storeOriginalText != storeEnglish) {
            "${getString(R.string.original_text)}: $storeOriginalText"
        } else ""

        selectedDateMillis = parsed.dateMillis ?: System.currentTimeMillis()
        updateDateField()

        etTotal.setText(
            if (parsed.total != null) String.format(Locale.US, "%.2f", parsed.total) else ""
        )

        itemRows.clear()
        lineItemsContainer.removeAllViews()
        parsed.items.forEach { addItemRowFromParsed(it) }
        if (parsed.items.isEmpty()) addBlankItemRow()
    }

    // ---- Edit-existing-bill flow ----

    private fun loadCategoriesThenPopulateForEdit() {
        lifecycleScope.launch {
            val dao = app.database.billDao()
            val bill = dao.getBillById(editingBillId)
            if (bill == null) {
                Toast.makeText(this@ReviewActivity, "Bill not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val items = dao.getLineItemsForBill(editingBillId)
            categories = app.database.categoryDao().getAll()
            populateCategoryDropdown()
            // Editing an already-saved bill: no fresh template-vs-generic
            // comparison to show (that happened at scan time), so the
            // banner stays hidden here.
            updateTemplateComparisonUi(null)
            populateFromExistingBill(bill, items)

            // Baseline for detecting a correction on save, same as the
            // new-scan flow — an edit that changes any field is just as
            // valid a correction signal to log (and, for store/category,
            // to learn from).
            initialAutoStoreName = etStoreEnglish.text.toString().trim()
            initialAutoCategoryId = selectedCategoryId
            initialAutoDateMillis = selectedDateMillis
            initialAutoTotal = bill.totalAmount
        }
    }

    private fun populateFromExistingBill(bill: Bill, items: List<LineItem>) {
        imageUriString = bill.imageUri
        showThumbnail()

        detectedLanguage = bill.detectedLanguage
        currency = bill.currency
        dateWasGuessed = bill.dateWasGuessed
        createdAtMillis = bill.createdAtMillis
        rawOcrText = bill.rawOcrText

        storeOriginalText = bill.storeNameOriginal
        etStoreEnglish.setText(bill.storeNameEnglish)
        tvStoreOriginal.text = if (storeOriginalText.isNotEmpty() && storeOriginalText != bill.storeNameEnglish) {
            "${getString(R.string.original_text)}: $storeOriginalText"
        } else ""

        selectedDateMillis = bill.billDateMillis
        updateDateField()

        etTotal.setText(String.format(Locale.US, "%.2f", bill.totalAmount))

        selectedCategoryId = bill.categoryId
        categories.firstOrNull { it.id == bill.categoryId }?.let {
            spinnerCategory.setText(it.name, false)
        }

        etNotes.setText(bill.notes)

        itemRows.clear()
        lineItemsContainer.removeAllViews()
        items.forEach { addRow(original = it.nameOriginal, english = it.nameEnglish, price = it.totalPrice) }
        if (items.isEmpty()) addBlankItemRow()
    }

    // ---- Shared helpers ----

    private fun populateCategoryDropdown() {
        val names = categories.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        spinnerCategory.setAdapter(adapter)
        spinnerCategory.setOnClickListener { spinnerCategory.showDropDown() }
        spinnerCategory.setOnItemClickListener { _, _, position, _ ->
            selectedCategoryId = categories[position].id
        }
    }

    private fun updateDateField() {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        var text = fmt.format(Date(selectedDateMillis))
        if (dateWasGuessed) text += "  (not found on receipt — please check)"
        etDate.setText(text)
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day, 12, 0, 0)
                selectedDateMillis = cal.timeInMillis
                dateWasGuessed = false
                updateDateField()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun addItemRowFromParsed(item: ParsedItem) {
        val english = translator.translate(item.nameOriginal, detectedLanguage)
        addRow(original = item.nameOriginal, english = english, price = item.totalPrice)
    }

    private fun addBlankItemRow() {
        addRow(original = "", english = "", price = null)
    }

    private fun addRow(original: String, english: String, price: Double?) {
        val rowView = LayoutInflater.from(this).inflate(R.layout.item_line_item_edit, lineItemsContainer, false)
        val etName = rowView.findViewById<EditText>(R.id.etItemNameEnglish)
        val tvOriginal = rowView.findViewById<TextView>(R.id.tvItemNameOriginal)
        val etPrice = rowView.findViewById<EditText>(R.id.etItemPrice)
        val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeleteItem)

        etName.setText(english)
        tvOriginal.text = if (original.isNotEmpty() && original != english) original else ""
        etPrice.setText(if (price != null) String.format(Locale.US, "%.2f", price) else "")

        val row = ItemRow(original = original, englishView = etName, priceView = etPrice)
        itemRows.add(row)

        btnDelete.setOnClickListener {
            lineItemsContainer.removeView(rowView)
            itemRows.remove(row)
        }

        lineItemsContainer.addView(rowView)
    }

    /**
     * Compares what's about to be saved against what was shown automatically
     * (see initialAutoStoreName/initialAutoCategoryId), and if the user
     * changed the store name and/or category, remembers that correction —
     * keyed to this receipt's stable anchor lines (address/phone) — so the
     * same shop's future receipts get it right without asking again.
     */
    private suspend fun learnCorrectionIfNeeded(finalStoreName: String, finalCategoryId: Long) {
        val storeChanged = finalStoreName.isNotBlank() &&
            !finalStoreName.equals(initialAutoStoreName, ignoreCase = true)
        val categoryChanged = finalCategoryId != -1L && finalCategoryId != initialAutoCategoryId
        if (!storeChanged && !categoryChanged) return

        val sourceRawText = rawOcrText.ifBlank { PendingReceiptHolder.rawOcrText }
        val anchorText = StoreMemoryEngine.buildAnchorText(sourceRawText) ?: return

        val dao = app.database.storeMemoryDao()
        val existingId = matchedStoreMemoryId
        val existing = existingId?.let { id -> dao.getAll().firstOrNull { it.id == id } }
        if (existing != null) {
            dao.update(
                existing.copy(
                    anchorText = anchorText,
                    correctedStoreName = finalStoreName,
                    categoryId = finalCategoryId,
                    timesConfirmed = existing.timesConfirmed + 1,
                    lastUsedMillis = System.currentTimeMillis()
                )
            )
        } else {
            dao.insert(
                StoreMemory(
                    anchorText = anchorText,
                    correctedStoreName = finalStoreName,
                    categoryId = finalCategoryId
                )
            )
        }
        app.database.activityLogDao().insert(
            ActivityLogEntry(
                eventType = LogEventType.STORE_LEARNED,
                summary = "Learned store: $finalStoreName",
                detail = "Will be recognized on future scans by:\n" + anchorText.split("\n").joinToString("\n") { "  - $it" }
            )
        )
    }

    /**
     * Compares what's about to be saved against what was shown automatically
     * (see initialAuto* fields) and logs a human-readable "here's exactly
     * what you corrected" entry — covering store, category, date, and total,
     * not just the two fields the store-memory system can learn from.
     */
    private suspend fun logCorrectionsIfAny(
        finalStoreName: String,
        finalCategoryId: Long,
        finalDateMillis: Long,
        finalTotal: Double
    ) {
        val corrections = mutableListOf<String>()
        if (finalStoreName.isNotBlank() && !finalStoreName.equals(initialAutoStoreName, ignoreCase = true)) {
            corrections.add("Store: \"$initialAutoStoreName\" → \"$finalStoreName\"")
        }
        if (finalCategoryId != -1L && finalCategoryId != initialAutoCategoryId) {
            val from = categories.firstOrNull { it.id == initialAutoCategoryId }?.name ?: "?"
            val to = categories.firstOrNull { it.id == finalCategoryId }?.name ?: "?"
            corrections.add("Category: $from → $to")
        }
        if (initialAutoDateMillis != -1L && finalDateMillis != initialAutoDateMillis) {
            corrections.add(
                "Date: ${DATE_LOG_FMT.format(Date(initialAutoDateMillis))} → ${DATE_LOG_FMT.format(Date(finalDateMillis))}"
            )
        }
        val fromTotal = initialAutoTotal
        if (fromTotal == null || kotlin.math.abs(fromTotal - finalTotal) >= 0.005) {
            val fromText = fromTotal?.let { String.format(Locale.US, "%.2f", it) } ?: "not found"
            corrections.add("Total: $fromText → ${String.format(Locale.US, "%.2f", finalTotal)} $currency")
        }
        if (corrections.isEmpty()) return

        app.database.activityLogDao().insert(
            ActivityLogEntry(
                eventType = if (isEditMode) LogEventType.BILL_EDITED else LogEventType.BILL_CORRECTED,
                summary = "${if (isEditMode) "Edited" else "Corrected"}: $finalStoreName",
                detail = corrections.joinToString("\n")
            )
        )
    }

    private fun saveBill() {
        val storeEnglish = etStoreEnglish.text.toString().trim()
        if (storeEnglish.isEmpty()) {
            etStoreEnglish.error = "Required"
            return
        }
        val totalText = etTotal.text.toString().trim().replace(",", ".")
        val total = totalText.toDoubleOrNull()
        if (total == null) {
            etTotal.error = "Enter a valid amount"
            return
        }
        if (selectedCategoryId == -1L && categories.isNotEmpty()) {
            selectedCategoryId = categories.first().id
        }

        val notesText = etNotes.text.toString().trim()

        val lineItems = itemRows.mapNotNull { row ->
            val eng = row.englishView.text.toString().trim()
            val priceStr = row.priceView.text.toString().trim().replace(",", ".")
            val price = priceStr.toDoubleOrNull() ?: return@mapNotNull null
            if (eng.isEmpty()) return@mapNotNull null
            LineItem(
                billId = if (isEditMode) editingBillId else 0, // filled in by DAO transaction when new
                nameOriginal = row.original.ifEmpty { eng },
                nameEnglish = eng,
                quantity = 1.0,
                unitPrice = null,
                totalPrice = price,
                categoryId = selectedCategoryId
            )
        }

        if (isEditMode) {
            val updatedBill = Bill(
                id = editingBillId,
                imageUri = imageUriString,
                storeNameOriginal = storeOriginalText.ifEmpty { storeEnglish },
                storeNameEnglish = storeEnglish,
                billDateMillis = selectedDateMillis,
                dateWasGuessed = dateWasGuessed,
                totalAmount = total,
                currency = currency,
                detectedLanguage = detectedLanguage,
                categoryId = selectedCategoryId,
                rawOcrText = rawOcrText,
                createdAtMillis = createdAtMillis,
                notes = notesText
            )
            lifecycleScope.launch {
                val dao = app.database.billDao()
                dao.updateBill(updatedBill)
                dao.replaceLineItems(editingBillId, lineItems)
                logCorrectionsIfAny(storeEnglish, selectedCategoryId, selectedDateMillis, total)
                learnCorrectionIfNeeded(storeEnglish, selectedCategoryId)
                Toast.makeText(this@ReviewActivity, "Bill updated", Toast.LENGTH_SHORT).show()
                // Simply return to the BillDetailActivity already on the
                // back stack — its onResume() reloads the fresh data, so
                // there's no need to start a second instance of it.
                finish()
            }
        } else {
            val bill = Bill(
                imageUri = imageUriString,
                storeNameOriginal = storeOriginalText.ifEmpty { storeEnglish },
                storeNameEnglish = storeEnglish,
                billDateMillis = selectedDateMillis,
                dateWasGuessed = dateWasGuessed,
                totalAmount = total,
                currency = currency,
                detectedLanguage = detectedLanguage,
                categoryId = selectedCategoryId,
                rawOcrText = PendingReceiptHolder.rawOcrText,
                createdAtMillis = System.currentTimeMillis(),
                notes = notesText
            )

            lifecycleScope.launch {
                app.database.billDao().insertBillWithItems(bill, lineItems)
                logCorrectionsIfAny(storeEnglish, selectedCategoryId, selectedDateMillis, total)
                learnCorrectionIfNeeded(storeEnglish, selectedCategoryId)
                PendingReceiptHolder.parsed = null
                PendingReceiptHolder.rawOcrText = ""
                Toast.makeText(this@ReviewActivity, "Bill saved", Toast.LENGTH_SHORT).show()

                val intent = Intent(this@ReviewActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
        }
    }
}
