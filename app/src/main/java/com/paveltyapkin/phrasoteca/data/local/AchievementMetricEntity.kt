package com.paveltyapkin.phrasoteca.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievement_metrics")
data class AchievementMetricEntity(
    @PrimaryKey val metricKey: String,
    val value: Long
)

@Entity(tableName = "achievement_unlocks")
data class AchievementUnlockEntity(
    @PrimaryKey val achievementId: String,
    val unlockedAt: Long,
    val seen: Boolean = false
)
