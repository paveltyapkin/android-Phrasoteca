package com.paveltyapkin.phrasoteca

import android.content.Context
import org.json.JSONObject

/** Bundled read-only idioms; created Room cards are editable, independent copies. */
object IdiomLibrary {
    private const val FILE = "idioms/phrasoteca_idioms_40_10_languages.json"
    private const val FORMAT = "phrasoteca-idioms-multilingual-catalog"
    val meaningLocales = listOf("ru-RU", "en", "es-CL", "pt-BR", "vi-VN", "th-TH", "zh-Hans", "zh-Hant-TW", "ja-JP", "ka-GE")

    data class Folder(val id: String, val title: String, val sourceLocale: String, val count: Int)
    data class Entry(val id: String, val folderId: String, val sourceLocale: String, val expression: String, val meaning: String)
    data class Library(val folders: List<Folder>, val entries: List<Entry>)

    fun load(context: Context, meaningLocale: String): Library {
        require(meaningLocale in meaningLocales) { "Unsupported idiom meaning locale" }
        val json = context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val catalog = JSONObject(json)
        require(catalog.getString("format") == FORMAT && catalog.getInt("version") == 2) {
            "Unsupported idiom library format"
        }
        val folderArray = catalog.getJSONArray("folders")
        val folders = ArrayList<Folder>()
        val folderIds = HashSet<String>()
        for (i in 0 until folderArray.length()) {
            val item = folderArray.getJSONObject(i)
            val folder = Folder(item.getString("id"), item.getString("title"), item.getString("sourceLocale"), item.getInt("count"))
            require(folder.id.isNotBlank() && folder.title.isNotBlank() && folderIds.add(folder.id)) { "Invalid folder" }
            require(folder.sourceLocale in listOf("ru-RU", "en", "es-CL", "pt-BR", "vi-VN")) { "Invalid source locale" }
            folders.add(folder)
        }
        require(folders.size == 5) { "Invalid folder count" }
        val entriesArray = catalog.getJSONArray("entries")
        require(entriesArray.length() == 40) { "Invalid idiom count" }
        val ids = HashSet<String>()
        val entries = ArrayList<Entry>(entriesArray.length())
        for (i in 0 until entriesArray.length()) {
            val item = entriesArray.getJSONObject(i)
            val id = item.getString("id")
            val folderId = item.getString("folderId")
            val sourceLocale = item.getString("sourceLocale")
            val expression = item.getString("expression").trim()
            val meaning = item.getJSONObject("meanings").getString(meaningLocale).trim()
            require(ids.add(id) && id.isNotBlank() && folderId in folderIds) { "Invalid or duplicate idiom ID" }
            require(folders.first { it.id == folderId }.sourceLocale == sourceLocale) { "Invalid source language" }
            require(expression.isNotEmpty() && meaning.isNotEmpty()) { "Empty idiom or meaning: $id" }
            entries.add(Entry(id, folderId, sourceLocale, expression, meaning))
        }
        require(folders.all { folder -> entries.count { it.folderId == folder.id } == folder.count }) {
            "Folder counts do not match catalog"
        }
        return Library(folders, entries)
    }

    /** Map catalog codes to supported Android TTS language tags. */
    fun androidTag(locale: String): String = when (locale) {
        "en" -> "en-US"
        else -> locale
    }
}
