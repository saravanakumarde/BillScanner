package com.billscanner.app.ui.recurring

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.data.db.RecurringExpense
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.RecurringSchedule
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Fixed monthly expenses (rent, insurance, subscriptions) and EMIs that run
 * for a set number of months. Shows a summary of what is due this month and
 * how much EMI balance is still to be paid, then one row per entry. Tapping
 * a row edits it (and can delete it); the + button adds a new one.
 */
class RecurringExpensesActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RecurringExpensesActivity::class.java))
        }
    }

    private val app get() = application as BillScannerApp
    private lateinit var adapter: RecurringExpenseAdapter
    private lateinit var tvEmpty: TextView
    private var latest: List<RecurringExpense> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_expenses)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvRecEmpty)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RecurringExpenseAdapter { expense -> showEditor(expense) }
        recyclerView.adapter = adapter

        app.database.recurringExpenseDao().observeAll().observe(this) { list ->
            latest = list
            adapter.submitList(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            updateSummary()
        }

        findViewById<FloatingActionButton>(R.id.fabAddRecurring).setOnClickListener { showEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        // Progress is derived from today's date, so re-evaluate when coming
        // back to the screen (a new day/month may have started meanwhile).
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        val dueNow = latest.filter { RecurringSchedule.isDueInMonth(it, year, month) }
        val fixedTotal = dueNow.filter { it.kind == RecurringExpense.KIND_FIXED }.sumOf { it.amount }
        val emiTotal = dueNow.filter { it.kind == RecurringExpense.KIND_EMI }.sumOf { it.amount }
        val balance = latest.filter { it.kind == RecurringExpense.KIND_EMI }
            .sumOf { RecurringSchedule.balanceRemaining(it, RecurringSchedule.statusOn(it, year, month, day)) }

        fun money(v: Double) = CurrencyFormat.format(v, "EUR")
        findViewById<TextView>(R.id.tvRecSummaryMonth).text =
            getString(R.string.rec_summary_month_format, money(fixedTotal + emiTotal), dueNow.size)
        findViewById<TextView>(R.id.tvRecSummarySplit).text =
            getString(R.string.rec_summary_split_format, money(fixedTotal), money(emiTotal))
        findViewById<TextView>(R.id.tvRecSummaryBalance).text =
            getString(R.string.rec_summary_balance_format, money(balance))
    }

    /** Accepts "1234.56", "1234,56" and "1.234,56". */
    private fun parseAmount(raw: String): Double? {
        val s = raw.trim()
        val normalized = if (s.contains(',')) s.replace(".", "").replace(',', '.') else s
        return normalized.toDoubleOrNull()
    }

    private fun showEditor(existing: RecurringExpense?) {
        val view = layoutInflater.inflate(R.layout.dialog_recurring_expense, null)
        val rgKind = view.findViewById<RadioGroup>(R.id.rgRecKind)
        val etName = view.findViewById<EditText>(R.id.etRecName)
        val etAmount = view.findViewById<EditText>(R.id.etRecAmount)
        val etFirstDate = view.findViewById<EditText>(R.id.etRecFirstDate)
        val layoutInstallments = view.findViewById<View>(R.id.layoutRecInstallments)
        val etInstallments = view.findViewById<EditText>(R.id.etRecInstallments)
        val etNotes = view.findViewById<EditText>(R.id.etRecNotes)

        var pickedYear = 0
        var pickedMonth = 0 // 1-12
        var pickedDay = 0
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        fun showPickedDate() {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(pickedYear, pickedMonth - 1, pickedDay)
            etFirstDate.setText(dateFormat.format(cal.time))
        }

        rgKind.setOnCheckedChangeListener { _, checkedId ->
            layoutInstallments.visibility = if (checkedId == R.id.rbRecEmi) View.VISIBLE else View.GONE
        }

        if (existing != null) {
            rgKind.check(if (existing.kind == RecurringExpense.KIND_EMI) R.id.rbRecEmi else R.id.rbRecFixed)
            etName.setText(existing.name)
            etAmount.setText(String.format(Locale.US, "%.2f", existing.amount))
            existing.totalInstallments?.let { etInstallments.setText(it.toString()) }
            etNotes.setText(existing.notes)
            pickedYear = existing.startYear
            pickedMonth = existing.startMonth
            pickedDay = existing.dayOfMonth
            showPickedDate()
        } else {
            rgKind.check(R.id.rbRecFixed)
        }

        etFirstDate.setOnClickListener {
            val cal = Calendar.getInstance()
            val y = if (pickedYear != 0) pickedYear else cal.get(Calendar.YEAR)
            val m = if (pickedYear != 0) pickedMonth - 1 else cal.get(Calendar.MONTH)
            val d = if (pickedYear != 0) pickedDay else cal.get(Calendar.DAY_OF_MONTH)
            DatePickerDialog(this, { _, year, monthZeroBased, dayOfMonth ->
                pickedYear = year
                pickedMonth = monthZeroBased + 1
                pickedDay = dayOfMonth
                showPickedDate()
            }, y, m, d).show()
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_recurring_expense else R.string.edit_recurring_expense)
            .setView(view)
            .setPositiveButton(R.string.rec_save, null) // real handler set below so validation errors keep the dialog open
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete_recurring) { _, _ -> confirmDelete(existing) }
        }
        val dialog: AlertDialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val isEmi = rgKind.checkedRadioButtonId == R.id.rbRecEmi
            val name = etName.text.toString().trim()
            val amount = parseAmount(etAmount.text.toString())
            val installments = if (isEmi) etInstallments.text.toString().trim().toIntOrNull() else null

            if (name.isEmpty()) { etName.error = getString(R.string.rec_name_required); return@setOnClickListener }
            if (amount == null || amount <= 0.0) { etAmount.error = getString(R.string.rec_amount_invalid); return@setOnClickListener }
            if (pickedYear == 0) {
                Toast.makeText(this, R.string.rec_date_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isEmi && (installments == null || installments !in 1..600)) {
                etInstallments.error = getString(R.string.rec_installments_invalid)
                return@setOnClickListener
            }

            val entity = RecurringExpense(
                id = existing?.id ?: 0L,
                name = name,
                amount = amount,
                kind = if (isEmi) RecurringExpense.KIND_EMI else RecurringExpense.KIND_FIXED,
                dayOfMonth = pickedDay,
                startYear = pickedYear,
                startMonth = pickedMonth,
                totalInstallments = installments,
                notes = etNotes.text.toString().trim(),
                createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()
            )
            lifecycleScope.launch {
                val dao = app.database.recurringExpenseDao()
                if (existing == null) dao.insert(entity) else dao.update(entity)
            }
            dialog.dismiss()
        }
    }

    private fun confirmDelete(expense: RecurringExpense) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_recurring)
            .setMessage(R.string.delete_recurring_confirm)
            .setPositiveButton(R.string.delete_recurring) { _, _ ->
                lifecycleScope.launch { app.database.recurringExpenseDao().delete(expense) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
