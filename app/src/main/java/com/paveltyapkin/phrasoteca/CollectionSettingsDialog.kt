package com.paveltyapkin.phrasoteca

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity

/** UI-only editor. Callers persist the returned entity in Room. */
object CollectionSettingsDialog {
    // Preserve the original eight swatches (and their order) for existing collections.
    // The additional tones are optional; persisted colors remain plain hex strings.
    private val palette = arrayOf(
        "#F2F4FD", "#E5F3ED", "#FFF3E2", "#F8E8F0",
        "#ECEFF3", "#E8F4F8", "#808080", "#202124",
        "#FFE4D6", "#DCEAFE", "#DFEACF", "#574664"
    )
    private val types = arrayOf("LANGUAGE", "GENERAL", "CHECKLIST")

    fun show(
        context: Context,
        existing: CollectionEntity?,
        includeName: Boolean,
        onSave: (CollectionEntity) -> Unit
    ) {
        val dp = context.resources.displayMetrics.density
        fun dip(value: Int): Int = (value * dp + 0.5f).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(22), dip(8), dip(22), dip(14))
        }
        fun label(resId: Int) {
            box.addView(TextView(context).apply {
                setText(resId)
                textSize = 14f
                setPadding(0, dip(12), 0, 0)
            })
        }
        fun field(resId: Int, value: String): EditText {
            label(resId)
            return EditText(context).also {
                it.setSingleLine(true)
                it.setText(value)
                box.addView(it)
            }
        }
        val name = if (includeName) field(R.string.collection_name, existing?.name.orEmpty()) else null
        label(R.string.collection_type)
        val typeSpinner = Spinner(context)
        val typeNames = listOf(context.getString(R.string.type_language), context.getString(R.string.type_general), context.getString(R.string.type_checklist))
        typeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, typeNames)
        typeSpinner.setSelection(types.indexOf(existing?.defaultCardType ?: "LANGUAGE").coerceAtLeast(0))
        // Existing collection types are fixed to prevent interpreting old cards as checklist data.
        typeSpinner.isEnabled = existing == null
        box.addView(typeSpinner)
        val front = field(R.string.front_side_label, existing?.frontLabel ?: context.getString(R.string.default_front_label))
        val backStart = box.childCount
        val back = field(R.string.back_side_label, existing?.backLabel ?: context.getString(R.string.default_back_label))
        // Visible only for LANGUAGE collections. An automatic option preserves legacy
        // collections whose labels already contain a recognized language name.
        val frontTagStart = box.childCount
        label(R.string.front_tts_language)
        val frontTagSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                LanguageOptions.titles(context))
            setSelection(LanguageOptions.indexOf(existing?.frontLanguageTag ?: "en-US"))
        }
        box.addView(frontTagSpinner)
        val backTagStart = box.childCount
        label(R.string.back_tts_language)
        val backTagSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                LanguageOptions.titles(context))
            setSelection(LanguageOptions.indexOf(existing?.backLanguageTag ?: "ru-RU"))
        }
        box.addView(backTagSpinner)
        var selectedIcon = existing?.iconKey.orEmpty()
        label(R.string.collection_icon_title)
        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconPreview = ImageView(context).apply {
            setPadding(dip(10), dip(10), dip(10), dip(10))
            setBackgroundResource(R.drawable.bg_home_tile_icon)
        }
        fun updateIconPreview() {
            iconPreview.setImageResource(CollectionIcons.drawable(
                selectedIcon, existing?.defaultCardType ?: types[typeSpinner.selectedItemPosition]
            ))
        }
        iconRow.addView(iconPreview, LinearLayout.LayoutParams(dip(52), dip(52)))
        val chooseIcon = android.widget.Button(context).apply {
            setText(R.string.collection_icon_choose)
            isAllCaps = false
        }
        iconRow.addView(chooseIcon, LinearLayout.LayoutParams(0, dip(48), 1f))
        fun showIconPicker() {
            val chooser = GridLayout(context).apply {
                columnCount = 3
                setPadding(dip(10), dip(10), dip(10), dip(10))
            }
            val content = ScrollView(context).apply { addView(chooser) }
            val popup = AlertDialog.Builder(context)
                .setTitle(R.string.collection_icon_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create()
            CollectionIcons.entries.forEachIndexed { index, item ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dip(4), dip(7), dip(4), dip(7))
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(item.name)
                }
                cell.addView(ImageView(context).apply {
                    setImageResource(item.drawable)
                    setPadding(dip(12), dip(12), dip(12), dip(12))
                    setBackgroundResource(R.drawable.bg_home_tile_icon)
                }, LinearLayout.LayoutParams(dip(52), dip(52)))
                cell.addView(TextView(context).apply {
                    setText(item.name)
                    gravity = Gravity.CENTER
                    textSize = 12f
                    maxLines = 2
                }, LinearLayout.LayoutParams(dip(80), dip(38)))
                cell.setOnClickListener {
                    selectedIcon = item.key
                    updateIconPreview()
                    popup.dismiss()
                }
                chooser.addView(cell, GridLayout.LayoutParams(
                    GridLayout.spec(index / 3), GridLayout.spec(index % 3)
                ).apply { width = dip(82); height = dip(104) })
            }
            popup.show()
        }
        chooseIcon.setOnClickListener { showIconPicker() }
        iconPreview.setOnClickListener { showIconPicker() }
        box.addView(iconRow)
        updateIconPreview()
        var frontColor = existing?.frontColor ?: palette[0]
        var backColor = existing?.backColor ?: palette[1]
        fun colorButton(resId: Int, initial: String, onColorChanged: (String) -> Unit) {
            label(resId)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dip(4), 0, dip(4))
            }
            val preview = View(context)
            fun updatePreview(color: String) {
                preview.background = GradientDrawable().apply {
                    setColor(Color.parseColor(color))
                    cornerRadius = dip(9).toFloat()
                    setStroke(dip(1), Color.GRAY)
                }
            }
            updatePreview(initial)
            row.addView(preview, LinearLayout.LayoutParams(dip(52), dip(42)))
            val choose = android.widget.Button(context).apply {
                setText(R.string.choose_color)
                isAllCaps = false
            }
            row.addView(choose, LinearLayout.LayoutParams(0, dip(48), 1f))
            fun showPalette() {
                val grid = GridLayout(context).apply {
                    columnCount = 4
                    setPadding(dip(14), dip(14), dip(14), dip(14))
                }
                val current = if (resId == R.string.front_side_color) frontColor else backColor
                val dialog = AlertDialog.Builder(context)
                    .setTitle(resId)
                    .setView(grid)
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                palette.forEachIndexed { index, color ->
                    val isSelected = color.equals(current, ignoreCase = true)
                    val swatchColor = Color.parseColor(color)
                    val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(swatchColor) < 0.35
                    val tile = TextView(context).apply {
                        text = if (isSelected) "✓" else ""
                        textSize = 24f
                        gravity = Gravity.CENTER
                        setTextColor(if (isDark) Color.WHITE else Color.rgb(44, 62, 99))
                        contentDescription = context.getString(
                            when (index) {
                                0 -> R.string.palette_lavender
                                1 -> R.string.palette_mint
                                2 -> R.string.palette_cream
                                3 -> R.string.palette_pink
                                4 -> R.string.palette_light_gray
                                5 -> R.string.palette_blue
                                6 -> R.string.palette_gray
                                7 -> R.string.palette_black
                                8 -> R.string.palette_peach
                                9 -> R.string.palette_sky
                                10 -> R.string.palette_sage
                                else -> R.string.palette_plum
                            }
                        )
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor(color))
                            cornerRadius = dip(12).toFloat()
                            setStroke(dip(if (isSelected) 3 else 1),
                                if (isSelected) Color.rgb(69, 107, 215) else Color.LTGRAY)
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onColorChanged(color)
                            updatePreview(color)
                            dialog.dismiss()
                        }
                    }
                    grid.addView(tile, GridLayout.LayoutParams(
                        GridLayout.spec(index / 4), GridLayout.spec(index % 4)
                    ).apply {
                        width = dip(56)
                        height = dip(56)
                        setMargins(dip(3), dip(3), dip(3), dip(3))
                    })
                }
                dialog.show()
            }
            choose.setOnClickListener { showPalette() }
            preview.setOnClickListener { showPalette() }
            preview.contentDescription = context.getString(R.string.choose_color)
            box.addView(row)
        }
        colorButton(R.string.front_side_color, frontColor) { frontColor = it }
        val backColorStart = box.childCount
        colorButton(R.string.back_side_color, backColor) { backColor = it }
        val single = CheckBox(context).apply {
            setText(R.string.single_sided_cards)
            isChecked = existing?.sideMode == "ONE_SIDED"
            // Changing sidedness is intentionally disallowed once a collection exists.
            isEnabled = existing == null
        }
        box.addView(single)
        fun updateLanguageVisibility() {
            val language = types[typeSpinner.selectedItemPosition] == "LANGUAGE"
            val showBack = language && !single.isChecked
            box.getChildAt(frontTagStart).visibility = if (language) View.VISIBLE else View.GONE
            box.getChildAt(frontTagStart + 1).visibility = if (language) View.VISIBLE else View.GONE
            box.getChildAt(backTagStart).visibility = if (showBack) View.VISIBLE else View.GONE
            box.getChildAt(backTagStart + 1).visibility = if (showBack) View.VISIBLE else View.GONE
        }

        fun applyTypeDefaults() {
            if (existing == null && types[typeSpinner.selectedItemPosition] == "CHECKLIST") {
                single.isChecked = true
                single.isEnabled = false
                front.setText(context.getString(R.string.checklist_title_label))
            } else if (existing == null) {
                single.isEnabled = true
            }
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyTypeDefaults()
                updateLanguageVisibility()
                updateIconPreview()
            }
        }
        fun updateSidedness() {
            (backStart until backStart + 2).forEach {
                box.getChildAt(it).visibility = if (single.isChecked) View.GONE else View.VISIBLE
            }
            (backColorStart until backColorStart + 2).forEach {
                box.getChildAt(it).visibility = if (single.isChecked) View.GONE else View.VISIBLE
            }
        }
        single.setOnCheckedChangeListener { _, _ -> updateSidedness(); updateLanguageVisibility() }
        updateSidedness()
        updateLanguageVisibility()
        val note = TextView(context).apply {
            setText(R.string.side_mode_fixed_hint)
            textSize = 12f
            visibility = if (existing == null) View.GONE else View.VISIBLE
        }
        box.addView(note)
        val scroll = ScrollView(context).apply { addView(box) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (existing == null) R.string.new_collection else R.string.collection_settings)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val resolvedName = name?.text?.toString()?.trim() ?: existing?.name.orEmpty()
                val frontLabel = front.text.toString().trim()
                val backLabel = back.text.toString().trim()
                if (resolvedName.isEmpty()) { name?.error = context.getString(R.string.collection_required); return@setOnClickListener }
                if (frontLabel.isEmpty()) { front.error = context.getString(R.string.required_field); return@setOnClickListener }
                if (backLabel.isEmpty() && !single.isChecked) { back.error = context.getString(R.string.required_field); return@setOnClickListener }
                val updated = (existing ?: CollectionEntity(name = resolvedName)).copy(
                    name = resolvedName,
                    defaultCardType = types[typeSpinner.selectedItemPosition],
                    frontLabel = frontLabel,
                    backLabel = backLabel.ifEmpty { context.getString(R.string.default_back_label) },
                    frontColor = frontColor,
                    backColor = backColor,
                    iconKey = selectedIcon,
                    frontLanguageTag = if (types[typeSpinner.selectedItemPosition] == "LANGUAGE")
                        LanguageOptions.supported[frontTagSpinner.selectedItemPosition].tag else "",
                    backLanguageTag = if (types[typeSpinner.selectedItemPosition] == "LANGUAGE")
                        LanguageOptions.supported[backTagSpinner.selectedItemPosition].tag else "",
                    sideMode = existing?.sideMode ?: if (single.isChecked) "ONE_SIDED" else "TWO_SIDED"
                )
                onSave(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
