package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TimeWorkerRe : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("NewsNotification", "alarm broadcast received: action=${intent?.action}")
        val pendingResult = goAsync()
        HintUtil.sendTthree {
            Log.d("NewsNotification", "alarm broadcast async work finished")
            pendingResult.finish()
        }
        HintUtil.markLing()
    }
}
