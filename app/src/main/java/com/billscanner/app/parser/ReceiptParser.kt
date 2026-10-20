package com.billscanner.app.parser

import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import com.billscanner.app.util.DateFinder

/**
 * Rule-based receipt parser. No AI/cloud calls — pure regex + keyword
 * heuristics, tuned for German supermarket/drugstore receipts (Rewe, Edeka,
 * Aldi, dm, Rossmann, etc.) and generic English receipts.
 *
 * This is intentionally conservative: when unsure, it leaves a field null /
 * flags it as guessed rather than inventing data, so the Review screen can
 * prompt the user to fix it.
 */
class ReceiptParser {

    // ---- Date patterns ----
    // German: 31.12.2025 / 31.12.25 / 31-12-2025
    // Two variants: a 4-digit-year one with no trailing boundary requirement,
    // and a 2..4-digit-year one that does require a trailing boundary.
    // The exact-4-digit variant matters because receipts often print the
    // date immediately followed by a time (e.g. "24.09.2026 18:19:35"); if
    // OCR collapses that space, "2026" is immediately followed by another
    // digit ("1" of "18"), and \b never matches between two digits — so a
    // pattern that *requires* a trailing \b would silently fail to find a
    // date that is plainly there. Capping the year group at exactly 4
    // digits makes the match unambiguous without needing to know what
    // (if anything) follows it.
    private val dateDe4YearPattern = Pattern.compile(
        """(?<!\d)(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})"""
    )
    private val dateDePattern = Pattern.compile(
        """\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2,4})\b"""
    )
    // ISO: 2025-12-31
    private val dateIsoPattern = Pattern.compile(
        """\b(\d{4})-(\d{1,2})-(\d{1,2})\b"""
    )

    // ---- Total amount keywords (German + English), longest/most specific first ----
    private val totalKeywords = listOf(
        "gesamtsumme", "gesamtbetrag", "zu zahlen", "zu bezahlen", "zahlbetrag", "rechnungsbetrag", "endbetrag",
        "summe", "gesamt", "total amount", "amount due", "total",
        "betrag", "bar", "kartenzahlung", "grand total"
    )

    // Lines to ignore when looking for totals (subtotal / change / tax lines
    // often also contain currency numbers and would otherwise be false hits).
    private val totalExclusionKeywords = listOf(
        "zwischensumme", "subtotal", "rückgeld", "change", "gegeben", "zurück",
        "barzahlung", // "Gegeben/Zurück Barzahlung ..." lines contain "bar"
                      // (a totalKeyword meant for a standalone "BAR 12,34"
                      // cash-total line) as a substring of "Barzahlung" and
                      // were being mistaken for the total line otherwise.
        "mwst", "ust", "vat", "steuer", "tax", "pfand" // deposit handled separately if needed
    )

    // Generic money pattern: 12,34 or 12.34 or 1.234,56 or 1,234.56
    private val moneyPattern = Pattern.compile(
        """(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})\s*(EUR|€|USD|\$|GBP|£)?"""
    )

    // A line that is nothing but an amount (optionally with a currency and a tax letter): "2,59", "EUR 2,59", "2,59 B".
    private val amountOnlyLinePattern = Regex(
        """^(?:EUR\s*|€\s*)?-?\d{1,3}(?:[.,]\d{3})*[.,]\d{2}\s*(?:EUR|€)?\s*[A-Za-z]?$"""
    )

    private val currencySymbolPattern = Pattern.compile("""(€|EUR|\$|USD|£|GBP)""")

    fun parse(rawText: String): ParsedReceipt {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val language = detectLanguage(lines)
        val storeName = detectStoreName(lines)
        val (dateMillis, dateFound) = detectDate(lines)
        val currency = detectCurrency(rawText, language)
        val total = detectTotal(lines)
        val items = detectItems(lines, total)

        return ParsedReceipt(
            storeName = storeName,
            dateMillis = dateMillis,
            dateWasFound = dateFound,
            total = total,
            currency = currency,
            language = language,
            items = items
        )
    }

