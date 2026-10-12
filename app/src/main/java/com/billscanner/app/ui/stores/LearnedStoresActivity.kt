package com.billscanner.app.ui.stores

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.Category
import com.billscanner.app.data.db.StoreMemory
import com.billscanner.app.util.ExportHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LearnedStoresActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: StoreMemoryAdapter
    private var categoriesById: Map<Long, Category> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exportable_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.view_learned_stores)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.tvHeaderDesc).apply {
            text = getString(R.string.learned_stores_screen_desc)
            visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.tvEmpty).text = getString(R.string.no_learned_stores)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = StoreMemoryAdapter(
            categoryNameFor = { id -> categoriesById[id]?.name },
            onDelete = { memory -> confirmDeleteOne(memory) }
        )
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { exportMemories() }
        findViewById<MaterialButton>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }

        lifecycleScope.launch {
            categoriesById = app.database.categoryDao().getAll().associateBy { it.id }
            refresh()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val memories = app.database.storeMemoryDao().getAll()
            adapter.submitList(memories)
            findViewById<TextView>(R.id.tvEmpty).visibility =
                if (memories.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteOne(memory: StoreMemory) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete) + ": " + memory.correctedStoreName + "?")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    app.database.storeMemoryDao().delete(memory.id)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear_learned_stores)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    app.database.storeMemoryDao().deleteAll()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportMemories() {
        lifecycleScope.launch {
            val memories = app.database.storeMemoryDao().getAll()
            val content = buildExportText(memories)
            ExportHelper.shareText(
                this@LearnedStoresActivity,
                ExportHelper.timestampedFileName("bill_scanner_learned_stores"),
                content,
                getString(R.string.view_learned_stores)
            )
        }
    }

    private fun buildExportText(memories: List<StoreMemory>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Bill Scanner — Learned Stores\n")
        sb.append("Exported ${fmt.format(Date())}\n")
        sb.append("${memories.size} stores\n")
        sb.append("=".repeat(50)).append("\n\n")
        memories.forEach { memory ->
            sb.append("Store: ${memory.correctedStoreName}\n")
            sb.append("Category: ${memory.categoryId?.let { categoriesById[it]?.name } ?: "(none learned)"}\n")
            sb.append("Confirmed ${memory.timesConfirmed} time(s), last used ${fmt.format(Date(memory.lastUsedMillis))}\n")
            sb.append("Matched by these lines from the receipt:\n")
            memory.anchorText.split("\n").forEach { line -> sb.append("  - $line\n") }
            sb.append("\n")
        }
        return sb.toString()
    }
}

class StoreMemoryAdapter(
    private val categoryNameFor: (Long) -> String?,
    private val onDelete: (StoreMemory) -> Unit
) : RecyclerView.Adapter<StoreMemoryAdapter.VH>() {

    private val items = mutableListOf<StoreMemory>()
    private val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun submitList(newItems: List<StoreMemory>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_learned_store, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], categoryNameFor, fmt, onDelete)

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStoreName: TextView = itemView.findViewById(R.id.tvStoreName)
        private val tvStoreCategory: TextView = itemView.findViewById(R.id.tvStoreCategory)
        private val tvStoreAnchors: TextView = itemView.findViewById(R.id.tvStoreAnchors)
        private val tvStoreMeta: TextView = itemView.findViewById(R.id.tvStoreMeta)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteMemory)

        fun bind(
            memory: StoreMemory,
            categoryNameFor: (Long) -> String?,
            fmt: SimpleDateFormat,
            onDelete: (StoreMemory) -> Unit
        ) {
            tvStoreName.text = memory.correctedStoreName
            val categoryName = memory.categoryId?.let(categoryNameFor)
            tvStoreCategory.text = "Category: ${categoryName ?: "(none learned)"}"
            tvStoreAnchors.text = "Matched by: " + memory.anchorText.replace("\n", " · ")
            tvStoreMeta.text = "Confirmed ${memory.timesConfirmed}× · last used ${fmt.format(Date(memory.lastUsedMillis))}"
            btnDelete.setOnClickListener { onDelete(memory) }
        }
    }
}
