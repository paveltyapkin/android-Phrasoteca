package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Card title, login, password and URL are all inside ciphertext. */
@Entity(
    tableName = "vault_cards",
    foreignKeys = [ForeignKey(
        entity = VaultEntity::class, parentColumns = ["collectionId"],
        childColumns = ["vaultId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("vaultId")]
)
data class VaultCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vaultId: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val sortOrder: Int = 0
)
