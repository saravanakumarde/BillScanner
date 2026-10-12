package com.billscanner.app.ui.list

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.BillWithCategory
import com.billscanner.app.ui.detail.BillDetailActivity
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.DateFormat

class BillListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILTER = "filter"

        fun start(context: Context, filter: ListFilter) {
            val intent = Intent(context, BillListActivity::class.java)
            intent.putExtra(EXTRA_FILTER, filter)
            context.startActivity(intent)
        }
    }

    private val app get() = application as BillScannerApp
    private lateinit var adapter: BillListAdapter
    private lateinit var tvPeriodTotal: TextView
    private lateinit var tvEmpty: TextView
    private var currentLiveData: LiveData<List<BillWithCategory>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvPeriodTotal = findViewById(R.id.tvPeriodTotal)
        tvEmpty = findViewById(R.id.tvEmpty)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BillListAdapter { bill ->
            BillDetailActivity.start(this, bill.id)
        }
        recyclerView.adapter = adapter

        @Suppress("DEPRECATION")
        val filter = intent.getSerializableExtra(EXTRA_FILTER) as? ListFilter ?: ListFilter.All

        toolbar.title = titleFor(filter)
        observeBills(filter)
    }

    private fun titleFor(filter: ListFilter): String = when (filter) {
        is ListFilter.All -> getString(R.string.all_bills)
        is ListFilter.ThisWeek -> getString(R.string.this_week)
        is ListFilter.ThisMonth -> getString(R.string.this_month)
        is ListFilter.ByCategory -> filter.categoryName
        is ListFilter.ByStore -> filter.storeName
    }

    private fun observeBills(filter: ListFilter) {
        val dao = app.database.billDao()
        val liveData: LiveData<List<BillWithCategory>> = when (filter) {
            is ListFilter.All -> dao.observeAllBills()
            is ListFilter.ThisWeek -> dao.observeBillsBetween(
                DateFormat.startOfThisWeek(), DateFormat.endOfThisWeek()
            )
            is ListFilter.ThisMonth -> dao.observeBillsBetween(
                DateFormat.startOfThisMonth(), DateFormat.endOfThisMonth()
            )
            is ListFilter.ByCategory -> dao.observeBillsByCategory(filter.categoryId)
            is ListFilter.ByStore -> dao.observeBillsByStore(filter.storeName)
        }
        currentLiveData = liveData
        liveData.observe(this) { bills ->
            adapter.submitList(bills)
            tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
            val total = bills.sumOf { it.totalAmount }
            val currency = bills.firstOrNull()?.currency ?: "EUR"
            tvPeriodTotal.text = getString(R.string.grand_total) + ": " +
                CurrencyFormat.format(total, currency) + " (${bills.size})"
        }
    }
}
