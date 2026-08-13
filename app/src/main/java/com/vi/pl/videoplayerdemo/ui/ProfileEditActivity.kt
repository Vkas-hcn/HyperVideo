package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.R

class ProfileEditActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_placeholder)
        // TODO: Implement user profile edit/detail page in future iteration.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.placeholder_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<TextView>(R.id.placeholder_title).setText(R.string.profile_edit_title)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ProfileEditActivity::class.java)
    }
}
