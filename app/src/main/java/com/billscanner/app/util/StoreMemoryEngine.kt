package com.billscanner.app.util

import com.billscanner.app.data.db.StoreMemory

/**
 * Learns from the user's manual corrections so the SAME shop's receipts get
 * recognized automatically next time, without needing OCR to read the
 * store's brand name correctly (or at all).
 *
 * How it works: when the user corrects a bill's store name, a few lines from
 * that receipt's raw OCR text are saved as "anchors": lines that will be
 * printed identically on every future receipt from that shop. On a later scan,
 * if enough of a saved memory's anchors reappear, the correction is applied.
 * All on-device, no network, no ML.
 *
 * What makes a good anchor (learned from real scan history, where almost
 * every saved anchor was useless): a stable identifier of the SHOP, such as
 * its VAT or tax number, phone number, street address, postcode + city,
 * company line ("... GmbH & Co. KG") or its own name. What makes a bad one:
 * anything that belongs to the PURCHASE rather than the shop: items, totals,
 * dates, "Summe", "Geg. VISA", "Steuer %". Those either differ on every
 * receipt (so never match again) or appear on every shop's receipts (so they
 * would match the wrong shop). Lines are therefore scored, all lines are
 * considered (OCR line order is not top-to-bottom), and only strong ones kept.
 */
object StoreMemoryEngine {

    private const val MAX_ANCHORS = 6
    private const val MAX_STORED_ANCHORS = 10
    private const val MIN_ANCHOR_SCORE = 4
    /** One matching anchor this strong (a VAT number, say) is enough on its own. */
    private const val STRONG_ENOUGH_ALONE = 10

    // ---- what a shop-identifying line looks like ----
    private val vatRegex = Regex("""(?i)\b[A-Z]{2}\s?[0-9OIl|]{9,11}\b""")      // DE812706034, also OCR'd as DEl47042129
    private val steuerNrRegex = Regex("""(?i)steuer-?\s?nr\.?\s*:?\s*[\d/ ]{6,}""")
    private val phoneLikeRegex = Regex("""[+]?\d[\d /.\-|]{6,}\d""")
    // Whole words only: "Bestellung" contains "tel" but is not a phone label.
    private val phoneLabelRegex = Regex("""(?i)\b(tel|telefon|fon|fax|mobil|handy)\b""")
    private val addressWordRegex = Regex("""(?i)(str\.|strasse|straße|street|platz|allee|gasse|weg)(?![A-Za-z])""")
    private val postalCityRegex = Regex("""(?<!\d)\d{5}\s+[A-Za-zÄÖÜäöüß]{3,}""")
    // KG/AG only in capitals: a lowercase "kg" is a weight ("kg Must."), not a company form.
    private val companyRegex = Regex("""\b(?i:gmbh|ohg|ltd|inc)\b|\b(?:AG|KG)\b|\bCo\.""")
    // An address printed under these labels is the CUSTOMER's, not the shop's.
    private val customerAddressLabelRegex = Regex(
        """(?i)(rechnungsadresse|lieferadresse|versandadresse|billing address|shipping address|delivery address|invoice address|bill to|ship to)"""
    )
    private val urlRegex = Regex("""(?i)(www\.|https?://|@|\.(de|com|net|eu)\b)""")

    // ---- what is never a shop identifier ----
    private val moneyRegex = Regex("""\d[.,]\d{2}(?!\d)""")
    private val dateRegex = Regex("""\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}""")
    private val timeRegex = Regex("""\d{1,2}:\d{2}""")
    private val longDigitsRegex = Regex("""\d{10,}""")

    /** Words that belong to the purchase/payment, not the shop. A line containing one is rejected unless it also carries a VAT/tax number. */
    private val genericTokens = setOf(
        "summe", "total", "zwischensumme", "posten", "beleg", "steuer", "mwst", "brutto", "netto",
        "visa", "mastercard", "girocard", "ec", "bar", "gegeben", "rückgeld", "rueckgeld", "kasse",
        "bon", "datum", "uhrzeit", "danke", "artikel", "stk", "menge", "quantity", "anzahl", "eur",
        "kundenbeleg", "tse", "signatur", "transaktion", "zahlung", "kartenzahlung", "contactless",
        "trace", "terminal", "geg", "digitaler", "wechselgeld", "mehrwertsteuer", "preis", "gesamt",
        "betrag", "zahlart", "kassierer", "bediener", "summary", "receipt", "tax", "vat", "cash",
        "card", "change", "thank", "zu", "bezahlen"
    )

