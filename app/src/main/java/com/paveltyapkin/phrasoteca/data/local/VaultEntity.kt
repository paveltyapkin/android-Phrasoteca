package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Sensitive content is never stored here; the wrapped data key needs both PIN and Keystore. */
@Entity(
    tableName = "vaults",
    foreignKeys = [ForeignKey(
        entity = CollectionEntity::class, parentColumns = ["id"],
        childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["collectionId"], unique = true)]
)
data class VaultEntity(
    @PrimaryKey val collectionId: Long,
    val pinSalt: ByteArray,
    val pinNonce: ByteArray,
    val keystoreNonce: ByteArray,
    val wrappedDataKey: ByteArray,
    val failedAttempts: Int = 0
)
