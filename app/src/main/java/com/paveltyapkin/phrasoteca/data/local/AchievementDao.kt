package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievement_metrics")
    suspend fun metrics(): List<AchievementMetricEntity>

    @Query("SELECT * FROM achievement_unlocks")
    suspend fun unlocks(): List<AchievementUnlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMetric(metric: AchievementMetricEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(unlock: AchievementUnlockEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceUnlock(unlock: AchievementUnlockEntity)

    @Query("UPDATE achievement_unlocks SET seen = 1 WHERE achievementId = :id")
    suspend fun markSeen(id: String)
}
