package com.billscanner.app.util

import java.time.LocalDate
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Finds a receipt's purchase date in OCR text. Shared by the generic parser
 * and the store templates so both read dates the same way.
 *
 * Built from what real scans showed: dates were missing on most receipts
 * because OCR rarely returns them in a perfectly clean "dd.mm.yyyy" form.
 * Passes run from strictest to most tolerant, and the first plausible hit
 * wins:
 *   1. ISO (2026-10-17), then dd.mm.yyyy and dd.mm.yy exactly as printed
 *   2. OCR look-alikes and loose spacing: "3O.O9.2O26", "30. 09. 2026"
 *   3. Written months, German and English: "7. Oktober 2026", "Oct 7, 2026"
 *   4. A date split across lines ("30.09." then "2026 17:46")
 *
 * A date is "plausible" only if it is a real calendar day, not in the
 * future, and not older than ten years. That rejects misreads such as the
 * year 2028 (a mis-read 2026), 2010, or a coupon's "valid until" date.
 */
object DateFinder {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val TEN_YEARS_MS = 3653L * DAY_MS

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mär" to 3, "mar" to 3, "mae" to 3, "mrz" to 3,
        "apr" to 4, "mai" to 5, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12
    )

    private val isoPattern = Pattern.compile("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")

    // Four-digit year deliberately has no trailing check: OCR often glues the
    // time on ("01.10.202618:51"), and a trailing \b would then never match.
    private val num4Pattern = Pattern.compile("""(?<!\d)(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})""")
    private val num2Pattern = Pattern.compile("""(?<![\d.])(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2})(?!\d)""")

    private const val LOOK = "[0-9OoIl|]"
    private val tolerantPattern = Pattern.compile(
        "(?<![A-Za-z0-9])($LOOK{1,2})\\s{0,2}([.\\-/ ])\\s{0,2}($LOOK{1,2})\\s{0,2}([.\\-/ ])\\s{0,2}($LOOK{2,4})(?![A-Za-z0-9])"
    )

    private val germanMonthPattern = Pattern.compile(
        """(?<!\d)(\d{1,2})\.?\s*([A-Za-zÄÖÜäöüß]{3,9})\.?,?\s*(\d{4})(?!\d)"""
    )
    private val englishMonthPattern = Pattern.compile(
        """([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)"""
    )

    private val splitPattern = Pattern.compile(
        """(?<!\d)(\d{1,2})\s?[.\-/]\s?(\d{1,2})\s?[.\-/]\s?(20\d{2})"""
    )

    /** Returns the purchase date as epoch millis (noon, local time), or null if none is found. */
    fun find(lines: List<String>, nowMillis: Long = System.currentTimeMillis()): Long? {
        // Pass 1: exact formats, in reading order.
        for (line in lines) {
            val iso = isoPattern.matcher(line)
            if (iso.find()) plausible(iso.group(1)!!.toInt(), iso.group(2)!!.toInt(), iso.group(3)!!.toInt(), nowMillis)?.let { return it }
            val n4 = num4Pattern.matcher(line)
            if (n4.find()) plausible(n4.group(3)!!.toInt(), n4.group(2)!!.toInt(), n4.group(1)!!.toInt(), nowMillis)?.let { return it }
            val n2 = num2Pattern.matcher(line)
            if (n2.find()) plausible(2000 + n2.group(3)!!.toInt(), n2.group(2)!!.toInt(), n2.group(1)!!.toInt(), nowMillis)?.let { return it }
        }
        // Pass 2: OCR look-alikes (O for 0, l/I/| for 1) and loose spacing.
        for (line in lines) {
            val m = tolerantPattern.matcher(line)
            while (m.find()) {
                val raw = m.group()
                if (raw.count { it.isDigit() } < 4) continue // too few real digits to trust a "fixed" date
                val sep1 = m.group(2)!!
                val sep2 = m.group(4)!!
                val yearText = m.group(5)!!
                // A two-digit year is only trusted with real punctuation. With
                // plain spaces it is far more likely a clock time: "11.05 24"
                // is 11:05:24, not 11 May 2024 (seen on a go asia receipt).
                if (yearText.length <= 2 && (sep1 == " " || sep2 == " ")) continue
                val d = fixLookalikes(m.group(1)!!).toIntOrNull() ?: continue
                val mo = fixLookalikes(m.group(3)!!).toIntOrNull() ?: continue
                var y = fixLookalikes(yearText).toIntOrNull() ?: continue
                if (y < 100) y += 2000
                plausible(y, mo, d, nowMillis)?.let { return it }
            }
        }
        // Pass 3: written months.
        for (line in lines) {
            val de = germanMonthPattern.matcher(line)
            while (de.find()) {
                val mo = MONTHS[de.group(2)!!.lowercase().take(3)] ?: continue
                plausible(de.group(3)!!.toInt(), mo, de.group(1)!!.toInt(), nowMillis)?.let { return it }
            }
            val en = englishMonthPattern.matcher(line)
            while (en.find()) {
                val mo = MONTHS[en.group(1)!!.lowercase().take(3)] ?: continue
                plausible(en.group(3)!!.toInt(), mo, en.group(2)!!.toInt(), nowMillis)?.let { return it }
            }
        }
        // Pass 4: a date broken across OCR lines.
        val flat = lines.joinToString(" ")
        val sp = splitPattern.matcher(flat)
        while (sp.find()) {
            plausible(sp.group(3)!!.toInt(), sp.group(2)!!.toInt(), sp.group(1)!!.toInt(), nowMillis)?.let { return it }
        }
        return null
    }

    private fun fixLookalikes(s: String): String =
        s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1')

    private fun plausible(year: Int, month: Int, day: Int, nowMillis: Long): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        try {
            LocalDate.of(year, month, day) // throws for 31 Feb etc.
        } catch (e: Exception) {
            return null
        }
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, 12, 0, 0)
        val millis = cal.timeInMillis
        if (millis > nowMillis + DAY_MS) return null      // in the future
        if (millis < nowMillis - TEN_YEARS_MS) return null // implausibly old
        return millis
    }
}
