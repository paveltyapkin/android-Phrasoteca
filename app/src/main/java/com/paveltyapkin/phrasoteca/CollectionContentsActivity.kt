package com.paveltyapkin.phrasoteca

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.BaseAdapter
import android.view.LayoutInflater
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.widget.ListView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.paveltyapkin.phrasoteca.data.local.CardEntity
import com.paveltyapkin.phrasoteca.data.local.FolderEntity
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase
import kotlinx.coroutines.launch

/** Displays one collection's root folders + cards, or the cards inside one folder. */
class CollectionContentsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_COLLECTION_ID = "contents_collection_id"
        const val EXTRA_FOLDER_ID = "contents_folder_id"
        const val EXTRA_SHOW_ALL = "contents_show_all"
    }

    private sealed class Entry {
        data class Folder(val data: FolderEntity, val count: Int) : Entry()
        data class Card(val data: CardEntity) : Entry()
        data class AllCards(val count: Int) : Entry()
    }

    private lateinit var database: PhrasotecaDatabase
    private lateinit var toolbar: Toolbar
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var fab: ExtendedFloatingActionButton
    private val entries = mutableListOf<Entry>()
    private val displayedEntries = mutableListOf<Entry>()
    private lateinit var search: EditText
    private lateinit var adapter: BaseAdapter
    private var collectionId = -1L
    private var folderId: Long? = null
    private var showAll = false
    private var collectionName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectionId = intent.getLongExtra(EXTRA_COLLECTION_ID, -1L)
        folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L).takeIf { it > 0 }
        showAll = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)
        if (collectionId <= 0) { finish(); return }
        enableEdgeToEdge()
        setContentView(R.layout.activity_collection_contents)
        database = PhrasotecaDatabase.getInstance(this)
        toolbar = findViewById(R.id.contentsToolbar)
        list = findViewById(R.id.listContents)
        search = findViewById(R.id.contentsSearch)
        search.visibility = if (showAll) View.VISIBLE else View.GONE
        empty = findViewById(R.id.textContentsEmpty)
        fab = findViewById(R.id.fabNewFolder)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.contents_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.contentsViewCards -> { openViewer(); true }
                R.id.contentsNewFolder -> { showFolderEditor(null); true }
                R.id.contentsOrganize -> { organizeExistingCatalog(); true }
                else -> false
            }
        }
        fab.setOnClickListener { anchor ->
            if (folderId != null || showAll) {
                createCardInCurrentFolder()
            } else {
                PopupMenu(this, anchor).apply {
                    menu.add(0, 1, 0, R.string.new_folder)
                    menu.add(0, 2, 1, R.string.new_card)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { showFolderEditor(null); true }
                            2 -> { createCardInCurrentFolder(); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = displayedEntries.size
            override fun getItem(position: Int): Entry = displayedEntries[position]
            override fun getItemId(position: Int): Long = when (val entry = getItem(position)) {
                is Entry.Folder -> entry.data.id
                is Entry.Card -> entry.data.id
                is Entry.AllCards -> -1L
            }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(this@CollectionContentsActivity)
                    .inflate(R.layout.item_collection_content, parent, false)
                val icon = row.findViewById<ImageView>(R.id.contentItemIcon)
                val title = row.findViewById<TextView>(R.id.contentItemTitle)
                val detail = row.findViewById<TextView>(R.id.contentItemSubtitle)
                when (val entry = getItem(position)) {
                    is Entry.Folder -> {
                        row.setBackgroundResource(R.drawable.bg_home_tile_language)
                        icon.setImageResource(R.drawable.ic_content_folder)
                        title.text = entry.data.name
                        detail.text = getString(R.string.contents_item_count, entry.count)
                    }
                    is Entry.Card -> {
                        row.setBackgroundResource(R.drawable.bg_home_tile_general)
                        icon.setImageResource(R.drawable.ic_content_card)
                        title.text = entry.data.frontText.ifBlank { getString(R.string.inline_card_enter_text) }
                        detail.text = entry.data.backText.ifBlank { "" }.lineSequence().firstOrNull().orEmpty()
                        detail.visibility = if (detail.text.isBlank()) View.GONE else View.VISIBLE
                    }
                    is Entry.AllCards -> {
                        row.setBackgroundResource(R.drawable.bg_home_tile_favorites)
                        icon.setImageResource(R.drawable.ic_content_all)
                        title.setText(R.string.catalog_all_cards)
                        detail.text = getString(R.string.contents_item_count, entry.count)
                    }
                }
                if (getItem(position) !is Entry.Card) detail.visibility = View.VISIBLE
                return row
            }
        }
        list.adapter = adapter
        list.emptyView = empty
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderEntries()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            when (val entry = displayedEntries[position]) {
                is Entry.Folder -> startActivity(Intent(this, CollectionContentsActivity::class.java).apply {
                    putExtra(EXTRA_COLLECTION_ID, collectionId)
                    putExtra(EXTRA_FOLDER_ID, entry.data.id)
                })
                is Entry.Card -> openViewer(entry.data.id)
                is Entry.AllCards -> startActivity(Intent(this, CollectionContentsActivity::class.java).apply {
                    putExtra(EXTRA_COLLECTION_ID, collectionId)
                    putExtra(EXTRA_SHOW_ALL, true)
                })
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            when (val entry = displayedEntries[position]) {
                is Entry.Folder -> folderActions(entry.data)
                is Entry.Card -> cardActions(entry.data)
                is Entry.AllCards -> openViewer()
            }
            true
        }
        // Insets stay on the root, including the navigation bar in edge-to-edge mode.
        val root = findViewById<View>(R.id.contentsRoot)
        val l = root.paddingLeft; val t = root.paddingTop
        val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized) {
            applyAppearanceSettings()
            refresh()
        }
    }

    private fun applyAppearanceSettings() {
        val background = when (getSharedPreferences("phrasoteca_settings", MODE_PRIVATE)
            .getString("home_background_style", "waves")) {
            "landscape" -> R.drawable.bg_home_landscape
            "gradient" -> R.drawable.bg_home_gradient
            "plain" -> R.drawable.bg_home_plain
            else -> R.drawable.bg_home_waves
        }
        findViewById<View>(R.id.contentsRoot).setBackgroundResource(background)
    }

    private fun refresh() {
        lifecycleScope.launch {
            try {
                val collection = database.collectionDao().getById(collectionId)
                if (collection == null) { finish(); return@launch }
                collectionName = collection.name
                val currentFolder = folderId?.let { database.folderDao().getById(it) }
                if (folderId != null && (currentFolder == null || currentFolder.collectionId != collectionId)) {
                    finish(); return@launch
                }
                toolbar.title = if (showAll) getString(R.string.catalog_all_cards)
                                else currentFolder?.name ?: collectionName
                entries.clear()
                if (folderId == null && !showAll) {
                    val total = database.cardDao().getByCollection(collectionId).size
                    if (total > 0) entries.add(Entry.AllCards(total))
                    database.folderDao().getRoots(collectionId).forEach { folder ->
                        entries.add(Entry.Folder(folder, database.cardDao().getInFolder(collectionId, folder.id).size))
                    }
                }
                val cards = when {
                    showAll -> database.cardDao().getByCollection(collectionId)
                    folderId == null -> database.cardDao().getAtCollectionRoot(collectionId)
                    else -> database.cardDao().getInFolder(collectionId, folderId!!)
                }
                cards.forEach { entries.add(Entry.Card(it)) }
                renderEntries()
                fab.visibility = if (showAll) View.GONE else View.VISIBLE
                fab.setText(if (folderId == null) R.string.add_card else R.string.new_card)
                toolbar.menu.findItem(R.id.contentsNewFolder)?.isVisible = folderId == null && !showAll
                toolbar.menu.findItem(R.id.contentsOrganize)?.isVisible = folderId == null && !showAll &&
                    collection.defaultCardType == "LANGUAGE" &&
                    setOf(collection.frontLanguageTag, collection.backLanguageTag).any { it.startsWith("ru") } &&
                    setOf(collection.frontLanguageTag, collection.backLanguageTag).any { it.startsWith("en") }
            } catch (e: Exception) { showError(e) }
        }
    }

    private fun renderEntries() {
        val query = if (showAll) search.text.toString().trim() else ""
        displayedEntries.clear()
        displayedEntries.addAll(if (query.isEmpty()) entries else entries.filter { entry ->
            entry is Entry.Card && (entry.data.frontText.contains(query, ignoreCase = true) ||
                entry.data.backText.contains(query, ignoreCase = true))
        })
        adapter.notifyDataSetChanged()
    }

    private fun openViewer(cardId: Long = -1L) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_COLLECTION_ID, collectionId)
            putExtra(MainActivity.EXTRA_FOLDER_SCOPE, folderId != null)
            putExtra(MainActivity.EXTRA_FOLDER_ID, folderId ?: -1L)
            if (cardId > 0) putExtra(MainActivity.EXTRA_CARD_ID, cardId)
        })
    }

    private fun createCardInCurrentFolder() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_COLLECTION_ID, collectionId)
            putExtra(MainActivity.EXTRA_FOLDER_SCOPE, true)
            putExtra(MainActivity.EXTRA_FOLDER_ID, folderId ?: -1L)
            putExtra(MainActivity.EXTRA_CREATE_CARD, true)
        })
    }

    /** Optional one-time repair for RU/EN collections created before folder import.
     * Matches both sides exactly. Edited or unrelated cards remain where they are.
     * Existing folders are reused and repeated runs are safe.
     */
    private fun organizeExistingCatalog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.catalog_organize)
            .setMessage(R.string.catalog_organize_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.catalog_organize) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val collection = database.collectionDao().getById(collectionId)
                            ?: return@launch
                        val entries = PhraseLibrary.loadEntries(
                            this@CollectionContentsActivity,
                            collection.frontLanguageTag, collection.backLanguageTag,
                            PhraseLibrary.Catalog.PRACTICAL_RU_EN
                        )
                        val matches = entries.associateBy { it.front to it.back }
                        var moved = 0
                        database.withTransaction {
                            val rootCards = database.cardDao().getAtCollectionRoot(collectionId)
                            val matched = rootCards.mapNotNull { card ->
                                matches[card.frontText to card.backText]?.let { card to PhraseLibrary.themeOf(it.category) }
                            }
                            val existingFolders = database.folderDao().getRoots(collectionId)
                            val folders = existingFolders.associate { it.name to it.id }.toMutableMap()
                            var order = database.folderDao().nextSortOrder(collectionId)
                            for ((card, category) in matched) {
                                val label = PhraseLibrary.categoryLabel(this@CollectionContentsActivity, category)
                                val destination = folders[label] ?: database.folderDao().insert(
                                    FolderEntity(collectionId = collectionId, name = label, sortOrder = order++)
                                ).also { folders[label] = it }
                                database.cardDao().moveToFolder(card.id, collectionId, destination)
                                moved++
                            }
                        }
                        Toast.makeText(this@CollectionContentsActivity,
                            getString(R.string.catalog_organized_count, moved), Toast.LENGTH_LONG).show()
                        refresh()
                    } catch (e: Exception) { showError(e) }
                }
            }.show()
    }

    private fun showFolderEditor(existing: FolderEntity?) {
        if (folderId != null || showAll) return
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(existing?.name ?: "")
            selectAll()
            hint = getString(R.string.folder_name)
        }
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.new_folder else R.string.rename_folder)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(this, R.string.folder_name_required, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        if (existing == null) {
                            val order = database.folderDao().nextSortOrder(collectionId)
                            database.folderDao().insert(FolderEntity(collectionId = collectionId, name = name, sortOrder = order))
                        } else database.folderDao().update(existing.copy(name = name))
                        refresh()
                    } catch (e: Exception) { showError(e) }
                }
            }.show()
    }

    private fun folderActions(folder: FolderEntity) {
        val choices = arrayOf(getString(R.string.rename_folder), getString(R.string.delete_folder))
        AlertDialog.Builder(this).setTitle(folder.name).setItems(choices) { _, which ->
            if (which == 0) showFolderEditor(folder) else {
                AlertDialog.Builder(this).setTitle(R.string.delete_folder)
                    .setMessage(R.string.delete_folder_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                database.withTransaction {
                                    // Keep the cards: move them to the collection root first.
                                    database.cardDao().moveAllToRoot(collectionId, folder.id)
                                    database.folderDao().delete(folder)
                                }
                                refresh()
                            } catch (e: Exception) { showError(e) }
                        }
                    }.show()
            }
        }.show()
    }

    private fun cardActions(card: CardEntity) {
        lifecycleScope.launch {
            try {
                val folders = database.folderDao().getRoots(collectionId)
                val labels = listOf(getString(R.string.collection_root)) + folders.map { it.name }
                AlertDialog.Builder(this@CollectionContentsActivity).setTitle(R.string.move_card_to_folder)
                    .setSingleChoiceItems(labels.toTypedArray(), folders.indexOfFirst { it.id == card.folderId } + 1) { dialog, position ->
                        val newFolderId = if (position == 0) null else folders[position - 1].id
                        lifecycleScope.launch {
                            try {
                                database.cardDao().moveToFolder(card.id, collectionId, newFolderId)
                                dialog.dismiss(); refresh()
                            } catch (e: Exception) { showError(e) }
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            } catch (e: Exception) { showError(e) }
        }
    }

    private fun showError(e: Exception) {
        android.util.Log.e("Phrasoteca", "Collection contents operation failed", e)
        Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show()
    }
}
