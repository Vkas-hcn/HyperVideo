package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import android.content.Intent
import androidx.core.app.WebCanService

class AssistanceLaunceJob : WebCanService() {
    override fun onHandleWork(intent: Intent) {
        HintUtil.launchFg()
    }

    companion object {
        fun enqueueWork(context: Context) {
            enqueueWork(
                context,
                AssistanceLaunceJob::class.java,
                4321,
                Intent(context, AssistanceLaunceJob::class.java),
            )
        }
    }
}
