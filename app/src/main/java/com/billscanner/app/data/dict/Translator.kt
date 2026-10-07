package com.billscanner.app.data.dict

/**
 * Facade over translation so the rest of the app doesn't care how a term
 * gets from German to English. Currently backed only by the offline
 * dictionary (free, no key, no network). Kept as an interface so a cloud
 * translation backend could be dropped in later without touching callers.
 */
interface Translator {
    /** Returns English text, or the original text unchanged if untranslatable. */
    fun translate(original: String, sourceLanguage: String): String
}

class OfflineDictionaryTranslator : Translator {
    override fun translate(original: String, sourceLanguage: String): String {
        if (sourceLanguage != "de") return original
        return GermanEnglishDictionary.translate(original) ?: original
    }
}
