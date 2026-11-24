
package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CardDao {


    @Query(
        "SELECT * FROM cards " +
                "WHERE collectionId = :collectionId " +
                "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getByCollection(
        collectionId: Long
    ): List<CardEntity>


    @Query(
        "SELECT COALESCE(MAX(sortOrder), 0) + 1 " +
                "FROM cards WHERE collectionId = :collectionId"
    )
    suspend fun nextSortOrder(collectionId: Long): Int

    @Query(
        "UPDATE cards SET sortOrder = :order " +
                "WHERE id = :id"
    )
    suspend fun updateSortOrder(id: Long, order: Int)


    @Query(
        """
    SELECT
        cards.id AS cardId,
        cards.collectionId AS collectionId,
        collections.name AS collectionName,
        cards.frontText AS frontText,
        cards.backText AS backText
    FROM cards
    INNER JOIN collections
        ON collections.id = cards.collectionId
    WHERE
        instr(lower(cards.frontText), lower(:query)) > 0
        OR
        instr(lower(cards.backText), lower(:query)) > 0
    ORDER BY
        collections.sortOrder,
        cards.sortOrder,
        cards.id
    LIMIT 100
    """
    )
    suspend fun searchAll(query: String): List<CardSearchResult>


    @Query("SELECT * FROM cards WHERE collectionId = :collectionId AND folderId IS NULL ORDER BY sortOrder, id")
    suspend fun getAtCollectionRoot(collectionId: Long): List<CardEntity>

    @Query("SELECT * FROM cards WHERE collectionId = :collectionId AND folderId = :folderId ORDER BY sortOrder, id")
    suspend fun getInFolder(collectionId: Long, folderId: Long): List<CardEntity>

    @Query("UPDATE cards SET folderId = :folderId WHERE id = :cardId AND collectionId = :collectionId")
    suspend fun moveToFolder(cardId: Long, collectionId: Long, folderId: Long?)

    @Query("UPDATE cards SET folderId = NULL WHERE collectionId = :collectionId AND folderId = :folderId")
    suspend fun moveAllToRoot(collectionId: Long, folderId: Long)

    /** Update only the favorite flag; never overwrite other card fields. */
    @Query("UPDATE cards SET isFavorite = :favorite WHERE id = :cardId")
    suspend fun setFavorite(cardId: Long, favorite: Boolean): Int

    /** Used by the upcoming favorites filter. Preserves manual sorting. */
    @Query("SELECT * FROM cards WHERE collectionId = :collectionId AND isFavorite = 1 ORDER BY sortOrder, id")
    suspend fun getFavoritesByCollection(collectionId: Long): List<CardEntity>

    /** Favorite cards from regular collections only; encrypted vaults are excluded. */
    @Query("""SELECT COUNT(*) FROM cards
        INNER JOIN collections ON collections.id = cards.collectionId
        WHERE cards.isFavorite = 1
          AND collections.defaultCardType != 'VAULT'
          AND COALESCE(cards.cardType, collections.defaultCardType) != 'VAULT'""")
    suspend fun countPublicFavorites(): Int

    @Query("""SELECT cards.id AS cardId, cards.collectionId AS collectionId,
        collections.name AS collectionName, cards.frontText AS frontText
        FROM cards INNER JOIN collections ON collections.id = cards.collectionId
        WHERE cards.isFavorite = 1
          AND collections.defaultCardType != 'VAULT'
          AND COALESCE(cards.cardType, collections.defaultCardType) != 'VAULT'
        ORDER BY collections.sortOrder, collections.id, cards.sortOrder, cards.id""")
    suspend fun getPublicFavoriteShortcuts(): List<FavoriteCardShortcut>

    /** Stable cross-collection order; do not expose encrypted/vault cards. */
    @Query("""SELECT cards.* FROM cards
        INNER JOIN collections ON collections.id = cards.collectionId
        WHERE cards.isFavorite = 1
          AND collections.defaultCardType != 'VAULT'
          AND COALESCE(cards.cardType, collections.defaultCardType) != 'VAULT'
        ORDER BY collections.sortOrder, collections.id, cards.sortOrder, cards.id""")
    suspend fun getAllPublicFavorites(): List<CardEntity>

    @Insert
    suspend fun insert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)
}
