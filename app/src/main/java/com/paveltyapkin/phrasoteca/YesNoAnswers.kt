package com.paveltyapkin.phrasoteca

/** Short affirmative and negative responses in supported card languages.
 * Context may require other answers (especially questions about permission or offers).
 */
object YesNoAnswers {
    data class Words(val affirmative: String, val negative: String)
    fun forLocale(tag: String): Words = when (tag.substringBefore('-')) {
        "ru" -> Words("Да", "Нет")
        "en" -> Words("Yes", "No")
        "es" -> Words("Sí", "No")
        "pt" -> Words("Sim", "Não")
        "vi" -> Words("Có", "Không")
        "th" -> Words("ใช่", "ไม่")
        "zh" -> Words("是", "不是")
        "ja" -> Words("はい", "いいえ")
        "ka" -> Words("დიახ", "არა")
        else -> Words("Yes", "No")
    }
}
