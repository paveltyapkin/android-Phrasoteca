package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"],
        childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")]
)
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Int = 0
)
