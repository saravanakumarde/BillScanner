package com.billscanner.app.parser.template

import java.util.Calendar
import java.util.regex.Pattern

/**
 * Finds which (if any) known [ReceiptTemplate] a piece of OCR text belongs
 * to, and extracts its fields using that template's rules. This is the
 * "first mechanism" referred to in the Review screen: checked before the
 * generic [com.billscanner.app.parser.ReceiptParser], which always still
 * runs too as a fallback/comparison (see
 * [com.billscanner.app.ui.review.ReviewActivity] for how both are shown).
 */
object TemplateReceiptMatcher {

    /** Returns the first template whose [ReceiptTemplate.identifierLines] all appear in [rawText], or null. */
    fun findMatch(rawText: String, templates: List<ReceiptTemplate>): ReceiptTemplate? {
        val haystack = rawText.uppercase()
        return templates.firstOrNull { template ->
            template.identifierLines.all { identifier -> haystack.contains(identifier.uppercase()) }
        }
    }

    fun extract(rawText: String, template: ReceiptTemplate): TemplateParsedReceipt {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val (total, totalLineIndices) = extractFieldWithLineIndices(lines, template.totalRule)
        val dateMillis = extractDate(lines, template.dateRule)
        val items = extractItems(lines, template.itemsRule, totalLineIndices)

        return TemplateParsedReceipt(
            templateId = template.id,
            storeName = template.displayName,
            dateMillis = dateMillis,
            total = total,
            currency = template.currency,
            language = template.language,
            items = items
        )
    }

    // ---------------------------------------------------------------------
    // Single-value fields (total)
    // ---------------------------------------------------------------------
    // Tries a same-line match first (the normal case: "SUMME   EUR   2,59"
    // is one printed/OCR'd line). If a template's label and amount land on
    // separate OCR lines — which happens when there's a wide gap between
    // the two printed columns — falls back to pairing the label line with
    // the very next line, if that next line is itself just a bare amount.
    //
    // Also returns which line index/indices supplied the total, so item
    // extraction can be told to skip them — otherwise a bare "EUR 2,59"
    // total-amount line (no "SUMME" text of its own) looks exactly like a
    // same-line item and would be double-counted as a product called "EUR".
    private fun extractFieldWithLineIndices(lines: List<String>, rule: TemplateFieldRule): Pair<Double?, Set<Int>> {
        val pattern = Pattern.compile(rule.linePattern, Pattern.CASE_INSENSITIVE)
        val bareAmountPattern = Pattern.compile(
            """^(?:EUR\s+)?(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})\s*$""", Pattern.CASE_INSENSITIVE
        )
        val labelOnly = Pattern.compile(
            rule.linePattern.substringBefore(".*?"), Pattern.CASE_INSENSITIVE
        )
        for ((index, line) in lines.withIndex()) {
            val m = pattern.matcher(line)
            if (m.find() && m.groupCount() >= 1) {
                normalizeMoney(m.group(1)!!)?.let { return it to setOf(index) }
            }
            if (labelOnly.matcher(line).find()) {
                val next = lines.getOrNull(index + 1) ?: continue
                val bm = bareAmountPattern.matcher(next)
                if (bm.find()) {
                    normalizeMoney(bm.group(1)!!)?.let { return it to setOf(index, index + 1) }
                }
            }
        }
        return null to emptySet()
    }

