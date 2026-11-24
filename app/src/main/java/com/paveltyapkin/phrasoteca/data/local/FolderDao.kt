package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE collectionId = :collectionId ORDER BY sortOrder, id")
    suspend fun getByCollection(collectionId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE collectionId = :collectionId AND parentFolderId IS NULL ORDER BY sortOrder, id")
    suspend fun getRoots(collectionId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE collectionId = :collectionId AND parentFolderId = :parentId ORDER BY sortOrder, id")
    suspend fun getChildren(collectionId: Long, parentId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM folders WHERE collectionId = :collectionId")
    suspend fun nextSortOrder(collectionId: Long): Int

    @Insert suspend fun insert(folder: FolderEntity): Long
    @Update suspend fun update(folder: FolderEntity)
    @Delete suspend fun delete(folder: FolderEntity)
}
