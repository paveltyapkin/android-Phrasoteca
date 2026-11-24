
package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CollectionDao {

    @Query(
        "SELECT * FROM collections " +
                "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CollectionEntity?

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Update
    suspend fun update(collection: CollectionEntity)

    @Delete
    suspend fun delete(collection: CollectionEntity)


    @Query(
        """
        SELECT
            collections.id AS id,
            collections.name AS name,
            collections.defaultCardType AS defaultCardType,
            collections.iconKey AS iconKey,
            COUNT(cards.id) AS cardCount
        FROM collections
        LEFT JOIN cards
            ON cards.collectionId = collections.id
        GROUP BY collections.id, collections.name
        ORDER BY collections.sortOrder ASC, collections.id ASC
        """
    )
    suspend fun getAllWithCounts(): List<CollectionSummary>


    @Query(
        "SELECT COALESCE(MAX(sortOrder), 0) + 1 " +
                "FROM collections"
    )
    suspend fun nextSortOrder(): Int

    @Query(
        "UPDATE collections SET sortOrder = :order " +
                "WHERE id = :id"
    )
    suspend fun updateSortOrder(id: Long, order: Int)


}
