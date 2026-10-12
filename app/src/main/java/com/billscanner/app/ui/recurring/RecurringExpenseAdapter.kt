package com.billscanner.app.ui.recurring

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.R
import com.billscanner.app.data.db.RecurringExpense
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.RecurringSchedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RecurringExpenseAdapter(
    private val onClick: (RecurringExpense) -> Unit
) : ListAdapter<RecurringExpense, RecurringExpenseAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecurringExpense>() {
            override fun areItemsTheSame(old: RecurringExpense, new: RecurringExpense) = old.id == new.id
            override fun areContentsTheSame(old: RecurringExpense, new: RecurringExpense) = old == new
        }

        /** "Mar 2027" for a 1-12 month number and a year. */
        fun monthLabel(year: Int, month: Int): String {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(year, month - 1, 1)
            return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvRecName)
        val tvAmount: TextView = itemView.findViewById(R.id.tvRecAmount)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tvRecSubtitle)
        val progressBar: ProgressBar = itemView.findViewById(R.id.pbRecProgress)
        val tvProgress: TextView = itemView.findViewById(R.id.tvRecProgress)
        val tvRemaining: TextView = itemView.findViewById(R.id.tvRecRemaining)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recurring_expense, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val e = getItem(position)
        val ctx = holder.itemView.context
        val now = Calendar.getInstance()
        val status = RecurringSchedule.statusOn(
            e, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        val isEmi = e.kind == RecurringExpense.KIND_EMI && e.totalInstallments != null

        holder.tvName.text = e.name
        holder.tvAmount.text = ctx.getString(R.string.rec_amount_per_month_format, CurrencyFormat.format(e.amount, "EUR"))
        holder.tvSubtitle.text = ctx.getString(
            if (isEmi) R.string.rec_emi_subtitle_format else R.string.rec_fixed_subtitle_format, e.dayOfMonth
        )

        // A finished EMI stays listed (as a record) but is visually dimmed.
        holder.itemView.alpha = if (status.state == RecurringSchedule.State.COMPLETED) 0.6f else 1f

        if (isEmi) {
            val total = e.totalInstallments ?: 0
            holder.progressBar.visibility = View.VISIBLE
            holder.progressBar.max = total
            holder.progressBar.progress = status.paidCount

            holder.tvProgress.text = when (status.state) {
                RecurringSchedule.State.UPCOMING ->
                    ctx.getString(R.string.rec_starts_format, monthLabel(e.startYear, e.startMonth))
                RecurringSchedule.State.COMPLETED ->
                    ctx.getString(R.string.rec_completed_format, total, monthLabel(status.endYear ?: e.startYear, status.endMonth ?: e.startMonth))
                RecurringSchedule.State.ACTIVE ->
                    ctx.getString(R.string.rec_progress_format, status.paidCount, total, monthLabel(status.endYear ?: e.startYear, status.endMonth ?: e.startMonth))
            }

            if (status.state == RecurringSchedule.State.COMPLETED) {
                holder.tvRemaining.visibility = View.GONE
            } else {
                holder.tvRemaining.visibility = View.VISIBLE
                holder.tvRemaining.text = ctx.getString(
                    R.string.rec_remaining_format,
                    status.remainingCount ?: total,
                    CurrencyFormat.format(RecurringSchedule.balanceRemaining(e, status), "EUR")
                )
            }
        } else {
            holder.progressBar.visibility = View.GONE
            holder.tvRemaining.visibility = View.GONE
            holder.tvProgress.text = if (status.state == RecurringSchedule.State.UPCOMING) {
                ctx.getString(R.string.rec_starts_format, monthLabel(e.startYear, e.startMonth))
            } else {
                ctx.getString(R.string.rec_since_ongoing_format, monthLabel(e.startYear, e.startMonth))
            }
        }

        holder.itemView.setOnClickListener { onClick(e) }
    }
}
