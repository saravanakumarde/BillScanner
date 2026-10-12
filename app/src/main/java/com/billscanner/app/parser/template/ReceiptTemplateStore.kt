package com.billscanner.app.parser.template

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads [ReceiptTemplate]s from `assets/receipt_templates.json`.
 *
 * Templates live as plain JSON (not Kotlin code) specifically so a future
 * app version can ship a bigger/updated template file — built from however
 * many receipts get collected over time — just by replacing this one
 * asset, without needing new Kotlin for every store added.
 *
 * Uses org.json (built into Android) rather than adding a JSON library
 * dependency, since the schema here is small and fixed.
 */
object ReceiptTemplateStore {

    private const val ASSET_PATH = "receipt_templates.json"

    @Volatile
    private var cached: List<ReceiptTemplate>? = null

    fun loadAll(context: Context): List<ReceiptTemplate> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = try {
                context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                    .let { parseTemplates(it) }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            cached = loaded
            return loaded
        }
    }

    internal fun parseTemplates(json: String): List<ReceiptTemplate> {
        val root = JSONArray(json)
        val result = mutableListOf<ReceiptTemplate>()
        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)
            try {
                result.add(parseTemplate(obj))
            } catch (e: Exception) {
                // One malformed template (e.g. from a future hand-edited
                // import) shouldn't take every other template down with it.
                e.printStackTrace()
            }
        }
        return result
    }

    private fun parseTemplate(obj: JSONObject): ReceiptTemplate {
        return ReceiptTemplate(
            id = obj.getString("id"),
            displayName = obj.getString("displayName"),
            identifierLines = obj.getJSONArray("identifierLines").toStringList(),
            currency = obj.optString("currency", "EUR"),
            language = obj.optString("language", "de"),
            totalRule = TemplateFieldRule(
                linePattern = obj.getJSONObject("totalRule").getString("linePattern")
            ),
            dateRule = TemplateFieldRule(
                linePattern = obj.getJSONObject("dateRule").getString("linePattern")
            ),
            itemsRule = obj.getJSONObject("itemsRule").let { items ->
                TemplateItemsRule(
                    namePattern = items.getString("namePattern"),
                    pricePattern = items.getString("pricePattern"),
                    itemLayout = items.optString("itemLayout", "SPLIT_OR_SAME_LINE").let { layoutName ->
                        try {
                            ItemLayout.valueOf(layoutName)
                        } catch (e: IllegalArgumentException) {
                            // Unknown layout name (e.g. a typo in a future
                            // hand-edited import) — fall back rather than
                            // dropping the whole template.
                            ItemLayout.SPLIT_OR_SAME_LINE
                        }
                    },
                    nameExclusions = items.optJSONArray("nameExclusions")?.toStringList() ?: emptyList(),
                    namePatternCaseSensitive = items.optBoolean("namePatternCaseSensitive", false)
                )
            }
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
