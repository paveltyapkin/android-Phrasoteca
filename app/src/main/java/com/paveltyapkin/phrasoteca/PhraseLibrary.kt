package com.paveltyapkin.phrasoteca

import android.content.Context
import org.json.JSONObject

/** Bundled, read-only phrase library. Imported cards are independent Room records. */
object PhraseLibrary {
    private const val DEMO_FILE = "phrases/phrases_travel_preview.json"
    private const val PRACTICAL_FILE = "phrases/phrasoteca_practical_10_languages_338.json"
    enum class Catalog { DEMO, PRACTICAL_RU_EN }

    fun languages(catalog: Catalog): List<String> = when (catalog) {
        Catalog.DEMO -> listOf("ru-RU", "en-US", "en-GB", "es-ES", "es-CL", "es-MX", "pt-BR", "pt-PT", "vi-VN")
        Catalog.PRACTICAL_RU_EN -> listOf("ru-RU", "en-US", "en-GB", "es-ES", "es-CL", "es-MX", "pt-BR", "vi-VN", "th-TH", "zh-Hans", "zh-Hant-TW", "ja-JP", "ka-GE")
    }
    private const val FORMAT = "phrasoteca-phrase-library"

    data class Pair(val front: String, val back: String)
    data class Entry(val id: String, val category: String, val front: String, val back: String, val mode: String)

    /** Shop and market are one theme in the UI; source categories remain separate. */
    fun themeOf(category: String): String = if (category == "shop" || category == "market")
        "shopping" else category

    fun loadEntries(
        context: Context, frontTag: String, backTag: String,
        catalog: Catalog = Catalog.DEMO
    ): List<Entry> {
        require(frontTag.isNotBlank() && backTag.isNotBlank() &&
                LanguageOptions.areDifferent(frontTag, backTag)) {
            "Choose two different languages"
        }
        require(LanguageOptions.isValidTag(frontTag) && LanguageOptions.isValidTag(backTag)) {
            "Unsupported language"
        }
        require(frontTag in languages(catalog) && backTag in languages(catalog)) {
            "Language not available in this phrase catalog"
        }
        val fileName = if (catalog == Catalog.DEMO) DEMO_FILE else PRACTICAL_FILE
        val json = context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val catalog = JSONObject(json)
        require(catalog.optString("format") == FORMAT && catalog.optInt("version") == 1) {
            "Unsupported phrase library format"
        }
        val phrases = catalog.getJSONArray("phrases")
        require(phrases.length() in 1..500) { "Invalid phrase count" }
        val result = ArrayList<Entry>(phrases.length())
        val ids = HashSet<String>()
        for (i in 0 until phrases.length()) {
            val phrase = phrases.getJSONObject(i)
            val id = phrase.getString("id")
            require(ids.add(id)) { "Duplicate phrase ID: $id" }
            val translations = phrase.getJSONObject("translations")
            val overrides = phrase.optJSONObject("regionalOverrides")
            fun resolve(tag: String): String {
                val primary = tag.substringBefore('-')
                // Script-aware Chinese and exact Japanese/Georgian translations
                // must not silently fall back to the wrong script or language.
                val value = overrides?.optString(tag)?.takeIf { it.isNotBlank() }
                    ?: translations.optString(tag).takeIf { it.isNotBlank() }
                    ?: if (primary == "zh") "" else translations.optString(primary)
                require(value.isNotBlank()) { "Translation missing: $id / $tag" }
                return value
            }
            val mode = phrase.optString("mode", "NORMAL")
            require(mode in setOf("NORMAL", "YES_NO", "QUANTITY")) { "Invalid phrase mode: $id" }
            result += Entry(id, phrase.getString("category"), resolve(frontTag), resolve(backTag), mode)
        }
        return result
    }

    /** Labels follow the app UI locale. Each source category maps to one folder. */
    fun categoryLabel(context: Context, category: String): String {
        val label = when (themeOf(category)) {
            "communication" -> R.string.phrase_practical_communication
            "shopping" -> R.string.phrase_practical_shopping
            "restaurant" -> R.string.phrase_category_restaurant
            "transport" -> R.string.phrase_practical_transport
            "airport" -> R.string.phrase_category_airport
            "accommodation" -> R.string.phrase_category_accommodation
            "health" -> R.string.phrase_practical_health
            "banking" -> R.string.phrase_practical_banking
            "city" -> R.string.phrase_practical_city
            "connectivity" -> R.string.phrase_practical_connectivity
            "delivery" -> R.string.phrase_practical_delivery
            "services" -> R.string.phrase_practical_services
            else -> return category
        }
        return context.getString(label)
    }

    fun loadPair(context: Context, frontTag: String, backTag: String): List<Pair> =
        loadEntries(context, frontTag, backTag).map { Pair(it.front, it.back) }
}
