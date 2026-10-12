package com.billscanner.app.data.dict

/**
 * Offline German -> English dictionary for common supermarket / drugstore /
 * receipt terms. Free, no network, no API key. Deliberately not exhaustive —
 * unmatched terms are left as-is in English fields and the original German
 * text is always preserved and shown alongside (per app requirement).
 *
 * Matching is case-insensitive and tries, in order:
 *  1. Exact full-string match against DICTIONARY
 *  2. Longest-substring-token match (multi-word entries first)
 *  3. No match -> original text returned unchanged
 *
 * To extend coverage: just add more "german" to "english" entries below.
 */
object GermanEnglishDictionary {

    val DICTIONARY: Map<String, String> = mapOf(
        // --- Common receipt/UI words ---
        "summe" to "Total", "gesamt" to "Total", "gesamtsumme" to "Grand total",
        "gesamtbetrag" to "Total amount", "zu zahlen" to "Amount due",
        "zwischensumme" to "Subtotal", "rückgeld" to "Change", "gegeben" to "Given",
        "bar" to "Cash", "kartenzahlung" to "Card payment", "ec-karte" to "EC card",
        "mwst" to "VAT", "ust" to "VAT", "steuer" to "Tax", "netto" to "Net",
        "brutto" to "Gross", "rechnung" to "Invoice", "kassenbon" to "Receipt",
        "beleg" to "Receipt", "kundenkarte" to "Loyalty card", "datum" to "Date",
        "uhrzeit" to "Time", "kassierer" to "Cashier", "kasse" to "Register",
        "pfand" to "Deposit", "artikel" to "Item", "stück" to "piece",
        "stk" to "pc", "menge" to "Quantity", "preis" to "Price",
        "einzelpreis" to "Unit price", "gesamtpreis" to "Total price",
        "vielen dank" to "Thank you", "danke" to "Thank you",
        "öffnungszeiten" to "Opening hours",

        // --- Dairy / fridge ---
        "milch" to "Milk", "vollmilch" to "Whole milk", "frischmilch" to "Fresh milk",
        "butter" to "Butter", "margarine" to "Margarine", "käse" to "Cheese",
        "quark" to "Quark", "joghurt" to "Yogurt", "sahne" to "Cream",
        "schlagsahne" to "Whipping cream", "eier" to "Eggs", "ei" to "Egg",

        // --- Bakery ---
        "brot" to "Bread", "brötchen" to "Bread rolls", "toast" to "Toast bread",
        "kuchen" to "Cake", "vollkornbrot" to "Wholegrain bread",

        // --- Fruit & veg ---
        "apfel" to "Apple", "äpfel" to "Apples", "banane" to "Banana",
        "bananen" to "Bananas", "orange" to "Orange", "orangen" to "Oranges",
        "tomate" to "Tomato", "tomaten" to "Tomatoes", "gurke" to "Cucumber",
        "zwiebel" to "Onion", "zwiebeln" to "Onions", "kartoffel" to "Potato",
        "kartoffeln" to "Potatoes", "salat" to "Lettuce/Salad", "karotte" to "Carrot",
        "karotten" to "Carrots", "paprika" to "Bell pepper", "zitrone" to "Lemon",
        "knoblauch" to "Garlic", "pilze" to "Mushrooms", "spinat" to "Spinach",

        // --- Meat / fish ---
        "hähnchen" to "Chicken", "hühnchen" to "Chicken", "rindfleisch" to "Beef",
        "schweinefleisch" to "Pork", "hackfleisch" to "Ground meat",
        "wurst" to "Sausage", "schinken" to "Ham", "speck" to "Bacon",
        "lachs" to "Salmon", "fisch" to "Fish", "thunfisch" to "Tuna",

        // --- Pantry / drinks ---
        "wasser" to "Water", "mineralwasser" to "Mineral water", "saft" to "Juice",
        "kaffee" to "Coffee", "tee" to "Tea", "zucker" to "Sugar", "salz" to "Salt",
        "mehl" to "Flour", "reis" to "Rice", "nudeln" to "Pasta", "öl" to "Oil",
        "olivenöl" to "Olive oil", "essig" to "Vinegar", "honig" to "Honey",
        "marmelade" to "Jam", "müsli" to "Muesli", "cornflakes" to "Cornflakes",
        "schokolade" to "Chocolate", "kekse" to "Biscuits", "chips" to "Chips",
        "bier" to "Beer", "wein" to "Wine",

        // --- Household ---
        "toilettenpapier" to "Toilet paper", "küchenrolle" to "Paper towels",
        "waschmittel" to "Laundry detergent", "spülmittel" to "Dish soap",
        "müllbeutel" to "Trash bags", "reiniger" to "Cleaner", "seife" to "Soap",

        // --- Personal care / pharmacy ---
        "shampoo" to "Shampoo", "duschgel" to "Shower gel", "zahnpasta" to "Toothpaste",
        "zahnbürste" to "Toothbrush", "deo" to "Deodorant", "creme" to "Cream (cosmetic)",
        "tabletten" to "Tablets", "schmerzmittel" to "Painkillers",
        "vitamine" to "Vitamins", "pflaster" to "Band-aids",

        // --- Store / place words often on receipts ---
        "filiale" to "Branch", "markt" to "Market", "drogerie" to "Drugstore",
        "apotheke" to "Pharmacy", "bäckerei" to "Bakery", "metzgerei" to "Butcher shop"
    )

    /**
     * Translate a phrase using the offline dictionary. Tries an exact match
     * first, then attempts word-by-word translation for multi-word phrases,
     * leaving unmatched words as-is. Returns null if nothing at all matched
     * (caller should then fall back to showing the original text only).
     */
    fun translate(original: String): String? {
        val trimmed = original.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        DICTIONARY[lower]?.let { return it }

        // Try progressively shorter leading phrases (handles "Bio Vollmilch 1L"
        // style names where extra words surround a known term).
        val words = trimmed.split(Regex("\\s+"))
        var anyMatched = false
        val translatedWords = words.map { word ->
            val cleanWord = word.trim().trimEnd(',', '.', ':').lowercase()
            val hit = DICTIONARY[cleanWord]
            if (hit != null) {
                anyMatched = true
                hit
            } else {
                word
            }
        }
        return if (anyMatched) translatedWords.joinToString(" ") else null
    }
}
