package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneLockRe : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "broadcast received: action=${intent?.action}, contextPresent=${context != null}")
        context ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            delay(1_500)
            HintUtil.sendTtwo {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NewsNotification"
    }
}
