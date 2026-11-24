package com.paveltyapkin.phrasoteca.model

data class CardCollection(
    val id: Long,
    val name: String,
    val cards: List<PhraseCard>
)
