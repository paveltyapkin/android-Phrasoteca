package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VaultDao {
    @Insert suspend fun insertVault(vault: VaultEntity)
    @Query("SELECT * FROM vaults WHERE collectionId = :id LIMIT 1")
    suspend fun getVault(id: Long): VaultEntity?
    @Query("UPDATE vaults SET failedAttempts = :attempts WHERE collectionId = :id")
    suspend fun setFailedAttempts(id: Long, attempts: Int)
    @Insert suspend fun insertCard(card: VaultCardEntity): Long
    @Query("SELECT * FROM vault_cards WHERE vaultId = :vaultId ORDER BY sortOrder, id")
    suspend fun getCards(vaultId: Long): List<VaultCardEntity>
    @Query("SELECT * FROM vault_cards WHERE id = :id AND vaultId = :vaultId LIMIT 1")
    suspend fun getCard(vaultId: Long, id: Long): VaultCardEntity?
    @Query("UPDATE vault_cards SET nonce = :nonce, ciphertext = :ciphertext WHERE id = :id AND vaultId = :vaultId")
    suspend fun updateCard(vaultId: Long, id: Long, nonce: ByteArray, ciphertext: ByteArray)
    @Query("DELETE FROM vault_cards WHERE id = :id AND vaultId = :vaultId")
    suspend fun deleteCard(vaultId: Long, id: Long)
}
