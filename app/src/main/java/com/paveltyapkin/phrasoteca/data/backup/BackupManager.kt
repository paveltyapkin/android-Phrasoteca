package com.paveltyapkin.phrasoteca.data.backup

import androidx.room.withTransaction
import com.paveltyapkin.phrasoteca.AchievementCatalog
import com.paveltyapkin.phrasoteca.CollectionIcons
import com.paveltyapkin.phrasoteca.data.local.*
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val database: PhrasotecaDatabase) {
    companion object {
        const val MAX_FILE_SIZE = 5 * 1024 * 1024
        private const val FORMAT = "phrasoteca"
        private const val VERSION = 6
        private const val MAX_COLLECTIONS = 500
        private const val MAX_CARDS = 10000
        private const val MAX_FOLDERS = 10000
        private const val MAX_ACHIEVEMENT_METRICS = 100
        private const val MAX_ACHIEVEMENT_UNLOCKS = 100
    }

    private data class BFolder(val oldId: Long, val name: String, val parentId: Long?, val order: Int)
    private data class BCard(
        val front: String, val back: String, val frontLanguage: String, val backLanguage: String,
        val order: Int, val folderId: Long?, val type: String?, val items: List<Pair<String, Boolean>>,
        val interactionMode: String, val isFavorite: Boolean
    )
    private data class BCollection(
        val name: String, val type: String, val frontLabel: String, val backLabel: String,
        val frontColor: String, val backColor: String, val sideMode: String,
        val frontLanguageTag: String, val backLanguageTag: String, val iconKey: String,
        val folders: List<BFolder>, val cards: List<BCard>
    )

    private data class BMetric(val key: String, val value: Long)
    private data class BUnlock(val id: String, val unlockedAt: Long, val seen: Boolean)
    private data class BProgress(val metrics: List<BMetric>, val unlocks: List<BUnlock>)

    /** No achievement progress in collection-only exports (including QR exchange). */
    private suspend fun achievementBackup(): JSONObject {
        val dao = database.achievementDao()
        return JSONObject().apply {
            put("metrics", JSONArray().apply {
                dao.metrics().forEach { metric ->
                    put(JSONObject().apply {
                        put("key", metric.metricKey)
                        put("value", metric.value)
                    })
                }
            })
            put("unlocks", JSONArray().apply {
                dao.unlocks().forEach { unlock ->
                    put(JSONObject().apply {
                        put("id", unlock.achievementId)
                        put("unlockedAt", unlock.unlockedAt)
                        put("seen", unlock.seen)
                    })
                }
            })
        }
    }

    /** Completely validate the optional v6 payload before beginning any import transaction. */
    private fun parseAchievementBackup(text: String): BProgress? {
        val root = JSONObject(text)
        val version = root.getInt("version")
        if (version < 6 || !root.has("achievements")) return null
        val obj = root.getJSONObject("achievements")
        val metricsArray = obj.getJSONArray("metrics")
        val unlocksArray = obj.getJSONArray("unlocks")
        require(metricsArray.length() <= MAX_ACHIEVEMENT_METRICS) { "Too many achievement metrics" }
        require(unlocksArray.length() <= MAX_ACHIEVEMENT_UNLOCKS) { "Too many achievement unlocks" }
        val allowedKeys = AchievementCatalog.definitions.map { it.metricKey }.toSet()
        val allowedIds = AchievementCatalog.definitions.map { it.id }.toSet()
        val metrics = (0 until metricsArray.length()).map { index ->
            val entry = metricsArray.getJSONObject(index)
            val key = entry.getString("key")
            val value = entry.getLong("value")
            require(key in allowedKeys && value >= 0L) { "Invalid achievement metric" }
            BMetric(key, value)
        }
        val unlocks = (0 until unlocksArray.length()).map { index ->
            val entry = unlocksArray.getJSONObject(index)
            val id = entry.getString("id")
            val unlockedAt = entry.getLong("unlockedAt")
            val seen = entry.getBoolean("seen")
            require(id in allowedIds && unlockedAt > 0L) { "Invalid achievement unlock" }
            BUnlock(id, unlockedAt, seen)
        }
        require(metrics.map { it.key }.toSet().size == metrics.size) { "Duplicate achievement metric" }
        require(unlocks.map { it.id }.toSet().size == unlocks.size) { "Duplicate achievement unlock" }
        return BProgress(metrics, unlocks)
    }

    /** Merge without reducing local progress, revoking unlocks or re-triggering notifications. */
    private suspend fun mergeAchievements(progress: BProgress) {
        val dao = database.achievementDao()
        val existingMetrics = dao.metrics().associateBy { it.metricKey }
        for (metric in progress.metrics) {
            val merged = maxOf(existingMetrics[metric.key]?.value ?: 0L, metric.value)
            if (existingMetrics[metric.key]?.value != merged) {
                dao.setMetric(AchievementMetricEntity(metric.key, merged))
            }
        }
        val existingUnlocks = dao.unlocks().associateBy { it.achievementId }
        for (unlock in progress.unlocks) {
            val old = existingUnlocks[unlock.id]
            if (old == null) {
                // Restored rewards are already acknowledged; no surprise notification on Home.
                dao.unlock(AchievementUnlockEntity(unlock.id, unlock.unlockedAt, seen = true))
            } else {
                val restored = AchievementUnlockEntity(
                    unlock.id,
                    minOf(old.unlockedAt, unlock.unlockedAt),
                    seen = old.seen || unlock.seen
                )
                if (restored != old) dao.replaceUnlock(restored)
            }
        }
    }

    suspend fun exportJson(collectionId: Long? = null): String = database.withTransaction {
        val collections = if (collectionId == null) database.collectionDao().getAll().filter { it.defaultCardType != "VAULT" }
        else listOfNotNull(database.collectionDao().getById(collectionId)).also {
            require(it.isNotEmpty()) { "Collection not found" }
        }
        val result = JSONArray()
        for (collection in collections) {
            // VAULT is deliberately not supported until a separately encrypted export exists.
            require(collection.defaultCardType != "VAULT") { "Vault export is not supported" }
            val folders = JSONArray()
            database.folderDao().getByCollection(collection.id).forEach { f ->
                folders.put(JSONObject().apply {
                    put("id", f.id); put("name", f.name); put("sortOrder", f.sortOrder)
                    put("parentFolderId", f.parentFolderId ?: JSONObject.NULL)
                })
            }
            val cards = JSONArray()
            database.cardDao().getByCollection(collection.id).forEach { c ->
                require(c.cardType != "VAULT") { "Vault export is not supported" }
                val checklistItems = JSONArray()
                database.checklistItemDao().getForCard(c.id).forEach { item ->
                    checklistItems.put(JSONObject().apply {
                        put("text", item.text); put("checked", item.checked)
                    })
                }
                cards.put(JSONObject().apply {
                    put("items", checklistItems)
                    put("frontText", c.frontText); put("backText", c.backText)
                    put("frontLanguage", c.frontLanguage); put("backLanguage", c.backLanguage)
                    put("sortOrder", c.sortOrder)
                    put("folderId", c.folderId ?: JSONObject.NULL)
                    put("cardType", c.cardType ?: JSONObject.NULL)
                    put("interactionMode", c.interactionMode)
                    put("isFavorite", c.isFavorite)
                })
            }
            result.put(JSONObject().apply {
                put("name", collection.name); put("defaultCardType", collection.defaultCardType)
                put("frontLabel", collection.frontLabel); put("backLabel", collection.backLabel)
                put("frontColor", collection.frontColor); put("backColor", collection.backColor)
                put("sideMode", collection.sideMode)
                put("frontLanguageTag", collection.frontLanguageTag); put("backLanguageTag", collection.backLanguageTag)
                if (collection.iconKey.isNotEmpty()) put("iconKey", collection.iconKey)
                put("folders", folders); put("cards", cards)
            })
        }
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("collections", result)
            if (collectionId == null) put("achievements", achievementBackup())
        }.toString(2)
    }

    suspend fun importJson(jsonText: String): Int {
        require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_FILE_SIZE) { "Backup file is too large" }
        // Validate the complete backup before modifying Room.
        val imported = parseBackup(jsonText)
        val achievementProgress = parseAchievementBackup(jsonText)
        return database.withTransaction {
            val names = database.collectionDao().getAll().map { it.name.lowercase() }.toMutableSet()
            for (c in imported) {
                val id = database.collectionDao().insert(CollectionEntity(
                    name = uniqueName(c.name, names),
                    sortOrder = database.collectionDao().nextSortOrder(),
                    defaultCardType = c.type, frontLabel = c.frontLabel, backLabel = c.backLabel,
                    frontColor = c.frontColor, backColor = c.backColor, sideMode = c.sideMode,
                    frontLanguageTag = c.frontLanguageTag, backLanguageTag = c.backLanguageTag,
                    iconKey = c.iconKey
                ))
                val remap = mutableMapOf<Long, Long>()
                // All parent IDs are validated; recursive order handles arbitrary hierarchy.
                suspend fun insertFolder(oldId: Long): Long {
                    remap[oldId]?.let { return it }
                    val f = c.folders.first { it.oldId == oldId }
                    val parent = f.parentId?.let { insertFolder(it) }
                    val newId = database.folderDao().insert(FolderEntity(
                        collectionId = id, name = f.name, parentFolderId = parent, sortOrder = f.order
                    ))
                    remap[oldId] = newId
                    return newId
                }
                c.folders.forEach { insertFolder(it.oldId) }
                c.cards.forEachIndexed { index, card ->
                    val newCardId = database.cardDao().insert(CardEntity(
                        collectionId = id, frontText = card.front, backText = card.back,
                        frontLanguage = card.frontLanguage, backLanguage = card.backLanguage,
                        sortOrder = card.order.takeIf { it > 0 } ?: index + 1,
                        folderId = card.folderId?.let { remap.getValue(it) }, cardType = card.type,
                        interactionMode = card.interactionMode,
                        isFavorite = card.isFavorite
                    ))
                    card.items.forEachIndexed { itemIndex, item ->
                        database.checklistItemDao().insert(ChecklistItemEntity(
                            cardId = newCardId, text = item.first,
                            checked = item.second, sortOrder = itemIndex
                        ))
                    }
                }
            }
            if (achievementProgress != null) mergeAchievements(achievementProgress)
            imported.size
        }
    }

    /** Merge exactly one public collection into an existing compatible collection.
     * Destination appearance is preserved; imported folders receive new IDs.
     */
    suspend fun importJsonIntoCollection(jsonText: String, targetId: Long): Int {
        require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_FILE_SIZE)
        val source = parseBackup(jsonText).single()
        require(source.type != "VAULT")
        return database.withTransaction {
            val target = database.collectionDao().getById(targetId)
                ?: throw IllegalArgumentException("Destination not found")
            require(target.defaultCardType == source.type && target.sideMode == source.sideMode) {
                "Collection type or sidedness does not match"
            }
            val remap = mutableMapOf<Long, Long>()
            suspend fun insertFolder(oldId: Long): Long {
                remap[oldId]?.let { return it }
                val folder = source.folders.first { it.oldId == oldId }
                val parent = folder.parentId?.let { insertFolder(it) }
                val newId = database.folderDao().insert(FolderEntity(
                    collectionId = targetId, name = folder.name, parentFolderId = parent,
                    sortOrder = database.folderDao().nextSortOrder(targetId)
                ))
                remap[oldId] = newId
                return newId
            }
            source.folders.forEach { insertFolder(it.oldId) }
            source.cards.forEach { card ->
                val newId = database.cardDao().insert(CardEntity(
                    collectionId = targetId, frontText = card.front, backText = card.back,
                    frontLanguage = card.frontLanguage, backLanguage = card.backLanguage,
                    sortOrder = database.cardDao().nextSortOrder(targetId),
                    folderId = card.folderId?.let { remap.getValue(it) }, cardType = card.type,
                    interactionMode = card.interactionMode,
                    isFavorite = card.isFavorite
                ))
                card.items.forEachIndexed { index, item ->
                    database.checklistItemDao().insert(ChecklistItemEntity(
                        cardId = newId, text = item.first, checked = item.second, sortOrder = index
                    ))
                }
            }
            source.cards.size
        }
    }

    private fun parseBackup(text: String): List<BCollection> {
        val root = JSONObject(text)
        require(root.getString("format") == FORMAT) { "Not a Phrasoteca backup" }
        val version = root.getInt("version")
        require(version in 1..VERSION) { "Unsupported backup version" }
        val array = root.getJSONArray("collections")
        require(array.length() <= MAX_COLLECTIONS) { "Too many collections" }
        var cardTotal = 0
        var folderTotal = 0
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val name = requiredText(obj, "name", 200)
            val fArray = if (version >= 2) obj.getJSONArray("folders") else JSONArray()
            folderTotal += fArray.length()
            require(folderTotal <= MAX_FOLDERS) { "Too many folders" }
            val folders = (0 until fArray.length()).map { j ->
                val f = fArray.getJSONObject(j)
                BFolder(f.getLong("id"), requiredText(f, "name", 200),
                    if (f.isNull("parentFolderId")) null else f.getLong("parentFolderId"),
                    f.getInt("sortOrder"))
            }
            val ids = folders.map { it.oldId }.toSet()
            require(ids.size == folders.size && ids.all { it > 0 }) { "Duplicate or invalid folder ID" }
            folders.forEach { f ->
                require(f.parentId == null || (f.parentId in ids && f.parentId != f.oldId)) { "Invalid parent folder" }
                val visited = mutableSetOf(f.oldId)
                var parent = f.parentId
                while (parent != null) {
                    require(visited.add(parent)) { "Cyclic folder structure" }
                    parent = folders.first { it.oldId == parent }.parentId
                }
            }
            val cArray = obj.getJSONArray("cards")
            cardTotal += cArray.length()
            require(cardTotal <= MAX_CARDS) { "Too many cards" }
            val cards = (0 until cArray.length()).map { j ->
                val c = cArray.getJSONObject(j)
                val folderId = if (version >= 2 && !c.isNull("folderId")) c.getLong("folderId") else null
                require(folderId == null || folderId in ids) { "Unknown folder" }
                val type = if (version >= 2 && !c.isNull("cardType")) c.getString("cardType") else null
                require(type == null || type in setOf("GENERAL", "LANGUAGE", "CHECKLIST")) { "Invalid card type" }
                val items = if (version >= 3) {
                    val itemArray = c.optJSONArray("items") ?: JSONArray()
                    require(itemArray.length() <= 500) { "Too many checklist items" }
                    (0 until itemArray.length()).map { k ->
                        val item = itemArray.getJSONObject(k)
                        requiredText(item, "text", 1000) to item.getBoolean("checked")
                    }
                } else emptyList()
                val interactionMode = if (version >= 5) c.optString("interactionMode", "NORMAL") else "NORMAL"
                require(interactionMode in setOf("NORMAL", "YES_NO", "QUANTITY")) { "Invalid interaction mode" }
                // Optional for existing v1-v6 backups; reject coercion of strings/numbers.
                val isFavorite = if (c.has("isFavorite")) {
                    require(c.get("isFavorite") is Boolean) { "Invalid favorite flag" }
                    c.getBoolean("isFavorite")
                } else false
                BCard(requiredTextAllowEmpty(c, "frontText", 10000),
                    optionalText(c, "backText", 10000),
                    optionalText(c, "frontLanguage", 80), optionalText(c, "backLanguage", 80),
                    if (version >= 2) c.getInt("sortOrder") else j + 1, folderId, type, items, interactionMode, isFavorite)
            }
            val type = if (version >= 2) obj.getString("defaultCardType") else "LANGUAGE"
            val sideMode = if (version >= 2) obj.getString("sideMode") else "TWO_SIDED"
            require(type in setOf("GENERAL", "LANGUAGE", "CHECKLIST")) { "Unsupported collection type" }
            require(sideMode in setOf("ONE_SIDED", "TWO_SIDED")) { "Invalid side mode" }
            fun label(key: String, fallback: String) = if (version >= 2) requiredText(obj, key, 80) else fallback
            fun color(key: String, fallback: String): String {
                val value = if (version >= 2) obj.getString(key) else fallback
                require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "Invalid color" }
                return value
            }
            fun languageTag(key: String): String {
                val tag = if (version >= 4) obj.optString(key, "") else ""
                require(tag.isEmpty() || tag.matches(Regex("[a-z]{2,3}(-[A-Za-z]{2,8}){0,3}"))) {
                    "Invalid language tag"
                }
                return tag
            }
            BCollection(name, type, label("frontLabel", "Front"), label("backLabel", "Back"),
                color("frontColor", "#F2F4FD"), color("backColor", "#E5F3ED"), sideMode,
                if (type == "LANGUAGE") languageTag("frontLanguageTag") else "",
                if (type == "LANGUAGE") languageTag("backLanguageTag") else "",
                CollectionIcons.validatedKey(obj), folders, cards)
        }
    }

    // A new inline-editor card can legitimately have an empty front side.
    private fun requiredTextAllowEmpty(obj: JSONObject, key: String, max: Int): String {
        require(obj.get(key) is String) { "Invalid field: $key" }
        return obj.getString(key).also { require(it.length <= max) { "Invalid field: $key" } }
    }

    private fun requiredText(obj: JSONObject, key: String, max: Int): String {
        require(obj.get(key) is String) { "Invalid field: $key" }
        return obj.getString(key).trim().also { require(it.isNotEmpty() && it.length <= max) { "Invalid field: $key" } }
    }
    private fun optionalText(obj: JSONObject, key: String, max: Int): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        require(obj.get(key) is String) { "Invalid field: $key" }
        return obj.getString(key).also { require(it.length <= max) { "Invalid field: $key" } }
    }
    private fun uniqueName(original: String, names: MutableSet<String>): String {
        if (names.add(original.lowercase())) return original
        var n = 1
        while (true) {
            val suffix = if (n == 1) " (imported)" else " (imported $n)"
            val name = original.take(200 - suffix.length) + suffix
            if (names.add(name.lowercase())) return name
            n++
        }
    }
}
