package com.spdev22.mycare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.HisaabBuilder
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.notify.NotifyFactory

/**
 * DailyAlarmReceiver — DailyScheduler ke alarms yahan aate hain.
 * Kaam: sambandhit workflow ki notification bhejna, aur agle din
 * ke liye alarm dobara set karna.
 */
class DailyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val wfId = intent.getStringExtra(DailyScheduler.EXTRA_WF) ?: return
        val pack = WorkflowEngine.loadPack(context)
        val wf = pack.firstOrNull { it.id == wfId } ?: return

        // 0.2.2: pack ki value nahi, user ka toggle final hai
        if (WorkflowPrefs.isEnabled(context, wf)) {
            if (wf.id == "aaj_ka_hisaab") {
                val summary = HisaabBuilder.buildText(context)
                NotifyFactory.postWorkflow(context, wf, mapOf("hisaab" to summary))
            } else {
                NotifyFactory.postWorkflow(context, wf)
            }
        }

        // Agle din ke liye re-arm
        DailyScheduler.scheduleOne(context, wf)
    }
}
