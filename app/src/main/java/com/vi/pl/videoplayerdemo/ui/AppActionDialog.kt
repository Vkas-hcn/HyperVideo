package com.vi.pl.videoplayerdemo.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.vi.pl.videoplayerdemo.R

/**
 * Shared centered dialog used by rename / delete flows.
 * Title + body (message or input) + red primary + text Cancel.
 */
class AppActionDialog private constructor(
    context: Context,
    private val config: Config,
) : Dialog(context, R.style.AppActionDialogTheme) {

    data class Config(
        val title: CharSequence,
        val primaryText: CharSequence,
        val message: CharSequence? = null,
        val inputInitial: CharSequence? = null,
        val inputLabel: CharSequence? = null,
        val cancelText: CharSequence? = null,
        val onPrimary: (input: String?) -> Unit,
    )

    private var inputView: EditText? = null
    private var showInput = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_app_action)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )

        findViewById<TextView>(R.id.dialog_title).text = config.title
        val messageView = findViewById<TextView>(R.id.dialog_message)
        val inputSection = findViewById<android.view.View>(R.id.dialog_input_section)
        inputView = findViewById(R.id.dialog_input)
        val clear = findViewById<android.view.View>(R.id.dialog_input_clear)
        val primary = findViewById<MaterialButton>(R.id.dialog_primary)
        val cancel = findViewById<TextView>(R.id.dialog_cancel)

        showInput = config.inputInitial != null
        messageView.isVisible = !showInput && config.message != null
        inputSection.isVisible = showInput
        if (!showInput) {
            messageView.text = config.message
            // Avoid hidden EditText stealing focus and opening the keyboard.
            inputView?.isFocusable = false
            inputView?.isFocusableInTouchMode = false
        } else {
            findViewById<TextView>(R.id.dialog_input_label).text =
                config.inputLabel ?: context.getString(R.string.enter_new_name)
            inputView?.setText(config.inputInitial)
            inputView?.setSelection(inputView?.text?.length ?: 0)
            clear.setOnClickListener { inputView?.text?.clear() }
        }

        primary.text = config.primaryText
        primary.setOnClickListener {
            val value = if (showInput) inputView?.text?.toString()?.trim().orEmpty() else null
            if (showInput && value.isNullOrBlank()) return@setOnClickListener
            config.onPrimary(value)
            dismiss()
        }
        cancel.text = config.cancelText ?: context.getString(R.string.cancel)
        cancel.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        if (!showInput) {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            return
        }
        inputView?.requestFocus()
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        inputView?.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    companion object {
        fun rename(
            context: Context,
            currentName: String,
            onConfirm: (String) -> Unit,
        ): AppActionDialog =
            AppActionDialog(
                context,
                Config(
                    title = context.getString(R.string.rename),
                    primaryText = context.getString(R.string.confirm),
                    inputInitial = currentName,
                    onPrimary = { name -> onConfirm(name.orEmpty()) },
                ),
            )

        fun delete(
            context: Context,
            onConfirm: () -> Unit,
        ): AppActionDialog =
            AppActionDialog(
                context,
                Config(
                    title = context.getString(R.string.delete),
                    primaryText = context.getString(R.string.delete),
                    message = context.getString(R.string.confirm_video_deletion),
                    onPrimary = { onConfirm() },
                ),
            )

        fun linkDetected(
            context: Context,
            onOpen: () -> Unit,
        ): AppActionDialog =
            AppActionDialog(
                context,
                Config(
                    title = context.getString(R.string.link_detected_title),
                    primaryText = context.getString(R.string.open_now),
                    message = context.getString(R.string.link_detected_message),
                    cancelText = context.getString(R.string.later),
                    onPrimary = { onOpen() },
                ),
            )
    }
}
