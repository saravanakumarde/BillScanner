package com.billscanner.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.CategoryTotal
import com.billscanner.app.ui.list.BillListActivity
import com.billscanner.app.ui.list.ListFilter
import com.billscanner.app.util.CurrencyFormat
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class CategoryStatsActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: CategoryTotalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.by_category)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CategoryTotalAdapter { ct ->
            BillListActivity.start(this, ListFilter.ByCategory(ct.categoryId, ct.categoryName))
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            val earliest = app.database.billDao().getEarliestBillDate() ?: System.currentTimeMillis()
            app.database.billDao()
                .observeCategoryTotals(earliest, System.currentTimeMillis())
                .observe(this@CategoryStatsActivity) { totals ->
                    val nonEmpty = totals.filter { it.billCount > 0 }
                    adapter.submitList(nonEmpty)
                    findViewById<TextView>(R.id.tvEmpty).visibility =
                        if (nonEmpty.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }
}

class CategoryTotalAdapter(
    private val onClick: (CategoryTotal) -> Unit
) : RecyclerView.Adapter<CategoryTotalAdapter.VH>() {

    private val items = mutableListOf<CategoryTotal>()

    fun submitList(newItems: List<CategoryTotal>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.total })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_total, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)
    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.colorDot)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: CategoryTotal, onClick: (CategoryTotal) -> Unit) {
            tvName.text = item.categoryName
            tvCount.text = "${item.billCount} bill" + if (item.billCount == 1) "" else "s"
            tvAmount.text = CurrencyFormat.format(item.total, "EUR")
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
            card.setOnClickListener { onClick(item) }
        }
    }
}
