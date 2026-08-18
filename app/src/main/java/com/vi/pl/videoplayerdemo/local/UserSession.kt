package com.vi.pl.videoplayerdemo.local

import android.content.Context

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "user_session"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_PHOTO = "photo"
    private const val KEY_REMEMBER = "remember_email"
}
