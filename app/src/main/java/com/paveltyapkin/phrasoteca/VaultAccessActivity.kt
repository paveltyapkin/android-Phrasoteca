package com.paveltyapkin.phrasoteca

import android.os.Bundle
import android.os.PersistableBundle
import android.os.Build
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.paveltyapkin.phrasoteca.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.90: Secrets remain in a single FLAG_SECURE activity; no key or plaintext is sent in Intents.
 * Leaving the Activity (including backgrounding or rotation) closes the key-bearing session.
 */
class VaultAccessActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_VAULT_ID = "vault_id"
        const val EXTRA_NEW_VAULT_NAME = "new_vault_name"
    }

    private lateinit var repo: VaultRepository
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var session: VaultRepository.VaultSession? = null
    private var vaultId = -1L
    private var newName: String? = null
    private var failedPinAttempts = 0
    private val maxPinAttempts = 3
    private var working = false
    private var isForeground = false
    private var unlockedCards = emptyList<VaultRepository.VaultCard>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        repo = VaultRepository(applicationContext)
        vaultId = intent.getLongExtra(EXTRA_VAULT_ID, -1L)
        newName = intent.getStringExtra(EXTRA_NEW_VAULT_NAME)?.takeIf { it.isNotBlank() }
        if (newName == null && vaultId < 0) { finish(); return }

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        showPinScreen()
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        isForeground = false
        lockVault()
        if (!isFinishing && ::content.isInitialized) showPinScreen()
        super.onStop()
    }

    override fun onDestroy() {
        lockVault()
        super.onDestroy()
    }

    private fun lockVault() {
        session?.close()
        session = null
        unlockedCards = emptyList()
        // Ensure secret input widgets are released when Activity leaves the foreground.
        if (::content.isInitialized) content.removeAllViews()
    }

    private fun showPinScreen() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val creating = newName != null
        content.addView(label(if (creating) R.string.vault_set_pin else R.string.vault_enter_pin, 24f))
        val message = label(if (creating) R.string.vault_loss_notice else R.string.vault_attempt_warning, 15f)
        message.setPadding(0, dp(12), 0, dp(14))
        content.addView(message)
        val pin = pinInput(R.string.vault_pin_hint)
        content.addView(pin)
        val confirm = if (creating) pinInput(R.string.vault_confirm_pin).also { content.addView(it) } else null
        val submit = button(if (creating) R.string.vault_create else R.string.vault_unlock) { }
        content.addView(submit)
        submit.setOnClickListener {
            if (working || !isForeground) return@setOnClickListener
            val value = pin.text.toString()
            if (value.length != 6 || value.any { it !in '0'..'9' }) {
                pin.error = getString(R.string.vault_pin_invalid)
                return@setOnClickListener
            }
            if (creating && confirm?.text?.toString() != value) {
                confirm?.error = getString(R.string.vault_pin_mismatch)
                confirm?.text?.clear()
                return@setOnClickListener
            }
            working = true
            submit.isEnabled = false
            lifecycleScope.launch {
                var openVaultAfterUnlock = false
                try {
                    if (creating) {
                        val name = newName ?: return@launch
                        vaultId = withContext(Dispatchers.IO) { repo.createVault(name, value) }
                        newName = null
                        // Only count creation after the vault has been committed.
                        AchievementRepository(
                            com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase.getInstance(applicationContext)
                        ).record("vault.created")
                    }
                    when (val result = withContext(Dispatchers.IO) { repo.unlock(vaultId, value) }) {
                        is VaultRepository.UnlockResult.Unlocked -> {
                            if (!isForeground) result.session.close()
                            else {
                                session?.close()
                                session = result.session
                                failedPinAttempts = 0
                                // Render only after the unlock operation releases `working`.
                                openVaultAfterUnlock = true
                            }
                        }
                        VaultRepository.UnlockResult.IncorrectPin -> {
                            failedPinAttempts++
                            val remaining = maxPinAttempts - failedPinAttempts
                            pin.text?.clear()
                            if (remaining <= 0) {
                                toast(R.string.vault_attempt_limit_reached)
                                finish() // HomeActivity stays beneath this Activity.
                            } else {
                                message.text = getString(R.string.vault_remaining_attempts, remaining)
                            }
                        }
                        VaultRepository.UnlockResult.NotFound -> {
                            message.setText(R.string.vault_not_found)
                            submit.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PhrasotecaVault", "Vault access failed", e)
                    if (isForeground) message.setText(R.string.vault_access_error)
                } finally {
                    pin.text?.clear()
                    confirm?.text?.clear()
                    working = false
                    submit.isEnabled = true
                }
                if (openVaultAfterUnlock && isForeground && session != null) {
                    showVaultScreen()
                }
            }
        }
    }

    private fun showVaultScreen() {
        val activeSession = session ?: return
        content.removeAllViews()
        content.addView(label(R.string.vault_records, 24f))
        val newButton = button(R.string.vault_new_record) { showEditDialog(null) }
        content.addView(newButton)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        refreshRecords(list, activeSession)
    }

    private fun refreshRecords(list: LinearLayout, activeSession: VaultRepository.VaultSession) {
        if (working || session !== activeSession) return
        working = true
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { activeSession.readCards() }
                if (!isForeground || session !== activeSession) return@launch
                unlockedCards = loaded
                list.removeAllViews()
                if (loaded.isEmpty()) list.addView(label(R.string.vault_empty, 16f))
                loaded.forEach { record ->
                    list.addView(buttonWithText(record.title.ifBlank { getString(R.string.vault_untitled) }) {
                        showRecordDialog(record)
                    })
                }
            } catch (e: Exception) {
                android.util.Log.e("PhrasotecaVault", "Reading vault failed", e)
                if (isForeground) toast(R.string.vault_record_error)
            } finally { working = false }
        }
    }

    private fun showRecordDialog(record: VaultRepository.VaultCard) {
        if (!isForeground || session == null) return
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        frame.addView(textRow(R.string.vault_username, record.username))
        val passwordValue = TextView(this).apply {
            text = "••••••••"
            textSize = 17f
            setPadding(0, dp(10), 0, dp(10))
        }
        frame.addView(label(R.string.vault_password, 14f))
        frame.addView(passwordValue)
        passwordValue.isLongClickable = true
        passwordValue.setOnLongClickListener {
            if (!isForeground || session == null) return@setOnLongClickListener true
            val clip = ClipData.newPlainText("", record.password)
            // Hide clipboard previews on Android 13+ and compatible older launchers.
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                if (Build.VERSION.SDK_INT >= 33) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(clip)
            toast(R.string.vault_password_copied)
            true
        }
        frame.addView(label(R.string.vault_copy_password_hint, 12f))
        val toggle = button(R.string.vault_show_password) { }
        frame.addView(toggle)
        var visible = false
        toggle.setOnClickListener {
            visible = !visible
            passwordValue.text = if (visible) record.password else "••••••••"
            toggle.setText(if (visible) R.string.vault_hide_password else R.string.vault_show_password)
        }
        if (record.website.isNotBlank()) frame.addView(textRow(R.string.vault_website, record.website))
        if (record.notes.isNotBlank()) frame.addView(textRow(R.string.vault_notes, record.notes))
        val dialog = AlertDialog.Builder(this)
            .setTitle(record.title)
            .setView(frame)
            .setPositiveButton(R.string.edit_card, null)
            .setNeutralButton(R.string.delete_card, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                showEditDialog(record)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                confirmDelete(record)
            }
        }
        dialog.show()
    }

    private fun showEditDialog(existing: VaultRepository.VaultCard?) {
        val activeSession = session ?: return
        if (!isForeground || working) return
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val title = editField(fields, R.string.vault_title, existing?.title.orEmpty(), false)
        val user = editField(fields, R.string.vault_username, existing?.username.orEmpty(), false)
        val pass = editField(fields, R.string.vault_password, existing?.password.orEmpty(), true)
        val website = editField(fields, R.string.vault_website, existing?.website.orEmpty(), false)
        val notes = editField(fields, R.string.vault_notes, existing?.notes.orEmpty(), false, true)
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.vault_new_record else R.string.vault_edit_record)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                if (working || session !== activeSession) return@setOnClickListener
                val name = title.text.toString().trim()
                if (name.isEmpty()) { title.error = getString(R.string.required_field); return@setOnClickListener }
                val login = user.text.toString()
                val secret = pass.text.toString()
                val site = website.text.toString()
                val comment = notes.text.toString()
                if (name.length > 300 || login.length > 2000 || secret.length > 10000 ||
                    site.length > 2000 || comment.length > 20000) {
                    toast(R.string.vault_record_too_long)
                    return@setOnClickListener
                }
                working = true
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (existing == null) activeSession.addCard(name, login, secret, site, comment)
                            else activeSession.updateCard(existing.id, name, login, secret, site, comment)
                        }
                        if (isForeground && session === activeSession) {
                            dialog.dismiss()
                            working = false
                            showVaultScreen()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PhrasotecaVault", "Saving vault record failed", e)
                        if (isForeground) toast(R.string.vault_record_error)
                    } finally {
                        working = false
                        save.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(record: VaultRepository.VaultCard) {
        val activeSession = session ?: return
        if (!isForeground) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirmation_title)
            .setMessage(R.string.vault_delete_record_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_card) { _, _ ->
                if (working || session !== activeSession) return@setPositiveButton
                working = true
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { activeSession.deleteCard(record.id) }
                        if (isForeground && session === activeSession) {
                            working = false
                            showVaultScreen()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PhrasotecaVault", "Deleting vault record failed", e)
                        if (isForeground) toast(R.string.vault_record_error)
                    } finally { working = false }
                }
            }.show()
    }

    private fun editField(parent: LinearLayout, labelId: Int, initial: String,
                          secret: Boolean, multiline: Boolean = false): EditText {
        parent.addView(label(labelId, 14f))
        return EditText(this).apply {
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { minLines = 2; maxLines = 4 }
            else setSingleLine(true)
            if (secret) {
                // Avoid selection and personalised keyboard suggestions for passwords.
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            setText(initial)
            if (secret) setSelection(text?.length ?: 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            parent.addView(this)
        }
    }

    private fun pinInput(hintId: Int) = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(6))
        hint = getString(hintId)
        // Apply LAST: setSingleLine/inputType can replace the transformation method.
        transformationMethod = PasswordTransformationMethod.getInstance()
        textSize = 20f
        gravity = Gravity.CENTER_HORIZONTAL
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun label(textId: Int, size: Float) = TextView(this).apply {
        setText(textId)
        textSize = size
        setPadding(0, dp(8), 0, dp(5))
    }
    private fun button(labelId: Int, onClick: () -> Unit) = Button(this).apply {
        setText(labelId)
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    private fun buttonWithText(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    private fun textRow(labelId: Int, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(labelId, 14f))
        addView(TextView(this@VaultAccessActivity).apply { text = value; textSize = 17f })
    }
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
}
