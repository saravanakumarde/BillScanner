package com.billscanner.app.util

import com.billscanner.app.data.db.RecurringExpense
import java.time.YearMonth

/**
 * Works out where a [RecurringExpense] stands on a given date. Pure
 * date arithmetic, no Android classes, so it can be tested in isolation.
 *
 * Months are compared as a single index (year * 12 + month - 1) so
 * year boundaries need no special handling.
 */
object RecurringSchedule {

    enum class State { UPCOMING, ACTIVE, COMPLETED }

    data class Status(
        val state: State,
        /** Installments whose due date has already passed (capped at the total for an EMI). */
        val paidCount: Int,
        /** Installments still to come; null for an ongoing fixed expense. */
        val remainingCount: Int?,
        /** Month the last installment falls in (1-12); null for an ongoing fixed expense. */
        val endYear: Int?,
        val endMonth: Int?
    )

    private fun monthIndex(year: Int, month: Int) = year * 12 + (month - 1)

    private fun daysInMonth(year: Int, month: Int) = YearMonth.of(year, month).lengthOfMonth()

    fun statusOn(e: RecurringExpense, year: Int, month: Int, day: Int): Status {
        val startIdx = monthIndex(e.startYear, e.startMonth)
        val nowIdx = monthIndex(year, month)
        val total = e.totalInstallments

        var paid = 0
        if (nowIdx >= startIdx) {
            // Every month before this one is fully past. This month's
            // installment counts once its due day (clamped to the month's
            // length, so "31" still falls due on 28 Feb) has been reached.
            val dueDay = minOf(e.dayOfMonth, daysInMonth(year, month))
            paid = (nowIdx - startIdx) + if (day >= dueDay) 1 else 0
        }
        if (total != null && paid > total) paid = total

        val endIdx = total?.let { startIdx + it - 1 }
        val state = when {
            total != null && paid >= total -> State.COMPLETED
            nowIdx < startIdx -> State.UPCOMING
            else -> State.ACTIVE
        }
        return Status(
            state = state,
            paidCount = paid,
            remainingCount = total?.let { it - paid },
            endYear = endIdx?.let { it / 12 },
            endMonth = endIdx?.let { it % 12 + 1 }
        )
    }

    /** True if an installment falls due in the given month (regardless of whether its day has passed yet). */
    fun isDueInMonth(e: RecurringExpense, year: Int, month: Int): Boolean {
        val startIdx = monthIndex(e.startYear, e.startMonth)
        val nowIdx = monthIndex(year, month)
        val total = e.totalInstallments
        return nowIdx >= startIdx && (total == null || nowIdx <= startIdx + total - 1)
    }

    /** Money still to be paid on an EMI; 0 for a fixed expense (it has no end). */
    fun balanceRemaining(e: RecurringExpense, status: Status): Double {
        val total = e.totalInstallments ?: return 0.0
        if (e.kind != RecurringExpense.KIND_EMI) return 0.0
        return when (status.state) {
            State.COMPLETED -> 0.0
            State.UPCOMING -> total * e.amount
            State.ACTIVE -> (status.remainingCount ?: 0) * e.amount
        }
    }
}
