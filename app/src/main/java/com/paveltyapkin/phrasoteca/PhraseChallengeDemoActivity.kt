package com.paveltyapkin.phrasoteca

import android.os.Bundle
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.widget.Toolbar
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Offline practice quiz. Uses the bundled English idiom library, never edits Room. */
class PhraseChallengeDemoActivity : AppCompatActivity() {
    companion object {
        private const val S_QUESTIONS = "questions"
        private const val S_CHOICES = "choices"
        private const val S_INDEX = "index"
        private const val S_SCORE = "score"
        private const val S_ANSWERED = "answered"
        private const val S_SELECTED = "selected"
        private const val S_FINISHED = "finished"
        private const val S_SWIPE_ENABLED = "swipe_enabled"
        private const val S_TAP_TO_CHOOSE = "tap_to_choose"
        private const val SLIDE_OUT_MS = 110L
        private const val SLIDE_IN_MS = 140L
    }

    private data class DemoQuestion(
        val id: String,
        val prompt: String,
        val correct: String,
        val options: List<String>,
        val correctOptionIndex: Int
    )

    private lateinit var questions: List<DemoQuestion>
    private lateinit var entriesById: Map<String, DemoQuestion>
    private lateinit var questionIds: ArrayList<String>
    private lateinit var choiceIds: ArrayList<String>
    private lateinit var topText: TextView
    private lateinit var progressText: TextView
    private lateinit var answerText: TextView
    private lateinit var indicator: TextView
    private lateinit var feedback: TextView
    private lateinit var nextButton: Button
    private lateinit var previousButton: Button
    private lateinit var followingButton: Button
    private lateinit var answerCard: View
    private lateinit var answerArea: View
    private lateinit var navigationButtons: View
    private var swipeEnabled = true
    private var tapToChoose = true
    private var isSliding = false
    private var currentIndex = 0
    private var score = 0
    private var answered = false
    private var selected = 0
    private var finished = false
    private var touchStartX = 0f
    private var touchStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_phrase_challenge_demo)
        applyWindowInsets()
        applyAppearanceSettings()
        swipeEnabled = savedInstanceState?.getBoolean(S_SWIPE_ENABLED, true) ?: true
        tapToChoose = savedInstanceState?.getBoolean(S_TAP_TO_CHOOSE, true) ?: true
        val toolbar = findViewById<Toolbar>(R.id.idiomGameToolbar)
        toolbar.navigationIcon?.mutate()?.setTint(
            ContextCompat.getColor(this, R.color.home_title_color)
        )
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.findItem(R.id.action_idiom_swipe_navigation).isChecked = swipeEnabled
        toolbar.menu.findItem(R.id.action_idiom_tap_to_choose).isChecked = tapToChoose
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_idiom_swipe_navigation -> {
                    swipeEnabled = !swipeEnabled
                    item.isChecked = swipeEnabled
                    updateNavigationControls()
                    true
                }
                R.id.action_idiom_tap_to_choose -> {
                    tapToChoose = !tapToChoose
                    item.isChecked = tapToChoose
                    updateNavigationControls()
                    true
                }
                else -> false
            }
        }
        val toolbarTitle = findViewById<Toolbar>(R.id.idiomGameToolbar)
        toolbarTitle.setTitle(R.string.phrase_demo_title)
        questions = try { loadDemo() } catch (e: Exception) {
            Toast.makeText(this, R.string.phrase_demo_load_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        entriesById = questions.associateBy { it.id }
        findViewById<TextView>(R.id.idiomGameQuestionLabel).setText(R.string.phrase_demo_question_label)
        topText = findViewById(R.id.idiomGameQuestion)
        progressText = findViewById(R.id.idiomGameProgress)
        answerText = findViewById(R.id.idiomGameAnswer)
        indicator = findViewById(R.id.idiomGameIndicator)
        feedback = findViewById(R.id.idiomGameFeedback)
        previousButton = findViewById(R.id.idiomGamePrevious)
        followingButton = findViewById(R.id.idiomGameFollowing)
        nextButton = findViewById(R.id.idiomGameNext)
        answerCard = findViewById(R.id.idiomGameAnswerCard)
        answerArea = findViewById(R.id.idiomGameAnswerArea)
        navigationButtons = findViewById(R.id.idiomGameNavigationButtons)
        updateNavigationControls()
        previousButton.setOnClickListener { animateAnswerShift(-1) }
        followingButton.setOnClickListener { animateAnswerShift(1) }
        nextButton.setOnClickListener {
            if (finished) startNewGame()
            else if (answered) advance() else confirmAnswer()
        }
        answerCard.setOnClickListener {
            if (tapToChoose && !isSliding && !answered && !finished) confirmAnswer()
        }
        answerCard.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    val threshold = 45f * resources.displayMetrics.density
                    if (swipeEnabled && abs(dx) >= threshold && abs(dx) > abs(dy)) {
                        animateAnswerShift(if (dx < 0) 1 else -1)
                    } else if (tapToChoose && abs(dx) < threshold && abs(dy) < threshold) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        if (savedInstanceState == null) startNewGame()
        else {
            questionIds = savedInstanceState.getStringArrayList(S_QUESTIONS) ?: arrayListOf()
            choiceIds = savedInstanceState.getStringArrayList(S_CHOICES) ?: arrayListOf()
            currentIndex = savedInstanceState.getInt(S_INDEX, 0)
            score = savedInstanceState.getInt(S_SCORE, 0)
            answered = savedInstanceState.getBoolean(S_ANSWERED, false)
            selected = savedInstanceState.getInt(S_SELECTED, 0)
            finished = savedInstanceState.getBoolean(S_FINISHED, false)
            if (questionIds.size != questions.size || questionIds.toSet().size != questions.size ||
                !questionIds.all { it in entriesById } ||
                (!finished && (currentIndex !in questionIds.indices || choiceIds.size != 4 ||
                    choiceIds.toSet() != setOf("0", "1", "2", "3")))) {
                startNewGame()
            } else render()
        }
    }

    /** The approved twenty-question pilot, not generated from user's Room cards. */
    private fun loadDemo(): List<DemoQuestion> {
        val source = assets.open("phrase_challenge_demo_questions.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = org.json.JSONObject(source).getJSONArray("questions")
        val result = ArrayList<DemoQuestion>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val optionsJson = item.getJSONArray("options")
            require(optionsJson.length() == 4)
            val texts = (0 until 4).map { j -> optionsJson.getJSONObject(j).getString("text") }
            val ids = (0 until 4).map { j -> optionsJson.getJSONObject(j).getString("id") }
            val sourceId = item.getString("source_phrase_id")
            val correct = item.getString("correct_ru")
            val correctIndex = ids.indexOf(sourceId)
            require(correctIndex in 0..3 && texts[correctIndex] == correct)
            require(texts.toSet().size == 4 && ids.toSet().size == 4)
            require(item.getBoolean("all_same_category") && item.getBoolean("all_same_mode"))
            result.add(DemoQuestion(item.getString("question_id"), item.getString("prompt_pt"), correct, texts, correctIndex))
        }
        require(result.size == 20 && result.map { it.id }.toSet().size == 20)
        return result
    }

    private fun startNewGame() {
        questionIds = ArrayList(questions.map { it.id }.shuffled())
        currentIndex = 0
        score = 0
        answered = false
        selected = 0
        finished = false
        generateOptions()
        render()
    }

    /** Only the order is randomized; all four options come from the reviewed demo dataset. */
    private fun generateOptions() {
        choiceIds = ArrayList((0..3).map { it.toString() }.shuffled())
        check(choiceIds.size == 4 && choiceIds.toSet().size == 4)
        selected = 0
        answered = false
    }

    private fun isCurrentAnswerCorrect(): Boolean {
        val question = entriesById.getValue(questionIds[currentIndex])
        return choiceIds[selected].toInt() == question.correctOptionIndex
    }

    /** Two-phase translation and fade, matching the navigation style in MainActivity.
     * Block further gestures until the incoming answer has fully appeared.
     */
    private fun animateAnswerShift(step: Int) {
        if (answered || finished || isSliding) return
        isSliding = true
        updateNavigationControls()
        val direction = if (step > 0) 1f else -1f
        val distance = 64f * resources.displayMetrics.density
        answerCard.animate().cancel()
        answerCard.translationX = 0f
        answerCard.alpha = 1f
        answerCard.animate()
            .translationX(-direction * distance)
            .alpha(0f)
            .setDuration(SLIDE_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                selected = (selected + step + 4) % 4
                render()  // Update answer text only while the old card is invisible.
                answerCard.translationX = direction * distance
                answerCard.alpha = 0f
                answerCard.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(SLIDE_IN_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        isSliding = false
                        updateNavigationControls()
                    }
                    .start()
            }
            .start()
    }

    private fun confirmAnswer() {
        if (answered || finished) return
        answered = true
        val correct = isCurrentAnswerCorrect()
        if (correct) score++
        render()
    }

    private fun advance() {
        if (!answered || finished) return
        if (currentIndex == questionIds.lastIndex) {
            finished = true
            // The transition to the results screen occurs once per run.
            // Restoring a finished activity does not repeat this event.
            lifecycleScope.launch {
                val achievements = AchievementRepository(
                    com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase.getInstance(applicationContext)
                )
                achievements.record("phrase_demo.game.finished")
                if (score == questions.size) achievements.record("phrase_demo.game.perfect")
            }
        } else {
            currentIndex++
            generateOptions()
        }
        render()
    }

    private fun render() {
        if (finished) {
            progressText.text = getString(R.string.phrase_demo_complete)
            topText.text = getString(R.string.idiom_game_score, score, questions.size)
            answerText.text = getString(R.string.idiom_game_finished_detail, (score * 100) / questions.size)
            indicator.text = ""
            feedback.text = ""
            answerArea.visibility = View.GONE
            nextButton.text = getString(R.string.idiom_game_replay)
            nextButton.isEnabled = true
            updateNavigationControls()
            return
        }
        answerArea.visibility = View.VISIBLE
        val id = questionIds[currentIndex]
        val current = entriesById.getValue(id)
        progressText.text = getString(R.string.idiom_game_progress, currentIndex + 1, questions.size, score)
        topText.text = current.prompt
        answerText.text = current.options[choiceIds[selected].toInt()]
        indicator.text = getString(R.string.idiom_game_option, selected + 1, 4)
        if (answered) {
            val correct = isCurrentAnswerCorrect()
            feedback.text = if (correct) getString(R.string.idiom_game_correct)
                else getString(R.string.idiom_game_incorrect, current.correct)
            nextButton.text = if (currentIndex == questionIds.lastIndex)
                getString(R.string.idiom_game_results) else getString(R.string.idiom_game_next)
        } else {
            feedback.text = currentHint()
            nextButton.text = getString(R.string.idiom_game_choose)
        }
        previousButton.isEnabled = !answered
        followingButton.isEnabled = !answered
        nextButton.isEnabled = !isSliding
        answerCard.isEnabled = !answered && !isSliding && (tapToChoose || swipeEnabled)
        updateNavigationControls()
    }

    private fun updateNavigationControls() {
        if (!::navigationButtons.isInitialized) return
        navigationButtons.visibility = if (swipeEnabled || finished) View.GONE else View.VISIBLE
        previousButton.isEnabled = !answered && !finished && !isSliding
        followingButton.isEnabled = !answered && !finished && !isSliding
        if (::answerCard.isInitialized) {
            answerCard.isEnabled = (tapToChoose || swipeEnabled) && !answered && !finished && !isSliding
        }
        if (::nextButton.isInitialized) {
            nextButton.visibility = if (!finished && !answered && tapToChoose) View.GONE else View.VISIBLE
            nextButton.isEnabled = !isSliding
        }
        if (::feedback.isInitialized && ::questionIds.isInitialized && !finished && !answered) {
            feedback.text = currentHint()
        }
    }

    private fun currentHint(): String {
        val resource = when {
            swipeEnabled && tapToChoose -> R.string.idiom_game_hint_swipe
            !swipeEnabled && tapToChoose -> R.string.idiom_game_hint_arrows
            swipeEnabled -> R.string.idiom_game_hint_swipe_button
            else -> R.string.idiom_game_hint_arrows_button
        }
        return getString(resource)
    }

    /** Match the background selected in SettingsActivity. */
    private fun applyAppearanceSettings() {
        val style = getSharedPreferences("phrasoteca_settings", MODE_PRIVATE)
            .getString("home_background_style", "waves")
        val drawable = when (style) {
            "landscape" -> R.drawable.bg_home_landscape
            "gradient" -> R.drawable.bg_home_gradient
            "plain" -> R.drawable.bg_home_plain
            else -> R.drawable.bg_home_waves
        }
        findViewById<View>(R.id.idiomGameRoot).setBackgroundResource(drawable)
    }

    override fun onResume() {
        super.onResume()
        applyAppearanceSettings()
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.idiomGameRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onDestroy() {
        if (::answerCard.isInitialized) {
            answerCard.animate().cancel()
            answerCard.translationX = 0f
            answerCard.alpha = 1f
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::questionIds.isInitialized) return
        outState.putStringArrayList(S_QUESTIONS, questionIds)
        outState.putStringArrayList(S_CHOICES, choiceIds)
        outState.putInt(S_INDEX, currentIndex)
        outState.putInt(S_SCORE, score)
        outState.putBoolean(S_ANSWERED, answered)
        outState.putInt(S_SELECTED, selected)
        outState.putBoolean(S_FINISHED, finished)
        outState.putBoolean(S_SWIPE_ENABLED, swipeEnabled)
        outState.putBoolean(S_TAP_TO_CHOOSE, tapToChoose)
    }
}
