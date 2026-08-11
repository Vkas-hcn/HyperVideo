package com.vi.pl.videoplayerdemo.download

import android.content.Context

object DownloadRegistry {
    private const val PREFS = "downloads"
    private const val IDS = "ids"
    fun add(context: Context, id: Long) {
        val values = ids(context).toMutableSet().apply { add(id) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(IDS, values.map(Long::toString).toSet()).apply()
    }
    fun ids(context: Context): Set<Long> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(IDS, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
    fun remove(context: Context, ids: Collection<Long>) {
        val values = this.ids(context) - ids.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(IDS, values.map(Long::toString).toSet()).apply()
    }
}
