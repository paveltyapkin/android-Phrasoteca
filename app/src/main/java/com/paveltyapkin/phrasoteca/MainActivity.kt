
package com.paveltyapkin.phrasoteca

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.text.Editable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.room.withTransaction
import com.paveltyapkin.phrasoteca.data.local.ChecklistItemEntity

import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope

import com.paveltyapkin.phrasoteca.data.DemoCardRepository
import com.paveltyapkin.phrasoteca.data.local.CardEntity
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase

import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "phrasoteca_settings"
        private const val KEY_INITIALIZED = "database_initialized"
        private const val KEY_COLLECTION = "collection_id"
        private const val KEY_CARD = "card_id"
        private const val KEY_FLIPPED = "flipped"
        private const val EXTRA_SHOW_ORIGINAL_SIDE = "show_original_side"
        private const val KEY_SWIPE_NAVIGATION = "swipe_navigation_enabled"
        private const val KEY_SHOW_CARD_LANDSCAPE = "show_card_landscape"
        private const val KEY_TEXT_ZOOM = "card_text_pinch_zoom"
        private const val MIN_TEXT_ZOOM = 0.60f
        private const val MAX_TEXT_ZOOM = 1.50f
        const val EXTRA_CARD_ID = "card_id_extra"
        const val EXTRA_ALL_FAVORITES = "all_favorites_extra"
        private const val FLIP_DURATION = 150L
        private const val SLIDE_OUT_DURATION = 110L
        private const val SLIDE_IN_DURATION = 140L
        private const val SLIDE_DISTANCE_DP = 36
        private const val SWIPE_THRESHOLD_DP = 48f
        private const val SWIPE_RATIO = 1.3f
        const val EXTRA_COLLECTION_ID = "collection_id_extra"
        const val EXTRA_FOLDER_SCOPE = "folder_scope_extra"
        const val EXTRA_FOLDER_ID = "folder_id_extra"
        const val EXTRA_CREATE_CARD = "create_card_extra"

    }

    private lateinit var database: PhrasotecaDatabase
    private lateinit var achievements: AchievementRepository

    private lateinit var cardContainer: View
    private lateinit var textCollectionName: TextView
    private lateinit var textCardCounter: TextView
    private lateinit var textLanguage: TextView
    private lateinit var textPhrase: TextView

    private lateinit var buttonPrevious: Button
    private lateinit var buttonNext: Button
    private lateinit var navigationButtons: View
    private lateinit var yesNoResponseButtons: View
    private lateinit var buttonYesResponse: Button
    private lateinit var buttonNoResponse: Button
    private lateinit var quantityControls: View
    private lateinit var buttonSetQuantity: Button
    private lateinit var textQuantityValue: TextView
    // In-memory, per-card display value. Never changes the source phrase or Room record.
    private val quantityValues = mutableMapOf<Long, String>()
    private val variableValues = mutableMapOf<Long, MutableMap<String, String>>()
    private lateinit var gestureHint: TextView
    private lateinit var toolbar: Toolbar
    private var queueCheckbox: ImageButton? = null
    // Queue selection persists as IDs only while switching between Home and MainActivity.
    // Scope: the current collection AND the currently opened folder/list.
    private val queueSelection = linkedSetOf<Long>()
    private var queueScopeKey: String? = null
    private var queueRestoredFromBundle = false
    private var showModeActive = false
    private var showQueueActive = false
    private var queuePosition = 0
    private var preQueueCardId = -1L
    private var preQueueFlipped = false
    private var buttonDeleteCardOverlay: View? = null
    private var buttonSpeakCardOverlay: ImageButton? = null
    private var buttonShowCard: View? = null
    private var buttonQuickVariables: View? = null
    private var buttonFavorite: ImageButton? = null
    private var favoriteUpdatePending = false
    // View-only filter: no change to Room ordering or stored queue selections.
    private var favoritesOnly = false
    private var preFavoritesCardId = -1L
    private var presentationBackButton: View? = null
    private var presentationRotateButton: View? = null
    private var landscapeBackButton: View? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { importQrText(it) }
    }
    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CardQrCodec.decodeImage(contentResolver, uri)
                }
                importQrText(text)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.qr_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }
    private var swipeNavigationEnabled = true
    // Persisted preference for the on-card Show button (direct menu action stays landscape).
    private var showCardLandscape = true

    // Cross-collection favorites retain each card in its original Room collection.
    private var allFavoritesMode = false
    private var favoriteCollectionMap: Map<Long, CollectionEntity> = emptyMap()
    private val cards = mutableListOf<CardEntity>()
    private var currentCollection: CollectionEntity? = null
    private var currentCardIndex = 0
    private var folderScoped = false
    private var activeFolderId: Long? = null

    private var isFlipped = false
    private var isAnimating = false
    // One preference shared by ordinary and presentation modes; auto-fit remains enabled.
    private var textZoom = 1.0f
    private var inlineEditing = false
    private var inlineEditor: EditText? = null
    private var inlineActions: LinearLayout? = null
    private var inlineEditingCardId = -1L
    private var inlineEditingBack = false
    private var isBusy = true

    private val currentCard: CardEntity?
        get() = cards.getOrNull(currentCardIndex)

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasMoved = false
    private var longPressHandled = false
    private var gestureCancelled = false
    private var touchSlop = 0f
    private var swipeThresholdPx = 0f

    private val preferences by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        database = PhrasotecaDatabase.getInstance(this)
        achievements = AchievementRepository(database)
        allFavoritesMode = intent.getBooleanExtra(EXTRA_ALL_FAVORITES, false)
        folderScoped = intent.getBooleanExtra(EXTRA_FOLDER_SCOPE, false)
        activeFolderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L).takeIf { it > 0L }

        savedInstanceState?.getStringArrayList("template_variable_values")?.forEach { item ->
            val parts = item.split('\u0000', limit = 3)
            if (parts.size == 3) {
                val id = parts[0].toLongOrNull()
                if (id != null) variableValues.getOrPut(id) { mutableMapOf() }[parts[1]] = parts[2]
            }
        }
        savedInstanceState?.getLongArray("quantity_ids")?.let { ids ->
            val values = savedInstanceState.getStringArrayList("quantity_values")
            if (values != null && ids.size == values.size) {
                ids.indices.forEach { i -> quantityValues[ids[i]] = values[i] }
            }
        }
        savedInstanceState?.let { state ->
            favoritesOnly = state.getBoolean("favorites_only", false)
            preFavoritesCardId = state.getLong("pre_favorites_card_id", -1L)
            showModeActive = state.getBoolean("presentation_mode_active", false)
            queueRestoredFromBundle = true
            queueSelection.addAll(state.getLongArray("show_queue_ids")?.toList().orEmpty())
            showQueueActive = state.getBoolean("show_queue_active", false)
            queuePosition = state.getInt("show_queue_position", 0)
            preQueueCardId = state.getLong("show_queue_original_id", -1L)
            preQueueFlipped = state.getBoolean("show_queue_original_flipped", false)
        }
        initializeViews()
        applyPresentationUi()
        initializeTextToSpeech()
        applyWindowInsets()
        updateSystemBarsForOrientation()
        initializeListeners()
        initializeTouchGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inlineEditing) closeInlineEditor()
                else if (isPresentationMode() || showQueueActive) returnFromShowMode()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        updateViewer()
        updateOrientationMenuItem()

        lifecycleScope.launch {
            runDatabaseOperation {
                initializeDatabase()
                restoreSelection(savedInstanceState)
            }
            // Open the existing editor only for a fresh explicit create request.
            // Never re-open it after configuration changes or returning to the viewer.
            if (savedInstanceState == null &&
                intent.getBooleanExtra(EXTRA_CREATE_CARD, false) &&
                currentCollection != null && !allFavoritesMode && !isBusy
            ) {
                intent.removeExtra(EXTRA_CREATE_CARD)
                createCard()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (allFavoritesMode) {
            outState.putLong("all_favorites_card_id", currentCard?.id ?: -1L)
            outState.putBoolean("all_favorites_flipped", isFlipped)
        }
        outState.putBoolean("favorites_only", favoritesOnly)
        outState.putLong("pre_favorites_card_id", preFavoritesCardId)
        outState.putBoolean("presentation_mode_active", showModeActive)
        outState.putLongArray("show_queue_ids", queueSelection.toLongArray())
        outState.putBoolean("show_queue_active", showQueueActive)
        outState.putInt("show_queue_position", queuePosition)
        outState.putLong("show_queue_original_id", preQueueCardId)
        outState.putBoolean("show_queue_original_flipped", preQueueFlipped)
        outState.putStringArrayList("template_variable_values", ArrayList(variableValues.flatMap { (id, map) ->
            map.map { (key, value) -> "$id\u0000$key\u0000$value" }
        }))
        outState.putLongArray("quantity_ids", quantityValues.keys.toLongArray())
        outState.putStringArrayList("quantity_values", ArrayList(quantityValues.values))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updateSystemBarsForOrientation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBarsForOrientation()
    }

    /** Presentation hides system bars in either orientation. */
    private fun updateSystemBarsForOrientation() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = WindowInsetsCompat.Type.systemBars()
        if (isPresentationMode()) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else {
            controller.show(bars)
        }
    }

    private fun initializeViews() {
        cardContainer = findViewById(R.id.cardContainer)
        buttonDeleteCardOverlay = findViewById(R.id.buttonDeleteCardOverlay)
        buttonSpeakCardOverlay = findViewById(R.id.buttonSpeakCardOverlay)
        // The presentation button exists only in the portrait layout.
        buttonShowCard = findViewById(R.id.buttonShowCard)
        buttonQuickVariables = findViewById(R.id.buttonQuickVariables)
        buttonFavorite = findViewById(R.id.buttonFavorite)
        presentationBackButton = findViewById(R.id.presentationBackButton)
        presentationRotateButton = findViewById(R.id.presentationRotateButton)
        landscapeBackButton = findViewById(R.id.landscapePortraitButton)
        textCollectionName = findViewById(R.id.textCollectionName)
        textCardCounter = findViewById(R.id.textCardCounter)
        textLanguage = findViewById(R.id.textLanguage)
        textPhrase = findViewById(R.id.textPhrase)

        buttonPrevious = findViewById(R.id.buttonPrevious)
        buttonNext = findViewById(R.id.buttonNext)
        navigationButtons = findViewById(R.id.navigationButtons)
        yesNoResponseButtons = findViewById(R.id.yesNoResponseButtons)
        buttonYesResponse = findViewById(R.id.buttonYesResponse)
        buttonNoResponse = findViewById(R.id.buttonNoResponse)
        quantityControls = findViewById(R.id.quantityControls)
        buttonSetQuantity = findViewById(R.id.buttonSetQuantity)
        textQuantityValue = findViewById(R.id.textQuantityValue)
        gestureHint = findViewById(R.id.gestureHint)
        toolbar = findViewById(R.id.mainToolbar)
        queueCheckbox = findViewById(R.id.buttonQueueSelect)
        queueCheckbox?.setOnClickListener { toggleQueueSelection() }
        swipeNavigationEnabled = preferences.getBoolean(KEY_SWIPE_NAVIGATION, true)
        showCardLandscape = preferences.getBoolean(KEY_SHOW_CARD_LANDSCAPE, true)
        textZoom = preferences.getFloat(KEY_TEXT_ZOOM, 1.0f)
            .takeIf { it.isFinite() }?.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM) ?: 1.0f
        updateNavigationMode()

        // Presentation controls exist in both layouts. Orientation is explicitly locked.
        landscapeBackButton?.setOnClickListener {
            returnFromShowMode()
        }
        presentationBackButton?.setOnClickListener {
            returnFromShowMode()
        }
        presentationRotateButton?.setOnClickListener {
            switchPresentationOrientation()
        }

        val density = resources.displayMetrics.density
        cardContainer.cameraDistance = 8000f * density
        swipeThresholdPx = SWIPE_THRESHOLD_DP * density
        touchSlop = ViewConfiguration.get(this)
            .scaledTouchSlop.toFloat()
    }

    private fun isPresentationMode(): Boolean = showModeActive || isLandscape()

    private fun applyPresentationUi() {
        val presenting = isPresentationMode()
        // Presentation-only ambient backdrop; restore the normal viewer background
        // on exit. Do not change card elevation, overlays, or input behavior.
        findViewById<View>(R.id.main).setBackgroundResource(
            if (presenting) R.drawable.bg_presentation_stage else R.color.home_background
        )
        toolbar.visibility = if (presenting) View.GONE else View.VISIBLE
        // Give the phrase more space in portrait Show Mode without removing the
        // collection title or counter. Restore exact normal-view spacing on exit.
        if (!isLandscape()) {
            val inset = if (presenting) dp(8) else dp(12)
            findViewById<View>(R.id.main).setPadding(inset, inset, inset, inset)
            findViewById<View>(R.id.cardContextStrip).apply {
                minimumHeight = dp(if (presenting) 34 else 42)
                val margins = layoutParams as ViewGroup.MarginLayoutParams
                margins.topMargin = dp(4)
                margins.bottomMargin = dp(if (presenting) 4 else 12)
                layoutParams = margins
            }
            findViewById<View>(R.id.presentationStage).apply {
                val margins = layoutParams as ViewGroup.MarginLayoutParams
                margins.bottomMargin = dp(if (presenting) 4 else 10)
                layoutParams = margins
            }
        }
        // The overlay control visibility is centralized in updateButtons() so
        // neither presentation layout can expose controls mid-animation.
        // Let Android fit the phrase into the actual TextView bounds, including
        // multiline text. Keep the former 32/48 sp sizes as upper limits.
        // Configure auto-size instead of setTextSize(), which can interfere with it.
        // Pinch changes the preferred upper bound, never the actual stored phrase.
        // AutoSize still shrinks long text to fit the measured card area.
        val baseMaxSp = if (presenting) 48f else 32f
        val maxSp = (baseMaxSp * textZoom).toInt().coerceAtLeast(16)
        textPhrase.setAutoSizeTextTypeUniformWithConfiguration(
            12, // Emergency fallback for very long text; avoid clipping where possible.
            maxSp,
            1,
            android.util.TypedValue.COMPLEX_UNIT_SP
        )
    }

    private fun switchPresentationOrientation() {
        if (!isPresentationMode() || isBusy || isAnimating) return
        showModeActive = true
        requestedOrientation = if (isLandscape())
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun initializeListeners() {
        textCollectionName.setOnClickListener {
            if (!isBusy && !isAnimating) finish()
        }

        buttonYesResponse.setOnClickListener { showYesNoAnswer(true) }
        buttonNoResponse.setOnClickListener { showYesNoAnswer(false) }
        buttonSetQuantity.setOnClickListener { showQuantityEditor() }
        buttonPrevious.setOnClickListener { navigate(-1) }
        buttonNext.setOnClickListener { navigate(1) }
        cardContainer.setOnClickListener {
            if (isChecklist(currentCard)) showChecklistItems() else flipCard()
        }
        buttonDeleteCardOverlay?.setOnClickListener { deleteCard() }
        buttonSpeakCardOverlay?.setOnClickListener { speakCurrentSide() }
        buttonShowCard?.setOnClickListener { toggleOrientation() }
        buttonQuickVariables?.setOnClickListener { showQuickVariablesDialog() }
        buttonFavorite?.setOnClickListener { toggleFavorite() }
        cardContainer.setOnLongClickListener {
            if (!isPresentationMode() && !isBusy && !isAnimating && currentCard != null) {
                beginInlineEdit()
                true
            } else false
        }

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menuSwipeNavigation -> {
                    swipeNavigationEnabled = !swipeNavigationEnabled
                    item.isChecked = swipeNavigationEnabled
                    preferences.edit()
                        .putBoolean(KEY_SWIPE_NAVIGATION, swipeNavigationEnabled)
                        .apply()
                    updateNavigationMode()
                    true
                }
                R.id.menuShowCardLandscape -> {
                    showCardLandscape = !showCardLandscape
                    item.isChecked = showCardLandscape
                    preferences.edit().putBoolean(KEY_SHOW_CARD_LANDSCAPE, showCardLandscape).apply()
                    true
                }
                R.id.menuFavoritesOnly -> { toggleFavoritesFilter(); true }
                R.id.menuQuickAddCard -> { createCard(); true }
                R.id.menuAddCard -> { createCard(); true }
                R.id.menuEditCard -> { editCard(); true }
                R.id.menuFillVariables -> { showVariableDialog(); true }
                R.id.menuCloneCard -> { cloneCard(); true }
                R.id.menuCopyCard -> { showCopyCardMenu(); true }
                R.id.menuSearchInCollection -> { showCollectionCardSearch(); true }
                R.id.menuDeleteCard -> { deleteCard(); true }
                R.id.menuOrderCard -> { showCardOrderDialog(); true }
                R.id.menuCollectionSettings -> { showCurrentCollectionSettings(); true }
                R.id.menuStartShowQueue -> { startShowQueue(); true }
                R.id.menuClearShowQueue -> { clearShowQueue(); true }
                R.id.menuOrientation -> { toggleOrientation(forceLandscape = true); true }
                R.id.menuShareCardQr -> { shareCurrentCardQr(); true }
                R.id.menuImportCardQr -> { showQrImportChoices(); true }
                else -> false
            }
        }
        updateMenuState()
    }

    private fun updateNavigationMode() {
        navigationButtons.visibility =
            if (isPresentationMode() || swipeNavigationEnabled) View.GONE else View.VISIBLE
        gestureHint.setText(
            when {
                currentCollection?.defaultCardType == "CHECKLIST" -> R.string.checklist_hint
                currentCollection?.sideMode == "ONE_SIDED" && (isPresentationMode() || swipeNavigationEnabled) -> R.string.card_hint_single_swipe
                currentCollection?.sideMode == "ONE_SIDED" -> R.string.card_hint_single_buttons
                isPresentationMode() || swipeNavigationEnabled -> R.string.card_gesture_hint_swipe
                else -> R.string.card_gesture_hint_buttons
            }
        )
    }

    private fun updateMenuState() {
        if (!::toolbar.isInitialized) return
        val menu = toolbar.menu
        val queueCount = queueSelection.size
        menu.findItem(R.id.menuStartShowQueue)?.apply {
            isVisible = !showQueueActive && !favoritesOnly && !isPresentationMode() && queueCount >= 2
            isEnabled = !isBusy && !isAnimating
            title = getString(R.string.show_queue_start_count, queueCount)
        }
        menu.findItem(R.id.menuClearShowQueue)?.apply {
            isVisible = !showQueueActive && !favoritesOnly && !isPresentationMode() && queueCount > 0
            isEnabled = !isBusy && !isAnimating
        }
        // Free toolbar space for the queue controls while selecting.
        menu.findItem(R.id.menuQuickAddCard)?.isVisible = queueCount == 0 || showQueueActive
        menu.findItem(R.id.menuSwipeNavigation)?.isChecked = swipeNavigationEnabled
        menu.findItem(R.id.menuShowCardLandscape)?.isChecked = showCardLandscape
        menu.findItem(R.id.menuFavoritesOnly)?.apply {
            isChecked = favoritesOnly
            isEnabled = !isBusy && !isAnimating && !favoriteUpdatePending && !showQueueActive && !isPresentationMode() && currentCollection != null
        }
        val active = !isBusy && !isAnimating && !inlineEditing
        val hasCard = currentCard != null
        menu.findItem(R.id.menuQuickAddCard)?.isEnabled = active && currentCollection != null
        menu.findItem(R.id.menuAddCard)?.isEnabled = active && currentCollection != null
        menu.findItem(R.id.menuEditCard)?.isEnabled = active && hasCard && !isPresentationMode()
        menu.findItem(R.id.menuFillVariables)?.apply {
            isVisible = !isPresentationMode() && !isVaultCard(currentCard) && !isChecklist(currentCard) &&
                currentCard?.let { TemplateVariables.names(it.frontText, it.backText).isNotEmpty() } == true
            isEnabled = active && hasCard
        }
        menu.findItem(R.id.menuCloneCard)?.isEnabled = active && hasCard && !isPresentationMode()
        menu.findItem(R.id.menuCopyCard)?.isEnabled = active && hasCard && !isPresentationMode() && !isVaultCard(currentCard)
        menu.findItem(R.id.menuSearchInCollection)?.isEnabled = active && currentCollection != null && !isPresentationMode()
        menu.findItem(R.id.menuDeleteCard)?.isEnabled = active && hasCard
        menu.findItem(R.id.menuOrderCard)?.isEnabled = active && cards.size > 1
        menu.findItem(R.id.menuCollectionSettings)?.isEnabled = active && currentCollection != null
        menu.findItem(R.id.menuOrientation)?.isEnabled = active
        val qrSupported = currentCollection?.defaultCardType != "CHECKLIST"
        menu.findItem(R.id.menuShareCardQr)?.isEnabled = active && hasCard && !isChecklist(currentCard)
        menu.findItem(R.id.menuImportCardQr)?.isEnabled = active && currentCollection != null && qrSupported
        if (allFavoritesMode) {
            listOf(R.id.menuQuickAddCard, R.id.menuAddCard,
                R.id.menuCloneCard, R.id.menuCopyCard, R.id.menuSearchInCollection,
                R.id.menuDeleteCard, R.id.menuOrderCard, R.id.menuCollectionSettings,
                R.id.menuStartShowQueue, R.id.menuClearShowQueue, R.id.menuFavoritesOnly,
                R.id.menuShareCardQr, R.id.menuImportCardQr).forEach { id ->
                menu.findItem(id)?.isVisible = false
            }
        }
    }

    // --------------------------------------------------------
    // QR exchange (public single-card data only)
    // --------------------------------------------------------

    private fun shareCurrentCardQr() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode()) return
        val card = currentCard ?: return
        val collection = currentCollection ?: return
        if (isChecklist(card) || (card.cardType ?: collection.defaultCardType) == "VAULT") {
            Toast.makeText(this, R.string.qr_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val payload = try {
            CardQrCodec.encode(card, collection)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, R.string.qr_too_large, Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = CardQrCodec.qrBitmap(payload)
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_share_title)
            .setView(image)
            .setPositiveButton(R.string.qr_save_gallery) { _, _ ->
                lifecycleScope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            CardQrCodec.saveToGallery(contentResolver, bitmap)
                        }
                        Toast.makeText(this@MainActivity, R.string.qr_saved, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, R.string.qr_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        // A QR displayed on screen is observable; a remote scan is not.
        lifecycleScope.launch {
            achievements.record("qr.card.shown")
            achievements.record("qr.card.exchange")
        }
    }

    private fun showQrImportChoices() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode()) return
        if (currentCollection?.defaultCardType == "CHECKLIST") {
            Toast.makeText(this, R.string.qr_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val options = arrayOf(getString(R.string.qr_scan_camera), getString(R.string.qr_open_gallery))
        AlertDialog.Builder(this).setTitle(R.string.qr_import_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    qrScanner.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt(getString(R.string.qr_scan_camera))
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    })
                } else qrImagePicker.launch("image/*")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importQrText(raw: String) {
        if (allFavoritesMode) return
        if (isFinishing || isDestroyed || isBusy || isAnimating) return
        val collection = currentCollection ?: return
        if (collection.defaultCardType == "CHECKLIST") return
        val payload = try { CardQrCodec.decode(raw) }
            catch (e: Exception) {
                Toast.makeText(this, R.string.qr_invalid, Toast.LENGTH_LONG).show()
                return
            }
        if (collection.sideMode == "ONE_SIDED" && payload.back.isNotBlank()) {
            Toast.makeText(this, R.string.qr_two_sided_incompatible, Toast.LENGTH_LONG).show()
            return
        }
        val preview = payload.front.take(160)
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_import_confirm_title)
            .setMessage(preview)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.qr_import_action) { _, _ ->
                lifecycleScope.launch {
                    runDatabaseOperation {
                        val newId = database.cardDao().insert(
                            CardEntity(
                                collectionId = collection.id,
                                frontText = payload.front,
                                backText = if (collection.sideMode == "ONE_SIDED") "" else payload.back,
                                frontLanguage = collection.frontLabel,
                                backLanguage = collection.backLabel,
                                sortOrder = database.cardDao().nextSortOrder(collection.id),
                                folderId = if (folderScoped) activeFolderId else null,
                                cardType = if (collection.defaultCardType == "LANGUAGE") "LANGUAGE" else "GENERAL"
                            )
                        )
                        refreshCards(newId)
                        achievements.record("qr.card.exchange")
                    }
                }
            }.show()
    }

    // --------------------------------------------------------
    // Database initialization and selection
    // --------------------------------------------------------

    private suspend fun initializeDatabase() {
        if (preferences.getBoolean(KEY_INITIALIZED, false)) return

        val existing = database.collectionDao().getAll()

        if (existing.isEmpty()) {
            val demo = DemoCardRepository.getCollection()

            val id = database.collectionDao().insert(
                CollectionEntity(name = demo.name)
            )

            for (card in demo.cards) {
                database.cardDao().insert(
                    CardEntity(
                        collectionId = id,
                        frontText = card.frontText,
                        backText = card.backText,
                        frontLanguage = card.frontLanguage,
                        backLanguage = card.backLanguage
                    )
                )
            }
        }

        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .commit()
    }


    private suspend fun restoreSelection(savedInstanceState: Bundle?) {
        if (allFavoritesMode) {
            favoriteCollectionMap = database.collectionDao().getAll()
                .filter { it.defaultCardType != "VAULT" }.associateBy { it.id }
            cards.clear()
            cards.addAll(database.cardDao().getAllPublicFavorites())
            val preservedId = savedInstanceState?.getLong("all_favorites_card_id", -1L) ?: -1L
            currentCardIndex = cards.indexOfFirst { it.id == preservedId }.coerceAtLeast(0)
            isFlipped = savedInstanceState?.getBoolean("all_favorites_flipped", false) ?: false
            updateViewer()
            return
        }

        val collections = database.collectionDao().getAll()

        val requestedCollectionId = intent.getLongExtra(
            EXTRA_COLLECTION_ID,
            -1L
        )

        val requestedCardId = if (savedInstanceState == null) {
            intent.getLongExtra(EXTRA_CARD_ID, -1L)
        } else {
            -1L
        }

        val savedCollectionId =
            preferences.getLong(KEY_COLLECTION, -1L)

        val savedCardId =
            preferences.getLong(KEY_CARD, -1L)

        val savedFlipped =
            preferences.getBoolean(KEY_FLIPPED, false)

        val selected = collections.firstOrNull {
            it.id == requestedCollectionId
        } ?: collections.firstOrNull {
            it.id == savedCollectionId
        } ?: collections.firstOrNull()

        val restorePreviousCard =
            selected?.id == savedCollectionId

        val preferredCardId = when {
            requestedCardId >= 0L &&
                    selected?.id == requestedCollectionId ->
                requestedCardId

            restorePreviousCard -> savedCardId

            else -> -1L
        }

        loadCollection(selected, preferredCardId)

        isFlipped =
            restorePreviousCard &&
                    requestedCardId < 0L &&
                    savedFlipped &&
                    currentCard?.id == savedCardId

        saveSelection()
        updateViewer()
    }

    private suspend fun loadCollection(
        collection: CollectionEntity?,
        preferredCardId: Long = -1L
    ) {
        currentCollection = collection
        cards.clear()

        if (collection != null) {
            cards.addAll(
                if (!folderScoped) database.cardDao().getByCollection(collection.id)
                else if (activeFolderId == null) database.cardDao().getAtCollectionRoot(collection.id)
                else database.cardDao().getInFolder(collection.id, activeFolderId!!)
            )
        }

        val foundIndex = cards.indexOfFirst {
            it.id == preferredCardId
        }

        currentCardIndex = if (foundIndex >= 0) foundIndex else 0
        // An Activity is destroyed on return to Home. Restore the queued IDs for
        // this collection/list rather than losing them on a new MainActivity.
        val newQueueScope = collection?.let { queueStorageKey(it.id) }
        if (queueScopeKey != newQueueScope) {
            if (!queueRestoredFromBundle || queueScopeKey != null) {
                queueSelection.clear()
                if (newQueueScope != null) {
                    queueSelection.addAll(readSavedQueue(newQueueScope))
                }
            }
            queueScopeKey = newQueueScope
            queueRestoredFromBundle = false
        }
        val validIds = cards.filterNot { isVaultCard(it) }.mapTo(hashSetOf()) { it.id }
        queueSelection.retainAll(validIds)
        persistQueueSelection()
        if (showQueueActive && queueSelection.isNotEmpty()) {
            queuePosition = queuePosition.coerceIn(0, queueSelection.size - 1)
            val selectedId = queueSelection.elementAt(queuePosition)
            currentCardIndex = cards.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        } else if (showQueueActive) {
            showQueueActive = false
        }
        // Restrict only the visible list, after validating the presentation queue
        // against ALL cards in this folder/collection.
        if (favoritesOnly && !showQueueActive) {
            cards.retainAll { it.isFavorite && !isVaultCard(it) }
            val visibleIndex = cards.indexOfFirst { it.id == preferredCardId }
            currentCardIndex = if (visibleIndex >= 0) visibleIndex else 0
        }
        isFlipped = false

        saveSelection()
        updateViewer()
    }

    private fun saveSelection() {
        if (allFavoritesMode) return // Do not overwrite the last normal collection selection.
        preferences.edit()
            .putLong(KEY_COLLECTION, currentCollection?.id ?: -1L)
            .putLong(KEY_CARD, currentCard?.id ?: -1L)
            .putBoolean(KEY_FLIPPED, isFlipped)
            .apply()
    }

    private suspend fun refreshCards(preferredId: Long = -1L) {
        val collection = currentCollection ?: return
        loadCollection(collection, preferredId)
    }

    /** Reload the live cross-collection list, retaining the selected card if possible. */
    private suspend fun refreshAllFavorites(preferredId: Long = currentCard?.id ?: -1L) {
        if (!allFavoritesMode) return
        val previousIndex = currentCardIndex
        favoriteCollectionMap = database.collectionDao().getAll()
            .filter { it.defaultCardType != "VAULT" }.associateBy { it.id }
        val fresh = database.cardDao().getAllPublicFavorites()
        cards.clear()
        cards.addAll(fresh)
        val found = cards.indexOfFirst { it.id == preferredId }
        currentCardIndex = if (found >= 0) found else
            previousIndex.coerceAtMost((cards.size - 1).coerceAtLeast(0))
        if (found < 0) isFlipped = false
        updateViewer()
    }

    // --------------------------------------------------------
    // Card operations
    // --------------------------------------------------------

    private fun showCurrentCollectionSettings() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode()) return
        val existing = currentCollection ?: return
        CollectionSettingsDialog.show(this, existing, includeName = false) { updated ->
            lifecycleScope.launch {
                runDatabaseOperation {
                    database.collectionDao().update(updated)
                    currentCollection = updated
                    isFlipped = false
                    saveSelection()
                    updateViewer()
                    updateNavigationMode()
                }
            }
        }
    }

    /** Create a blank record; the italic hint is UI-only and never stored in Room. */
    private fun createCard() {
        if (allFavoritesMode || favoritesOnly || isBusy || isAnimating || isPresentationMode()) return
        if (currentCollection?.defaultCardType == "CHECKLIST") {
            editChecklist(null)
            return
        }
        val collection = currentCollection ?: return
        lifecycleScope.launch {
            runDatabaseOperation {
                val newId = database.cardDao().insert(
                    CardEntity(
                        collectionId = collection.id,
                        frontText = "",
                        backText = "",
                        frontLanguage = collection.frontLabel,
                        backLanguage = collection.backLabel,
                        folderId = if (folderScoped) activeFolderId else null,
                        cardType = collection.defaultCardType.takeUnless { it == "LANGUAGE" },
                        sortOrder = database.cardDao().nextSortOrder(collection.id)
                    )
                )
                achievements.record("card.created.manual")
                if (collection.defaultCardType == "GENERAL" && collection.sideMode == "ONE_SIDED") {
                    achievements.record("note.created.manual")
                }
                refreshCards(newId)
            }
        }
    }

    /** Edits the currently visible side in place; it never edits the opposite side. */
    private fun beginInlineEdit() {
        if (inlineEditing || isBusy || isAnimating || isPresentationMode() || showQueueActive) return
        val card = currentCard ?: return
        if (isChecklist(card) || isVaultCard(card)) {
            editCard() // Existing specialized editor, no security downgrade.
            return
        }
        val collection = currentCollection ?: return
        val back = isFlipped && collection.sideMode != "ONE_SIDED"
        val originalText = if (back) card.backText else card.frontText
        val parent = textPhrase.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(textPhrase)
        if (index < 0) return
        inlineEditing = true
        inlineEditingCardId = card.id
        inlineEditingBack = back
        textToSpeech?.stop()
        val editor = EditText(this).apply {
            setText(originalText)
            hint = getString(R.string.inline_card_enter_text)
            gravity = android.view.Gravity.CENTER
            setTextColor(textPhrase.currentTextColor)
            setHintTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textPhrase.currentTextColor, 140))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            setPadding(textPhrase.paddingLeft, textPhrase.paddingTop,
                textPhrase.paddingRight, textPhrase.paddingBottom)
        }
        editor.layoutParams = LinearLayout.LayoutParams(
            textPhrase.layoutParams.width, textPhrase.layoutParams.height,
            (textPhrase.layoutParams as LinearLayout.LayoutParams).weight
        )
        textPhrase.visibility = View.GONE
        parent.addView(editor, index + 1)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            val buttonParams = { LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)
            ) }
            addView(Button(this@MainActivity).apply {
                setText(R.string.cancel)
                isAllCaps = false
                textSize = 14f
                minWidth = dp(88)
                setPadding(dp(16), 0, dp(16), 0)
                backgroundTintList = null
                setBackgroundResource(R.drawable.bg_card_action_button)
                setTextColor(getColor(R.color.card_action_icon))
                setOnClickListener { closeInlineEditor() }
            }, buttonParams().apply { marginEnd = dp(8) })
            addView(Button(this@MainActivity).apply {
                setText(R.string.save)
                isAllCaps = false
                textSize = 14f
                minWidth = dp(88)
                setPadding(dp(16), 0, dp(16), 0)
                backgroundTintList = null
                setBackgroundResource(R.drawable.bg_inline_save_button)
                setTextColor(getColor(R.color.button_primary_text))
                setOnClickListener { saveInlineEdit() }
            }, buttonParams())
        }
        parent.addView(actions, index + 2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        inlineEditor = editor
        inlineActions = actions
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                editor.post { if (inlineEditor === editor) resizeInlineText(editor) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        editor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (inlineEditor === editor) resizeInlineText(editor)
        }
        updateButtons()
        editor.requestFocus()
        editor.setSelection(editor.text.length)
        editor.post {
            resizeInlineText(editor)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** EditText has no Android TextView AutoSize; fit text after each change. */
    private fun resizeInlineText(editor: EditText) {
        val width = editor.width - editor.compoundPaddingLeft - editor.compoundPaddingRight
        val height = editor.height - editor.compoundPaddingTop - editor.compoundPaddingBottom
        if (width <= 0 || height <= 0) return
        val content = editor.text?.toString().orEmpty().ifBlank { editor.hint?.toString().orEmpty() }
        val maxSp = (32f * textZoom).coerceIn(16f, 48f)
        val density = resources.displayMetrics.scaledDensity
        val paint = TextPaint(editor.paint)
        var low = 12
        var high = maxSp.toInt().coerceAtLeast(low)
        var best = low
        while (low <= high) {
            val mid = (low + high) / 2
            paint.textSize = mid * density
            val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .build()
            if (layout.height <= height) { best = mid; low = mid + 1 }
            else high = mid - 1
        }
        if (kotlin.math.abs(editor.textSize - best * density) > 0.5f) {
            editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, best.toFloat())
        }
    }

    private fun closeInlineEditor() {
        val editor = inlineEditor ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editor.windowToken, 0)
        val parent = editor.parent as? LinearLayout
        parent?.removeView(editor)
        inlineActions?.let { parent?.removeView(it) }
        inlineEditor = null
        inlineActions = null
        inlineEditing = false
        inlineEditingCardId = -1L
        textPhrase.visibility = View.VISIBLE
        updateCardContent()
        updateButtons()
    }

    private fun saveInlineEdit() {
        if (isBusy || isAnimating) return
        val cardId = inlineEditingCardId
        val back = inlineEditingBack
        val text = inlineEditor?.text?.toString()?.trim() ?: return
        val original = currentCard?.takeIf { it.id == cardId } ?: return
        // Empty sides are allowed: they appear as a placeholder until filled in.
        val edited = if (back) original.copy(backText = text) else original.copy(frontText = text)
        val updated = edited.copy(variableDefinitions = TemplateVariables.reconcile(
            original.variableDefinitions, edited.frontText, edited.backText
        ))
        closeInlineEditor()
        lifecycleScope.launch {
            runDatabaseOperation {
                database.cardDao().update(updated)
                achievements.record("card.updated.manual")
                if (allFavoritesMode) refreshAllFavorites(cardId) else refreshCards(cardId)
            }
        }
    }

    private fun editCard() {
        if (isBusy || isAnimating || isPresentationMode()) return
        val original = currentCard ?: return
        if (isChecklist(original)) {
            editChecklist(original)
            return
        }

        showCardEditor(original) { updated ->
            lifecycleScope.launch {
                runDatabaseOperation {
                    database.cardDao().update(updated)
                    achievements.record("card.updated.manual")
                    if (allFavoritesMode) refreshAllFavorites(updated.id)
                    else refreshCards(updated.id)
                }
            }
        }
    }

    /** Clone into the same collection and folder, with a fresh database ID. */
    private fun cloneCard() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode()) return
        val source = currentCard ?: return
        val collection = currentCollection ?: return
        if (isVaultCard(source)) return
        if (isChecklist(source)) {
            editChecklist(source, clone = true)
            return
        }
        showCardEditor(source, clone = true) { edited ->
            lifecycleScope.launch {
                runDatabaseOperation {
                    val newId = database.cardDao().insert(
                        edited.copy(
                            id = 0,
                            collectionId = collection.id,
                            folderId = source.folderId,
                            cardType = source.cardType,
                            sortOrder = database.cardDao().nextSortOrder(collection.id)
                        )
                    )
                    achievements.record("card.cloned")
                    refreshCards(newId)
                }
            }
        }
    }

    private fun isVaultCard(card: CardEntity?): Boolean = card != null &&
        (card.cardType ?: currentCollection?.defaultCardType) == "VAULT"

    private fun copyCardText(copyBoth: Boolean) {
        val card = currentCard ?: return
        if (isVaultCard(card)) return  // Never offer the system clipboard for vault data.
        val text = if (copyBoth && currentCollection?.sideMode != "ONE_SIDED") {
            listOf(card.frontText, card.backText).filter { it.isNotBlank() }.joinToString("\n\n")
        } else if (isFlipped && currentCollection?.sideMode != "ONE_SIDED") {
            card.backText
        } else {
            card.frontText
        }
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_card_text), text))
        lifecycleScope.launch { achievements.record("card.text.copied") }
        Toast.makeText(this, R.string.copy_card_success, Toast.LENGTH_SHORT).show()
    }

    private fun showCopyCardMenu() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode() || currentCard == null || isVaultCard(currentCard)) return
        if (currentCollection?.sideMode == "ONE_SIDED") {
            copyCardText(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.copy_card_text)
            .setItems(arrayOf(
                getString(R.string.copy_current_side),
                getString(R.string.copy_both_sides)
            )) { _, choice -> copyCardText(choice == 1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Search all folders of the current collection, not just the open folder. */
    private fun showCollectionCardSearch() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode()) return
        val collection = currentCollection ?: return
        lifecycleScope.launch {
            val searchableCards = database.cardDao().getByCollection(collection.id)
                .filterNot { (it.cardType ?: collection.defaultCardType) == "VAULT" }
            // No SQLite lower(): Kotlin's Unicode-aware ignoreCase handles non-ASCII alphabets.
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            val searchInput = EditText(this@MainActivity).apply {
                hint = getString(R.string.search_in_collection_hint)
                setSingleLine(true)
            }
            val resultsList = ListView(this@MainActivity)
            val adapter = ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_list_item_1, mutableListOf()
            )
            resultsList.adapter = adapter
            container.addView(searchInput)
            container.addView(resultsList, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(310)
            ))
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.search_in_collection)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .create()
            var matches = emptyList<CardEntity>()
            fun updateMatches(query: String) {
                val term = query.trim()
                matches = if (term.isEmpty()) emptyList() else searchableCards.filter {
                    it.frontText.contains(term, ignoreCase = true) ||
                        it.backText.contains(term, ignoreCase = true)
                }.take(100)
                adapter.clear()
                adapter.addAll(matches.map { card ->
                    card.frontText.lineSequence().firstOrNull().orEmpty().take(90).ifBlank {
                        card.backText.lineSequence().firstOrNull().orEmpty().take(90)
                    }
                })
                adapter.notifyDataSetChanged()
            }
            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateMatches(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            resultsList.setOnItemClickListener { _, _, position, _ ->
                val target = matches.getOrNull(position) ?: return@setOnItemClickListener
                dialog.dismiss()
                lifecycleScope.launch { achievements.record("search.card.opened") }
                val existingIndex = cards.indexOfFirst { it.id == target.id }
                if (existingIndex >= 0) {
                    currentCardIndex = existingIndex
                    isFlipped = false
                    saveSelection()
                    updateViewer()
                } else {
                    // Found outside the current folder; open the card in collection-wide scope.
                    startActivity(Intent(this@MainActivity, MainActivity::class.java).apply {
                        putExtra(EXTRA_COLLECTION_ID, collection.id)
                        putExtra(EXTRA_CARD_ID, target.id)
                    })
                }
            }
            dialog.show()
        }
    }

    private fun deleteCard() {
        if (allFavoritesMode) return
        if (isBusy) return
        val card = currentCard ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirmation_title)
            .setMessage(R.string.delete_confirmation_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_card) { _, _ ->
                val nextId = cards.getOrNull(
                    (currentCardIndex + 1) % cards.size
                )?.takeIf { it.id != card.id }?.id ?: -1L

                lifecycleScope.launch {
                    runDatabaseOperation {
                        database.cardDao().delete(card)
                        refreshCards(nextId)
                    }
                }
            }
            .show()
    }

    private fun showCardEditor(existing: CardEntity?, clone: Boolean = false, onSave: (CardEntity) -> Unit) {
        val collection = currentCollection ?: return
        val singleSided = collection.sideMode == "ONE_SIDED"
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        scrollView.addView(container)
        val frontText = editorField(container, collection.frontLabel,
            existing?.frontText ?: "", true)
        val backText = if (!singleSided) editorField(container, collection.backLabel,
            existing?.backText ?: "", true) else null
        // Interaction is mutually exclusive: NORMAL, YES_NO or QUANTITY.
        val modeChoices = arrayOf(
            getString(R.string.interaction_normal),
            getString(R.string.yes_no_editor_checkbox),
            getString(R.string.quantity_editor_option)
        )
        val modeCodes = listOf("NORMAL", "YES_NO", "QUANTITY")
        val modeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeChoices
            )
            setSelection(modeCodes.indexOf(existing?.interactionMode ?: "NORMAL").coerceAtLeast(0))
        }
        if (!singleSided && collection.defaultCardType == "LANGUAGE") {
            container.addView(TextView(this).apply { setText(R.string.interaction_mode_label) })
            container.addView(modeSpinner)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (clone) R.string.clone_card else if (existing == null) R.string.create_card_title else R.string.edit_card_title)
            .setView(scrollView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = frontText.text.toString().trim()
                if (text.isEmpty()) {
                    frontText.error = getString(R.string.required_field)
                    return@setOnClickListener
                }
                val result = CardEntity(
                    id = existing?.id ?: 0,
                    collectionId = existing?.collectionId ?: collection.id,
                    frontText = text,
                    backText = if (singleSided) existing?.backText.orEmpty() else backText?.text.toString().trim(),
                    // Legacy fields retained for JSON v1 compatibility.
                    frontLanguage = collection.frontLabel,
                    backLanguage = collection.backLabel,
                    sortOrder = existing?.sortOrder ?: 0,
                    folderId = existing?.folderId ?: if (folderScoped) activeFolderId else null,
                    cardType = existing?.cardType,
                    interactionMode = if (!singleSided && collection.defaultCardType == "LANGUAGE") modeCodes[modeSpinner.selectedItemPosition] else "NORMAL",
                    isFavorite = if (clone) false else existing?.isFavorite ?: false,
                    variableDefinitions = TemplateVariables.reconcile(
                        existing?.variableDefinitions ?: "{}", text,
                        if (singleSided) existing?.backText.orEmpty() else backText?.text.toString().trim()
                    )
                )
                onSave(result)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isChecklist(card: CardEntity?): Boolean = card != null &&
        (card.cardType ?: currentCollection?.defaultCardType) == "CHECKLIST"

    /** Card text is the title; checklist items are stored separately with persistent flags. */
    private fun editChecklist(existing: CardEntity?, clone: Boolean = false) {
        if (isBusy || isPresentationMode()) return
        val collection = currentCollection ?: return
        lifecycleScope.launch {
            val oldItems = if (existing != null) database.checklistItemDao().getForCard(existing.id)
                           else emptyList()
            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            val titleInput = editorField(body, getString(R.string.checklist_title_label),
                existing?.frontText.orEmpty(), false)
            val itemsInput = editorField(body, getString(R.string.checklist_items_label),
                oldItems.joinToString("\n") { it.text }, true).apply {
                minLines = 5
                maxLines = 12
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(body) }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(if (clone) R.string.clone_card else if (existing == null) R.string.create_card_title else R.string.edit_card_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = titleInput.text.toString().trim()
                    if (name.isBlank()) {
                        titleInput.error = getString(R.string.required_field)
                        return@setOnClickListener
                    }
                    val lines = itemsInput.text.toString().lineSequence()
                        .map { it.trim() }.filter { it.isNotBlank() }.toList()
                    if (lines.size > 500 || lines.any { it.length > 1000 }) {
                        itemsInput.error = getString(R.string.operation_failed)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    lifecycleScope.launch {
                        runDatabaseOperation {
                            val cardId = database.withTransaction {
                                val record = (if (clone) null else existing) ?: CardEntity(
                                    collectionId = collection.id, frontText = name,
                                    backText = "", frontLanguage = collection.frontLabel,
                                    backLanguage = collection.backLabel,
                                    folderId = if (folderScoped) activeFolderId else null,
                                    cardType = "CHECKLIST",
                                    sortOrder = database.cardDao().nextSortOrder(collection.id)
                                )
                                val finalRecord = record.copy(frontText = name, cardType = "CHECKLIST",
                                    folderId = if (clone) existing?.folderId else record.folderId)
                                val id = if (existing == null || clone) database.cardDao().insert(finalRecord)
                                         else { database.cardDao().update(finalRecord); finalRecord.id }
                                database.checklistItemDao().deleteForCard(id)
                                lines.forEachIndexed { index, line ->
                                    val retained = oldItems.getOrNull(index)
                                    database.checklistItemDao().insert(ChecklistItemEntity(
                                        cardId = id, text = line,
                                        checked = !clone && retained?.text == line && retained.checked,
                                        sortOrder = index
                                    ))
                                }
                                id
                            }
                            when {
                                clone -> achievements.record("card.cloned")
                                existing == null -> {
                                    achievements.record("card.created.manual")
                                }
                                else -> achievements.record("card.updated.manual")
                            }
                            if (allFavoritesMode) refreshAllFavorites(cardId)
                            else refreshCards(cardId)
                        }
                    }
                }
            }
            dialog.show()
        }
    }

    private fun loadChecklistPreview(cardId: Long) {
        lifecycleScope.launch {
            val items = database.checklistItemDao().getForCard(cardId)
            if (currentCard?.id != cardId || !isChecklist(currentCard)) return@launch
            val title = currentCard?.frontText.orEmpty()
            val preview = items.take(7).joinToString("\n") {
                (if (it.checked) "☑ " else "☐ ") + it.text
            }
            textPhrase.text = buildString {
                append(title)
                if (items.isNotEmpty()) { append("\n\n"); append(preview) }
                if (items.size > 7) append("\n…")
            }
        }
    }

    private fun showChecklistItems() {
        if (isBusy || isAnimating || isPresentationMode()) return
        val card = currentCard ?: return
        if (!isChecklist(card)) return
        lifecycleScope.launch {
            val items = database.checklistItemDao().getForCard(card.id)
            if (items.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(card.frontText)
                    .setMessage(R.string.checklist_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val checked = items.map { it.checked }.toBooleanArray()
            val labels = items.map { it.text }.toTypedArray()
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(card.frontText)
                .setMultiChoiceItems(labels, checked) { _, which, selected ->
                    checked[which] = selected
                    lifecycleScope.launch {
                        try {
                            database.checklistItemDao().setChecked(items[which].id, card.id, selected)
                            loadChecklistPreview(card.id)
                        } catch (e: Exception) { showError(e) }
                    }
                }
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.show()
        }
    }

    private fun editorField(
        container: LinearLayout,
        label: String,
        value: String,
        multiline: Boolean
    ): EditText {
        container.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        })

        val input = EditText(this).apply {
            setText(value)
            textSize = 16f

            inputType = InputType.TYPE_CLASS_TEXT or
                    if (multiline)
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS

            if (multiline) {
                minLines = 2
                maxLines = 4
            } else {
                setSingleLine(true)
            }
        }

        container.addView(input)
        return input
    }

    // --------------------------------------------------------
    // Viewer
    // --------------------------------------------------------

    // Viewer may redraw several times per card. The repository additionally keeps
    // persistent per-card flags, so a card is never counted twice.
    private val tutorialSeenInSession = mutableSetOf<Int>()

    private fun trackTutorialCardView() {
        if (showQueueActive) return
        val index = TutorialLibrary.cardIndex(currentCollection, currentCard) ?: return
        if (!tutorialSeenInSession.add(index)) return
        lifecycleScope.launch {
            try {
                achievements.markTutorialViewed(index)
            } catch (e: Exception) {
                android.util.Log.e("PhrasotecaAchievements", "Tutorial progress update failed", e)
                tutorialSeenInSession.remove(index)
            }
        }
    }

    private fun updateViewer() {
        if (allFavoritesMode) {
            // All card-specific behavior (color, labels, TTS, single-sided rules)
            // must follow the ORIGINAL collection of the selected favorite.
            currentCollection = currentCard?.let { favoriteCollectionMap[it.collectionId] }
        } else {
            trackTutorialCardView()
        }
        textCollectionName.text = if (allFavoritesMode) {
            "‹  ${getString(R.string.favorites_collection_title)}"
        } else {
            "‹  ${currentCollection?.name ?: getString(R.string.no_collections)}"
        }


        textCollectionName.isClickable = !isBusy

        textCardCounter.text = if (showQueueActive) {
            getString(R.string.card_counter_format, queuePosition + 1, queueSelection.size)
        } else {
            getString(R.string.card_counter_format,
                if (cards.isEmpty()) 0 else currentCardIndex + 1, cards.size)
        }
        updateQueueSelectionUi()

        applyPresentationUi()
        updateCardContent()
        updateNavigationMode()
        updateButtons()
    }

    private fun updateCardContent() {
        val card = currentCard
        val collection = currentCollection
        val singleSided = collection?.sideMode == "ONE_SIDED"
        if (singleSided && isFlipped) isFlipped = false
        val back = isFlipped && !singleSided
        val color = if (back) collection?.backColor else collection?.frontColor
        val parsedColor = try {
            android.graphics.Color.parseColor(color ?: "#F2F4FD")
        } catch (_: IllegalArgumentException) {
            android.graphics.Color.parseColor("#F2F4FD")
        }
        // The fold previews the opposite side, even after flipping the card.
        val oppositeColor = try {
            android.graphics.Color.parseColor(
                (if (back) collection?.frontColor else collection?.backColor) ?: "#F2F4FD"
            )
        } catch (_: IllegalArgumentException) {
            android.graphics.Color.parseColor("#F2F4FD")
        }
        // New drawable for each update; never mutate the shared XML background.
        cardContainer.background = CardSurfaceDrawable(
            parsedColor,
            collection?.sideMode != "ONE_SIDED" && card != null,
            resources.displayMetrics.density,
            oppositeColor
        )
        // Choose readable foreground colors for both pastel and dark card backgrounds.
        val isDarkCard = ColorUtils.calculateLuminance(parsedColor) < 0.35
        val primary = if (isDarkCard) android.graphics.Color.WHITE
                      else android.graphics.Color.rgb(29, 35, 48)
        val secondary = if (isDarkCard) android.graphics.Color.rgb(220, 226, 237)
                        else android.graphics.Color.rgb(76, 86, 108)
        textLanguage.setTextColor(secondary)
        textPhrase.setTextColor(primary)
        textQuantityValue.setTextColor(primary)
        gestureHint.setTextColor(secondary)
        // Action icons sit on their own opaque, theme-aware surfaces. Their tint
        // must not follow the user-defined card text color (which can be white).
        textQuantityValue.visibility = if (card != null && card.interactionMode == "QUANTITY" &&
            !quantityValues[card.id].isNullOrBlank()) View.VISIBLE else View.GONE
        textQuantityValue.text = card?.let { quantityValues[it.id] }.orEmpty()
        if (card == null) {
            textPhrase.setTypeface(null, Typeface.NORMAL)
            textPhrase.alpha = 1f
            textLanguage.text = ""
            textPhrase.setText(if (favoritesOnly || allFavoritesMode) R.string.favorites_empty else R.string.empty_collection)
        } else if (isChecklist(card)) {
            textPhrase.setTypeface(null, Typeface.NORMAL)
            textPhrase.alpha = 1f
            textLanguage.text = if (allFavoritesMode) {
                "${collection?.name.orEmpty()} · ${collection?.frontLabel.orEmpty()}"
            } else collection?.frontLabel ?: getString(R.string.checklist_title_label)
            textPhrase.text = card.frontText
            loadChecklistPreview(card.id)
        } else {
            val sideLabel = if (back) collection?.backLabel ?: card.backLanguage
                            else collection?.frontLabel ?: card.frontLanguage
            textLanguage.text = if (allFavoritesMode) {
                "${collection?.name.orEmpty()} · $sideLabel"
            } else sideLabel
            val visibleText = if (back) card.backText else card.frontText
            if (visibleText.isBlank()) {
                textPhrase.setText(R.string.inline_card_enter_text)
                textPhrase.setTypeface(null, Typeface.ITALIC)
                textPhrase.alpha = 0.65f
            } else {
                textPhrase.text = renderCardSide(card, visibleText)
                textPhrase.setTypeface(null, Typeface.NORMAL)
                textPhrase.alpha = 1f
            }
        }
    }


    /** The original template is never overwritten by substitutions. */
    private fun renderCardSide(card: CardEntity, template: String): String {
        val definitions = TemplateVariables.reconcile(card.variableDefinitions, card.frontText, card.backText)
        return TemplateVariables.render(template, definitions, variableValues[card.id].orEmpty())
    }

    /** Fast, temporary variable entry. Works in both presentation orientations.
     * It never edits persistent definitions or the original card text.
     */
    private fun showQuickVariablesDialog() {
        if (isBusy || isAnimating || inlineEditing) return
        val card = currentCard ?: return
        if (isVaultCard(card) || isChecklist(card)) return
        val names = TemplateVariables.names(card.frontText, card.backText)
        if (names.isEmpty()) return
        val definitions = org.json.JSONObject(TemplateVariables.reconcile(
            card.variableDefinitions, card.frontText, card.backText))
        val existing = variableValues[card.id].orEmpty()
        val units = arrayOf("", "kg", "g", "L", "USD", "EUR", "BRL", "CLP", "RUB")
        data class Field(val name: String, val kind: String,
            val input: EditText?, val unit: android.widget.Spinner?,
            val choice: android.widget.Spinner?)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val fields = names.map { name ->
            body.addView(TextView(this).apply { text = name; textSize = 16f })
            val kind = TemplateVariables.typeOf(definitions, name)
            if (kind == "CHOICE") {
                val choices = TemplateVariables.choiceOptions(definitions, name)
                val selection = android.widget.Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(getString(R.string.variables_choice_unset)) + choices)
                    setSelection((choices.indexOf(existing[name]) + 1).coerceAtLeast(0))
                }
                body.addView(selection)
                Field(name, kind, null, null, selection)
            } else {
                val old = existing[name].orEmpty()
                val oldUnit = if (kind == "NUMBER")
                    units.drop(1).firstOrNull { old.endsWith(" $it") }.orEmpty() else ""
                val input = EditText(this).apply {
                    setSingleLine(true)
                    inputType = if (kind == "NUMBER")
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    filters = arrayOf(android.text.InputFilter.LengthFilter(200))
                    setText(if (oldUnit.isEmpty()) old else old.removeSuffix(" $oldUnit"))
                    hint = name
                }
                body.addView(input)
                val unit = if (kind == "NUMBER") android.widget.Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        units.map { if (it.isEmpty()) getString(R.string.quantity_no_unit) else it })
                    setSelection(units.indexOf(oldUnit).coerceAtLeast(0))
                    body.addView(this)
                } else null
                Field(name, kind, input, unit, null)
            }
        }
        body.addView(TextView(this).apply { setText(R.string.variables_temporary) })
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.variables_fill)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = mutableMapOf<String, String>()
                for (field in fields) {
                    if (field.kind == "CHOICE") {
                        val options = TemplateVariables.choiceOptions(definitions, field.name)
                        val selected = (field.choice?.selectedItemPosition ?: 0) - 1
                        if (selected in options.indices) next[field.name] = options[selected]
                    } else {
                        val raw = field.input?.text?.toString()?.trim().orEmpty()
                        if (raw.isEmpty()) continue
                        if (field.kind == "NUMBER") {
                            val normalized = raw.replace(',', '.')
                            if (!Regex("[+-]?[0-9]{1,12}(\\.[0-9]{1,4})?").matches(normalized)) {
                                field.input?.error = getString(R.string.variables_invalid_number)
                                field.input?.requestFocus()
                                return@setOnClickListener
                            }
                            val unit = units[field.unit?.selectedItemPosition ?: 0]
                            next[field.name] = normalized + if (unit.isEmpty()) "" else " $unit"
                        } else next[field.name] = raw
                    }
                }
                variableValues[card.id] = next
                dialog.dismiss()
                updateCardContent() // AutoSize reflows the substituted phrase.
                updateButtons()
            }
        }
        dialog.show()
    }

    /** Configure TEXT / NUMBER / CHOICE and fill temporary values without changing templates. */
    private fun showVariableDialog() {
        if (isBusy || isAnimating || inlineEditing || isPresentationMode()) return
        val card = currentCard ?: return
        if (isVaultCard(card) || isChecklist(card)) return
        val names = TemplateVariables.names(card.frontText, card.backText)
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.variables_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val definitions = TemplateVariables.reconcile(
            card.variableDefinitions, card.frontText, card.backText
        )
        val types = org.json.JSONObject(definitions)
        val existing = variableValues[card.id].orEmpty()
        val units = arrayOf("", "kg", "g", "L", "USD", "EUR", "BRL", "CLP", "RUB")
        data class VariableField(
            val name: String,
            val type: android.widget.Spinner,
            val value: EditText,
            val unit: android.widget.Spinner,
            val options: EditText,
            val choice: android.widget.Spinner
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val fields = names.map { name ->
            container.addView(TextView(this).apply { text = name })
            val typeSpinner = android.widget.Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    arrayOf(getString(R.string.variables_type_text),
                        getString(R.string.variables_type_number),
                        getString(R.string.variables_type_choice))
                )
            }
            val startingType = TemplateVariables.typeOf(types, name)
            typeSpinner.setSelection(when (startingType) {
                "NUMBER" -> 1
                "CHOICE" -> 2
                else -> 0
            })
            container.addView(typeSpinner)
            val old = existing[name].orEmpty()
            val oldUnit = if (startingType == "NUMBER")
                units.drop(1).firstOrNull { old.endsWith(" $it") }.orEmpty() else ""
            val oldValue = if (oldUnit.isNotEmpty()) old.removeSuffix(" $oldUnit") else old
            val field = EditText(this).apply {
                setSingleLine(true)
                setText(if (startingType == "CHOICE") "" else oldValue)
                hint = name
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
            container.addView(field)
            val unitSpinner = android.widget.Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    units.map { if (it.isEmpty()) getString(R.string.quantity_no_unit) else it })
                setSelection(units.indexOf(oldUnit).coerceAtLeast(0))
            }
            container.addView(unitSpinner)
            val optionsLabel = TextView(this).apply { setText(R.string.variables_choice_options_label) }
            container.addView(optionsLabel)
            val optionEditor = EditText(this).apply {
                hint = getString(R.string.variables_choice_options_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 7
                filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
                setText(TemplateVariables.choiceOptions(types, name).joinToString("\n"))
            }
            container.addView(optionEditor)
            val choiceSpinner = android.widget.Spinner(this)
            val choiceAdapter = ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, mutableListOf())
            choiceSpinner.adapter = choiceAdapter
            container.addView(choiceSpinner)
            fun updateChoices(preferred: String? = null) {
                val previous = preferred ?: choiceSpinner.selectedItem?.toString().orEmpty()
                val parsed = optionEditor.text.toString().lines()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
                choiceAdapter.clear()
                choiceAdapter.add(getString(R.string.variables_choice_unset))
                choiceAdapter.addAll(parsed)
                choiceAdapter.notifyDataSetChanged()
                val pos = parsed.indexOf(previous)
                choiceSpinner.setSelection(if (pos >= 0) pos + 1 else 0)
            }
            updateChoices(old)
            optionEditor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateChoices()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            fun updateInputMode() {
                val mode = typeSpinner.selectedItemPosition
                val number = mode == 1
                val choice = mode == 2
                field.visibility = if (choice) View.GONE else View.VISIBLE
                field.inputType = if (number) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                field.setSingleLine(true)
                unitSpinner.visibility = if (number) View.VISIBLE else View.GONE
                optionsLabel.visibility = if (choice) View.VISIBLE else View.GONE
                optionEditor.visibility = if (choice) View.VISIBLE else View.GONE
                choiceSpinner.visibility = if (choice) View.VISIBLE else View.GONE
                // The hint label immediately preceding the editor is toggled too.
                // No values are persisted until the dialog's OK button is pressed.
            }
            typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                                            position: Int, id: Long) = updateInputMode()
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            updateInputMode()
            VariableField(name, typeSpinner, field, unitSpinner, optionEditor, choiceSpinner)
        }
        container.addView(TextView(this).apply { setText(R.string.variables_temporary) })
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.variables_fill)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nextValues = mutableMapOf<String, String>()
                val nextDefinitions = org.json.JSONObject(definitions)
                for (item in fields) {
                    val mode = item.type.selectedItemPosition
                    val raw = item.value.text.toString().trim()
                    when (mode) {
                        2 -> {
                            val choices = TemplateVariables.parseChoices(item.options.text.toString())
                            if (choices == null) {
                                item.options.error = getString(R.string.variables_choice_invalid)
                                item.options.requestFocus()
                                return@setOnClickListener
                            }
                            nextDefinitions.put(item.name, TemplateVariables.choiceDefinition(choices))
                            val selected = item.choice.selectedItemPosition - 1
                            if (selected in choices.indices) nextValues[item.name] = choices[selected]
                        }
                        1 -> {
                            nextDefinitions.put(item.name, "NUMBER")
                            if (raw.isNotEmpty()) {
                                val normalized = raw.replace(',', '.')
                                if (!Regex("[+-]?[0-9]{1,12}(\\.[0-9]{1,4})?").matches(normalized)) {
                                    item.value.error = getString(R.string.variables_invalid_number)
                                    item.value.requestFocus()
                                    return@setOnClickListener
                                }
                                val unit = units[item.unit.selectedItemPosition]
                                nextValues[item.name] = normalized + if (unit.isEmpty()) "" else " $unit"
                            }
                        }
                        else -> {
                            nextDefinitions.put(item.name, "TEXT")
                            if (raw.isNotEmpty()) nextValues[item.name] = raw
                        }
                    }
                }
                val persistedDefinitions = nextDefinitions.toString()
                // Definitions are persistent; selected CHOICE/TEXT/NUMBER values remain Activity-only.
                dialog.dismiss()
                variableValues[card.id] = nextValues
                if (persistedDefinitions != definitions) {
                    lifecycleScope.launch {
                        runDatabaseOperation {
                            database.cardDao().update(card.copy(variableDefinitions = persistedDefinitions))
                            if (allFavoritesMode) refreshAllFavorites(card.id)
                            else refreshCards(card.id)
                        }
                    }
                } else {
                    updateCardContent()
                    updateButtons()
                }
            }
        }
        dialog.show()
    }

    /** Resolve the recipient's (back-side) language, including legacy collections
     * where the explicit BCP-47 tag was not stored. */
    private fun yesNoBackLanguageTag(): String {
        val collection = currentCollection ?: return ""
        if (collection.backLanguageTag.isNotBlank()) return collection.backLanguageTag
        return localeFromSideTitle(collection.backLabel)?.toLanguageTag()
            ?: localeFromSideTitle(currentCard?.backLanguage.orEmpty())?.toLanguageTag()
            ?: ""
    }

    /** Transient response: it never alters the phrase or its stored translation. */
    private fun showYesNoAnswer(affirmative: Boolean) {
        val card = currentCard ?: return
        val collection = currentCollection ?: return
        if (isBusy || isAnimating || card.interactionMode != "YES_NO") return
        // The recipient reads the back of the card, so show that answer first
        // regardless of which side is currently facing the device owner.
        val recipientTag = yesNoBackLanguageTag()
        val ownerTag = collection.frontLanguageTag.ifBlank {
            localeFromSideTitle(collection.frontLabel)?.toLanguageTag()
                ?: localeFromSideTitle(card.frontLanguage)?.toLanguageTag()
                ?: ""
        }
        val recipient = YesNoAnswers.forLocale(recipientTag)
        val owner = YesNoAnswers.forLocale(ownerTag)
        val first = if (affirmative) recipient.affirmative else recipient.negative
        val second = if (affirmative) owner.affirmative else owner.negative
        val landscapeResponse = isPresentationMode()
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(if (landscapeResponse) 4 else 14), dp(24), dp(8))
        }
        display.addView(TextView(this).apply {
            text = first
            textSize = if (landscapeResponse) 28f else 38f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(if (landscapeResponse) 2 else 12), 0,
                dp(if (landscapeResponse) 2 else 12))
        })
        if (ownerTag.isNotBlank() && ownerTag.substringBefore('-') != recipientTag.substringBefore('-')) {
            display.addView(TextView(this).apply {
                text = "$ownerTag: $second"
                textSize = if (landscapeResponse) 16f else 20f
                gravity = android.view.Gravity.CENTER
            })
        }
        display.addView(TextView(this).apply {
            setText(R.string.yes_no_answer_note)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(if (landscapeResponse) 4 else 16), 0, 0)
        })
        lifecycleScope.launch { achievements.record("card.yes_no.used") }
        AlertDialog.Builder(this)
            .setTitle(R.string.yes_no_answer_title)
            .setView(display)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Edit a non-persistent quantity/price to show alongside the translated phrase. */
    private fun showQuantityEditor() {
        val card = currentCard ?: return
        val collection = currentCollection ?: return
        if (isBusy || isAnimating || card.interactionMode != "QUANTITY" ||
            collection.defaultCardType != "LANGUAGE" || collection.sideMode == "ONE_SIDED") return

        val units = arrayOf("", "kg", "g", "L", "USD", "EUR", "BRL", "CLP", "RUB")
        val existing = quantityValues[card.id].orEmpty().trim()
        val oldUnit = units.drop(1).firstOrNull { existing.endsWith(" $it") }.orEmpty()
        val oldNumber = if (oldUnit.isNotEmpty()) existing.removeSuffix(" $oldUnit") else existing
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.quantity_number_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(14))
            setText(oldNumber)
            selectAll()
        }
        content.addView(input)
        content.addView(TextView(this).apply { setText(R.string.quantity_unit_label) })
        val unitSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                units.map { if (it.isEmpty()) getString(R.string.quantity_no_unit) else it }
            )
            setSelection(units.indexOf(oldUnit).coerceAtLeast(0))
        }
        content.addView(unitSpinner)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.quantity_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.quantity_clear, null)
            .setPositiveButton(R.string.quantity_show, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = input.text.toString().trim().replace(',', '.')
                if (!Regex("[0-9]{1,10}(\\.[0-9]{1,3})?").matches(number) ||
                    number.toBigDecimalOrNull()?.signum() != 1) {
                    input.error = getString(R.string.quantity_invalid)
                    return@setOnClickListener
                }
                val unit = units[unitSpinner.selectedItemPosition]
                quantityValues[card.id] = number + if (unit.isEmpty()) "" else " $unit"
                lifecycleScope.launch { achievements.record("card.quantity.confirmed") }
                updateCardContent()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                quantityValues.remove(card.id)
                updateCardContent()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Filter the current folder/list; never modify the underlying saved cards. */
    private fun toggleFavoritesFilter() {
        if (allFavoritesMode || isBusy || isAnimating || favoriteUpdatePending || isPresentationMode() || showQueueActive) return
        val collection = currentCollection ?: return
        val oldCardId = currentCard?.id ?: -1L
        val enabling = !favoritesOnly
        if (enabling) preFavoritesCardId = oldCardId
        favoritesOnly = enabling
        val targetId = if (enabling) oldCardId else preFavoritesCardId
        if (!enabling) preFavoritesCardId = -1L
        lifecycleScope.launch {
            runDatabaseOperation { loadCollection(collection, targetId) }
        }
    }

    /** Persist only this flag, without rewriting the entire card or changing its order. */
    private fun toggleFavorite() {
        if (isBusy || isAnimating || favoriteUpdatePending || isPresentationMode()) return
        val card = currentCard ?: return
        if (isVaultCard(card)) return
        favoriteUpdatePending = true
        updateButtons()
        lifecycleScope.launch {
            try {
                val favorite = !card.isFavorite
                val updated = database.cardDao().setFavorite(card.id, favorite)
                if (updated != 1) {
                    throw IllegalStateException("Card no longer exists")
                }
                val index = cards.indexOfFirst { it.id == card.id }
                if (allFavoritesMode) {
                    // Unfavoriting removes this shortcut, never the original card.
                    refreshAllFavorites(if (favorite) card.id else -1L)
                } else if (index >= 0) {
                    if (favoritesOnly && !favorite) {
                        cards.removeAt(index)
                        currentCardIndex = currentCardIndex.coerceAtMost((cards.size - 1).coerceAtLeast(0))
                        isFlipped = false
                        saveSelection()
                        updateViewer()
                    } else {
                        cards[index] = cards[index].copy(isFavorite = favorite)
                    }
                }
            } catch (e: Exception) {
                showError(e)
            } finally {
                favoriteUpdatePending = false
                updateButtons()
            }
        }
    }

    private fun updateButtons() {
        val active = !isBusy && !isAnimating && !inlineEditing
        val supportsYesNo = currentCard?.interactionMode == "YES_NO" &&
            currentCollection?.defaultCardType == "LANGUAGE" &&
            currentCollection?.sideMode != "ONE_SIDED"
        yesNoResponseButtons.visibility = if (supportsYesNo && !isAnimating && !inlineEditing) View.VISIBLE else View.GONE
        if (supportsYesNo) {
            // Buttons must be readable to the person viewing the back-side translation.
            val words = YesNoAnswers.forLocale(yesNoBackLanguageTag())
            buttonYesResponse.text = words.affirmative
            buttonNoResponse.text = words.negative
        }
        buttonYesResponse.isEnabled = active && supportsYesNo
        buttonNoResponse.isEnabled = active && supportsYesNo
        val supportsQuantity = currentCard?.interactionMode == "QUANTITY" &&
            currentCollection?.defaultCardType == "LANGUAGE" &&
            currentCollection?.sideMode != "ONE_SIDED"
        quantityControls.visibility = if (supportsQuantity && !isAnimating && !inlineEditing) View.VISIBLE else View.GONE
        buttonSetQuantity.isEnabled = active && supportsQuantity
        buttonPrevious.isEnabled = active && (if (showQueueActive) queueSelection.size > 1 else cards.size > 1)
        buttonNext.isEnabled = active && (if (showQueueActive) queueSelection.size > 1 else cards.size > 1)
        buttonShowCard?.visibility = if (currentCard != null && !isPresentationMode() &&
            !isAnimating && !showQueueActive && !inlineEditing) View.VISIBLE else View.GONE
        buttonShowCard?.isEnabled = active && currentCard != null
        val quickCard = currentCard
        buttonQuickVariables?.visibility = if (active && quickCard != null &&
            !isVaultCard(quickCard) && !isChecklist(quickCard) &&
            TemplateVariables.names(quickCard.frontText, quickCard.backText).isNotEmpty())
            View.VISIBLE else View.GONE
        buttonQuickVariables?.isEnabled = active
        val favoriteCard = currentCard?.takeUnless { isVaultCard(it) }
        buttonFavorite?.apply {
            visibility = if (favoriteCard != null && !isPresentationMode() &&
                !showQueueActive && !isAnimating && !inlineEditing) View.VISIBLE else View.GONE
            isEnabled = active && !favoriteUpdatePending && favoriteCard != null
            setImageResource(if (favoriteCard?.isFavorite == true)
                R.drawable.ic_star_filled_24 else R.drawable.ic_star_outline_24)
            setColorFilter(getColor(if (favoriteCard?.isFavorite == true)
                R.color.card_action_favorite else R.color.card_action_icon))
            contentDescription = getString(if (favoriteCard?.isFavorite == true)
                R.string.remove_from_favorites else R.string.add_to_favorites)
        }
        val showPresentationControls = isPresentationMode() && !isAnimating && !isBusy
        presentationBackButton?.visibility = if (showPresentationControls) View.VISIBLE else View.GONE
        presentationRotateButton?.visibility = if (showPresentationControls) View.VISIBLE else View.GONE
        landscapeBackButton?.visibility = if (showPresentationControls) View.VISIBLE else View.GONE
        presentationBackButton?.isEnabled = active
        presentationRotateButton?.isEnabled = active
        landscapeBackButton?.isEnabled = active
        buttonDeleteCardOverlay?.isEnabled = active && currentCard != null
        buttonDeleteCardOverlay?.visibility = if (allFavoritesMode || currentCard == null || isAnimating || isPresentationMode() || inlineEditing) View.GONE else View.VISIBLE
        val isLanguage = (currentCard?.cardType ?: currentCollection?.defaultCardType) == "LANGUAGE"
        val spokenText = if (isFlipped) currentCard?.backText else currentCard?.frontText
        val canSpeak = isLanguage && !isPresentationMode() && !spokenText.isNullOrBlank()
        buttonSpeakCardOverlay?.visibility = if (canSpeak && !isAnimating && !inlineEditing) View.VISIBLE else View.GONE
        buttonSpeakCardOverlay?.isEnabled = active && ttsReady
        // Mirror delete/TTS overlays: hide the queue checkbox during a slide and
        // show it again after the incoming card has finished animating.
        updateQueueSelectionUi()
        updateMenuState()
    }

    private fun navigate(step: Int) {
        if (isBusy || isAnimating || inlineEditing) return
        if ((if (showQueueActive) queueSelection.size else cards.size) < 2) return

        textToSpeech?.stop()
        isAnimating = true
        updateButtons() // Hide the delete and TTS overlay controls during both phases.

        // Next moves left, previous moves right. The incoming card arrives
        // from the opposite side. Keep the movement short and understated.
        val direction = if (step > 0) 1f else -1f
        val offset = dp(SLIDE_DISTANCE_DP).toFloat()

        cardContainer.animate().cancel()
        cardContainer.translationX = 0f
        cardContainer.alpha = 1f
        cardContainer.animate()
            .translationX(-direction * offset)
            .alpha(0f)
            .setDuration(SLIDE_OUT_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (showQueueActive) {
                    queuePosition = Math.floorMod(queuePosition + step, queueSelection.size)
                    val id = queueSelection.elementAt(queuePosition)
                    currentCardIndex = cards.indexOfFirst { it.id == id }.coerceAtLeast(0)
                } else {
                    currentCardIndex = Math.floorMod(currentCardIndex + step, cards.size)
                }
                isFlipped = showQueueActive && currentCollection?.defaultCardType == "LANGUAGE" &&
                    currentCollection?.sideMode != "ONE_SIDED" && !currentCard?.backText.isNullOrBlank()
                saveSelection()
                updateViewer() // Refresh content while transparent; overlays stay hidden.

                cardContainer.translationX = direction * offset
                cardContainer.alpha = 0f
                cardContainer.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(SLIDE_IN_DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        isAnimating = false
                        updateButtons()
                    }
                    .start()
            }
            .start()
    }

    /** Restore a stable card view when leaving the screen mid-animation. */
    private fun cancelCardAnimation() {
        if (!::cardContainer.isInitialized) return
        cardContainer.animate().cancel()
        cardContainer.alpha = 1f
        cardContainer.translationX = 0f
        cardContainer.rotationY = 0f
        if (isAnimating) {
            isAnimating = false
            updateCardContent()
            updateButtons()
        }
    }

    private fun flipCard() {
        if (isBusy || isAnimating || inlineEditing || currentCard == null || isChecklist(currentCard) || currentCollection?.sideMode == "ONE_SIDED") return

        textToSpeech?.stop()
        isAnimating = true
        isFlipped = !isFlipped
        saveSelection()
        updateButtons()

        cardContainer.animate()
            .rotationY(90f)
            .setDuration(FLIP_DURATION)
            .withEndAction {
                updateCardContent()
                cardContainer.rotationY = -90f

                cardContainer.animate()
                    .rotationY(0f)
                    .setDuration(FLIP_DURATION)
                    .withEndAction {
                        isAnimating = false
                        updateButtons()
                    }
                    .start()
            }
            .start()
    }

    // --------------------------------------------------------
    // Gestures
    // --------------------------------------------------------

    private fun initializeTouchGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (isBusy || isAnimating || currentCard == null) return false
                    gestureCancelled = true // Pinch must not trigger a tap, flip or swipe.
                    hasMoved = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    if (!factor.isFinite() || factor <= 0f) return false
                    val next = (textZoom * factor).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
                    if (kotlin.math.abs(next - textZoom) >= 0.002f) {
                        textZoom = next
                        applyPresentationUi()
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    preferences.edit().putFloat(KEY_TEXT_ZOOM, textZoom).apply()
                }
            })
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!gestureCancelled && !hasMoved && !longPressHandled) {
                        cardContainer.performClick()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!gestureCancelled && !hasMoved && !longPressHandled) {
                        longPressHandled = true
                        cardContainer.performLongClick()
                    }
                }
            }
        )

        cardContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    hasMoved = false
                    gestureCancelled = false
                    longPressHandled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        hasMoved = true
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    gestureCancelled = true
                    hasMoved = true
                }
                MotionEvent.ACTION_CANCEL -> gestureCancelled = true
            }

            // GestureDetector dispatches tap and long-press events;
            // horizontal travel is checked separately to accept slow swipes.
            scaleDetector.onTouchEvent(event)
            if (!gestureCancelled && !scaleDetector.isInProgress) detector.onTouchEvent(event)

            if (event.actionMasked == MotionEvent.ACTION_UP &&
                (showQueueActive || isPresentationMode() || swipeNavigationEnabled) && !gestureCancelled && !longPressHandled &&
                !isBusy && !isAnimating
            ) {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (abs(dx) >= swipeThresholdPx &&
                    abs(dx) > abs(dy) * SWIPE_RATIO
                ) {
                    navigate(if (dx < 0f) 1 else -1)
                }
            }
            true
        }
    }

    // --------------------------------------------------------
    // Text-to-Speech: language cards only
    // --------------------------------------------------------

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) updateButtons()
                }
            }
        }
    }

    private fun speakCurrentSide() {
        if (isBusy || isAnimating || isPresentationMode()) return
        val card = currentCard ?: return
        val collection = currentCollection ?: return
        if ((card.cardType ?: collection.defaultCardType) != "LANGUAGE") return
        val content = renderCardSide(card, if (isFlipped) card.backText else card.frontText).trim()
        if (content.isEmpty()) return
        val engine = textToSpeech
        if (!ttsReady || engine == null) {
            Toast.makeText(this, R.string.tts_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        // A configured BCP-47 tag takes priority over free-form labels.
        // Empty tags are used by migrated legacy collections and preserve their TTS behavior.
        val configuredTag = if (isFlipped) collection.backLanguageTag else collection.frontLanguageTag
        val title = if (isFlipped) collection.backLabel else collection.frontLabel
        val legacy = if (isFlipped) card.backLanguage else card.frontLanguage
        val locale = if (configuredTag.isNotBlank()) Locale.forLanguageTag(configuredTag)
            else localeFromSideTitle(legacy) ?: localeFromSideTitle(title)
        if (locale == null) {
            Toast.makeText(this, R.string.tts_unknown_language, Toast.LENGTH_LONG).show()
            return
        }
        val availability = engine.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Toast.makeText(this, R.string.tts_language_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, R.string.tts_language_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        engine.stop()
        val result = engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, "card-${card.id}-${if (isFlipped) "back" else "front"}")
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(this, R.string.tts_failed, Toast.LENGTH_SHORT).show()
        } else {
            lifecycleScope.launch { achievements.record("tts.speak.accepted") }
        }
    }

    private fun localeFromSideTitle(input: String): Locale? {
        val normalized = input.trim().lowercase(Locale.ROOT)
            .replace('ё', 'е')
        val code = when (normalized) {
            "russian", "русский", "русский язык", "ru", "ru-ru" -> "ru-RU"
            "english", "английский", "английский язык", "en", "en-us" -> "en-US"
            "en-gb" -> "en-GB"
            "spanish", "español", "espanol", "испанский", "испанский язык", "es", "es-es" -> "es-ES"
            "es-cl" -> "es-CL"
            "es-mx" -> "es-MX"
            "portuguese", "português", "portugues", "португальский", "pt", "pt-br" -> "pt-BR"
            "pt-pt" -> "pt-PT"
            "vietnamese", "tiếng việt", "вьетнамский", "vi", "vi-vn" -> "vi-VN"
            "thai", "ภาษาไทย", "тайский", "th", "th-th" -> "th-TH"
            "german", "deutsch", "немецкий", "de", "de-de" -> "de-DE"
            "french", "français", "francais", "французский", "fr", "fr-fr" -> "fr-FR"
            "italian", "italiano", "итальянский", "it", "it-it" -> "it-IT"
            "chinese", "中文", "汉语", "китайский", "zh", "zh-cn", "zh-hans", "简体中文" -> "zh-Hans"
            "traditional chinese", "繁體中文", "китайский традиционный", "zh-hant", "zh-hant-tw", "zh-tw" -> "zh-Hant-TW"
            "georgian", "ქართული", "грузинский", "ka", "ka-ge" -> "ka-GE"
            "japanese", "日本語", "японский", "ja", "ja-jp" -> "ja-JP"
            "korean", "한국어", "корейский", "ko", "ko-kr" -> "ko-KR"
            else -> normalized.takeIf { it.matches(Regex("[a-z]{2,3}(-[a-z]{2,4})?")) }
                ?: return null
        }
        return Locale.forLanguageTag(code).takeIf { it.language.isNotBlank() }
    }

    // --------------------------------------------------------
    // Temporary Show Queue (current collection and open folder only)
    // --------------------------------------------------------

    private fun updateQueueSelectionUi() {
        val card = currentCard
        queueCheckbox?.apply {
            visibility = if (!allFavoritesMode && !showQueueActive && !favoritesOnly && card != null && !isPresentationMode() &&
                !isVaultCard(card) && !isBusy && !isAnimating) View.VISIBLE else View.GONE
            isEnabled = !isBusy && !isAnimating
            val selected = card != null && card.id in queueSelection
            setImageResource(if (selected) R.drawable.ic_queue_checked_24
                else R.drawable.ic_queue_unchecked_24)
            contentDescription = getString(if (selected) R.string.show_queue_unselect
                else R.string.show_queue_select)
        }
    }

    private fun queueStorageKey(collectionId: Long): String {
        val scope = when {
            !folderScoped -> "all"
            activeFolderId == null -> "root"
            else -> "folder_${activeFolderId}"
        }
        return "show_queue_selected_${collectionId}_${scope}"
    }

    private fun readSavedQueue(key: String): List<Long> =
        preferences.getString(key, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }
            .distinct()

    private fun persistQueueSelection() {
        val key = queueScopeKey ?: return
        // Store only the card IDs, never their text (especially important for Vault).
        if (queueSelection.isEmpty()) {
            preferences.edit().remove(key).apply()
        } else {
            preferences.edit().putString(key, queueSelection.joinToString(",")).apply()
        }
    }

    private fun toggleQueueSelection() {
        if (isBusy || isAnimating || isPresentationMode() || showQueueActive || favoritesOnly) return
        val card = currentCard ?: return
        if (isVaultCard(card)) return // Never expose vault content in a presentation queue.
        if (!queueSelection.add(card.id)) queueSelection.remove(card.id)
        persistQueueSelection()
        updateQueueSelectionUi()
        updateMenuState()
    }

    private fun clearShowQueue() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating) return
        if (showQueueActive) {
            returnFromShowMode()
            return
        }
        queueSelection.clear()
        persistQueueSelection()
        updateQueueSelectionUi()
        updateMenuState()
    }

    private fun startShowQueue() {
        if (allFavoritesMode) return
        if (isBusy || isAnimating || isPresentationMode() || showQueueActive || favoritesOnly || queueSelection.size < 2) return
        val byId = cards.associateBy { it.id }
        val valid = queueSelection.filter { id -> byId[id]?.let { !isVaultCard(it) } == true }
        if (valid.size < 2) {
            queueSelection.retainAll(valid.toSet())
            persistQueueSelection()
            updateQueueSelectionUi()
            updateMenuState()
            return
        }
        // Playback follows the order in which cards were selected.
        queueSelection.clear()
        queueSelection.addAll(valid)
        persistQueueSelection()
        preQueueCardId = currentCard?.id ?: -1L
        preQueueFlipped = isFlipped
        showQueueActive = true
        if (valid.size >= 5) {
            lifecycleScope.launch { achievements.record("show_queue.size5") }
        }
        queuePosition = 0
        currentCardIndex = cards.indexOfFirst { it.id == valid[0] }
        isFlipped = currentCollection?.defaultCardType == "LANGUAGE" &&
            currentCollection?.sideMode != "ONE_SIDED" && !currentCard?.backText.isNullOrBlank()
        saveSelection()
        updateViewer()
        showModeActive = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    // --------------------------------------------------------
    // Orientation
    // --------------------------------------------------------

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleOrientation(forceLandscape: Boolean = false) {
        if (isBusy || isAnimating) return
        if (isPresentationMode()) {
            returnFromShowMode()
            return
        }
        // Restore the original side only when presentation is actually exited.
        intent.putExtra(EXTRA_SHOW_ORIGINAL_SIDE, isFlipped)
        val collection = currentCollection
        if (collection?.defaultCardType == "LANGUAGE" &&
            collection.sideMode != "ONE_SIDED" && currentCard != null &&
            !currentCard!!.backText.isNullOrBlank()
        ) {
            isFlipped = true
            saveSelection()
        }
        showModeActive = true
        val landscape = forceLandscape || showCardLandscape
        val target = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (landscape != isLandscape()) {
            requestedOrientation = target
        } else {
            // Portrait presentation does not recreate the Activity: update it now.
            requestedOrientation = target
            updateSystemBarsForOrientation()
            updateViewer()
        }
    }

    private fun returnFromShowMode() {
        if (isBusy || isAnimating) return
        if (showQueueActive) {
            showQueueActive = false
            lifecycleScope.launch { achievements.record("show_queue.finished") }
            queueSelection.clear()
            persistQueueSelection()
            queuePosition = 0
            val previous = cards.indexOfFirst { it.id == preQueueCardId }
            if (previous >= 0) currentCardIndex = previous
            isFlipped = preQueueFlipped
            preQueueCardId = -1L
            intent.removeExtra(EXTRA_SHOW_ORIGINAL_SIDE)
            saveSelection()
        } else if (intent.hasExtra(EXTRA_SHOW_ORIGINAL_SIDE)) {
            isFlipped = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL_SIDE, false)
            intent.removeExtra(EXTRA_SHOW_ORIGINAL_SIDE)
            saveSelection()
        }
        showModeActive = false
        if (isLandscape()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            applyPresentationUi()
            updateSystemBarsForOrientation()
            updateViewer()
            updateOrientationMenuItem()
        }
    }

    private fun updateOrientationMenuItem() {
        if (!::toolbar.isInitialized) return
        toolbar.menu.findItem(R.id.menuOrientation)?.setTitle(
            R.string.landscape_presentation_mode
        )
    }

    // --------------------------------------------------------
    // Utilities
    // --------------------------------------------------------

    private suspend fun runDatabaseOperation(
        operation: suspend () -> Unit
    ) {
        isBusy = true
        updateViewer()

        try {
            operation()
        } catch (e: Exception) {
            showError(e)
        } finally {
            isBusy = false
            updateViewer()
        }
    }

    private fun showError(e: Exception) {
        android.util.Log.e("Phrasoteca", "Database error", e)
        Toast.makeText(
            this,
            "${getString(R.string.operation_failed)}: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f)
            .toInt()
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.main)

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) {
                view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    override fun onStop() {
        if (inlineEditing) closeInlineEditor()
        textToSpeech?.stop()
        cancelCardAnimation()
        super.onStop()
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        cancelCardAnimation()
        super.onDestroy()
    }


    private fun showCardOrderDialog() {
        if (allFavoritesMode) return

        if (isBusy || isAnimating || isLandscape() || cards.size < 2) return

        val actions = arrayOf(
            getString(R.string.move_up),
            getString(R.string.move_down)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.card_order_title)
            .setItems(actions) { _, which ->
                moveCurrentCard(
                    if (which == 0) -1 else 1
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveCurrentCard(direction: Int) {

        if (isBusy || isAnimating) return

        val card = currentCard ?: return

        val targetIndex = currentCardIndex + direction

        if (targetIndex !in cards.indices) return

        val neighbor = cards[targetIndex]

        lifecycleScope.launch {

            runDatabaseOperation {

                database.withTransaction {

                    database.cardDao().updateSortOrder(
                        card.id,
                        neighbor.sortOrder
                    )

                    database.cardDao().updateSortOrder(
                        neighbor.id,
                        card.sortOrder
                    )
                }

                refreshCards(card.id)
                achievements.record("card.order.changed")
            }
        }
    }

}
