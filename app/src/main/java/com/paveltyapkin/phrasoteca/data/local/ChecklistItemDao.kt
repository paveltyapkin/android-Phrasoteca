package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChecklistItemDao {
    @Query("SELECT * FROM checklist_items WHERE cardId = :cardId ORDER BY sortOrder, id")
    suspend fun getForCard(cardId: Long): List<ChecklistItemEntity>

    @Insert
    suspend fun insert(item: ChecklistItemEntity): Long

    @Query("DELETE FROM checklist_items WHERE cardId = :cardId")
    suspend fun deleteForCard(cardId: Long)

    @Query("UPDATE checklist_items SET checked = :checked WHERE id = :id AND cardId = :cardId")
    suspend fun setChecked(id: Long, cardId: Long, checked: Boolean)
}
