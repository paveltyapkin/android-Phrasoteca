package com.paveltyapkin.phrasoteca

import android.util.Log
import androidx.room.withTransaction
import com.paveltyapkin.phrasoteca.data.local.AchievementMetricEntity
import com.paveltyapkin.phrasoteca.data.local.AchievementUnlockEntity
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase

/** Stage 1: persistent metrics and unlocks. Producers are connected in stage 2. */
class AchievementRepository(private val db: PhrasotecaDatabase) {
    data class Snapshot(
        val metrics: Map<String, Long>,
        val unlocks: Map<String, AchievementUnlockEntity>
    )

    suspend fun snapshot(): Snapshot = Snapshot(
        metrics = db.achievementDao().metrics().associate { it.metricKey to it.value },
        unlocks = db.achievementDao().unlocks().associateBy { it.achievementId }
    )

    /** Count each bundled tutorial card once, even across Activity recreation and reinstalls.
     *  Per-card markers use the same achievement_metrics table and are included in JSON backups.
     */
    suspend fun markTutorialViewed(index: Int): Boolean {
        require(index in 1..TutorialLibrary.TOTAL_CARDS)
        val key = "tutorial.viewed.card.%02d".format(java.util.Locale.ROOT, index)
        val unlocked = db.withTransaction {
            val dao = db.achievementDao()
            val current = dao.metrics().associate { it.metricKey to it.value }
            if ((current[key] ?: 0L) > 0L) return@withTransaction false
            dao.setMetric(AchievementMetricEntity(key, 1L))
            // Derive the total from distinct per-card flags, not repeated visits.
            val total = (1..TutorialLibrary.TOTAL_CARDS).count {
                (current["tutorial.viewed.card.%02d".format(java.util.Locale.ROOT, it)] ?: 0L) > 0L || it == index
            }.toLong()
            dao.setMetric(AchievementMetricEntity("tutorial.viewed.unique", total))
            if (total >= TutorialLibrary.REQUIRED_CARDS) {
                dao.unlock(AchievementUnlockEntity("LEARN_075", System.currentTimeMillis())) != -1L
            } else false
        }
        if (unlocked) AchievementNoticeCenter.onUnlocked()
        return unlocked
    }

    /** Atomic counter update; only invoke after confirmed, successful user actions. */
    suspend fun record(metricKey: String, count: Long = 1L): List<String> {
        require(count > 0L)
        val known = AchievementCatalog.definitions.filter { it.metricKey == metricKey && it.available }
        if (known.isEmpty()) return emptyList()
        val unlocked = db.withTransaction {
            val dao = db.achievementDao()
            val previous = dao.metrics().find { it.metricKey == metricKey }?.value ?: 0L
            val next = if (Long.MAX_VALUE - previous < count) Long.MAX_VALUE else previous + count
            dao.setMetric(AchievementMetricEntity(metricKey, next))
            val newlyUnlocked = mutableListOf<String>()
            for (definition in known) {
                if (next >= definition.target) {
                    val inserted = dao.unlock(AchievementUnlockEntity(definition.id, System.currentTimeMillis()))
                    if (inserted != -1L) newlyUnlocked += definition.id
                }
            }
            Log.d("PhrasotecaAchievements", "metric=$metricKey value=$next unlocked=${newlyUnlocked.joinToString()}")
            newlyUnlocked
        }
        // Notify the foreground HomeActivity only after the transaction commits.
        if (unlocked.isNotEmpty()) AchievementNoticeCenter.onUnlocked()
        return unlocked
    }
}
