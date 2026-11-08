package com.tellybox.tv.ui.parental

import com.tellybox.tv.R
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tellybox.tv.appContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

fun AppCompatActivity.requestPin(always: Boolean = false, cancel: () -> Unit = {}, success: () -> Unit) {
    val repo = appContainer.parental
    if (!repo.configured || (!always && repo.unlocked)) { success(); return }
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSaveEnabled = false
    }
    val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_enter_pin)).setView(input)
        .setPositiveButton(getString(R.string.ui_unlock), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> cancel() }
        .setOnCancelListener { cancel() }.create()
    var timer: Job? = null
    dialog.setOnShowListener {
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        var verifying = false
        fun update() {
            val seconds = repo.cooldownSeconds()
            button.isEnabled = seconds == 0L && !verifying
            if (seconds > 0) dialog.setTitle(getString(R.string.ui_try_again_in_1_s, resources.getQuantityString(R.plurals.seconds, seconds.toInt(), seconds)))
            else dialog.setTitle(getString(R.string.ui_enter_pin))
        }
        timer = lifecycleScope.launch {
            while (dialog.isShowing) { update(); delay(1000) }
        }
        update()
        button.setOnClickListener {
            verifying = true
            button.isEnabled = false
            lifecycleScope.launch {
                if (repo.verify(input.text.toString())) { dialog.dismiss(); success() }
                else { verifying = false; input.text.clear(); input.error = getString(R.string.ui_incorrect_pin); update() }
            }
        }
    }
    dialog.setOnDismissListener { timer?.cancel(); input.text.clear() }
    dialog.show()
}
