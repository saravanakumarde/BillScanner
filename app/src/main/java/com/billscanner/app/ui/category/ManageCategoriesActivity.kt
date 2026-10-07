package com.billscanner.app.ui.category

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.Category
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

// Every fresh install seeds a category named exactly this (see
// DefaultCategories.SEED in Category.kt) which line items/bills fall back to
// when a category is deleted, so it must never itself be deletable.
private const val FALLBACK_CATEGORY_NAME = "Other"

class ManageCategoriesActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: CategoryManageAdapter

    // A rotating palette so user-added categories still get distinct colors
    // without needing a color picker UI.
    private val palette = listOf(
        "#3F51B5", "#009688", "#FF5722", "#8BC34A", "#00BCD4",
        "#9E9E9E", "#F44336", "#673AB7", "#CDDC39", "#03A9F4"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_categories)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CategoryManageAdapter(
            onDeleteClick = { category -> confirmDelete(category) }
        )
        recyclerView.adapter = adapter

        app.database.categoryDao().observeAll().observe(this) { categories ->
            adapter.submitList(categories)
        }

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_category, null)
        val editText = view.findViewById<TextInputEditText>(R.id.etCategoryName)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_category)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) addCategory(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addCategory(name: String) {
        lifecycleScope.launch {
            val existing = app.database.categoryDao().findByName(name)
            if (existing != null) return@launch
            val count = app.database.categoryDao().count()
            val color = palette[count % palette.size]
            app.database.categoryDao().insert(Category(name = name, colorHex = color, isDefault = false))
        }
    }

    private fun confirmDelete(category: Category) {
        if (category.name.equals(FALLBACK_CATEGORY_NAME, ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_category_title)
                .setMessage(R.string.delete_category_cannot_delete_other)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        lifecycleScope.launch {
            val billCount = app.database.billDao().countBillsInCategory(category.id)
            val allCategories = app.database.categoryDao().getAll()
            val otherCategories = allCategories.filter { it.id != category.id }

            if (billCount == 0) {
                AlertDialog.Builder(this@ManageCategoriesActivity)
                    .setTitle(R.string.delete_category_title)
                    .setMessage(getString(R.string.delete_category_no_bills, category.name))
                    .setPositiveButton(R.string.delete_category_confirm) { _, _ ->
                        lifecycleScope.launch {
                            app.database.categoryDao().delete(category)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                showReassignAndDeleteDialog(category, billCount, otherCategories)
            }
        }
    }

    /**
     * Category is in use by at least one bill: warn the user, and require
     * picking a replacement category before the delete proceeds. All bills
     * (and their line items) using the deleted category are reassigned to
     * the chosen replacement so nothing is silently orphaned.
     */
    private fun showReassignAndDeleteDialog(category: Category, billCount: Int, otherCategories: List<Category>) {
        val fallback = otherCategories.firstOrNull { it.name.equals(FALLBACK_CATEGORY_NAME, ignoreCase = true) }
        val choices = otherCategories.sortedBy { it.name != FALLBACK_CATEGORY_NAME }

        val view = layoutInflater.inflate(R.layout.dialog_reassign_category, null)
        val dropdown = view.findViewById<AutoCompleteTextView>(R.id.spinnerReplacementCategory)
        val names = choices.map { it.name }
        dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))

        var selectedReplacement: Category? = fallback ?: choices.firstOrNull()
        selectedReplacement?.let { dropdown.setText(it.name, false) }
        dropdown.setOnItemClickListener { _, _, position, _ ->
            selectedReplacement = choices[position]
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_category_title)
            .setMessage(getString(R.string.delete_category_has_bills, billCount, category.name))
            .setView(view)
            .setPositiveButton(R.string.delete_category_confirm) { _, _ ->
                val replacement = selectedReplacement ?: return@setPositiveButton
                lifecycleScope.launch {
                    app.database.billDao().reassignCategoryAndDelete(category.id, replacement.id)
                    app.database.categoryDao().delete(category)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

class CategoryManageAdapter(
    private val onDeleteClick: (Category) -> Unit
) : RecyclerView.Adapter<CategoryManageAdapter.VH>() {

    private val items = mutableListOf<Category>()

    fun submitList(newItems: List<Category>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_manage, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onDeleteClick)
    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.colorDot)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteCategory)

        fun bind(item: Category, onDeleteClick: (Category) -> Unit) {
            tvName.text = item.name
            val bg = (dot.background ?: androidx.core.content.ContextCompat.getDrawable(
                itemView.context, com.billscanner.app.R.drawable.bg_color_dot
            ))?.mutate()
            if (bg != null) {
                try {
                    DrawableCompat.setTint(bg, Color.parseColor(item.colorHex.ifEmpty { "#607D8B" }))
                } catch (e: Exception) {
                    DrawableCompat.setTint(bg, Color.parseColor("#607D8B"))
                }
                dot.background = bg
            }

            // "Other" is the permanent fallback and can't be deleted - hide
            // the delete control entirely rather than showing it and then
            // always refusing, which would be confusing.
            btnDelete.visibility = if (item.name.equals("Other", ignoreCase = true)) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
