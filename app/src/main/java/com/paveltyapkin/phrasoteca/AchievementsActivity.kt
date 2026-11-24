package com.paveltyapkin.phrasoteca

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase
import kotlinx.coroutines.launch

class AchievementsActivity : AppCompatActivity() {
    private lateinit var repository: AchievementRepository
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_achievements)
        repository = AchievementRepository(PhrasotecaDatabase.getInstance(this))
        val root = findViewById<View>(R.id.achievementsRoot)
        val initial = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(initial[0] + bars.left, initial[1] + bars.top,
                initial[2] + bars.right, initial[3] + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<Toolbar>(R.id.achievementsToolbar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            navigationIcon?.mutate()?.setTint(ContextCompat.getColor(this@AchievementsActivity, R.color.home_title_color))
            setNavigationOnClickListener { finish() }
        }
        list = findViewById(R.id.achievementsList)
        summary = findViewById(R.id.achievementsSummary)
    }

    override fun onResume() {
        super.onResume()
        applyAppearanceSettings()
        lifecycleScope.launch {
            val snapshot = repository.snapshot()
            render(snapshot)
        }
    }

    /** Match HomeActivity's persisted background without touching achievement data. */
    private fun applyAppearanceSettings() {
        val style = getSharedPreferences("phrasoteca_settings", MODE_PRIVATE)
            .getString("home_background_style", "waves")
        val drawable = when (style) {
            "landscape" -> R.drawable.bg_home_landscape
            "gradient" -> R.drawable.bg_home_gradient
            "plain" -> R.drawable.bg_home_plain
            else -> R.drawable.bg_home_waves
        }
        findViewById<View>(R.id.achievementsRoot).setBackgroundResource(drawable)
    }

    private fun themedColor(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun label(text: CharSequence, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(themedColor(R.color.home_tile_title))
        }

    private fun render(snapshot: AchievementRepository.Snapshot) {
        list.removeAllViews()
        val definitions = AchievementCatalog.visible
        val unlocked = definitions.count { snapshot.unlocks.containsKey(it.id) }
        summary.text = getString(R.string.achievements_summary, unlocked, definitions.size)
        val categories = listOf("collections", "communication", "learning", "sharing")
        for (category in categories) {
            val section = definitions.filter { it.category == category }
                .sortedWith(compareByDescending<AchievementDefinition> { snapshot.unlocks.containsKey(it.id) }
                    .thenBy { definitions.indexOf(it) })
            if (section.isEmpty()) continue
            val categoryTitle = when (category) {
                "collections" -> R.string.achievements_category_collections
                "communication" -> R.string.achievements_category_communication
                "learning" -> R.string.achievements_category_learning
                else -> R.string.achievements_category_sharing
            }
            list.addView(label(getString(categoryTitle), 19f, true).apply {
                setPadding(dp(3), dp(18), 0, dp(10))
            })
            section.forEach { item -> addAchievement(item, snapshot) }
        }
    }

    private fun addAchievement(item: AchievementDefinition, snapshot: AchievementRepository.Snapshot) {
        val unlocked = snapshot.unlocks[item.id] != null
        val current = (snapshot.metrics[item.metricKey] ?: 0L).coerceAtMost(item.target)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            strokeColor = themedColor(R.color.achievement_card_border)
            setCardBackgroundColor(themedColor(if (unlocked) R.color.achievement_card_unlocked
                else R.color.achievement_card_locked))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        content.addView(label(if (unlocked) "🏆" else "🏅", 28f).apply {
            gravity = Gravity.CENTER
            alpha = if (unlocked) 1f else .7f
        }, LinearLayout.LayoutParams(dp(54), dp(60)))
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(label(getString(item.titleRes), 17f, true))
        textBox.addView(label(getString(item.descriptionRes), 14f).apply {
            setPadding(0, dp(4), 0, dp(4))
            setTextColor(themedColor(R.color.home_tile_subtitle))
        })
        val status = if (unlocked) getString(R.string.achievements_unlocked)
                     else getString(R.string.achievements_progress, current, item.target)
        textBox.addView(label(status, 12f).apply {
            setTextColor(themedColor(if (unlocked) R.color.achievement_success
                else R.color.home_tile_subtitle))
        })
        content.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(content)
        if (!unlocked) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = ((current.toDouble() / item.target.toDouble()) * 1000).toInt()
                progressTintList = ColorStateList.valueOf(themedColor(R.color.achievement_progress))
                progressBackgroundTintList = ColorStateList.valueOf(themedColor(R.color.achievement_progress_track))
            }
            val params = LinearLayout.LayoutParams(-1, dp(5))
            params.topMargin = dp(8)
            container.addView(bar, params)
        }
        card.addView(container)
        list.addView(card)
    }
}
