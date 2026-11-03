package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimeWorkerRe : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        NewsLogger.alarmReceived()
        val pendingResult = goAsync()
        HintUtil.sendTthree {
            NewsLogger.triggerWorkFinished(HintUtil.CO_S_TYPE_ALARM)
            pendingResult.finish()
        }
        HintUtil.markLing()
    }
}