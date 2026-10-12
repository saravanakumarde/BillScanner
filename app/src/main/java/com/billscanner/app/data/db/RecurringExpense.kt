package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A payment that repeats every month. Two kinds share one table:
 *  - [KIND_FIXED]: an ongoing fixed expense (rent, insurance, a subscription).
 *    [totalInstallments] is null, it runs until the user deletes it.
 *  - [KIND_EMI]: a loan/EMI that runs for [totalInstallments] monthly payments,
 *    starting with the one due on [startYear]/[startMonth]/[dayOfMonth].
 *
 * How many installments are paid so far, the end month and the remaining
 * balance are NOT stored; they are derived from today's date by
 * [com.billscanner.app.util.RecurringSchedule], so they never go stale.
 */
@Entity(tableName = "recurring_expenses")
data class RecurringExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Amount paid each month. */
    val amount: Double,
    /** [KIND_FIXED] or [KIND_EMI]. */
    val kind: String,
    /** Day of the month the payment is due (1-31; clamped to the month length, e.g. 31 becomes 28 in February). */
    val dayOfMonth: Int,
    val startYear: Int,
    /** 1-12 */
    val startMonth: Int,
    /** Number of monthly installments for an EMI; null for an ongoing fixed expense. */
    val totalInstallments: Int? = null,
    val notes: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_FIXED = "FIXED"
        const val KIND_EMI = "EMI"
    }
}
