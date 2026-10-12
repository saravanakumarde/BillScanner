package com.billscanner.app.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.ActivityLogEntry
import com.billscanner.app.util.ExportHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogActivity : AppCompatActivity() {

    private val app get() = application as BillScannerApp
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exportable_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.activity_log)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.tvHeaderDesc).apply {
            text = getString(R.string.activity_log_desc)
            visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.tvEmpty).text = getString(R.string.no_log_entries)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter()
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { exportLog() }
        findViewById<MaterialButton>(R.id.btnClearAll).setOnClickListener { confirmClear() }

        app.database.activityLogDao().observeAll().observe(this) { entries ->
            adapter.submitList(entries)
            findViewById<TextView>(R.id.tvEmpty).visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun exportLog() {
        lifecycleScope.launch {
            val entries = app.database.activityLogDao().getAll()
            val content = buildLogText(entries)
            ExportHelper.shareText(
                this@ActivityLogActivity,
                ExportHelper.timestampedFileName("bill_scanner_activity_log"),
                content,
                getString(R.string.activity_log)
            )
        }
    }

    private fun buildLogText(entries: List<ActivityLogEntry>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Bill Scanner — Activity Log\n")
        sb.append("Exported ${fmt.format(Date())}\n")
        sb.append("${entries.size} entries\n")
        sb.append("=".repeat(50)).append("\n\n")
        entries.forEach { entry ->
            sb.append("[${fmt.format(Date(entry.timestampMillis))}] ${entry.eventType}\n")
            sb.append(entry.summary).append("\n")
            if (entry.detail.isNotBlank()) sb.append(entry.detail).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear_log)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { app.database.activityLogDao().deleteAll() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
    private val items = mutableListOf<ActivityLogEntry>()
    private val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    fun submitList(newItems: List<ActivityLogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], fmt)
    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvLogTimestamp)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvLogSummary)
        private val tvDetail: TextView = itemView.findViewById(R.id.tvLogDetail)

        fun bind(entry: ActivityLogEntry, fmt: SimpleDateFormat) {
            tvTimestamp.text = fmt.format(Date(entry.timestampMillis))
            tvSummary.text = entry.summary
            if (entry.detail.isNotBlank()) {
                tvDetail.text = entry.detail
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }
            itemView.setOnClickListener {
                tvDetail.visibility = if (tvDetail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }
}
