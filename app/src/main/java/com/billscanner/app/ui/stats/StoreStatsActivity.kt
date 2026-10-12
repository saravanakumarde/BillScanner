package com.billscanner.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.BillWithCategory
import com.billscanner.app.ui.list.BillListActivity
import com.billscanner.app.ui.list.ListFilter
import com.billscanner.app.util.CurrencyFormat
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

data class StoreTotal(val storeName: String, val total: Double, val count: Int)

class StoreStatsActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: StoreTotalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.by_store)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = StoreTotalAdapter { store ->
            BillListActivity.start(this, ListFilter.ByStore(store.storeName))
        }
        recyclerView.adapter = adapter

        app.database.billDao().observeAllBills().observe(this) { bills ->
            val grouped: List<StoreTotal> = bills
                .groupBy { it.storeNameEnglish.ifEmpty { it.storeNameOriginal } }
                .map { (name, list) ->
                    StoreTotal(name, list.sumOf { it.totalAmount }, list.size)
                }
                .sortedByDescending { it.total }
            adapter.submitList(grouped)
            findViewById<TextView>(R.id.tvEmpty).visibility =
                if (grouped.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}

class StoreTotalAdapter(
    private val onClick: (StoreTotal) -> Unit
) : RecyclerView.Adapter<StoreTotalAdapter.VH>() {

    private val items = mutableListOf<StoreTotal>()

    fun submitList(newItems: List<StoreTotal>) {
        items.clear()
        items.addAll(newItems)
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

        fun bind(item: StoreTotal, onClick: (StoreTotal) -> Unit) {
            dot.visibility = View.GONE
            tvName.text = item.storeName
            tvCount.text = "${item.count} bill" + if (item.count == 1) "" else "s"
            tvAmount.text = CurrencyFormat.format(item.total, "EUR")
            card.setOnClickListener { onClick(item) }
        }
    }
}
