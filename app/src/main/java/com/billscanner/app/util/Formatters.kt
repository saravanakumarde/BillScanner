package com.billscanner.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CurrencyFormat {
    fun format(amount: Double, currency: String): String {
        val symbol = when (currency) {
            "EUR" -> "€"
            "USD" -> "$"
            "GBP" -> "£"
            else -> "$currency "
        }
        return String.format(Locale.GERMANY, "%s%,.2f", symbol, amount)
    }
}

object DateFormat {
    private val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val dayFormat = SimpleDateFormat("EEE, dd MMM", Locale.ENGLISH)

    fun display(millis: Long): String = displayFormat.format(Date(millis))
    fun displayShort(millis: Long): String = dayFormat.format(Date(millis))

    /** Start of the current calendar week (Monday 00:00) in millis. */
    fun startOfThisWeek(): Long {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        setToStartOfDay(cal)
        return cal.timeInMillis
    }

    fun endOfThisWeek(): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfThisWeek()
        cal.add(Calendar.DAY_OF_YEAR, 6)
        setToEndOfDay(cal)
        return cal.timeInMillis
    }

    fun startOfThisMonth(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        setToStartOfDay(cal)
        return cal.timeInMillis
    }

    fun endOfThisMonth(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        setToEndOfDay(cal)
        return cal.timeInMillis
    }

    /** ISO-ish "YYYY-Www" label for grouping bills by week. */
    fun weekLabel(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.minimalDaysInFirstWeek = 4
        cal.timeInMillis = millis
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        return String.format(Locale.US, "%d-W%02d", year, week)
    }

    /** "YYYY-MM" label for grouping bills by month. */
    fun monthLabel(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return String.format(Locale.US, "%d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun monthDisplayLabel(millis: Long): String {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH)
        return fmt.format(Date(millis))
    }

    private fun setToStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun setToEndOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
    }
}
