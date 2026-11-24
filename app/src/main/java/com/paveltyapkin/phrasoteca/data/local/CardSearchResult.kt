
package com.paveltyapkin.phrasoteca.data.local

data class CardSearchResult(
    val cardId: Long,
    val collectionId: Long,
    val collectionName: String,
    val frontText: String,
    val backText: String
)
