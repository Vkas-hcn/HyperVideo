package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneLockRe : BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        p0 ?: return
        val pendingResult = goAsync()
        NewsLogger.triggerReceived(HintUtil.CO_S_TYPE_UNLOCK, extra = ",延迟1500ms")
        CoroutineScope(Dispatchers.IO).launch {
            delay(1_500)
            HintUtil.sendTtwo {
                NewsLogger.triggerWorkFinished(HintUtil.CO_S_TYPE_UNLOCK)
                pendingResult.finish()
            }
        }
    }
}