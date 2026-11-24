package com.paveltyapkin.phrasoteca

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Appearance-only preferences: never writes to the database or backup format. */
class SettingsActivity : AppCompatActivity() {
    private val preferences by lazy {
        getSharedPreferences("phrasoteca_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.home_background)
        }
        val toolbar = Toolbar(this).apply {
            setTitle(R.string.settings_title)
            setTitleTextColor(getColor(R.color.home_title_color))
            setBackgroundResource(R.color.home_toolbar_background)
            setNavigationIcon(R.drawable.ic_arrow_back_settings_24)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(16), pad, dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addSelectionGroup(
            content, R.string.settings_background,
            "home_background_style", "waves",
            listOf(
                "waves" to R.string.settings_waves,
                "landscape" to R.string.settings_landscape,
                "gradient" to R.string.settings_gradient,
                "plain" to R.string.settings_plain
            )
        )
        addSelectionGroup(
            content, R.string.settings_collection_layout,
            "home_collection_layout", "cards",
            listOf(
                "cards" to R.string.settings_cards,
                "list" to R.string.settings_compact
            )
        )
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun addSelectionGroup(
        parent: LinearLayout,
        titleRes: Int,
        key: String,
        defaultValue: String,
        choices: List<Pair<String, Int>>
    ) {
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 18f
            setTextColor(getColor(R.color.home_title_color))
            setPadding(0, dp(14), 0, dp(10))
        }
        parent.addView(title)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val selected = preferences.getString(key, defaultValue)
        choices.forEach { (value, textRes) ->
            val item = RadioButton(this).apply {
                id = View.generateViewId()
                setText(textRes)
                textSize = 16f
                minHeight = dp(50)
                gravity = Gravity.CENTER_VERTICAL
                isChecked = value == selected
                setOnClickListener {
                    preferences.edit().putString(key, value).apply()
                }
            }
            group.addView(item)
        }
        parent.addView(group)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
