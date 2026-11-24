package com.paveltyapkin.phrasoteca.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("collectionId"), Index("folderId")]
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val frontText: String,
    // Empty back text will be allowed in the new editor; existing labels are retained for compatibility.
    val backText: String,
    val frontLanguage: String,
    val backLanguage: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val folderId: Long? = null,
    // Null inherits CollectionEntity.defaultCardType.
    val cardType: String? = null,
    // Explicit interaction type; imported legacy cards remain NORMAL.
    @ColumnInfo(defaultValue = "'NORMAL'") val interactionMode: String = "NORMAL",
    // Persistent user favorite; existing cards are not marked by default.
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "'{}'") val variableDefinitions: String = "{}"
)
