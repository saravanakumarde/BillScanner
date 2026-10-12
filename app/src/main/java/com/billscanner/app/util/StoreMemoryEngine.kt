package com.billscanner.app.util

import com.billscanner.app.data.db.StoreMemory

/**
 * Learns from the user's manual corrections so the SAME shop's receipts get
 * recognized automatically next time, without needing OCR to correctly read
 * (or even successfully recognize at all) the store's actual brand name.
 *
 * How it works: a small number of lines from the receipt's raw OCR text —
 * its address, phone number, or other short letters-only header lines —
 * are pulled out as "anchors". These are chosen because they're stable:
 * every receipt printed at the same physical shop location repeats the same
 * address/phone lines verbatim, unlike the date, total, or items, which are
 * different on every single receipt. When the user corrects a bill's store
 * name (and/or category), the anchors from THAT receipt are saved alongside
 * the correction. On a future scan, if enough of a saved memory's anchors
 * reappear in the new receipt's raw text, the remembered correction is
 * applied automatically. Everything here runs on-device against text
 * already in the database — no network calls, no ML model.
 */
object StoreMemoryEngine {

    private val addressLikeRegex = Regex("""(?i)(str\.|strasse|straße|street|platz|weg|allee|gasse)""")
    private val phoneLikeRegex = Regex("""[+]?\d[\d /.\-]{6,}\d""")
    private val genericHeaderWords = setOf(
        "KUNDENBELEG", "KASSENBON", "KASSENZETTEL", "QUITTUNG", "RECHNUNG",
        "BELEG", "BON", "EUR", "USD", "GBP"
    )

    private fun normalizedLines(rawText: String): List<String> =
        rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Picks out up to 5 lines from a receipt's raw text that are likely to
     * reappear, unchanged, on every future receipt from the same shop:
     * an address line, a phone number, or another short, letters-heavy
     * header line that isn't a generic receipt-type word (like "Quittung")
     * or something clearly bill-specific (a date, a price, an item name —
     * those live further down and change every time, so only the first 8
     * lines are even considered).
     */
    fun extractAnchorLines(rawText: String): List<String> {
        val lines = normalizedLines(rawText).take(8)
        return lines.filter { line ->
            val upper = line.uppercase()
            if (upper in genericHeaderWords) return@filter false
            if (line.length !in 4..60) return@filter false
            val isAddress = addressLikeRegex.containsMatchIn(line)
            val isPhone = phoneLikeRegex.containsMatchIn(line)
            val letters = line.count { it.isLetter() }
            val digits = line.count { it.isDigit() }
            val isPlainHeaderText = letters >= 4 && digits <= 2
            isAddress || isPhone || isPlainHeaderText
        }.take(5)
    }

    /**
     * Finds a previously-learned store whose anchors sufficiently overlap
     * with this receipt's own anchors. Requires at least half (rounded up,
     * minimum 1) of the saved memory's anchor lines to reappear verbatim,
     * so a single generic line shared by coincidence can't false-match.
     */
    fun findMatch(rawText: String, memories: List<StoreMemory>): StoreMemory? {
        if (memories.isEmpty()) return null
        val candidateLines = normalizedLines(rawText)
        for (memory in memories) {
            val anchorLines = memory.anchorText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (anchorLines.isEmpty()) continue
            val matchCount = anchorLines.count { anchor ->
                candidateLines.any { it.equals(anchor, ignoreCase = true) }
            }
            val threshold = ((anchorLines.size + 1) / 2).coerceAtLeast(1)
            if (matchCount >= threshold) return memory
        }
        return null
    }

    fun buildAnchorText(rawText: String): String? {
        val anchors = extractAnchorLines(rawText)
        return if (anchors.isEmpty()) null else anchors.joinToString("\n")
    }
}
