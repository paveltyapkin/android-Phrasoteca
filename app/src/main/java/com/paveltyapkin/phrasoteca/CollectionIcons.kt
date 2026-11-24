package com.paveltyapkin.phrasoteca

import org.json.JSONObject

/** Stable icon identifiers shared by the editor, home tiles and JSON backup. */
object CollectionIcons {
    data class Entry(val key: String, val drawable: Int, val name: Int)
    val entries = listOf(
        Entry("languages", R.drawable.ic_collection_languages, R.string.collection_icon_languages),
        Entry("plane", R.drawable.ic_collection_plane, R.string.collection_icon_plane),
        Entry("house", R.drawable.ic_collection_house, R.string.collection_icon_house),
        Entry("shopping", R.drawable.ic_collection_shopping, R.string.collection_icon_shopping),
        Entry("work", R.drawable.ic_collection_work, R.string.collection_icon_work),
        Entry("study", R.drawable.ic_collection_study, R.string.collection_icon_study),
        Entry("health", R.drawable.ic_collection_health, R.string.collection_icon_health),
        Entry("food", R.drawable.ic_collection_food, R.string.collection_icon_food),
        Entry("notes", R.drawable.ic_collection_notes, R.string.collection_icon_notes),
        Entry("books", R.drawable.ic_collection_books, R.string.collection_icon_books),
        Entry("tech", R.drawable.ic_collection_tech, R.string.collection_icon_tech),
        Entry("finance", R.drawable.ic_collection_finance, R.string.collection_icon_finance)
    )

    fun defaultKey(type: String): String = when (type) {
        "LANGUAGE" -> "languages"
        "CHECKLIST" -> "notes"
        "VAULT" -> "lock"
        else -> "notes"
    }

    fun drawable(key: String, type: String): Int {
        if (type == "VAULT") return R.drawable.ic_collection_lock
        val selected = key.takeIf { it.isNotEmpty() } ?: defaultKey(type)
        return entries.firstOrNull { it.key == selected }?.drawable
            ?: entries.first { it.key == defaultKey(type) }.drawable
    }

    /** Import validation must precede the Room transaction. Old backups omit this field. */
    fun validatedKey(obj: JSONObject): String {
        if (!obj.has("iconKey")) return ""
        require(obj.get("iconKey") is String) { "Invalid collection icon" }
        val key = obj.getString("iconKey")
        require(key.isEmpty() || entries.any { it.key == key }) { "Unknown collection icon" }
        return key
    }
}
