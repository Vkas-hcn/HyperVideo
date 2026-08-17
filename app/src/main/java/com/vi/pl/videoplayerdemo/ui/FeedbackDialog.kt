package com.vi.pl.videoplayerdemo.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import com.vi.pl.videoplayerdemo.R

class FeedbackDialog(context: Context) : Dialog(context, R.style.AppActionDialogTheme) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_feedback)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        val input = findViewById<EditText>(R.id.feedback_input)
        findViewById<android.view.View>(R.id.feedback_close).setOnClickListener { dismiss() }
        findViewById<android.view.View>(R.id.feedback_submit).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            sendEmail(text)
        }
    }

    private fun sendEmail(body: String) {
        val email = context.getString(R.string.feedback_email)
        val subject = Uri.encode(context.getString(R.string.feedback_subject))
        val encoded = Uri.encode(body)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=$subject&body=$encoded"))
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.send_feedback)))
            Toast.makeText(context, R.string.feedback_submitted, Toast.LENGTH_LONG).show()
            dismiss()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_email_app, Toast.LENGTH_SHORT).show()
        }
    }
}
