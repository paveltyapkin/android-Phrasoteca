package com.paveltyapkin.phrasoteca.model

data class PhraseCard(
    val id: Long,
    val frontText: String,
    val backText: String,
    val frontLanguage: String,
    val backLanguage: String
)
