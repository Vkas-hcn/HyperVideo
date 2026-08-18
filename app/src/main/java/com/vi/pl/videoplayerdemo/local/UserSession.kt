package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
    val photoUrl: String?,
)

object UserSession {
    fun get(context: Context): UserProfile? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_ID, null) ?: return null
        return UserProfile(
            id = id,
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            photoUrl = prefs.getString(KEY_PHOTO, null),
        )
    }

    fun save(context: Context, profile: UserProfile) {
        prefs(context).edit()
            .putString(KEY_ID, profile.id)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_PHOTO, profile.photoUrl)
            .apply()
    }

    fun rememberedEmail(context: Context): String =
        prefs(context).getString(KEY_REMEMBER, "").orEmpty()

    fun rememberEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_REMEMBER, email).apply()
    }

    fun bindPhoto(imageView: ImageView, url: String?) {
        imageView.setImageDrawable(null)
        imageView.tag = url
        if (url.isNullOrBlank()) return
        Thread {
            val bmp = decodePhoto(imageView.context, url)
            imageView.post {
                if (imageView.tag == url && bmp != null) imageView.setImageBitmap(bmp)
            }
        }.start()
    }

    fun copyPhoto(context: Context, uri: Uri): String? {
        val dest = File(context.filesDir, "avatar")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null
            dest.absolutePath
        }.getOrNull()
    }

    private fun decodePhoto(context: Context, url: String): Bitmap? = runCatching {
        when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }
            url.startsWith("content:") || url.startsWith("file:") ->
                context.contentResolver.openInputStream(Uri.parse(url))?.use { BitmapFactory.decodeStream(it) }
            else -> BitmapFactory.decodeFile(url)
        }
    }.getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "user_session"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_PHOTO = "photo"
    private const val KEY_REMEMBER = "remember_email"
}