    // ---------------------------------------------------------------------
    // Language detection
    // ---------------------------------------------------------------------
    private val germanMarkers = listOf(
        "MWST", "UST", "GESAMT", "SUMME", "BETRAG", "ARTIKEL", "STÜCK", "STK",
        "RECHNUNG", "KASSE", "BAR", "KARTE", "DATUM", "ZAHLUNG", "PFAND",
        "STEUER", "NETTO", "BRUTTO"
    )
    private val englishMarkers = listOf(
        "TOTAL", "SUBTOTAL", "TAX", "CASH", "CARD", "RECEIPT", "CHANGE",
        "ITEM", "QTY", "DATE", "PAYMENT"
    )

    private fun detectLanguage(lines: List<String>): String {
        val text = lines.joinToString(" ").uppercase()
        val hasUmlaut = Regex("[ÄÖÜäöüß]").containsMatchIn(text)
        var deScore = germanMarkers.count { text.contains(it) }
        var enScore = englishMarkers.count { text.contains(it) }
        if (hasUmlaut) deScore += 2
        if (KnownRetailers.findKnownName(lines)?.let { KnownRetailers.isGermanRetailer(it) } == true) {
            deScore += 2
        }
        return when {
            deScore == 0 && enScore == 0 -> "unknown"
            deScore >= enScore -> "de"
            else -> "en"
        }
    }

    // ---------------------------------------------------------------------
    // Store name
    // ---------------------------------------------------------------------
    private fun detectStoreName(lines: List<String>): String? {
        KnownRetailers.findKnownName(lines)?.let { return normalizeStoreLabel(it) }

        // Fallback: first non-empty line that isn't purely numeric/symbols
        // and isn't obviously an address (contains digits + street-ish words).
        //
        // Some receipts print a branch/location label directly above its
        // own street address (e.g. "Frauentor" / "Frauentorstraße 44" for a
        // shop location named after the street it's on) rather than the
        // shop's actual brand name — often because the brand name itself is
        // a stylized logo that OCR can't read as text at all, so the first
        // recognizable line ends up being that location label. Such a line
        // is a weak candidate: keep looking for a better one first, and
        // only fall back to it if nothing better turns up in the first 5
        // lines.
        val addressHints = listOf("STR.", "STRASSE", "STREET", "PLATZ", "WEG")
        // Generic receipt-header words that are never a store's actual name
        // (German "customer receipt" / "receipt" / "cash-register slip"),
        // so a line consisting only of one of these should never be
        // returned even if it's otherwise letter-heavy and address-free.
        val genericHeaderWords = listOf(
            "KUNDENBELEG", "KASSENBON", "KASSENZETTEL", "QUITTUNG", "RECHNUNG", "BELEG", "BON"
        )
        var fallbackCandidate: String? = null
        for ((index, line) in lines.take(5).withIndex()) {
            val upper = line.uppercase()
            if (line.length < 3) continue
            if (line.any { it.isDigit() } && addressHints.any { upper.contains(it) }) continue
            if (line.all { !it.isLetter() }) continue
            // A German postal-code-plus-city line, e.g. "86152 Augsburg".
            if (Regex("""^\d{4,5}\s""").containsMatchIn(line)) continue
            if (genericHeaderWords.any { upper == it || upper.trim() == it }) continue
            if (isJunkStoreLine(line)) continue

            val nextLine = lines.getOrNull(index + 1)
            if (nextLine != null && looksLikeStreetAddress(nextLine)) {
                if (fallbackCandidate == null) fallbackCandidate = line
                continue
            }
            return line
        }
        return fallbackCandidate
    }

