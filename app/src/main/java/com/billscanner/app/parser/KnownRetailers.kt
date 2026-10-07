package com.billscanner.app.parser

/**
 * Common DACH-region retailers. Used to confidently pick the store name out
 * of the top lines of a receipt (which otherwise might contain an address,
 * VAT number, or slogan before/after the actual store name), and as a
 * secondary language hint (German retailer name => likely German receipt).
 */
object KnownRetailers {
    val GERMAN_RETAILERS = listOf(
        "REWE", "EDEKA", "ALDI", "ALDI SÜD", "ALDI NORD", "LIDL", "PENNY",
        "NETTO", "KAUFLAND", "REAL", "DM", "DM-DROGERIE", "ROSSMANN",
        "MÜLLER", "MEDIA MARKT", "MEDIAMARKT", "SATURN", "IKEA", "OBI",
        "HORNBACH", "BAUHAUS", "TEDI", "KIK", "C&A", "H&M", "PRIMARK",
        "DECATHLON", "GALERIA", "BÄCKEREI", "APOTHEKE", "GLOBUS", "NORMA",
        "FRISTO", "WOOLWORTH", "TCHIBO", "DEPOT"
    )

    val ENGLISH_RETAILERS = listOf(
        "TESCO", "SAINSBURY", "ASDA", "WALMART", "TARGET", "COSTCO",
        "WHOLE FOODS", "TRADER JOE", "SAFEWAY", "KROGER", "CVS", "WALGREENS",
        "BEST BUY", "HOME DEPOT", "IKEA", "STARBUCKS"
    )

    fun findKnownName(lines: List<String>, maxLinesToCheck: Int = 6): String? {
        val upperLines = lines.take(maxLinesToCheck).map { it.uppercase() }
        for (line in upperLines) {
            for (name in GERMAN_RETAILERS + ENGLISH_RETAILERS) {
                if (line.contains(name)) return name
            }
        }
        return null
    }

    fun isGermanRetailer(name: String): Boolean =
        GERMAN_RETAILERS.any { name.uppercase().contains(it) }
}
