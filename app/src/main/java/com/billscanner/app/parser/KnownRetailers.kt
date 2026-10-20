package com.billscanner.app.parser

/**
 * Common DACH-region retailers. Used to confidently pick the store name out
 * of the top lines of a receipt (which otherwise might contain an address,
 * VAT number, or slogan before/after the actual store name), and as a
 * secondary language hint (German retailer name => likely German receipt).
 *
 * The second group are shops that actually appeared in this app's own scan
 * history, where the name had to be corrected by hand.
 */
object KnownRetailers {
    val GERMAN_RETAILERS = listOf(
        "REWE", "EDEKA", "ALDI", "ALDI SÜD", "ALDI NORD", "LIDL", "PENNY",
        "NETTO", "KAUFLAND", "REAL", "DM", "DM-DROGERIE", "ROSSMANN",
        "MÜLLER", "MEDIA MARKT", "MEDIAMARKT", "SATURN", "IKEA", "OBI",
        "HORNBACH", "BAUHAUS", "TEDI", "KIK", "C&A", "H&M", "PRIMARK",
        "DECATHLON", "GALERIA", "BÄCKEREI", "APOTHEKE", "GLOBUS", "NORMA",
        "FRISTO", "WOOLWORTH", "TCHIBO", "DEPOT",
        // seen in scan history
        "KFC", "LIEFERANDO", "NEW YORKER", "NEWYORKER", "AMAZON", "ZALANDO",
        "PUNJAB ASIA", "GO ASIA", "MCDONALD", "BURGER KING", "SUBWAY",
        "DOUGLAS", "ANNA APOTHEKE"
    )

    val ENGLISH_RETAILERS = listOf(
        "TESCO", "SAINSBURY", "ASDA", "WALMART", "TARGET", "COSTCO",
        "WHOLE FOODS", "TRADER JOE", "SAFEWAY", "KROGER", "CVS", "WALGREENS",
        "BEST BUY", "HOME DEPOT", "IKEA", "STARBUCKS"
    )

    /** How a matched name should be shown (brand spelling), where plain title-casing would get it wrong. */
    private val DISPLAY_NAMES = mapOf(
        "DM" to "dm", "DM-DROGERIE" to "dm", "KIK" to "KiK", "KFC" to "KFC",
        "C&A" to "C&A", "H&M" to "H&M", "MEDIAMARKT" to "MediaMarkt", "MEDIA MARKT" to "MediaMarkt",
        "NEWYORKER" to "New Yorker", "NEW YORKER" to "New Yorker", "TEDI" to "Tedi",
        "GO ASIA" to "go asia", "PUNJAB ASIA" to "Punjab Asia Shop", "LIEFERANDO" to "Lieferando",
        "MCDONALD" to "McDonald's", "IKEA" to "IKEA", "OBI" to "OBI", "REWE" to "REWE",
        "ROSSMANN" to "ROSSMANN", "AMAZON" to "Amazon", "BURGER KING" to "Burger King",
        "ANNA APOTHEKE" to "Anna Apotheke"
    )

    // Longest names first, so "ANNA APOTHEKE" wins over "APOTHEKE" and "ALDI SÜD" over "ALDI".
    private val ALL_NAMES = (GERMAN_RETAILERS + ENGLISH_RETAILERS).distinct().sortedByDescending { it.length }

    /**
     * Short names ("DM", "OBI", "KIK", "KFC") must stand alone as a word, otherwise
     * "DM" matches inside any line that merely contains those letters.
     */
    private fun lineHasName(upperLine: String, name: String): Boolean {
        if (name.length > 4) return upperLine.contains(name)
        return Regex("(?<![A-ZÄÖÜ0-9])" + Regex.escape(name) + "(?![A-ZÄÖÜ0-9])").containsMatchIn(upperLine)
    }

    fun findKnownName(lines: List<String>, maxLinesToCheck: Int = 6): String? {
        val upperLines = lines.take(maxLinesToCheck).map { it.uppercase() }
        for (line in upperLines) {
            for (name in ALL_NAMES) {
                if (lineHasName(line, name)) return name
            }
        }
        return null
    }

    /** True if the whole line is just a known shop name (used to allow very short real names like "dm"). */
    fun isKnown(line: String): Boolean = line.trim().uppercase() in ALL_NAMES

    fun displayName(matchedName: String): String? = DISPLAY_NAMES[matchedName.uppercase()]

    fun isGermanRetailer(name: String): Boolean =
        GERMAN_RETAILERS.any { name.uppercase().contains(it) }
}
