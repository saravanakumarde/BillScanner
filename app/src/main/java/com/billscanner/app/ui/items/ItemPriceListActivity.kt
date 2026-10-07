package com.billscanner.app.ui.items

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.LineItemWithStore
import com.billscanner.app.ui.detail.BillDetailActivity
import com.billscanner.app.util.CurrencyFormat
import com.google.android.material.card.MaterialCardView

/**
 * Every line item from every bill, sorted alphabetically by item name, with
 * the shop it was bought at shown underneath in a dimmer color. Because
 * items with the same name sort next to each other, this makes it easy to
 * see what the same product costs at different shops.
 */
class ItemPriceListActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: ItemPriceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.item_prices)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ItemPriceAdapter { entry ->
            BillDetailActivity.start(this, entry.billId)
        }
        recyclerView.adapter = adapter

        findViewById<TextView>(R.id.tvEmpty).text = getString(R.string.no_items_yet)

        app.database.billDao().observeAllLineItemsWithStore().observe(this) { entries ->
            adapter.submitList(entries)
            findViewById<TextView>(R.id.tvEmpty).visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}

class ItemPriceAdapter(
    private val onClick: (LineItemWithStore) -> Unit
) : RecyclerView.Adapter<ItemPriceAdapter.VH>() {

    private val items = mutableListOf<LineItemWithStore>()

    fun submitList(newItems: List<LineItemWithStore>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_price_entry, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)
    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvItemName: TextView = itemView.findViewById(R.id.tvItemName)
        private val tvShopName: TextView = itemView.findViewById(R.id.tvShopName)
        private val tvItemPrice: TextView = itemView.findViewById(R.id.tvItemPrice)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(entry: LineItemWithStore, onClick: (LineItemWithStore) -> Unit) {
            tvItemName.text = entry.nameEnglish.ifEmpty { entry.nameOriginal }
            tvShopName.text = entry.storeNameEnglish.ifEmpty { entry.storeNameOriginal }
            tvItemPrice.text = CurrencyFormat.format(entry.totalPrice, entry.currency)
            card.setOnClickListener { onClick(entry) }
        }
    }
}
