package com.paveltyapkin.phrasoteca.data.local

/** Read-only projection for the Home favorites shortcut. */
data class FavoriteCardShortcut(
    val cardId: Long,
    val collectionId: Long,
    val collectionName: String,
    val frontText: String
)
