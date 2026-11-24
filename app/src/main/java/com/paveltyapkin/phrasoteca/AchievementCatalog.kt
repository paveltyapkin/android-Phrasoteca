package com.paveltyapkin.phrasoteca

/** Immutable IDs. Future definitions remain hidden until their features ship. */
data class AchievementDefinition(
    val id: String, val titleRes: Int, val descriptionRes: Int,
    val metricKey: String, val target: Long, val category: String, val available: Boolean = true
)

object AchievementCatalog {
    val definitions: List<AchievementDefinition> = listOf(
        AchievementDefinition("LEARN_075", R.string.achievement_learn_075_title, R.string.achievement_learn_075_description, "tutorial.viewed.unique", 16L, "learning", true),
        AchievementDefinition("COLL_DELETE_1", R.string.achievement_coll_delete_1_title, R.string.achievement_coll_delete_1_description, "collection.deleted", 1L, "collections", true),
        AchievementDefinition("COLL_CREATE_1", R.string.achievement_coll_create_1_title, R.string.achievement_coll_create_1_description, "collection.created.manual", 1L, "collections", true),
        AchievementDefinition("CARD_CREATE_10", R.string.achievement_card_create_10_title, R.string.achievement_card_create_10_description, "card.created.manual", 10L, "collections", true),
        AchievementDefinition("CARD_CREATE_25", R.string.achievement_card_create_25_title, R.string.achievement_card_create_25_description, "card.created.manual", 25L, "collections", true),
        AchievementDefinition("CARD_CREATE_50", R.string.achievement_card_create_50_title, R.string.achievement_card_create_50_description, "card.created.manual", 50L, "collections", true),
        AchievementDefinition("CARD_CREATE_100", R.string.achievement_card_create_100_title, R.string.achievement_card_create_100_description, "card.created.manual", 100L, "collections", true),
        AchievementDefinition("CARD_CREATE_200", R.string.achievement_card_create_200_title, R.string.achievement_card_create_200_description, "card.created.manual", 200L, "collections", true),
        AchievementDefinition("QUEUE_FINISH_1", R.string.achievement_queue_finish_1_title, R.string.achievement_queue_finish_1_description, "show_queue.finished", 1L, "communication", true),
        AchievementDefinition("QUEUE_FINISH_10", R.string.achievement_queue_finish_10_title, R.string.achievement_queue_finish_10_description, "show_queue.finished", 10L, "communication", true),
        AchievementDefinition("QUEUE_FINISH_25", R.string.achievement_queue_finish_25_title, R.string.achievement_queue_finish_25_description, "show_queue.finished", 25L, "communication", true),
        AchievementDefinition("IDIOM_FINISH_1", R.string.achievement_idiom_finish_1_title, R.string.achievement_idiom_finish_1_description, "idiom.game.finished", 1L, "learning", true),
        AchievementDefinition("CARD_CLONE_1", R.string.achievement_card_clone_1_title, R.string.achievement_card_clone_1_description, "card.cloned", 1L, "collections", true),
        AchievementDefinition("IDIOM_SCORE_20", R.string.achievement_idiom_score_20_title, R.string.achievement_idiom_score_20_description, "idiom.game.score20", 1L, "learning", true),
        AchievementDefinition("PHRASE_DEMO_1", R.string.achievement_phrase_demo_1_title, R.string.achievement_phrase_demo_1_description, "phrase_demo.game.finished", 1L, "learning", true),
        AchievementDefinition("QR_EXCHANGE_1", R.string.achievement_qr_exchange_1_title, R.string.achievement_qr_exchange_1_description, "qr.card.exchange", 1L, "sharing", true),
        AchievementDefinition("QR_SHARE_10", R.string.achievement_qr_share_10_title, R.string.achievement_qr_share_10_description, "qr.card.shown", 10L, "sharing", true),
        AchievementDefinition("VAULT_CREATE_1", R.string.achievement_vault_create_1_title, R.string.achievement_vault_create_1_description, "vault.created", 1L, "collections", true),
        AchievementDefinition("TTS_PLAY_50", R.string.achievement_tts_play_50_title, R.string.achievement_tts_play_50_description, "tts.speak.accepted", 50L, "communication", true),
        AchievementDefinition("TTS_PLAY_1", R.string.achievement_tts_play_1_title, R.string.achievement_tts_play_1_description, "tts.speak.accepted", 1L, "communication", true),
        AchievementDefinition("CARD_EDIT_1", R.string.achievement_card_edit_1_title, R.string.achievement_card_edit_1_description, "card.updated.manual", 1L, "collections", true),
        AchievementDefinition("CARD_SORT_1", R.string.achievement_card_sort_1_title, R.string.achievement_card_sort_1_description, "card.order.changed", 1L, "collections", true),
        AchievementDefinition("COLL_CREATE_5", R.string.achievement_coll_create_5_title, R.string.achievement_coll_create_5_description, "collection.created.manual", 5L, "collections", true),
        AchievementDefinition("SEARCH_OPEN_1", R.string.achievement_search_open_1_title, R.string.achievement_search_open_1_description, "search.card.opened", 1L, "collections", true),
        AchievementDefinition("LANG_3", R.string.achievement_lang_3_title, R.string.achievement_lang_3_description, "language_tag.unique", 3L, "collections", true),
        AchievementDefinition("YESNO_1", R.string.achievement_yesno_1_title, R.string.achievement_yesno_1_description, "card.yes_no.used", 1L, "communication", true),
        AchievementDefinition("QUANTITY_1", R.string.achievement_quantity_1_title, R.string.achievement_quantity_1_description, "card.quantity.confirmed", 1L, "communication", true),
        AchievementDefinition("QUEUE_SIZE_5", R.string.achievement_queue_size_5_title, R.string.achievement_queue_size_5_description, "show_queue.size5", 1L, "communication", true),
        AchievementDefinition("IDIOM_PERFECT", R.string.achievement_idiom_perfect_title, R.string.achievement_idiom_perfect_description, "idiom.game.perfect", 1L, "learning", true),
        AchievementDefinition("IDIOM_FINISH_5", R.string.achievement_idiom_finish_5_title, R.string.achievement_idiom_finish_5_description, "idiom.game.finished", 5L, "learning", true),
        AchievementDefinition("IDIOM_LIB_1", R.string.achievement_idiom_lib_1_title, R.string.achievement_idiom_lib_1_description, "idiom.library.added", 1L, "learning", true),
        AchievementDefinition("PHRASE_PERFECT", R.string.achievement_phrase_perfect_title, R.string.achievement_phrase_perfect_description, "phrase_demo.game.perfect", 1L, "learning", true),
        AchievementDefinition("BACKUP_EXPORT_1", R.string.achievement_backup_export_1_title, R.string.achievement_backup_export_1_description, "backup.export.succeeded", 1L, "sharing", true),
        AchievementDefinition("BACKUP_IMPORT_1", R.string.achievement_backup_import_1_title, R.string.achievement_backup_import_1_description, "backup.import.succeeded", 1L, "sharing", true),
        AchievementDefinition("CLIPBOARD_1", R.string.achievement_clipboard_1_title, R.string.achievement_clipboard_1_description, "card.text.copied", 1L, "sharing", true),
        AchievementDefinition("NOTE_CREATE_1", R.string.achievement_note_create_1_title, R.string.achievement_note_create_1_description, "note.created.manual", 1L, "collections", true),
        AchievementDefinition("RECIPE_CREATE_1", R.string.achievement_recipe_create_1_title, R.string.achievement_recipe_create_1_description, "recipe.created", 1L, "collections", false),
    )
    val visible: List<AchievementDefinition> get() = definitions.filter { it.available }
}