    private fun extractDate(lines: List<String>, rule: TemplateFieldRule): Long? {
        val pattern = Pattern.compile(rule.linePattern, Pattern.CASE_INSENSITIVE)
        for (line in lines) {
            val m = pattern.matcher(line)
            if (m.find() && m.groupCount() >= 3) {
                val day = m.group(1)?.toIntOrNull() ?: continue
                val month = m.group(2)?.toIntOrNull() ?: continue
                var year = m.group(3)?.toIntOrNull() ?: continue
                if (year < 100) year += 2000
                if (month !in 1..12 || day !in 1..31) continue
                val cal = Calendar.getInstance()
                cal.set(year, month - 1, day, 12, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun normalizeMoney(raw: String): Double? {
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
    private fun extractItems(
        lines: List<String>,
        rule: TemplateItemsRule,
        preConsumedIndices: Set<Int> = emptySet()
    ): List<TemplateParsedItem> {
        val consumed = BooleanArray(lines.size) { preConsumedIndices.contains(it) }
        return when (rule.itemLayout) {
            ItemLayout.SPLIT_OR_SAME_LINE -> extractItemsSplitOrSameLine(lines, rule, consumed)
            ItemLayout.SAME_LINE_ONLY -> extractItemsSameLineOnly(lines, rule, consumed)
            ItemLayout.PRICE_THEN_NAME -> extractItemsPriceThenName(lines, rule, consumed)
        }
    }

    /** See [TemplateItemsRule.namePatternCaseSensitive] for why this isn't always [Pattern.CASE_INSENSITIVE]. */
    private fun compileNamePattern(rule: TemplateItemsRule): Pattern =
        if (rule.namePatternCaseSensitive) {
            Pattern.compile(rule.namePattern)
        } else {
            Pattern.compile(rule.namePattern, Pattern.CASE_INSENSITIVE)
        }

    private fun isExcluded(line: String, exclusions: List<String>): Boolean {
        val lower = line.lowercase()
        return exclusions.any { lower.contains(it.lowercase()) }
    }

    // A lone "EUR" (or similar bare currency code) line, printed as a
    // column header above a block of prices, must never be treated as a
    // product name — even though it's otherwise letter-only and would
    // otherwise pass a typical namePattern.
    private fun isBareCurrencyLine(line: String): Boolean =
        line.trim().uppercase() in setOf("EUR", "USD", "GBP", "€", "$", "£")

    // REWE (and most German supermarket till receipts) print each item in
    // one of two ways depending on how wide the name is and how OCR's
    // column reader happens to split it:
    //
    //   (a) SAME LINE:  "EDELSALAMI          EUR 1,39 B"
    //   (b) SPLIT:       "TRAUBE HELL KL"   then, later,   "1,00 B"
    //
    // So both are handled: a same-line match is taken immediately; any
    // name-only line left over is paired positionally with a later
    // price-only line, same as the generic parser's "Strategy 3" (which
    // this logic is a more tightly-scoped, store-specific version of).
    private fun extractItemsSplitOrSameLine(
        lines: List<String>,
        rule: TemplateItemsRule,
        consumed: BooleanArray
    ): List<TemplateParsedItem> {
        // The optional trailing tax-class letter REQUIRES a preceding
        // space ("1,59 B", never "1,59B"). This is what tells apart a real
        // "price + tax class" line from a product name that itself ends in
        // a unit-suffixed quantity glued to a number, e.g. "WEIN ROT
        // 0,75L" (a 0.75-litre wine) or "MILCH 3,8%" — without the space
        // requirement, "0,75" + "L" looks identical in shape to a price
        // plus tax-class letter, and the bottle's own name gets wrongly
        // split into a fake product called "WEIN ROT" priced at €0.75.
        val sameLinePattern = Pattern.compile(
            """^(.{2,40}?)\s+(?:EUR\s+)?(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?:\s+([A-Za-z]))?\s*$"""
        )
        val namePattern = compileNamePattern(rule)
        val pricePattern = Pattern.compile(rule.pricePattern, Pattern.CASE_INSENSITIVE)

        val items = mutableListOf<TemplateParsedItem>()
        val pendingNames = mutableListOf<String>()

        fun flushPendingAgainst(prices: List<Pair<String?, Double>>) {
            // Pair positionally: oldest pending name with oldest collected price.
            val count = minOf(pendingNames.size, prices.size)
            for (k in 0 until count) {
                val name = pendingNames[pendingNames.size - count + k]
                val (taxClass, price) = prices[k]
                items.add(TemplateParsedItem(name = name, price = price, taxClass = taxClass))
            }
            pendingNames.clear()
        }

        var i = 0
        val collectedPrices = mutableListOf<Pair<String?, Double>>()
        while (i < lines.size) {
            if (consumed[i]) { i++; continue }
            val line = lines[i]

            if (isExcluded(line, rule.nameExclusions)) { i++; continue }
            if (isBareCurrencyLine(line)) { i++; continue }

            // (a) same line: name + price (+ optional tax-class letter).
            // The candidate "name" half is also checked against the
            // template's own namePattern (not just length/digit
            // heuristics) so a template's quantity-line or boilerplate
            // exclusions apply here too — otherwise a line like
            // "2 Stk x 2,49" matches this generic same-line shape and gets
            // treated as a product literally named "2 Stk x", bypassing
            // whatever the template's namePattern was written to reject.
            val sameLineMatch = sameLinePattern.matcher(line)
            if (sameLineMatch.matches()) {
                val name = sameLineMatch.group(1)!!.trim()
                val price = normalizeMoney(sameLineMatch.group(2)!!)
                val taxClass = sameLineMatch.group(3)
                val nameLooksValid = namePattern.matcher(name).matches()
                if (price != null && price > 0.0 && price <= 500.0 && name.length >= 2 &&
                    !isExcluded(name, rule.nameExclusions) && nameLooksValid
                ) {
                    items.add(TemplateParsedItem(name = name, price = price, taxClass = taxClass))
                    consumed[i] = true
                    i++
                    continue
                }
            }

            // (b) price-only (+ optional tax-class) line
            val priceMatch = pricePattern.matcher(line)
            if (priceMatch.find()) {
                val price = normalizeMoney(priceMatch.group(1)!!)
                val taxClass = if (priceMatch.groupCount() >= 2) priceMatch.group(2) else null
                if (price != null && price > 0.0 && price <= 500.0) {
                    collectedPrices.add(taxClass to price)
                    consumed[i] = true
                    i++
                    continue
                }
            }

            // (c) name-only line
            val nameMatch = namePattern.matcher(line)
            if (nameMatch.matches()) {
                val letters = line.count { it.isLetter() }
                val digits = line.count { it.isDigit() }
                if (letters >= 2 && letters > digits) {
                    pendingNames.add(line.trim())
                    consumed[i] = true
                }
            }
            i++
        }

        flushPendingAgainst(collectedPrices)
        return items
    }

    // Müller/dm-style: name and price are on the SAME physical line.
    // namePattern and pricePattern are both tried against that one line —
    // not against each other's leftovers — so each pattern must itself be
    // specific enough to reject boilerplate (card blocks, tax tables,
    // totals) by structure, with nameExclusions only as a safety net.
    private fun extractItemsSameLineOnly(
        lines: List<String>,
        rule: TemplateItemsRule,
        consumed: BooleanArray
    ): List<TemplateParsedItem> {
        val namePattern = compileNamePattern(rule)
        val pricePattern = Pattern.compile(rule.pricePattern, Pattern.CASE_INSENSITIVE)
        val items = mutableListOf<TemplateParsedItem>()

        for (i in lines.indices) {
            if (consumed[i]) continue
            val line = lines[i]
            if (isExcluded(line, rule.nameExclusions)) continue

            val nameMatch = namePattern.matcher(line)
            val priceMatch = pricePattern.matcher(line)
            if (!nameMatch.find() || !priceMatch.find()) continue

            val name = nameMatch.group(1)?.trim()
            val price = priceMatch.group(1)?.let { normalizeMoney(it) }
            val taxClass = if (priceMatch.groupCount() >= 2) priceMatch.group(2) else null

            // price == 0.0 is allowed through deliberately: dm's own
            // cancellation pairs (see dm_de template) are a price and its
            // exact negation, and a 500-cap keeps absurd OCR noise out
            // without blocking a legitimate negative/cancelled line.
            if (!name.isNullOrBlank() && price != null && price >= -500.0 && price <= 500.0) {
                items.add(TemplateParsedItem(name = name, price = price, taxClass = taxClass))
                consumed[i] = true
            }
        }
        return items
    }

    // C&A-style: the price (+ tax class) line comes FIRST, then the name
    // line immediately follows. Each price match looks at the very next
    // line for a name; if that line doesn't match namePattern, the price
    // is dropped rather than guessing further ahead (keeps this strategy
    // simple and matches what's actually been seen on real receipts).
    private fun extractItemsPriceThenName(
        lines: List<String>,
        rule: TemplateItemsRule,
        consumed: BooleanArray
    ): List<TemplateParsedItem> {
        val namePattern = compileNamePattern(rule)
        val pricePattern = Pattern.compile(rule.pricePattern, Pattern.CASE_INSENSITIVE)
        val items = mutableListOf<TemplateParsedItem>()

        var i = 0
        while (i < lines.size) {
            if (consumed[i]) { i++; continue }
            val line = lines[i]
            if (isExcluded(line, rule.nameExclusions)) { i++; continue }

            val priceMatch = pricePattern.matcher(line)
            if (priceMatch.find()) {
                val price = priceMatch.group(1)?.let { normalizeMoney(it) }
                val taxClass = if (priceMatch.groupCount() >= 2) priceMatch.group(2) else null
                val nextIndex = i + 1
                val nextLine = lines.getOrNull(nextIndex)
                if (price != null && price > 0.0 && price <= 500.0 && nextLine != null &&
                    !consumed[nextIndex] && !isExcluded(nextLine, rule.nameExclusions)
                ) {
                    val nameMatch = namePattern.matcher(nextLine)
                    if (nameMatch.matches()) {
                        items.add(TemplateParsedItem(name = nextLine.trim(), price = price, taxClass = taxClass))
                        consumed[i] = true
                        consumed[nextIndex] = true
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        return items
    }
}
