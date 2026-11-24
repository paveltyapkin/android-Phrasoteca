
package com.paveltyapkin.phrasoteca.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CollectionEntity::class,
        CardEntity::class,
        FolderEntity::class,
        ChecklistItemEntity::class,
        VaultEntity::class,
        VaultCardEntity::class,
        AchievementMetricEntity::class,
        AchievementUnlockEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class PhrasotecaDatabase : RoomDatabase() {

    abstract fun collectionDao(): CollectionDao
    abstract fun cardDao(): CardDao
    abstract fun folderDao(): FolderDao
    abstract fun checklistItemDao(): ChecklistItemDao
    abstract fun vaultDao(): VaultDao
    abstract fun achievementDao(): AchievementDao

    companion object {
        @Volatile
        private var INSTANCE: PhrasotecaDatabase? = null

        fun getInstance(context: Context): PhrasotecaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhrasotecaDatabase::class.java,
                    "phrasoteca.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
