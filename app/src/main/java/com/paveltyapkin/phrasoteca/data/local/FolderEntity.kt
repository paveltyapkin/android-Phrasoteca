package com.paveltyapkin.phrasoteca.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["parentFolderId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("collectionId"), Index("parentFolderId")]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val name: String,
    val parentFolderId: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
)
