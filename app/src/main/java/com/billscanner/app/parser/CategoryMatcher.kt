package com.billscanner.app.parser

/**
 * Guesses a Category (by name, matching the default seeded list) from the
 * store name and/or item names, using simple keyword rules in both German
 * and English. This is a best-effort default; the Review screen always lets
 * the user override it before saving.
 */
object CategoryMatcher {

    // Ordered map: category name -> keywords (lowercase) that suggest it.
    // Checked in this order, first match wins, so more specific categories
    // (Pharmacy) are listed before broad catch-alls (Groceries).
    private val rules: List<Pair<String, List<String>>> = listOf(
        "Pharmacy" to listOf(
            "apotheke", "pharmacy", "dm", "rossmann", "drogerie", "tabletten",
            "schmerzmittel", "vitamine", "medikament", "pflaster"
        ),
        "Electronics" to listOf(
            "media markt", "mediamarkt", "saturn", "electronics", "best buy",
            "kabel", "ladegerät", "akku", "handy", "laptop"
        ),
        "Clothing" to listOf(
            "c&a", "h&m", "primark", "clothing", "kleidung", "schuhe", "shoes",
            "jacke", "hose", "shirt"
        ),
        "Dining" to listOf(
            "restaurant", "café", "cafe", "bäckerei", "bakery", "imbiss",
            "starbucks", "pizzeria", "bar", "bistro"
        ),
        "Transport" to listOf(
            "tankstelle", "gas station", "shell", "aral", "esso", "bahn",
            "db ", "deutsche bahn", "taxi", "parkhaus", "parking"
        ),
        "Household" to listOf(
            "ikea", "obi", "hornbach", "bauhaus", "möbel", "furniture",
            "waschmittel", "reiniger", "haushalt"
        ),
        "Groceries" to listOf(
            "rewe", "edeka", "aldi", "lidl", "penny", "netto", "kaufland",
            "real", "globus", "norma", "supermarkt", "supermarket"
        )
    )

    /**
     * @param storeName store name as detected (original or English, doesn't matter)
     * @param itemNames item names (English preferred, for keyword coverage)
     * @return best-guess category name, or "Other" if nothing matched.
     */
    fun guessCategory(storeName: String?, itemNames: List<String>): String {
        val haystack = buildString {
            append(storeName?.lowercase() ?: "")
            append(" ")
            append(itemNames.joinToString(" ") { it.lowercase() })
        }

        for ((category, keywords) in rules) {
            if (keywords.any { haystack.contains(it) }) return category
        }
        return "Other"
    }
}
