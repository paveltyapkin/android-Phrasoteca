
package com.paveltyapkin.phrasoteca.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(connection: SQLiteConnection) {

        // Add ordering columns without deleting data
        connection.execSQL(
            "ALTER TABLE collections " +
                    "ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
        )

        connection.execSQL(
            "ALTER TABLE cards " +
                    "ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
        )

        // Preserve existing collection order by ID
        connection.execSQL(
            "UPDATE collections SET sortOrder = id"
        )

        // Preserve existing card order by ID
        connection.execSQL(
            "UPDATE cards SET sortOrder = id"
        )
    }
}


// Preserves installed v2 databases. Also supports a clean install at version 3.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE collections ADD COLUMN defaultCardType TEXT NOT NULL DEFAULT 'LANGUAGE'")
        connection.execSQL("ALTER TABLE collections ADD COLUMN frontLabel TEXT NOT NULL DEFAULT 'Front'")
        connection.execSQL("ALTER TABLE collections ADD COLUMN backLabel TEXT NOT NULL DEFAULT 'Back'")
        connection.execSQL("ALTER TABLE collections ADD COLUMN frontColor TEXT NOT NULL DEFAULT '#F2F4FD'")
        connection.execSQL("ALTER TABLE collections ADD COLUMN backColor TEXT NOT NULL DEFAULT '#E5F3ED'")
        connection.execSQL("ALTER TABLE collections ADD COLUMN sideMode TEXT NOT NULL DEFAULT 'TWO_SIDED'")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `folders` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `collectionId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `parentFolderId` INTEGER,
            `sortOrder` INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`parentFolderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_collectionId` ON `folders` (`collectionId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_parentFolderId` ON `folders` (`parentFolderId`)")
        connection.execSQL("ALTER TABLE cards ADD COLUMN folderId INTEGER REFERENCES folders(id) ON DELETE SET NULL")
        connection.execSQL("ALTER TABLE cards ADD COLUMN cardType TEXT")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_folderId` ON `cards` (`folderId`)")
    }
}


val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `checklist_items` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `cardId` INTEGER NOT NULL,
            `text` TEXT NOT NULL,
            `checked` INTEGER NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_checklist_items_cardId` ON `checklist_items` (`cardId`)")
    }
}


/** Dedicated vault metadata and ciphertext tables; no secret fields in public cards. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `vaults` (
            `collectionId` INTEGER NOT NULL PRIMARY KEY,
            `pinSalt` BLOB NOT NULL,
            `pinNonce` BLOB NOT NULL,
            `keystoreNonce` BLOB NOT NULL,
            `wrappedDataKey` BLOB NOT NULL,
            `failedAttempts` INTEGER NOT NULL,
            FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vaults_collectionId` ON `vaults` (`collectionId`)")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `vault_cards` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `vaultId` INTEGER NOT NULL,
            `nonce` BLOB NOT NULL,
            `ciphertext` BLOB NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            FOREIGN KEY(`vaultId`) REFERENCES `vaults`(`collectionId`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_cards_vaultId` ON `vault_cards` (`vaultId`)")
    }
}


/** Explicit BCP-47 locale for language cards. Empty tag keeps legacy inference. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE collections ADD COLUMN frontLanguageTag TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE collections ADD COLUMN backLanguageTag TEXT NOT NULL DEFAULT ''")
    }
}


/** v7: per-card interactive response mode. No existing card content is modified. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cards ADD COLUMN interactionMode TEXT NOT NULL DEFAULT 'NORMAL'")
    }
}


/** Adds independent achievement progress without modifying existing user content. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `achievement_metrics` (
            `metricKey` TEXT NOT NULL PRIMARY KEY,
            `value` INTEGER NOT NULL
        )""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `achievement_unlocks` (
            `achievementId` TEXT NOT NULL PRIMARY KEY,
            `unlockedAt` INTEGER NOT NULL,
            `seen` INTEGER NOT NULL
        )""")
    }
}


/** v9: a favorite flag on public cards. Existing rows default to false. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cards ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
    }
}

/** v10: structured variable definitions. Stored templates and existing cards are unchanged. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cards ADD COLUMN variableDefinitions TEXT NOT NULL DEFAULT '{}'")
    }
}

/** v11: optional visual icon selection; empty value keeps the legacy type icon. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE collections ADD COLUMN iconKey TEXT NOT NULL DEFAULT ''")
    }
}
