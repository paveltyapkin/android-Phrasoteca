package com.paveltyapkin.phrasoteca

import android.content.Context

/** BCP-47 locales stored separately from the editable card-side labels. */
object LanguageOptions {
    data class Option(val tag: String, val title: Int)

    val supported = listOf(
        Option("", R.string.language_automatic),
        Option("en-US", R.string.language_en_us),
        Option("en-GB", R.string.language_en_gb),
        Option("ru-RU", R.string.language_ru_ru),
        Option("es-ES", R.string.language_es_es),
        Option("es-CL", R.string.language_es_cl),
        Option("es-MX", R.string.language_es_mx),
        Option("pt-BR", R.string.language_pt_br),
        Option("pt-PT", R.string.language_pt_pt),
        Option("vi-VN", R.string.language_vi_vn),
        Option("th-TH", R.string.language_th_th),
        Option("zh-Hans", R.string.language_zh_hans),
        Option("zh-Hant-TW", R.string.language_zh_hant_tw),
        Option("ja-JP", R.string.language_ja_jp),
        Option("ka-GE", R.string.language_ka_ge)
    )

    /** Regional variants of a language are the same choice, except separate Chinese scripts. */
    fun areDifferent(first: String, second: String): Boolean {
        fun identity(tag: String): String = if (tag == "zh-Hans" || tag == "zh-Hant-TW")
            tag else tag.substringBefore('-')
        return identity(first) != identity(second)
    }

    fun titles(context: Context) = supported.map { context.getString(it.title) }

    fun indexOf(tag: String): Int = supported.indexOfFirst { it.tag == tag }.coerceAtLeast(0)

    fun isValidTag(tag: String): Boolean = supported.any { it.tag == tag }
}
