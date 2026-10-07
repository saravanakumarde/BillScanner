package com.billscanner.app.parser.template

/**
 * A known, fixed receipt layout for one store/chain, matched by distinctive
 * text that always appears near the top of that store's receipts (address,
 * VAT/UID number, etc. — NOT the store's brand name, since many receipts,
 * REWE included, never actually print their own brand name near the top).
 *
 * This is the "first mechanism": an exact, hand-verified template built
 * from real receipts, checked FIRST. [com.billscanner.app.parser.ReceiptParser]
 * (the generic regex/heuristic engine) always still runs too, as a
 * fallback for anything without a template and as a point of comparison
 * when a template does match (see [TemplateParseResult]).
 *
 * Templates are data, not code (see [ReceiptTemplateStore]) specifically so
 * that a future version of the app can import a larger/updated template
 * set (e.g. built from hundreds of receipts) without needing a new app
 * build for every store added — only the bundled JSON changes.
 */
data class ReceiptTemplate(
    /** Stable id, e.g. "rewe_de". Used as the key in [com.billscanner.app.data.db.Bill] bookkeeping/logs. */
    val id: String,
    /** Display name to show as the recognized store, e.g. "REWE". */
    val displayName: String,
    /**
     * Lines that must ALL appear (case-insensitive substring match) in the
     * receipt's OCR text for this template to match. Pick lines that are
     * printed on EVERY receipt from this store and are unlikely to OCR
     * incorrectly — a VAT/UID number is ideal (fixed-format, no small
     * punctuation-heavy characters to misread), a street address is a
     * reasonable second choice.
     */
    val identifierLines: List<String>,
    /** ISO currency code this template assumes, e.g. "EUR". */
    val currency: String = "EUR",
    /** BCP-47-ish language hint, "de" or "en". */
    val language: String = "de",
    /** How to extract the total amount. */
    val totalRule: TemplateFieldRule,
    /** How to extract the transaction date. */
    val dateRule: TemplateFieldRule,
    /** How to extract line items. */
    val itemsRule: TemplateItemsRule
)

/**
 * A single-value field extracted by regex: the first capture group of the
 * first line matching [linePattern] is the value.
 */
data class TemplateFieldRule(
    val linePattern: String
)

/**
 * How a store's till lays out one line item on the receipt. Different
 * chains print this very differently, so the extraction strategy itself
 * has to be chosen per template, not assumed — see each [ItemLayout] value.
 */
enum class ItemLayout {
    /**
     * REWE-style: the item's name and its price(+tax class) can each land
     * on their own OCR line (split across a name-block then a price-block,
     * paired by position), OR occasionally end up together on one line —
     * both are tried. [TemplateItemsRule.namePattern] matches a name-only
     * line; [TemplateItemsRule.pricePattern] matches a price-only line.
     */
    SPLIT_OR_SAME_LINE,

    /**
     * Müller/dm-style: name and price are always on the SAME physical
     * line, e.g. "1 HEFT A4 16BL LIN.40 0,35 0,35b". Both
     * [TemplateItemsRule.namePattern] and [TemplateItemsRule.pricePattern]
     * are applied to that one line — namePattern's group 1 is the item
     * name, pricePattern's group 1 is the price (group 2 the tax
     * class/code, if present). Lines that match neither pattern are
     * skipped; this layout never pairs separate lines positionally.
     */
    SAME_LINE_ONLY,

    /**
     * C&A-style: the PRICE line comes first, then the NAME line follows
     * immediately after it (the reverse of [SPLIT_OR_SAME_LINE]'s usual
     * name-then-price order). Each matched price line is paired with the
     * very next line that matches namePattern.
     */
    PRICE_THEN_NAME
}

/**
 * Line-item extraction for a specific known layout. See [ItemLayout] for
 * how [namePattern]/[pricePattern] are actually combined — that choice
 * changes what each pattern is matched against (a whole line vs. just the
 * name/price part of it) and how matches are paired into items.
 */
data class TemplateItemsRule(
    val namePattern: String,
    val pricePattern: String,
    /** Defaults to the original REWE-only behavior so existing templates/JSON without this field still work unchanged. */
    val itemLayout: ItemLayout = ItemLayout.SPLIT_OR_SAME_LINE,
    /** Lines containing any of these (case-insensitive) are never treated as an item name, even if they'd otherwise match [namePattern]. */
    val nameExclusions: List<String> = emptyList(),
    /**
     * Whether [namePattern] is matched case-sensitively. Defaults to false
     * (case-insensitive) since most templates don't rely on case. Set to
     * true when a template's namePattern deliberately uses letter case as
     * a filter — e.g. C&A's "only ALL-CAPS lines are product names" rule,
     * which exists specifically to reject mixed-case boilerplate; matching
     * it case-insensitively would silently defeat that filter.
     * [pricePattern] is always matched case-insensitively (its structure —
     * digits, punctuation, a single tax-class letter — doesn't depend on
     * surrounding text case the way a whole-line name filter can).
     */
    val namePatternCaseSensitive: Boolean = false
)

/** One item as extracted by a template, with the tax class the till printed (e.g. "B" = reduced 7% rate in Germany), not just name+price. */
data class TemplateParsedItem(
    val name: String,
    val price: Double,
    val taxClass: String?
)

/** Full result of running a template against a receipt's OCR text. */
data class TemplateParsedReceipt(
    val templateId: String,
    val storeName: String,
    val dateMillis: Long?,
    val total: Double?,
    val currency: String,
    val language: String,
    val items: List<TemplateParsedItem>
) {
    /**
     * Converts to the app's normal [com.billscanner.app.parser.ParsedReceipt]
     * shape, so the rest of the save/review pipeline (translation, category
     * guessing, item rows) treats a template match exactly like any other
     * parse result — it's just a more trustworthy one. Tax class isn't part
     * of [com.billscanner.app.parser.ParsedItem] yet (no UI surfaces it per
     * item today), so it's dropped here rather than plumbed further; it
     * stays available on [TemplateParsedReceipt] itself for anything that
     * wants it later (e.g. automatic per-item category mapping).
     */
    fun toParsedReceipt(): com.billscanner.app.parser.ParsedReceipt =
        com.billscanner.app.parser.ParsedReceipt(
            storeName = storeName,
            dateMillis = dateMillis,
            dateWasFound = dateMillis != null,
            total = total,
            currency = currency,
            language = language,
            items = items.map {
                com.billscanner.app.parser.ParsedItem(
                    nameOriginal = it.name,
                    quantity = 1.0,
                    unitPrice = it.price,
                    totalPrice = it.price
                )
            }
        )
}
