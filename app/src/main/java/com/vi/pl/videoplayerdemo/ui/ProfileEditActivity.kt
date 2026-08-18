package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.UserProfile
import com.vi.pl.videoplayerdemo.local.UserSession

class ProfileEditActivity : AppCompatActivity() {
    private lateinit var avatar: ImageView
    private lateinit var nameView: TextView
    private lateinit var nameError: TextView
    private lateinit var emailView: TextView
    private var name = ""
    private var photoUrl: String? = null

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        val path = UserSession.copyPhoto(this, uri)
        if (path == null) {
            Toast.makeText(this, R.string.profile_avatar_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        photoUrl = path
        UserSession.bindPhoto(avatar, path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = UserSession.get(this)
        if (user == null) {
            startActivity(LoginActivity.createIntent(this))
            finish()
            return
        }
        setContentView(R.layout.activity_profile_edit)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.profile_edit_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        avatar = findViewById(R.id.profile_edit_avatar)
        nameView = findViewById(R.id.profile_edit_name)
        nameError = findViewById(R.id.profile_edit_name_error)
        emailView = findViewById(R.id.profile_edit_email)
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatar.clipToOutline = true
        name = user.name
        photoUrl = user.photoUrl
        nameView.text = name
        emailView.text = user.email
        UserSession.bindPhoto(avatar, photoUrl)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.profile_edit_change_avatar).setOnClickListener {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.profile_edit_name_row).setOnClickListener { editName() }
        findViewById<View>(R.id.profile_edit_save).setOnClickListener { save(user.id, user.email) }
    }

    private fun editName() {
        AppActionDialog.editNickname(this, name) { value ->
            name = value
            nameView.text = value
            showNameError(null)
        }.show()
    }

    private fun save(id: String, email: String) {
        val error = nicknameError(name)
        showNameError(error)
        if (error != null) return
        UserSession.save(this, UserProfile(id, name, email, photoUrl))
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun nicknameError(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> getString(R.string.profile_name_empty)
            trimmed.length > 15 -> getString(R.string.profile_name_too_long)
            else -> null
        }
    }

    private fun showNameError(message: String?) {
        nameError.isVisible = message != null
        nameError.text = message
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ProfileEditActivity::class.java)
    }
}
