package com.vi.pl.videoplayerdemo.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.UserProfile
import com.vi.pl.videoplayerdemo.local.UserSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var toggle: ImageView
    private var passwordVisible = false

    private val googleSignIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val mail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (mail.isNullOrBlank()) {
            Toast.makeText(this, R.string.login_google_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        fetchGoogleProfile(mail)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.login_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        email = findViewById(R.id.login_email)
        password = findViewById(R.id.login_password)
        remember = findViewById(R.id.login_remember)
        toggle = findViewById(R.id.login_password_toggle)
        val saved = UserSession.rememberedEmail(this)
        if (saved.isNotBlank()) {
            email.setText(saved)
            remember.isChecked = true
        }
        toggle.setOnClickListener { togglePassword() }
        findViewById<View>(R.id.btn_login).setOnClickListener { onEmailLogin() }
        findViewById<View>(R.id.login_sign_up).setOnClickListener {
            Toast.makeText(this, R.string.login_signup_later, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_google).setOnClickListener { onGoogleLogin() }
        findViewById<View>(R.id.login_guest).setOnClickListener { finish() }
    }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        val start = password.selectionStart
        password.inputType = if (passwordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        password.setSelection(start.coerceAtLeast(0))
        toggle.setImageResource(
            if (passwordVisible) R.drawable.ic_show_password else R.drawable.ic_hide_password,
        )
    }

    private fun onEmailLogin() {
        val mail = email.text.toString().trim()
        val pwd = password.text.toString()
        email.error = if (LoginValidator.email(mail)) null else getString(R.string.login_email_invalid)
        password.error = if (LoginValidator.password(pwd)) null else getString(R.string.login_password_invalid)
        if (email.error != null || password.error != null) return
        UserSession.rememberEmail(this, if (remember.isChecked) mail else null)
        Toast.makeText(this, R.string.login_email_later, Toast.LENGTH_SHORT).show()
    }

    private fun onGoogleLogin() {
        googleSignIn.launch(
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null,
            ),
        )
    }

    private fun fetchGoogleProfile(mail: String) {
        val button = findViewById<View>(R.id.btn_google)
        button.isEnabled = false
        AccountManager.get(this).getAuthToken(
            Account(mail, "com.google"),
            PROFILE_SCOPE,
            Bundle(),
            this,
            { future ->
                Thread {
                    val token = runCatching {
                        future.result.getString(AccountManager.KEY_AUTHTOKEN)
                    }.getOrNull()
                    val info = token?.let(::loadUserInfo)
                    runOnUiThread {
                        completeGoogleLogin(
                            mail,
                            info?.name ?: mail.substringBefore("@").ifBlank { mail },
                            info?.photo,
                        )
                        button.isEnabled = true
                    }
                }.start()
            },
            null,
        )
    }

    private fun loadUserInfo(token: String): GoogleInfo? {
        val conn = URL(USERINFO).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            GoogleInfo(
                name = json.optString("name").takeIf { it.isNotBlank() },
                photo = json.optString("picture").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun completeGoogleLogin(mail: String, name: String, photoUrl: String?) {
        UserSession.save(this, UserProfile(id = mail, name = name, email = mail, photoUrl = photoUrl))
        if (remember.isChecked) UserSession.rememberEmail(this, mail)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val PROFILE_SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile"
        private const val USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo"

        fun createIntent(context: Context): Intent = Intent(context, LoginActivity::class.java)
    }

    private data class GoogleInfo(val name: String?, val photo: String?)
}

object LoginValidator {
    private val EMAIL = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun email(value: String): Boolean = EMAIL.matches(value.trim())

    fun password(value: String): Boolean = value.length >= 6
}
