package com.paveltyapkin.phrasoteca

import android.content.Intent
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.BaseAdapter
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction

import com.paveltyapkin.phrasoteca.data.vault.VaultRepository
import com.paveltyapkin.phrasoteca.data.DemoCardRepository
import com.paveltyapkin.phrasoteca.data.local.FolderEntity
import com.paveltyapkin.phrasoteca.data.local.CardEntity
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity
import com.paveltyapkin.phrasoteca.data.local.CollectionSummary
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase
import android.text.InputType

import android.net.Uri
import android.util.Log

import androidx.activity.result.contract.ActivityResultContracts

import com.paveltyapkin.phrasoteca.data.backup.BackupManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.android.material.snackbar.Snackbar

import java.io.ByteArrayOutputStream
import java.io.IOException
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.paveltyapkin.phrasoteca.data.local.FavoriteCardShortcut
import com.paveltyapkin.phrasoteca.data.local.CardSearchResult
import androidx.appcompat.widget.Toolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton



class HomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "phrasoteca_settings"
        private const val KEY_INITIALIZED = "database_initialized"
    }

    private lateinit var database: PhrasotecaDatabase
    private lateinit var achievements: AchievementRepository
    private var pendingAchievementNotice: Job? = null
    private lateinit var buttonNewCollection: ExtendedFloatingActionButton
    private lateinit var homeToolbar: Toolbar
    private lateinit var listCollections: ListView
    private lateinit var textEmpty: TextView
    private lateinit var favoritesTile: View
    private lateinit var favoritesTileSubtitle: TextView

    private val collections = mutableListOf<CollectionSummary>()
    private val visibleCollections = mutableListOf<CollectionSummary>()

    private lateinit var collectionAdapter: BaseAdapter

    private var appliedCollectionLayout: String? = null

    private var isBusy = false
    private var isLoaded = false

    // Backup manager
    private lateinit var backupManager: BackupManager
    private var pendingExternalJsonUri: Uri? = null
    private var externalImportDialogVisible = false


    // Null means export the complete library
    private var pendingExportCollectionId: Long? = null

    // System file picker for importing a JSON backup
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null) {
            importFromUri(uri)
        }
    }

    // System file picker for exporting a JSON backup
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->

        if (uri != null) {
            exportToUri(uri)
        }
    }


    private val collectionQrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { importCollectionQrText(it) }
    }

    private val collectionQrImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    CardQrCodec.decodeImage(contentResolver, uri)
                }
                importCollectionQrText(raw)
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, R.string.qr_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val preferences by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    // --------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        database = PhrasotecaDatabase.getInstance(this)
        achievements = AchievementRepository(database)

        backupManager = BackupManager(database)

        pendingExportCollectionId =
            savedInstanceState?.let {
                if (it.getBoolean("has_pending_export", false)) {
                    it.getLong("pending_export_collection_id")
                } else {
                    null
                }
            }

        initializeViews()
        applyWindowInsets()
        initializeAdapter()
        applyAppearanceSettings()
        initializeListeners()
        updateBackupButtons()
        handleExternalJsonIntent(intent)
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalJsonIntent(intent)
    }

    private fun handleExternalJsonIntent(incoming: Intent?) {
        val uri = when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: incoming.clipData?.getItemAt(0)?.uri
            }
            else -> null
        } ?: return

        if (uri.scheme != "content" && uri.scheme != "file") return
        // The intent is consumed once, so recreation never silently re-imports it.
        incoming?.action = null
        pendingExternalJsonUri = uri
        showPendingExternalImport()
    }

    private fun showPendingExternalImport() {
        val uri = pendingExternalJsonUri ?: return
        if (externalImportDialogVisible || !::backupManager.isInitialized) return
        // onCreate receives the intent before the first onResume. Defer its
        // dialog until initialization has completed; never import silently.
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || isBusy) return
        externalImportDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(R.string.external_json_import_title)
            .setMessage(R.string.external_json_import_message)
            .setPositiveButton(R.string.import_collections) { _, _ ->
                pendingExternalJsonUri = null
                importFromUri(uri)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingExternalJsonUri = null }
            .setOnDismissListener {
                externalImportDialogVisible = false
                // Dismiss without explicitly choosing Import also cancels.
                if (pendingExternalJsonUri == uri) pendingExternalJsonUri = null
            }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val id = pendingExportCollectionId

        outState.putBoolean(
            "has_pending_export",
            id != null
        )

        if (id != null) {
            outState.putLong(
                "pending_export_collection_id",
                id
            )
        }
    }


    override fun onResume() {
        super.onResume()
        AchievementNoticeCenter.setHomeListener { scheduleAchievementNotice() }
        scheduleAchievementNotice()
        applyAppearanceSettings()

        lifecycleScope.launch {
            runDatabaseOperation {
                initializeDemoDataIfNeeded()
                TutorialLibrary.initializeIfNeeded(this@HomeActivity, database, preferences)
                refreshCollections()
            }
            showPendingExternalImport()
        }
    }


    override fun onPause() {
        AchievementNoticeCenter.setHomeListener(null)
        pendingAchievementNotice?.cancel()
        pendingAchievementNotice = null
        super.onPause()
    }

    /** Brief, soundless, in-app banner shown only on the home screen.
     *  Multiple unlocks produced by one operation are combined.
     *  Card presentation, challenge and vault screens never receive an overlay.
     */
    private fun scheduleAchievementNotice() {
        pendingAchievementNotice?.cancel()
        pendingAchievementNotice = lifecycleScope.launch {
            delay(250L) // Coalesce consecutive unlocks without timers or notifications.
            val unseen = try {
                achievements.snapshot().unlocks.values.filter { !it.seen }
            } catch (e: Exception) {
                Log.e("PhrasotecaAchievements", "Unable to load unseen achievements", e)
                return@launch
            }
            if (unseen.isEmpty()) return@launch
            val title = if (unseen.size == 1) {
                val definition = AchievementCatalog.visible.firstOrNull { it.id == unseen.first().achievementId }
                if (definition != null) getString(definition.titleRes)
                else getString(R.string.achievement_notice_generic)
            } else null
            val message = if (title != null) {
                getString(R.string.achievement_notice_single, title)
            } else {
                getString(R.string.achievement_notice_multiple, unseen.size)
            }
            Snackbar.make(findViewById(R.id.homeRoot), message, 3800).show()
            try {
                for (item in unseen) database.achievementDao().markSeen(item.achievementId)
            } catch (e: Exception) {
                Log.e("PhrasotecaAchievements", "Unable to mark achievements as seen", e)
            }
        }
    }

    // --------------------------------------------------------
    // Initialization
    // --------------------------------------------------------

    private fun initializeViews() {
        homeToolbar = findViewById(R.id.homeToolbar)
        buttonNewCollection =
            findViewById(R.id.buttonNewCollection)

        listCollections =
            findViewById(R.id.listCollections)

        textEmpty = findViewById(R.id.textEmpty)
        favoritesTile = findViewById(R.id.favoritesTile)
        favoritesTileSubtitle = findViewById(R.id.favoritesTileSubtitle)
    }

    /** Apply persisted appearance after returning from SettingsActivity. */
    private fun applyAppearanceSettings() {
        val background = when (preferences.getString("home_background_style", "waves")) {
            "landscape" -> R.drawable.bg_home_landscape
            "gradient" -> R.drawable.bg_home_gradient
            "plain" -> R.drawable.bg_home_plain
            else -> R.drawable.bg_home_waves
        }
        findViewById<View>(R.id.homeRoot).setBackgroundResource(background)
        val layoutMode = preferences.getString("home_collection_layout", "cards") ?: "cards"
        if (appliedCollectionLayout != layoutMode) {
            appliedCollectionLayout = layoutMode
            if (::collectionAdapter.isInitialized) collectionAdapter.notifyDataSetChanged()
        }
    }

    private fun initializeAdapter() {

        collectionAdapter = object : BaseAdapter() {

            override fun getCount(): Int =
                visibleCollections.size

            override fun getItem(position: Int): CollectionSummary =
                visibleCollections[position]

            override fun getItemId(position: Int): Long =
                visibleCollections[position].id

            override fun hasStableIds(): Boolean = true

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View {

                // Keep BaseAdapter and ListView event handling intact; only the
                // visual representation of each row changes in stage 5.2.2.
                val layoutMode = preferences.getString("home_collection_layout", "cards")
                val layoutRes = if (layoutMode == "list")
                    R.layout.item_home_collection_compact else R.layout.item_home_collection
                // Tag prevents recycling a tile into a compact row (or vice versa).
                val view = if (convertView?.tag == layoutRes) convertView
                    else layoutInflater.inflate(layoutRes, parent, false).apply { tag = layoutRes }
                val collection = getItem(position)
                val title = view.findViewById<TextView>(R.id.collectionTileTitle)
                val subtitle = view.findViewById<TextView>(R.id.collectionTileSubtitle)
                val icon = view.findViewById<android.widget.ImageView>(R.id.collectionTileIcon)

                title.text = collection.name
                subtitle.text = if (collection.defaultCardType == "VAULT") {
                    getString(R.string.vault_new_collection)
                } else {
                    resources.getQuantityString(
                        R.plurals.cards_count, collection.cardCount, collection.cardCount
                    )
                }
                when (collection.defaultCardType) {
                    "VAULT" -> {
                        view.setBackgroundResource(R.drawable.bg_home_tile_vault)
                        // Icon is selected below (type fallback or user choice).
                    }
                    "LANGUAGE" -> {
                        view.setBackgroundResource(R.drawable.bg_home_tile_language)
                        // Icon is selected below (type fallback or user choice).
                    }
                    "CHECKLIST" -> {
                        view.setBackgroundResource(R.drawable.bg_home_tile_checklist)
                        // Icon is selected below (type fallback or user choice).
                    }
                    else -> {
                        view.setBackgroundResource(R.drawable.bg_home_tile_general)
                        // Icon is selected below (type fallback or user choice).
                    }
                }
                icon.setImageResource(CollectionIcons.drawable(collection.iconKey, collection.defaultCardType))
                return view
            }
        }

        listCollections.adapter = collectionAdapter
        listCollections.emptyView = textEmpty
    }


    private fun initializeListeners() {

        // --------------------------------------------------------
        // Create collection
        // --------------------------------------------------------
        homeToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_achievements -> {
                    if (!isBusy) startActivity(Intent(this, AchievementsActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    if (!isBusy) startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_search_cards -> {
                    if (!isBusy) showCardSearchDialog()
                    true
                }
                R.id.action_phrase_challenge_demo -> {
                    if (!isBusy) startActivity(Intent(this, PhraseChallengeDemoActivity::class.java))
                    true
                }
                R.id.action_idiom_challenge -> {
                    if (!isBusy) showIdiomChallengeLanguageDialog()
                    true
                }
                R.id.action_import -> {
                    if (!isBusy) {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    }
                    true
                }
                R.id.action_export -> {
                    if (!isBusy && collections.isNotEmpty()) showExportDialog()
                    true
                }
                R.id.action_share_collection_qr -> {
                    if (!isBusy) chooseCollectionToShare()
                    true
                }
                R.id.action_import_collection_qr -> {
                    if (!isBusy) showCollectionQrImportChoices()
                    true
                }
                else -> false
            }
        }

        favoritesTile.setOnClickListener {
            if (!isBusy) startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ALL_FAVORITES, true)
            })
        }

        buttonNewCollection.setOnClickListener {
            if (!isBusy) showNewCollectionChoices()
        }

        // --------------------------------------------------------
        // Open collection
        // --------------------------------------------------------

        listCollections.setOnItemClickListener {
                _, _, position, _ ->

            if (isBusy) return@setOnItemClickListener

            val collection = visibleCollections[position]

            openCollection(collection)
        }

        // --------------------------------------------------------
        // Long press: collection actions
        // --------------------------------------------------------

        listCollections.setOnItemLongClickListener {
                _, _, position, _ ->

            if (!isBusy) {
                showCollectionActions(
                    visibleCollections[position]
                )
            }

            true
        }
    }

    // --------------------------------------------------------
    // Initial demo data
    // --------------------------------------------------------

    private suspend fun initializeDemoDataIfNeeded() {

        if (preferences.getBoolean(KEY_INITIALIZED, false)) {
            return
        }

        database.withTransaction {

            val existing =
                database.collectionDao().getAll()

            if (existing.isEmpty()) {

                val demo =
                    DemoCardRepository.getCollection()

                val collectionId =
                    database.collectionDao().insert(
                        CollectionEntity(name = demo.name)
                    )

                for (card in demo.cards) {

                    database.cardDao().insert(
                        CardEntity(
                            collectionId = collectionId,
                            frontText = card.frontText,
                            backText = card.backText,
                            frontLanguage = card.frontLanguage,
                            backLanguage = card.backLanguage
                        )
                    )
                }
            }
        }

        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .commit()
    }

    // --------------------------------------------------------
    // Loading and filtering
    // --------------------------------------------------------


    private suspend fun refreshCollections() {

        val loaded =
            database.collectionDao().getAllWithCounts()

        collections.clear()
        collections.addAll(loaded)

        // Never expose vault cards through global favorites.
        val favoriteCount = database.cardDao().countPublicFavorites()
        favoritesTile.visibility = if (favoriteCount > 0) View.VISIBLE else View.GONE
        favoritesTileSubtitle.text = resources.getQuantityString(
            R.plurals.cards_count, favoriteCount, favoriteCount
        )

        isLoaded = true

        // Home has a single global card search in the toolbar.
        // Collection-name searching can be integrated into that search later.
        filterCollections("")

        // Synchronize button state with collection data
        updateBackupButtons()
    }


    private fun filterCollections(query: String) {

        val normalized = query.trim()

        visibleCollections.clear()

        visibleCollections.addAll(
            collections.filter {
                it.name.contains(
                    normalized,
                    ignoreCase = true
                )
            }
        )

        if (::collectionAdapter.isInitialized) {
            collectionAdapter.notifyDataSetChanged()
        }

        if (!isLoaded) return

        textEmpty.setText(
            if (collections.isEmpty()) {
                R.string.home_empty
            } else {
                R.string.home_no_results
            }
        )
    }

    /** Step 3.5.1: lightweight shortcuts until the cross-collection viewer (3.5.2). */
    private fun showFavoriteShortcuts() {
        lifecycleScope.launch {
            try {
                val favorites = database.cardDao().getPublicFavoriteShortcuts()
                if (favorites.isEmpty()) {
                    favoritesTile.visibility = View.GONE
                    return@launch
                }
                val labels = favorites.map { card ->
                    "${card.collectionName} — " +
                        card.frontText.replace('\n', ' ').take(100)
                }.toTypedArray()
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle(R.string.favorites_collection_title)
                    .setItems(labels) { _, which ->
                        val selected = favorites[which]
                        // Open the original card; no copied data or synthetic Room collection.
                        startActivity(Intent(this@HomeActivity, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_COLLECTION_ID, selected.collectionId)
                            putExtra(MainActivity.EXTRA_CARD_ID, selected.cardId)
                        })
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                Log.e("Phrasoteca", "Failed to load favorites", e)
                Toast.makeText(this@HomeActivity, R.string.operation_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --------------------------------------------------------
    // Collection actions
    // --------------------------------------------------------

    private fun showCollectionActions(
        collection: CollectionSummary
    ) {

        if (collection.defaultCardType == "VAULT") {
            AlertDialog.Builder(this)
                .setTitle(collection.name)
                .setItems(arrayOf(getString(R.string.open_collection), getString(R.string.delete_collection_short))) { _, which ->
                    if (which == 0) openCollection(collection)
                    else confirmDeleteCollection(collection)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val actions = arrayOf(
            getString(R.string.open_collection),
            getString(R.string.rename_collection_short),
            getString(R.string.delete_collection_short),
            getString(R.string.move_up),
            getString(R.string.move_down)
        )

        AlertDialog.Builder(this)
            .setTitle(collection.name)
            .setItems(actions) { _, which ->

                when (which) {

                    0 -> openCollection(collection)


                    1 -> {
                        lifecycleScope.launch {
                            val entity = database.collectionDao()
                                .getById(collection.id)

                            if (entity != null) {
                                showCollectionEditor(entity)
                            }
                        }
                    }


                    2 -> {
                        confirmDeleteCollection(collection)
                    }


                    3 -> moveCollection(collection.id, -1)
                    4 -> moveCollection(collection.id, 1)

                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --------------------------------------------------------
    // Create and rename collection
    // --------------------------------------------------------

    private fun openCollection(collection: CollectionSummary) {
        if (collection.defaultCardType == "VAULT") {
            startActivity(Intent(this, VaultAccessActivity::class.java).apply {
                putExtra(VaultAccessActivity.EXTRA_VAULT_ID, collection.id)
            })
        } else {
            startActivity(Intent(this, CollectionContentsActivity::class.java).apply {
                putExtra(CollectionContentsActivity.EXTRA_COLLECTION_ID, collection.id)
            })
        }
    }

    private fun showNewCollectionChoices() {
        val choices = arrayOf(
            getString(R.string.vault_regular_collection),
            getString(R.string.phrase_template_short),
            getString(R.string.vault_new_collection)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.new_collection)
            .setItems(choices) { _, choice ->
                when (choice) {
                    0 -> showCollectionEditor(null)
                    1 -> showPhraseCatalogChoices()
                    2 -> showNewVaultName()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openOrInstallTutorial() {
        if (isBusy) return
        lifecycleScope.launch {
            runDatabaseOperation {
                val id = TutorialLibrary.getOrInstall(this@HomeActivity, database, preferences)
                refreshCollections()
                startActivity(Intent(this@HomeActivity, CollectionContentsActivity::class.java).apply {
                    putExtra(CollectionContentsActivity.EXTRA_COLLECTION_ID, id)
                })
            }
        }
    }

    private fun showPhraseCatalogChoices() {
        val options = arrayOf(
            getString(R.string.phrase_catalog_demo),
            getString(R.string.phrase_catalog_ru_en),
            getString(R.string.idioms_catalog_label),
            getString(R.string.tutorial_menu_title)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.phrase_catalog_choose)
            .setItems(options) { _, index ->
                when (index) {
                    2 -> showIdiomLibraryDialog()
                    3 -> openOrInstallTutorial()
                    else -> {
                        val catalog = if (index == 0) PhraseLibrary.Catalog.DEMO
                                      else PhraseLibrary.Catalog.PRACTICAL_RU_EN
                        showPhraseTemplateDialog(catalog)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Play the bundled English idioms; no Room collection or login required. */
    private fun showIdiomChallengeLanguageDialog() {
        val languageChoices = LanguageOptions.supported.filter { option ->
            IdiomLibrary.meaningLocales.any { IdiomLibrary.androidTag(it) == option.tag }
        }
        val labels = languageChoices.map { getString(it.title) }.toTypedArray()
        val defaultIndex = languageChoices.indexOfFirst { it.tag == "ru-RU" }.coerceAtLeast(0)
        var chosen = defaultIndex
        AlertDialog.Builder(this)
            .setTitle(R.string.idiom_game_language_prompt)
            .setSingleChoiceItems(labels, defaultIndex) { _, index -> chosen = index }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.idiom_game_start) { _, _ ->
                val tag = languageChoices[chosen].tag
                val locale = IdiomLibrary.meaningLocales.first { IdiomLibrary.androidTag(it) == tag }
                startActivity(Intent(this, IdiomChallengeActivity::class.java).apply {
                    putExtra(IdiomChallengeActivity.EXTRA_MEANING_LOCALE, locale)
                })
            }
            .show()
    }

    /** Create one independent collection with five language-origin folders. */
    private fun showIdiomLibraryDialog() {
        val languageChoices = LanguageOptions.supported.filter { option ->
            IdiomLibrary.meaningLocales.any { IdiomLibrary.androidTag(it) == option.tag }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val name = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(R.string.idioms_collection_name)
            hint = getString(R.string.collection_name)
        }
        val localeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity,
                android.R.layout.simple_spinner_dropdown_item,
                languageChoices.map { getString(it.title) })
            setSelection(languageChoices.indexOfFirst { it.tag == "ru-RU" }.coerceAtLeast(0))
        }
        layout.addView(name)
        layout.addView(TextView(this).apply {
            setText(R.string.idioms_meaning_language)
            setPadding(0, dp(12), 0, dp(5))
        })
        layout.addView(localeSpinner)
        layout.addView(TextView(this).apply {
            setText(R.string.idioms_import_description)
            setPadding(0, dp(12), 0, dp(5))
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.idioms_catalog_label)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.phrase_preview, null)
            .setPositiveButton(R.string.phrase_template_create, null)
            .create()
        dialog.setOnShowListener {
            fun selectedLibrary(): IdiomLibrary.Library? {
                val selected = languageChoices[localeSpinner.selectedItemPosition].tag
                val locale = IdiomLibrary.meaningLocales.first { IdiomLibrary.androidTag(it) == selected }
                return try { IdiomLibrary.load(this, locale) }
                catch (e: Exception) {
                    Log.e("Phrasoteca", "Cannot read idiom library", e)
                    Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show()
                    null
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val library = selectedLibrary() ?: return@setOnClickListener
                val sample = library.entries.take(12).joinToString("\n\n") {
                    "${it.expression}\n→ ${it.meaning}"
                }
                AlertDialog.Builder(this).setTitle(R.string.phrase_preview)
                    .setMessage(sample + "\n\n… (${library.entries.size} total)")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val collectionName = name.text.toString().trim()
                if (collectionName.isBlank() || collectionName.length > 200) {
                    name.error = getString(R.string.collection_required)
                    return@setOnClickListener
                }
                val library = selectedLibrary() ?: return@setOnClickListener
                val meaningTag = languageChoices[localeSpinner.selectedItemPosition].tag
                val meaningTitle = getString(languageChoices[localeSpinner.selectedItemPosition].title)
                dialog.dismiss()
                lifecycleScope.launch {
                    val imported = runDatabaseOperation {
                        database.withTransaction {
                            val collectionId = database.collectionDao().insert(CollectionEntity(
                                name = collectionName,
                                sortOrder = database.collectionDao().nextSortOrder(),
                                defaultCardType = "LANGUAGE",
                                frontLabel = getString(R.string.idioms_front_label),
                                backLabel = meaningTitle,
                                frontLanguageTag = "", // Each source folder has its own language.
                                backLanguageTag = meaningTag
                            ))
                            val folderIds = mutableMapOf<String, Long>()
                            library.folders.forEachIndexed { index, folder ->
                                folderIds[folder.id] = database.folderDao().insert(FolderEntity(
                                    collectionId = collectionId,
                                    name = folder.title,
                                    sortOrder = index + 1
                                ))
                            }
                            library.entries.forEachIndexed { index, entry ->
                                val frontTag = IdiomLibrary.androidTag(entry.sourceLocale)
                                database.cardDao().insert(CardEntity(
                                    collectionId = collectionId,
                                    folderId = folderIds.getValue(entry.folderId),
                                    frontText = entry.expression,
                                    backText = entry.meaning,
                                    // Store a BCP-47 tag per card: the collection mixes source languages.
                                    frontLanguage = frontTag,
                                    backLanguage = meaningTitle,
                                    sortOrder = index + 1,
                                    cardType = "LANGUAGE",
                                    interactionMode = "NORMAL"
                                ))
                            }
                        }
                        refreshCollections()
                    }
                    if (imported) {
                        achievements.record("idiom.library.added")
                        Toast.makeText(this@HomeActivity,
                            R.string.idioms_import_success, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // A bundled catalogue is different from a user backup JSON file.
    // All templates are read locally from assets; user collections remain independent.
    private fun showPhraseTemplateDialog(catalog: PhraseLibrary.Catalog) {
        val availableTags = PhraseLibrary.languages(catalog)
        val languageChoices = LanguageOptions.supported.filter { it.tag in availableTags }
        val isPractical = catalog == PhraseLibrary.Catalog.PRACTICAL_RU_EN
        val labels = languageChoices.map { getString(it.title) }
        val categoryResources = if (isPractical) linkedMapOf(
            "communication" to R.string.phrase_practical_communication,
            "shopping" to R.string.phrase_practical_shopping,
            "restaurant" to R.string.phrase_category_restaurant,
            "transport" to R.string.phrase_practical_transport,
            "airport" to R.string.phrase_category_airport,
            "accommodation" to R.string.phrase_category_accommodation,
            "health" to R.string.phrase_practical_health,
            "banking" to R.string.phrase_practical_banking,
            "city" to R.string.phrase_practical_city,
            "connectivity" to R.string.phrase_practical_connectivity,
            "delivery" to R.string.phrase_practical_delivery,
            "services" to R.string.phrase_practical_services
        ) else linkedMapOf(
            "general" to R.string.phrase_category_general,
            "street" to R.string.phrase_category_street,
            "metro" to R.string.phrase_category_metro,
            "restaurant" to R.string.phrase_category_restaurant,
            "shopping" to R.string.phrase_category_shop,
            "airport" to R.string.phrase_category_airport,
            "accommodation" to R.string.phrase_category_accommodation,
            "currency" to R.string.phrase_category_currency,
            "emergency" to R.string.phrase_category_emergency
        )
        // Load translations once per locale selection, then filter by selected category.
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val name = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(if (isPractical) getString(R.string.phrase_catalog_ru_en_name)
                    else getString(R.string.phrase_template_default_name))
            hint = getString(R.string.collection_name)
        }
        val front = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(languageChoices.indexOfFirst { it.tag == "ru-RU" }.coerceAtLeast(0))
        }
        val back = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(languageChoices.indexOfFirst {
                it.tag == (if (isPractical) "en-US" else "es-CL")
            }.coerceAtLeast(0))
        }
        layout.addView(name)
        layout.addView(TextView(this).apply { setText(R.string.phrase_template_front) })
        layout.addView(front)
        layout.addView(TextView(this).apply { setText(R.string.phrase_template_back) })
        layout.addView(back)
        layout.addView(TextView(this).apply {
            setText(R.string.phrase_categories)
            setPadding(0, dp(12), 0, dp(5))
        })
        val categoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = linkedMapOf<String, android.widget.CheckBox>()
        val countText = TextView(this)
        val availableCategories = try {
            PhraseLibrary.loadEntries(this, "ru-RU", if (isPractical) "en-US" else "es-CL", catalog)
                .groupingBy { PhraseLibrary.themeOf(it.category) }.eachCount()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show()
            return
        }
        fun selectedCategories(): Set<String> = checks.filterValues { it.isChecked }.keys
        fun updateCount() {
            val count = availableCategories.filterKeys { it in selectedCategories() }.values.sum()
            countText.text = getString(R.string.phrase_selected_count, count)
        }
        categoryResources.forEach { (id, label) ->
            val count = availableCategories[id] ?: 0
            if (count > 0) {
                val check = android.widget.CheckBox(this).apply {
                    text = "${getString(label)} ($count)"
                    isChecked = true
                    setOnCheckedChangeListener { _, _ -> updateCount() }
                }
                checks[id] = check
                categoryBox.addView(check)
            }
        }
        layout.addView(categoryBox)
        layout.addView(countText)
        updateCount()
        val scroll = android.widget.ScrollView(this).apply {
            addView(layout)
            isFillViewport = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isPractical) R.string.phrase_catalog_ru_en else R.string.phrase_template_title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.phrase_preview, null)
            .setPositiveButton(R.string.phrase_template_create, null)
            .create()
        dialog.setOnShowListener {
            fun entriesForSelection(): List<PhraseLibrary.Entry>? {
                val frontTag = languageChoices[front.selectedItemPosition].tag
                val backTag = languageChoices[back.selectedItemPosition].tag
                if (!LanguageOptions.areDifferent(frontTag, backTag)) {
                    Toast.makeText(this, R.string.phrase_template_distinct, Toast.LENGTH_LONG).show()
                    return null
                }
                if (selectedCategories().isEmpty()) {
                    Toast.makeText(this, R.string.phrase_select_one, Toast.LENGTH_LONG).show()
                    return null
                }
                return try {
                    PhraseLibrary.loadEntries(this, frontTag, backTag, catalog)
                        .filter { PhraseLibrary.themeOf(it.category) in selectedCategories() }
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show()
                    null
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val entries = entriesForSelection() ?: return@setOnClickListener
                // Avoid a very large dialog: full set contains 338 records.
                val preview = entries.take(15).joinToString("\n\n") {
                    "${it.front}\n→ ${it.back}"
                } + if (entries.size > 15) "\n\n… (${entries.size - 15} more)" else ""
                AlertDialog.Builder(this).setTitle(R.string.phrase_preview)
                    .setMessage(preview)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val collectionName = name.text.toString().trim()
                if (collectionName.isBlank() || collectionName.length > 200) {
                    name.error = getString(R.string.collection_required)
                    return@setOnClickListener
                }
                val entries = entriesForSelection() ?: return@setOnClickListener
                if (entries.isEmpty()) return@setOnClickListener
                val frontChoice = languageChoices[front.selectedItemPosition]
                val backChoice = languageChoices[back.selectedItemPosition]
                dialog.dismiss()
                lifecycleScope.launch {
                    val imported = runDatabaseOperation {
                        database.withTransaction {
                            val collection = CollectionEntity(
                                name = collectionName,
                                sortOrder = database.collectionDao().nextSortOrder(),
                                defaultCardType = "LANGUAGE",
                                frontLabel = getString(frontChoice.title),
                                backLabel = getString(backChoice.title),
                                frontLanguageTag = frontChoice.tag,
                                backLanguageTag = backChoice.tag
                            )
                            val id = database.collectionDao().insert(collection)
                            // The full practical catalog is organized into Room folders.
                            // Demonstration collections preserve their previous flat behavior.
                            val folders = mutableMapOf<String, Long>()
                            if (isPractical) {
                                entries.map { PhraseLibrary.themeOf(it.category) }
                                    .distinct().forEachIndexed { order, category ->
                                        folders[category] = database.folderDao().insert(FolderEntity(
                                            collectionId = id,
                                            name = PhraseLibrary.categoryLabel(this@HomeActivity, category),
                                            sortOrder = order + 1
                                        ))
                                    }
                            }
                            entries.forEachIndexed { index, entry ->
                                database.cardDao().insert(CardEntity(
                                    collectionId = id,
                                    frontText = entry.front,
                                    backText = entry.back,
                                    frontLanguage = collection.frontLabel,
                                    backLanguage = collection.backLabel,
                                    sortOrder = index + 1,
                                    folderId = folders[PhraseLibrary.themeOf(entry.category)],
                                    cardType = "LANGUAGE",
                                    interactionMode = if (isPractical && entry.mode in setOf("YES_NO", "QUANTITY")) entry.mode else "NORMAL"
                                ))
                            }
                        }
                        refreshCollections()
                    }
                    if (imported) {
                        Toast.makeText(this@HomeActivity,
                            R.string.phrase_template_success, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNewVaultName() {
        val field = EditText(this).apply {
            hint = getString(R.string.collection_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vault_new_collection)
            .setMessage(R.string.vault_loss_notice)
            .setView(field)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.vault_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isEmpty() || name.length > 200) {
                    field.error = getString(R.string.collection_required)
                } else {
                    dialog.dismiss()
                    startActivity(Intent(this, VaultAccessActivity::class.java).apply {
                        putExtra(VaultAccessActivity.EXTRA_NEW_VAULT_NAME, name)
                    })
                }
            }
        }
        dialog.show()
    }

    private fun showCollectionEditor(existing: CollectionEntity?) {
        CollectionSettingsDialog.show(this, existing, includeName = true) { updated ->
            lifecycleScope.launch {
                runDatabaseOperation {
                    if (existing == null) {
                        val order = database.collectionDao().nextSortOrder()
                        database.collectionDao().insert(updated.copy(sortOrder = order))
                        achievements.record("collection.created.manual")
                    } else {
                        database.collectionDao().update(updated)
                    }
                    refreshCollections()
                }
            }
        }
    }

    // --------------------------------------------------------
    // Delete collection
    // --------------------------------------------------------

    private fun confirmDeleteCollection(collection: CollectionSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_collection_title)
            .setMessage(if (collection.defaultCardType == "VAULT") R.string.delete_vault_message else R.string.delete_collection_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_collection_short) { _, _ ->
                if (collection.defaultCardType == "VAULT") {
                    requestVaultPinForDeletion(collection)
                } else {
                    lifecycleScope.launch {
                        runDatabaseOperation {
                            database.collectionDao().getById(collection.id)?.let {
                                database.collectionDao().delete(it)
                            }
                            achievements.record("collection.deleted")
                            refreshCollections()
                        }
                    }
                }
            }
            .show()
    }

    private fun requestVaultPinForDeletion(collection: CollectionSummary) {
        if (isBusy) return
        val pinField = EditText(this).apply {
            hint = getString(R.string.vault_pin_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            // Apply LAST: setSingleLine/inputType can replace the transformation method.
            transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(pinField)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vault_delete_pin_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_collection_short, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isBusy) return@setOnClickListener
                val pin = pinField.text.toString()
                if (pin.length != 6 || pin.any { it !in '0'..'9' }) {
                    pinField.error = getString(R.string.vault_pin_invalid)
                    return@setOnClickListener
                }
                // Avoid repeated submissions while authentication is running.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val completed = runDatabaseOperation {
                        when (withContext(Dispatchers.IO) {
                            VaultRepository(applicationContext).deleteVault(collection.id, pin)
                        }) {
                            VaultRepository.DeleteResult.DELETED -> {
                                achievements.record("collection.deleted")
                                refreshCollections()
                                dialog.dismiss()
                            }
                            VaultRepository.DeleteResult.INCORRECT_PIN -> {
                                pinField.text?.clear()
                                pinField.error = getString(R.string.vault_delete_pin_wrong)
                            }
                            VaultRepository.DeleteResult.NOT_FOUND -> {
                                dialog.dismiss()
                                refreshCollections()
                            }
                        }
                    }
                    if (!completed && !isFinishing) {
                        // runDatabaseOperation already reports unexpected errors.
                        pinField.text?.clear()
                    }
                    if (dialog.isShowing) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    // --------------------------------------------------------
    // Database operation helper
    // --------------------------------------------------------


    private suspend fun runDatabaseOperation(
        operation: suspend () -> Unit
    ): Boolean {

        if (isBusy) return false

        isBusy = true
        homeToolbar.menu.findItem(R.id.action_search_cards)?.isEnabled = false
        buttonNewCollection.isEnabled = false
        updateBackupButtons()

        return try {

            operation()
            true

        } catch (e: Exception) {

            android.util.Log.e(
                "Phrasoteca",
                "Database operation failed",
                e
            )

            Toast.makeText(
                this,
                getString(R.string.operation_failed),
                Toast.LENGTH_LONG
            ).show()

            false

        } finally {

            isBusy = false
            homeToolbar.menu.findItem(R.id.action_search_cards)?.isEnabled = true
            buttonNewCollection.isEnabled = true

            // Always restore the correct button state
            updateBackupButtons()
            if (pendingExternalJsonUri != null) showPendingExternalImport()
        }
    }



    private fun moveCollection(id: Long, direction: Int) {

        if (isBusy) return

        lifecycleScope.launch {

            runDatabaseOperation {

                database.withTransaction {

                    val items = database.collectionDao().getAll()

                    val index = items.indexOfFirst { it.id == id }
                    val targetIndex = index + direction

                    if (index >= 0 && targetIndex in items.indices) {

                        val first = items[index]
                        val second = items[targetIndex]

                        database.collectionDao().updateSortOrder(
                            first.id,
                            second.sortOrder
                        )

                        database.collectionDao().updateSortOrder(
                            second.id,
                            first.sortOrder
                        )
                    }
                }

                refreshCollections()
            }
        }
    }


    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f)
            .toInt()

    private fun applyWindowInsets() {

        val root = findViewById<View>(R.id.homeRoot)

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) {
                view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                left + systemBars.left,
                top + systemBars.top,
                right + systemBars.right,
                bottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }


    // ---------------------------------------------------------------------
    // QR collection exchange. Uses the existing validated JSON v3 format.
    // ---------------------------------------------------------------------

    private fun chooseCollectionToShare() {
        val shareable = collections.filter { it.defaultCardType != "VAULT" }
        if (isBusy || shareable.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_collection_choose)
            .setItems(shareable.map { it.name }.toTypedArray()) { _, index ->
                val id = shareable[index].id
                lifecycleScope.launch {
                    try {
                        val json = withContext(Dispatchers.IO) { backupManager.exportJson(id) }
                        val payload = try { CollectionQrCodec.encode(json) }
                            catch (_: IllegalArgumentException) { null }
                        if (payload == null) {
                            showLargeCollectionShare(json)
                        } else {
                            val bitmap = withContext(Dispatchers.Default) { CardQrCodec.qrBitmap(payload) }
                            val image = ImageView(this@HomeActivity).apply {
                                setImageBitmap(bitmap)
                                adjustViewBounds = true
                                setPadding(dp(20), dp(8), dp(20), dp(8))
                            }
                            AlertDialog.Builder(this@HomeActivity)
                                .setTitle(R.string.qr_collection_share)
                                .setMessage(getString(R.string.qr_collection_preview,
                                    shareable[index].name,
                                    CollectionQrCodec.inspect(json).folders,
                                    CollectionQrCodec.inspect(json).cards))
                                .setView(image)
                                .setPositiveButton(R.string.qr_save_gallery) { _, _ ->
                                    lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                CardQrCodec.saveToGallery(contentResolver, bitmap)
                                            }
                                            Toast.makeText(this@HomeActivity, R.string.qr_saved, Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(this@HomeActivity, R.string.qr_save_failed, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNeutralButton(R.string.qr_collection_share_file) { _, _ -> shareCollectionFile(json) }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    } catch (e: Exception) {
                        Log.e("Phrasoteca", "Collection QR export failed", e)
                        Toast.makeText(this@HomeActivity, R.string.export_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLargeCollectionShare(json: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_collection_too_large)
            .setMessage(R.string.qr_collection_file_fallback)
            .setPositiveButton(R.string.qr_collection_share_file) { _, _ -> shareCollectionFile(json) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareCollectionFile(json: String) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "qr-shares").apply { mkdirs() }
                    val file = File(dir, "phrasoteca-collection.json")
                    file.writeText(json, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        this@HomeActivity, "$packageName.fileprovider", file
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newUri(contentResolver, "Phrasoteca", uri)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.qr_collection_share_file)))
            } catch (e: Exception) {
                Log.e("Phrasoteca", "Share collection file failed", e)
                Toast.makeText(this@HomeActivity, R.string.export_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCollectionQrImportChoices() {
        if (isBusy) return
        val options = arrayOf(getString(R.string.qr_scan_camera), getString(R.string.qr_open_gallery))
        AlertDialog.Builder(this).setTitle(R.string.qr_collection_import)
            .setItems(options) { _, index ->
                if (index == 0) collectionQrScanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(getString(R.string.qr_scan_camera))
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }) else collectionQrImagePicker.launch("image/*")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importCollectionQrText(raw: String) {
        if (isBusy || isFinishing || isDestroyed) return
        val payload = try { CollectionQrCodec.decode(raw) }
            catch (e: Exception) {
                Toast.makeText(this, R.string.qr_invalid, Toast.LENGTH_LONG).show()
                return
            }
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_collection_import)
            .setMessage(getString(R.string.qr_collection_preview,
                payload.name, payload.folders, payload.cards))
            .setPositiveButton(R.string.qr_collection_create_new) { _, _ ->
                lifecycleScope.launch {
                    val success = runDatabaseOperation {
                        withContext(Dispatchers.IO) { backupManager.importJson(payload.json) }
                        refreshCollections()
                    }
                    if (success) Toast.makeText(this@HomeActivity,
                        R.string.qr_collection_imported, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.qr_collection_import_existing) { _, _ ->
                showCompatibleCollectionChoices(payload)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCompatibleCollectionChoices(payload: CollectionQrCodec.Preview) {
        val source = org.json.JSONObject(payload.json).getJSONArray("collections").getJSONObject(0)
        val sourceType = source.getString("defaultCardType")
        val sourceMode = source.getString("sideMode")
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                database.collectionDao().getAll().filter {
                    it.defaultCardType == sourceType && it.sideMode == sourceMode
                }
            }
            if (candidates.isEmpty()) {
                Toast.makeText(this@HomeActivity, R.string.qr_collection_no_compatible,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.qr_collection_import_existing)
                .setItems(candidates.map { it.name }.toTypedArray()) { _, index ->
                    val target = candidates[index]
                    AlertDialog.Builder(this@HomeActivity)
                        .setTitle(R.string.qr_collection_confirm_merge)
                        .setMessage(getString(R.string.qr_collection_merge_message,
                            payload.cards, target.name))
                        .setPositiveButton(R.string.qr_import_action) { _, _ ->
                            lifecycleScope.launch {
                                val success = runDatabaseOperation {
                                    withContext(Dispatchers.IO) {
                                        backupManager.importJsonIntoCollection(payload.json, target.id)
                                    }
                                    refreshCollections()
                                }
                                if (success) Toast.makeText(this@HomeActivity,
                                    R.string.qr_collection_imported, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun showExportDialog() {

        val shareable = collections.filter { it.defaultCardType != "VAULT" }
        val options = mutableListOf(
            getString(R.string.export_all)
        )

        options.addAll(
            shareable.map {
                getString(R.string.export_one, it.name)
            }
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setItems(options.toTypedArray()) { _, position ->

                pendingExportCollectionId = if (position == 0) {
                    null
                } else {
                    shareable[position - 1].id
                }

                val fileName = if (position == 0) {
                    "phrasoteca-backup.json"
                } else {
                    val collectionId =
                        shareable[position - 1].id

                    "phrasoteca-collection-$collectionId.json"
                }

                exportLauncher.launch(fileName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }



    private fun exportToUri(uri: Uri) {

        val selectedCollectionId =
            pendingExportCollectionId

        lifecycleScope.launch {

            val success = runDatabaseOperation {

                // Read data from Room and generate the JSON
                val json = withContext(Dispatchers.IO) {
                    backupManager.exportJson(
                        selectedCollectionId
                    )
                }

                // Enforce the same size limit as for imports
                val bytes = json.toByteArray(Charsets.UTF_8)

                require(
                    bytes.size <= BackupManager.MAX_FILE_SIZE
                ) {
                    "Backup exceeds the 5 MB limit"
                }

                // Write the document through ContentResolver
                withContext(Dispatchers.IO) {

                    val stream =
                        contentResolver.openOutputStream(uri)
                            ?: throw IOException(
                                "Cannot open the selected document"
                            )

                    stream.use {
                        it.write(bytes)
                        it.flush()
                    }
                }
            }

            if (success) {
                achievements.record("backup.export.succeeded")
                Toast.makeText(
                    this@HomeActivity,
                    R.string.export_success,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@HomeActivity,
                    R.string.export_error,
                    Toast.LENGTH_LONG
                ).show()
            }

            pendingExportCollectionId = null
        }
    }



    private fun importFromUri(uri: Uri) {

        lifecycleScope.launch {

            var importedCount = 0

            val success = runDatabaseOperation {

                // Read the selected document with a size limit
                val jsonText = withContext(Dispatchers.IO) {
                    readDocumentLimited(uri)
                }

                // Parse, validate and import using Room
                importedCount = withContext(Dispatchers.IO) {
                    backupManager.importJson(jsonText)
                }

                // Update the Home screen
                refreshCollections()
            }

            if (success) {
                achievements.record("backup.import.succeeded")

                Toast.makeText(
                    this@HomeActivity,
                    resources.getQuantityString(
                        R.plurals.imported_collections_count,
                        importedCount,
                        importedCount
                    ),
                    Toast.LENGTH_LONG
                ).show()

            } else {

                Toast.makeText(
                    this@HomeActivity,
                    R.string.import_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun readDocumentLimited(uri: Uri): String {

        val input = contentResolver.openInputStream(uri)
            ?: throw IOException(
                "Cannot open the selected document"
            )

        return input.use { stream ->

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)

            var totalBytes = 0

            while (true) {

                val count = stream.read(buffer)

                if (count == -1) break

                totalBytes += count

                if (totalBytes > BackupManager.MAX_FILE_SIZE) {
                    throw IOException(
                        "Backup file exceeds 5 MB"
                    )
                }

                output.write(buffer, 0, count)
            }

            val text = output.toString("UTF-8")

            require(text.isNotBlank()) {
                "Backup file is empty"
            }

            text
        }
    }


    private fun updateBackupButtons() {
        val menu = homeToolbar.menu
        menu.findItem(R.id.action_import)?.isEnabled = !isBusy
        menu.findItem(R.id.action_export)?.isEnabled =
            !isBusy && collections.any { it.defaultCardType != "VAULT" }
        menu.findItem(R.id.action_share_collection_qr)?.isEnabled = !isBusy && collections.any { it.defaultCardType != "VAULT" }
        menu.findItem(R.id.action_import_collection_qr)?.isEnabled = !isBusy
    }


    private fun showCardSearchDialog() {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val searchInput = EditText(this).apply {
            hint = getString(R.string.search_cards_hint)
            setSingleLine(true)
        }

        val status = TextView(this).apply {
            setText(R.string.search_start)
            setPadding(0, dp(8), 0, dp(8))
        }

        val resultsList = ListView(this)

        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )

        resultsList.adapter = adapter

        container.addView(searchInput)
        container.addView(status)

        container.addView(
            resultsList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.search_cards)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()

        var results = listOf<CardSearchResult>()
        var searchJob: Job? = null

        searchInput.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {

                    searchJob?.cancel()

                    val query = s?.toString()?.trim().orEmpty()

                    if (query.isEmpty()) {
                        results = emptyList()
                        adapter.clear()
                        status.setText(R.string.search_start)
                        return
                    }

                    searchJob = lifecycleScope.launch {

                        try {
                            val found = database.cardDao()
                                .searchAll(query)

                            results = found

                            adapter.clear()

                            adapter.addAll(
                                found.map { card ->
                                    "${card.collectionName}\n" +
                                            card.frontText + "\n" +
                                            card.backText
                                }
                            )

                            status.text = if (found.isEmpty()) {
                                getString(R.string.search_no_results)
                            } else {
                                resources.getQuantityString(
                                    R.plurals.search_results_count,
                                    found.size,
                                    found.size
                                )
                            }

                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) {
                                throw e
                            }

                            android.util.Log.e(
                                "Phrasoteca",
                                "Search failed",
                                e
                            )

                            status.setText(R.string.operation_failed)
                        }
                    }
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        resultsList.setOnItemClickListener {
                _, _, position, _ ->

            val selected = results.getOrNull(position)
                ?: return@setOnItemClickListener

            val intent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                putExtra(
                    MainActivity.EXTRA_COLLECTION_ID,
                    selected.collectionId
                )

                putExtra(
                    MainActivity.EXTRA_CARD_ID,
                    selected.cardId
                )
            }

            dialog.dismiss()
            startActivity(intent)
        }

        dialog.setOnDismissListener {
            searchJob?.cancel()
        }

        dialog.show()
    }


}