    // First words that mark a line as a label or greeting, never a shop's name.
    // Taken from names the app actually produced and you then had to fix:
    // "Total", "Quantity", "Receipt", "Farbe 1", "Abt. 1", "SEPA transfer", ...
    private val junkFirstWords = setOf(
        "TOTAL", "QUANTITY", "RECEIPT", "BELEG", "BON", "KASSENBON", "KUNDENBELEG", "QUITTUNG",
        "RECHNUNG", "INVOICE", "SUMME", "ABT", "FARBE", "GRÖSSE", "GROESSE", "DATUM", "DATE", "TIME",
        "UHRZEIT", "ARTIKEL", "MENGE", "ANZAHL", "POSTEN", "PREIS", "PRICE", "EUR", "TEL", "FAX",
        "DANKE", "THANK", "THANKS", "WILLKOMMEN", "WELCOME", "LIEBE", "LIEBER", "HALLO", "HELLO",
        "DEAR", "SEPA", "TRANSFER", "ORDER", "BESTELLUNG", "HOST", "KASSE", "BEDIENER", "KUNDE",
        "SHOP", "MARKT", "FILIALE", "TISCH", "ABHOLNUMMER", "BESTELLART", "BESTELLNUMMER",
        "RECHNUNGSNUMMER", "KUNDENNUMMER", "GESAMT", "FROM", "TO", "AMOUNT"
    )
    private val timeInLineRegex = Regex("""\d{1,2}[:.]\d{2}""")
    private val longNumberRegex = Regex("""\d{5,}""")
    private val priceInLineRegex = Regex("""\d[.,]\d{2}(?!\d)""")

