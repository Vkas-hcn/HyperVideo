package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class HomeRecentsReceiver : BroadcastReceiver() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    @Volatile private var lastEventAt = 0L

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: return
        if (reason != REASON_HOME && reason != REASON_RECENT) return

        val now = System.currentTimeMillis()
        if (now - lastEventAt < DEBOUNCE_MS) return
        lastEventAt = now

        val delayMs = if (reason == REASON_RECENT) RECENT_DELAY_MS else HOME_DELAY_MS
        NewsLogger.triggerReceived(HintUtil.CO_S_TYPE_UNLOCK, extra = ",来源=" + reason + ",延迟=" + delayMs + "ms")

        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable { HintUtil.sendTtwo() }
        pending = task
        handler.postDelayed(task, delayMs)
    }

    companion object {
        private const val EXTRA_REASON = "reason"
        private const val REASON_HOME = "homekey"
        private const val REASON_RECENT = "recentapps"
        private const val DEBOUNCE_MS = 1_000L
        private const val HOME_DELAY_MS = 1_000L
        private const val RECENT_DELAY_MS = 3_000L
    }
}