    private fun normalizedLines(rawText: String): List<String> =
        rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("""[^a-zäöüß]+""")).filter { it.isNotEmpty() }

    /** OCR often reads 1 as l/I/| and 0 as O; map them back before comparing numbers. */
    private fun digitsLoose(s: String): String =
        s.map { when (it) { 'l', 'I', '|' -> '1'; 'O', 'o' -> '0'; else -> it } }.filter { it.isDigit() }.joinToString("")

    private fun levenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * A phone number: 8-15 digits in the phone-shaped part of the line, with a
     * separator, a leading +, or a "Tel"/"Fon" label. A bare run of digits is
     * an article or order number ("04942868", "Bestellnummer: 332603840").
     */
    private fun looksLikePhone(line: String): Boolean {
        val m = phoneLikeRegex.find(line) ?: return false
        if (digitsLoose(m.value).length !in 8..15) return false
        val hasSeparator = m.value.any { it == ' ' || it == '/' || it == '.' || it == '-' || it == '|' } || m.value.startsWith("+")
        return hasSeparator || phoneLabelRegex.containsMatchIn(line)
    }

    private fun nameMatches(line: String, storeKey: String): Boolean {
        if (storeKey.isEmpty()) return false
        val lineKey = key(line)
        if (lineKey.isEmpty()) return false
        if (storeKey.length < 3) return lineKey == storeKey || lineKey.startsWith(storeKey)
        if (lineKey.contains(storeKey)) return true
        if (storeKey.contains(lineKey) && lineKey.length >= 4) return true
        return storeKey.length >= 5 && levenshtein(lineKey, storeKey, 1) <= 1
    }

    /**
     * How strongly [line] identifies a SHOP (0 = not at all). [index] is the line's
     * position in the receipt text (a small bonus for being near the top);
     * [storeKey] is key() of the store's name, so the shop's own name line scores high.
     */
    private fun anchorScore(line: String, index: Int, storeKey: String, ignoreAddress: Boolean = false): Int {
        if (line.length !in 4..70) return 0
        if (moneyRegex.containsMatchIn(line) || dateRegex.containsMatchIn(line) ||
            timeRegex.containsMatchIn(line) || longDigitsRegex.containsMatchIn(line)
        ) return 0

        var score = 0
        if (nameMatches(line, storeKey)) score += 9
        if (vatRegex.containsMatchIn(line)) score += 10
        if (steuerNrRegex.containsMatchIn(line)) score += 9
        if (looksLikePhone(line)) score += 6
        if (!ignoreAddress) {
            if (postalCityRegex.containsMatchIn(line)) score += 6
            if (addressWordRegex.containsMatchIn(line) && line.any { it.isDigit() }) score += 6
        }
        if (companyRegex.containsMatchIn(line)) score += 4
        if (urlRegex.containsMatchIn(line)) score += 4
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        if (letters >= 4 && digits == 0 && tokens(line).size <= 4 && index < 6) score += 2

        // Payment/purchase wording disqualifies a line, unless it carries a VAT/tax number.
        if (score < 9 && tokens(line).any { it in genericTokens }) return 0
        return score
    }

    /**
     * Picks up to [MAX_ANCHORS] lines from a receipt's raw text that identify
     * the shop, strongest first. [storeName] (the name the user settled on)
     * lets the shop's own name line count as an anchor. Returns empty when
     * nothing on the receipt is stable enough to recognise the shop by.
     */
    fun extractAnchorLines(rawText: String, storeName: String? = null): List<String> {
        val lines = normalizedLines(rawText)
        val storeKey = storeName?.let { key(it) } ?: ""
        // Invoices and order mails print the customer's own address ("Rechnungsadresse").
        // It identifies the buyer, not the shop, so no address is learned from them.
        val hasCustomerAddress = lines.any { customerAddressLabelRegex.containsMatchIn(it) }
        val seen = mutableSetOf<String>()
        return lines.mapIndexed { i, line -> Triple(i, line, anchorScore(line, i, storeKey, ignoreAddress = hasCustomerAddress)) }
            .filter { it.third >= MIN_ANCHOR_SCORE }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .filter { seen.add(key(it.second)) }
            .take(MAX_ANCHORS)
            .map { it.second }
    }

    /** Worth saving only if at least one anchor clearly identifies a shop (name, VAT/tax no., phone or address); a web address or company suffix alone is not enough. */
    fun isLearnable(anchors: List<String>, storeName: String): Boolean {
        val storeKey = key(storeName)
        return anchors.any { anchorScore(it, 99, storeKey) >= 6 }
    }

    fun buildAnchorText(rawText: String, storeName: String? = null): String? {
        val anchors = extractAnchorLines(rawText, storeName)
        return if (anchors.isEmpty()) null else anchors.joinToString("\n")
    }

    /** Anchor lines (as key()) that appear under two or more different shop names: they identify neither. */
    private fun sharedAnchorKeys(memories: List<StoreMemory>): Set<String> {
        val shops = mutableMapOf<String, MutableSet<String>>()
        memories.forEach { m -> anchorsOf(m).forEach { shops.getOrPut(key(it)) { mutableSetOf() }.add(key(m.correctedStoreName)) } }
        return shops.filterValues { it.size >= 2 }.keys
    }

    private fun anchorsOf(memory: StoreMemory): List<String> =
        memory.anchorText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** Numbers a line carries (VAT id, tax number, phone), as digit strings, for OCR-tolerant comparison. */
    private fun numericKeys(s: String): List<String> {
        val keys = mutableListOf<String>()
        vatRegex.findAll(s).forEach { keys.add(digitsLoose(it.value.filter { c -> !c.isWhitespace() }.drop(2))) }
        steuerNrRegex.findAll(s).forEach { keys.add(it.value.filter { c -> c.isDigit() }) }
        phoneLikeRegex.findAll(s).forEach { keys.add(digitsLoose(it.value)) }
        return keys.filter { it.length >= 8 }
    }

    /** Same printed line, allowing for OCR noise (a wrong character, a split or glued word). */
    private fun sameLine(anchor: String, candidate: String): Boolean {
        val ka = key(anchor)
        val kb = key(candidate)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        val na = numericKeys(anchor)
        if (na.isNotEmpty()) {
            val nb = numericKeys(candidate)
            if (na.any { a -> nb.any { b -> a == b || (a.length == b.length && levenshtein(a, b, 1) <= 1) } }) return true
        }
        if (ka.length >= 8) {
            if (kb.contains(ka)) return true
            val limit = maxOf(1, ka.length / 8)
            if (levenshtein(ka, kb, limit) <= limit) return true
        }
        return false
    }

    /**
     * Finds the best learned store for this receipt. Only each memory's STRONG
     * anchors count (older memories saved before this rule hold generic lines
     * such as "Summe:" or "Geg. VISA"; those are ignored so they can't match
     * the wrong shop). A memory matches if one very strong anchor (VAT number)
     * reappears, or at least half of its usable anchors do.
     */
    fun findMatch(rawText: String, memories: List<StoreMemory>): StoreMemory? {
        if (memories.isEmpty()) return null
        val candidateLines = normalizedLines(rawText)
        val shared = sharedAnchorKeys(memories)
        var best: StoreMemory? = null
        var bestStrength = -1
        for (memory in memories) {
            val storeKey = key(memory.correctedStoreName)
            val usable = anchorsOf(memory)
                .filter { key(it) !in shared }
                .map { it to anchorScore(it, 99, storeKey) }
                .filter { it.second >= MIN_ANCHOR_SCORE }
            if (usable.isEmpty()) continue
            val matched = usable.filter { (anchor, _) -> candidateLines.any { sameLine(anchor, it) } }
            val strength = matched.sumOf { it.second }
            val need = if (usable.size <= 2) usable.size else (usable.size + 1) / 2
            if (strength >= STRONG_ENOUGH_ALONE || matched.size >= need) {
                val better = best == null || strength > bestStrength ||
                    (strength == bestStrength && memory.timesConfirmed > (best?.timesConfirmed ?: 0))
                if (better) { best = memory; bestStrength = strength }
            }
        }
        return best
    }

    fun sameStore(a: String, b: String): Boolean = key(a) == key(b) && key(a).isNotEmpty()

    /** Unions [newAnchors] into an existing memory's anchors, keeping only strong ones (max [MAX_STORED_ANCHORS]). */
    fun mergeAnchorText(existingText: String, newAnchors: List<String>, storeName: String, excludeKeys: Set<String> = emptySet()): String {
        val storeKey = key(storeName)
        val existing = existingText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<String>()
        val strong = (newAnchors + existing)
            .filter { key(it) !in excludeKeys && seen.add(key(it)) }
            .map { it to anchorScore(it, 99, storeKey) }
            .filter { it.second >= MIN_ANCHOR_SCORE }
            .sortedByDescending { it.second }
            .take(MAX_STORED_ANCHORS)
            .map { it.first }
        return (if (strong.isEmpty()) newAnchors.take(MAX_ANCHORS) else strong).joinToString("\n")
    }

    data class ConsolidationPlan(val updates: List<StoreMemory>, val deleteIds: List<Long>)

    /**
     * Merges memories that are the same shop saved several times (the old
     * learning created a new entry on almost every correction). Per shop name:
     * the most recently used entry survives, anchors are united (strong ones
     * only), confirmation counts are added up, and the rest are deleted.
     */
    fun consolidate(memories: List<StoreMemory>): ConsolidationPlan {
        val updates = mutableListOf<StoreMemory>()
        val deletes = mutableListOf<Long>()
        val shared = sharedAnchorKeys(memories)
        memories.groupBy { key(it.correctedStoreName) }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val sorted = group.sortedByDescending { it.lastUsedMillis }
            val keeper = sorted.first()
            val mergedAnchors = sorted.flatMap { anchorsOf(it) }
            updates.add(
                keeper.copy(
                    anchorText = mergeAnchorText(keeper.anchorText, mergedAnchors, keeper.correctedStoreName, shared),
                    timesConfirmed = group.sumOf { it.timesConfirmed }
                )
            )
            sorted.drop(1).forEach { deletes.add(it.id) }
        }
        return ConsolidationPlan(updates, deletes)
    }
}
