package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import android.content.Intent
import androidx.core.app.WebCanService

/**
 * 幂等保活任务：onHandleWork 补排通知闹钟（对齐 AnonyBrowser AssistanceLaunceJob，
 * 差异在于无前台服务载体，launchFg 等价于 markLing）。
 */
class AssistanceLaunceJob : WebCanService() {
    override fun onHandleWork(intent: Intent) {
        NewsLogger.jobHandled()
        HintUtil.launchFg(true)
    }

    companion object {
        fun enqueueWork(context: Context) {
            enqueueWork(context, AssistanceLaunceJob::class.java, 4321, Intent(context, AssistanceLaunceJob::class.java))
        }
    }
}