    /** True for lines that can't be a store name: labels, greetings, times, ids, slogans. */
    private fun isJunkStoreLine(line: String): Boolean {
        val trimmed = line.trim()
        if (KnownRetailers.isKnown(trimmed)) return false // short real names such as "dm" or "C&A"
        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }
        if (letters < 3) return true                      // "F 5964", "10:29 9M ."
        if (digits > letters) return true                 // mostly numbers
        if (timeInLineRegex.containsMatchIn(trimmed)) return true
        if (longNumberRegex.containsMatchIn(trimmed)) return true // barcodes, receipt numbers
        if (priceInLineRegex.containsMatchIn(trimmed)) return true // an item line, never a shop name
        if (trimmed.contains(':')) return true            // "Host/Hostess: Toker", "Beleg-Nr.: ..."
        val words = trimmed.split(Regex("""[^A-Za-zÄÖÜäöüß]+""")).filter { it.isNotEmpty() }
        if (words.firstOrNull()?.uppercase() in junkFirstWords) return true
        if (words.count { it.length >= 2 } >= 5) return true // a sentence or slogan, not a name
        return false
    }

    private fun looksLikeStreetAddress(line: String): Boolean {
        val upper = line.uppercase()
        val hasStreetWord = listOf("STR.", "STRASSE", "STREET", "PLATZ", "WEG", "ALLEE", "GASSE")
            .any { upper.contains(it) }
        val endsWithHouseNumber = Regex("""\d+\s*[a-zA-Z]?\s*$""").containsMatchIn(line)
        return hasStreetWord && endsWithHouseNumber
    }

    private fun normalizeStoreLabel(raw: String): String {
        KnownRetailers.displayName(raw)?.let { return it }
        return raw.split(" ").joinToString(" ") { word ->
            if (word.length <= 2) word else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // ---------------------------------------------------------------------
    // Date
    // ---------------------------------------------------------------------
    private fun detectDate(lines: List<String>): Pair<Long?, Boolean> {
        val millis = DateFinder.find(lines)
        return millis to (millis != null)
    }

    private fun toMillisOrNull(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        if (year < 2000 || year > 2100) return null
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---------------------------------------------------------------------
    // Currency
    // ---------------------------------------------------------------------
    private fun detectCurrency(rawText: String, language: String): String {
        val m = currencySymbolPattern.matcher(rawText)
        if (m.find()) {
            return when (m.group(1)) {
                "€", "EUR" -> "EUR"
                "\$", "USD" -> "USD"
                "£", "GBP" -> "GBP"
                else -> "EUR"
            }
        }
        return if (language == "de") "EUR" else "EUR" // default; DACH-focused app
    }

    // ---------------------------------------------------------------------
    // Total
    // ---------------------------------------------------------------------
    private fun detectTotal(lines: List<String>): Double? {
        var best: Double? = null
        // Scan bottom-up: totals are almost always near the end of a receipt.
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val lower = line.lowercase(Locale.GERMAN)
            if (totalExclusionKeywords.any { lower.contains(it) }) continue
            if (totalKeywords.any { lower.contains(it) }) {
                parseMoney(line)?.let { return it }
                // The label can sit on its own line with the amount a line or
                // two further down (OCR splits widely spaced columns), e.g.
                //   SUMME / EUR / 2,59
                for (j in (i + 1)..minOf(i + 3, lines.size - 1)) {
                    val candidate = lines[j].trim()
                    if (candidate.equals("EUR", ignoreCase = true) || candidate == "€") continue
                    if (amountOnlyLinePattern.matches(candidate)) parseMoney(candidate)?.let { return it }
                    break // anything else means this label has no amount of its own
                }
            }
        }
        // Fallback: largest money value anywhere in the last third of the
        // receipt (totals are usually the biggest number near the bottom).
        //
        // This only gets reached when NO keyword-matched line above also had
        // a money value on it — which happens when OCR splits a label from
        // its value onto separate lines (columnar layouts where there's a
        // wide visual gap between them are prone to this). In that case a
        // bare value-only line carries none of the context that would let
        // the exclusion check above skip it, even though the very next or
        // previous line might be an excluded label like "Zurück"/"Gegeben".
        // So each candidate line's immediate neighbors are checked too,
        // not just the line itself.
        val tail = lines.takeLast((lines.size / 3).coerceAtLeast(3))
        val tailStartIndex = lines.size - tail.size
        for ((offset, line) in tail.withIndex()) {
            val globalIndex = tailStartIndex + offset
            val windowLines = ((globalIndex - 2)..(globalIndex + 2)).mapNotNull { lines.getOrNull(it) }
            val neighbors = windowLines.joinToString(" ").lowercase(Locale.GERMAN)
            if (totalExclusionKeywords.any { neighbors.contains(it) }) continue
            parseMoney(line)?.let { value ->
                if (best == null || value > best!!) best = value
            }
        }
        return best
    }

    private fun parseMoney(line: String): Double? {
        val m = moneyPattern.matcher(line)
        var last: Double? = null
        while (m.find()) {
            val raw = m.group(1) ?: continue
            if (isFragmentOfDateOrLongerNumber(line, m.end(1))) continue
            last = normalizeMoneyString(raw)
        }
        return last
    }

    /**
     * True when an amount-shaped match is really part of something else:
     *  - a date: "30.09" inside "30.09.2026" (the day.month of a date looked like
     *    an amount and was picked as the receipt total on five of your bills)
     *  - a longer number: "11.00" inside the TSE timestamp "17:46:11.000", or
     *    "0,84" inside the weight "0,848 kg"
     *  - a clock time written with a dot: "10.29 Uhr"
     */
    private fun isFragmentOfDateOrLongerNumber(line: String, end: Int): Boolean {
        if (end < line.length) {
            val next = line[end]
            if (next.isDigit()) return true
            if ((next == '.' || next == '-' || next == '/') && end + 1 < line.length && line[end + 1].isDigit()) return true
        }
        return line.substring(end).trimStart().startsWith("uhr", ignoreCase = true)
    }

    private fun normalizeMoneyString(raw: String): Double? {
        // German format uses comma as decimal separator, dot as thousands.
        // Heuristic: if there's a comma AND it's followed by exactly 2 digits
        // at the end, treat comma as decimal separator.
        val cleaned = if (raw.contains(",") && raw.substringAfterLast(",").length == 2) {
            raw.replace(".", "").replace(",", ".")
        } else {
            raw.replace(",", "")
        }
        return cleaned.toDoubleOrNull()
    }

    // ---------------------------------------------------------------------
    // Line items
    // ---------------------------------------------------------------------
    // Real receipts use several different layouts for name+price, so we try
    // several strategies and merge results, rather than assuming one fixed
    // format. Observed layouts this now handles:
    //
    // 1) SAME LINE - name and price together, e.g.:
    //      Bananen                                    1,49
    //      2 x Joghurt 0,45                           0,90
    //
    // 2) CODE+PRICE LINE, then NAME on the NEXT line (New Yorker, and
    //    similar fashion/retail receipts):
    //      1 X (06.04.105.1512)                       a 6,99
    //      Crepe Squares Star White
    //
    // 3) NAME-ONLY lines, followed by a separate PRICE-ONLY block, aligned
    //    by position/order (REWE and similar supermarket receipts where the
    //    printed columns don't OCR onto the same text line):
    //      BIO FR.MILCH 3,8
    //      ZAUBERRIEGEL 3J
    //      EUR
    //      1,35 B
    //      0,79 B

    // Same-line: name then price at the end, optional trailing currency.
    private val itemLinePattern = Pattern.compile(
        """^(.{2,60}?)\s+(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})\s*(?:EUR|€)?\s*[A-Za-z]?\s*$"""
    )

    // "1 X (06.04.105.1512)              a 6,99" - quantity + product code +
    // price, with the actual item name expected on the NEXT line. The "a" or
    // "@" before the price is a unit-price marker (often OCR'd from "à").
    private val codeAndPricePattern = Pattern.compile(
        """^\d+\s*[xX×]\s*\([^)]*\)\s*[a@]?\s*(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})\s*$"""
    )

    // A line that is ONLY a price (optionally with a trailing single-letter
    // tax-category code like "B", "A1" etc., and/or a leading "EUR"/"€").
    private val priceOnlyLinePattern = Pattern.compile(
        """^(?:EUR|€)?\s*(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})\s*[A-Za-z]{0,2}\s*$"""
    )

    private val qtyPrefixPattern = Pattern.compile("""^(\d+)\s*[xX×]\s*(.+)$""")
    // German receipts very commonly spell out a piece-count instead of using
    // "x", e.g. "1 STK Wolf-Brezel" or "1 STC ..." (STC is a common OCR
    // misread of "STK"). Recognize that too so a quantity-1 item doesn't
    // get left with a "1 STK " prefix stuck in its name (or, worse, fail
    // to be recognized as a name line at all upstream).
    private val qtyUnitPrefixPattern = Pattern.compile(
        """^(\d+)\s*(?:STK|STC|STÜCK|STUECK|ST)\.?\s+(.+)$""",
        Pattern.CASE_INSENSITIVE
    )

    // Product-code fragments like "(06.04.105.1512)" that sometimes leak
    // onto a name line and should be stripped rather than kept in the name.
    private val productCodePattern = Pattern.compile("""\(\s*[\d.]+\s*\)""")

    // A leftover per-unit price fragment at the end of a name, e.g. the
    // "0,45" in "Joghurt 0,45" after the leading "2 x" quantity is removed.
    private val trailingPriceFragmentPattern = Pattern.compile(
        """\s+\d{1,3}(?:[.,]\d{3})*[.,]\d{2}\s*$"""
    )

    private val skipLineKeywords = totalKeywords + totalExclusionKeywords + listOf(
        "danke", "thank you", "kassenbon", "beleg", "kundenkarte", "bonnummer",
        "uhrzeit", "kassierer", "trace", "terminal", "folgenr", "tse-",
        "vielen dank", "öffnungszeiten", "www.", "http", "geschäft", "bon-nr",
        "kasse:", "markt:", "bed.:", "signatur", "transaktion", "seriennummer",
        "netto", "brutto", "gesamtbetrag", "umtausch", "warengruppen",
        "geg.", "gegeben", "genehmigungs", "kartenzahlung", "kontaktlos",
        "zahlung erfolgt", "proc-code", "capt.-ref", "aid", "emv-aid",
        "vu-nr", "pan #", "terminal-id", "ta-nr"
    )

    private val dateInLineRegex = Regex("""\b\d{2}[.\-/]\d{2}[.\-/](?:\d{4}|\d{2})\b""")

    private fun isSkippableLine(line: String): Boolean {
        val lower = line.lowercase(Locale.GERMAN)
        if (skipLineKeywords.any { lower.contains(it) }) return true
        if (dateInLineRegex.containsMatchIn(line)) return true
        // Lines that are mostly punctuation/separators (====, ****, ><><, barcodes-as-digits)
        val letterCount = line.count { it.isLetter() }
        if (letterCount == 0 && line.any { !it.isWhitespace() }) return true
        return false
    }

    private fun cleanItemName(raw: String): String {
        var name = productCodePattern.matcher(raw).replaceAll("").trim()
        // Strip a leading "N X" / "N x" or "N STK" / "N STC" quantity prefix
        // if present on a name line.
        val qm = qtyPrefixPattern.matcher(name)
        val qmUnit = qtyUnitPrefixPattern.matcher(name)
        if (qm.matches()) {
            name = qm.group(2)!!.trim()
        } else if (qmUnit.matches()) {
            name = qmUnit.group(2)!!.trim()
        }
        // Strip a leftover trailing per-unit price fragment, e.g. a
        // "2 x Joghurt 0,45" line whose name group still ends in "0,45"
        // after the leading "2 x" was removed above.
        name = trailingPriceFragmentPattern.matcher(name).replaceAll("").trim()
        return name.trim()
    }

    private fun looksLikeNameOnlyLine(line: String): Boolean {
        // Bare currency-code lines (a lone "EUR" header above a price
        // column) look name-like (letters, no digits) but must never be
        // treated as a product name.
        val trimmedUpper = line.trim().uppercase()
        if (trimmedUpper in setOf("EUR", "USD", "GBP", "€", "$", "£")) return false
        if (isSkippableLine(line)) return false
        if (priceOnlyLinePattern.matcher(line).matches()) return false
        if (itemLinePattern.matcher(line).matches()) return false
        if (codeAndPricePattern.matcher(line).matches()) return false
        // Needs at least a couple of letters to be a plausible product name,
        // and not be almost entirely digits (e.g. a lone product code or barcode).
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        return letters >= 2 && letters > digits
    }

    /**
     * Accepts a strict price-only line ("0,98 €") normally, but also a
     * "combined" line like "0,98 €/STCK #121000 0,98 € D" — a per-unit
     * price, product code, and the real total price all printed on one
     * line, which is common on price-tag-style receipts. In that case the
     * name-only line right before it already supplies the item's name, so
     * only the trailing (final) price actually matters here.
     */
    private fun extractPriceFromPriceLikeLine(line: String): Double? {
        val pOnly = priceOnlyLinePattern.matcher(line)
        if (pOnly.matches()) return normalizeMoneyString(pOnly.group(1)!!)
        val pTrailing = itemLinePattern.matcher(line)
        if (pTrailing.matches()) return normalizeMoneyString(pTrailing.group(2)!!)
        return null
    }

    private fun detectItems(lines: List<String>, total: Double?): List<ParsedItem> {
        val items = mutableListOf<ParsedItem>()
        val consumed = BooleanArray(lines.size)

        fun isValidPrice(price: Double?): Boolean {
            if (price == null) return false
            // NOTE: this used to also reject any price that exactly matched
            // the bill's total, to stop a "Summe: 0,98 €" line from being
            // misread as a product. That's already handled by isSkippableLine()
            // via the "summe"/"gesamt"/etc. keywords, and the value-based
            // check had a real cost: on a single-item receipt the one item's
            // price legitimately equals the total, and this was silently
            // discarding it, leaving zero items detected.
            return price > 0.0 && price <= 500.0
        }

        // --- Strategy 2 first (most specific): "N X (code) ... price" line
        // immediately followed by a name-only line. Doing this before the
        // same-line strategy avoids the code+price line being misread as a
        // (garbled) same-line item by strategy 1.
        for (i in lines.indices) {
            if (consumed[i]) continue
            val line = lines[i]
            val m = codeAndPricePattern.matcher(line)
            if (!m.matches()) continue
            val price = normalizeMoneyString(m.group(1)!!) ?: continue
            if (!isValidPrice(price)) continue

            val nextIndex = i + 1
            if (nextIndex < lines.size && !consumed[nextIndex] && looksLikeNameOnlyLine(lines[nextIndex])) {
                val name = cleanItemName(lines[nextIndex])
                if (name.length >= 2) {
                    items.add(ParsedItem(nameOriginal = name, quantity = 1.0, unitPrice = price, totalPrice = price))
                    consumed[i] = true
                    consumed[nextIndex] = true
                }
            }
        }

        // --- Strategy 1: same-line "name ... price".
        for (i in lines.indices) {
            if (consumed[i]) continue
            val line = lines[i]
            if (isSkippableLine(line)) continue

            val m = itemLinePattern.matcher(line)
            if (!m.matches()) continue

            var name = m.group(1)!!.trim()
            val price = normalizeMoneyString(m.group(2)!!) ?: continue
            if (!isValidPrice(price)) continue
            // Some receipts print "unit price / unit  #productcode  total price  taxletter"
            // all on one physical line (e.g. "0,98 €/STCK #121000 0,98 € D").
            // itemLinePattern's non-greedy name group can end up swallowing
            // that whole leading unit-price+code fragment as if it were the
            // item's "name" — recognizable because the captured name group
            // itself still contains another money-like number. That's a
            // mis-split combined line, not a real "name ... price" line, so
            // skip it here rather than record a garbage name.
            if (moneyPattern.matcher(name).find()) continue

            var quantity = 1.0
            var unitPrice: Double? = null
            val qm = qtyPrefixPattern.matcher(name)
            val qmUnit = qtyUnitPrefixPattern.matcher(name)
            if (qm.matches()) {
                quantity = qm.group(1)!!.toDoubleOrNull() ?: 1.0
                name = qm.group(2)!!.trim()
                if (quantity > 0) unitPrice = price / quantity
            } else if (qmUnit.matches()) {
                quantity = qmUnit.group(1)!!.toDoubleOrNull() ?: 1.0
                name = qmUnit.group(2)!!.trim()
                if (quantity > 0) unitPrice = price / quantity
            }
            name = cleanItemName(name)
            if (name.length < 2) continue

            items.add(ParsedItem(nameOriginal = name, quantity = quantity, unitPrice = unitPrice, totalPrice = price))
            consumed[i] = true
        }

        // --- Strategy 3: a run of consecutive name-only lines followed by a
        // run of consecutive price-only lines, of the SAME length, paired by
        // position. This catches printer layouts where item names and prices
        // land in separate visual columns that OCR reads as separate blocks.
        var idx = 0
        while (idx < lines.size) {
            if (consumed[idx] || !looksLikeNameOnlyLine(lines[idx])) {
                idx++
                continue
            }
            // Collect the run of name-only lines.
            val nameStart = idx
            var nameEnd = idx
            while (nameEnd + 1 < lines.size && !consumed[nameEnd + 1] && looksLikeNameOnlyLine(lines[nameEnd + 1])) {
                nameEnd++
            }
            val nameCount = nameEnd - nameStart + 1

            // Immediately after the name run, allow one optional currency-only
            // line (e.g. a lone "EUR" header) before the price run starts.
            var priceStart = nameEnd + 1
            if (priceStart < lines.size && lines[priceStart].trim().equals("EUR", ignoreCase = true)) {
                priceStart++
            }

            var priceEnd = priceStart - 1
            while (priceEnd + 1 < lines.size && !consumed[priceEnd + 1] &&
                extractPriceFromPriceLikeLine(lines[priceEnd + 1]) != null
            ) {
                priceEnd++
            }
            val priceCount = priceEnd - priceStart + 1

            if (nameCount >= 1 && priceCount == nameCount) {
                var allValid = true
                val prices = mutableListOf<Double>()
                for (p in priceStart..priceEnd) {
                    val price = extractPriceFromPriceLikeLine(lines[p])
                    if (!isValidPrice(price)) { allValid = false; break }
                    prices.add(price!!)
                }
                if (allValid && prices.size == nameCount) {
                    for (offset in 0 until nameCount) {
                        val name = cleanItemName(lines[nameStart + offset])
                        if (name.length >= 2) {
                            items.add(
                                ParsedItem(
                                    nameOriginal = name,
                                    quantity = 1.0,
                                    unitPrice = prices[offset],
                                    totalPrice = prices[offset]
                                )
                            )
                        }
                    }
                    for (k in nameStart..priceEnd) consumed[k] = true
                }
            }
            idx = nameEnd + 1
        }

        return items
    }
}
