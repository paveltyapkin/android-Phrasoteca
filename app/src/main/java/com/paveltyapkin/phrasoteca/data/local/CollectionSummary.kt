package com.paveltyapkin.phrasoteca.data.local

data class CollectionSummary(
    val id: Long,
    val name: String,
    val cardCount: Int,
    val defaultCardType: String,
    val iconKey: String
)
