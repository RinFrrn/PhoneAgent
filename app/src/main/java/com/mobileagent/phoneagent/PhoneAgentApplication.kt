package com.mobileagent.phoneagent

import android.app.Application
import android.util.Log
import com.mobileagent.phoneagent.harness.trace.TraceStorageStartup

class PhoneAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TraceStorageStartup.startAsync(filesDir).whenComplete { report, error ->
            if (error == null && report != null) {
                Log.i(TAG, report.toLogText())
            } else {
                Log.e(TAG, "Trace 启动维护失败", error)
            }
        }
    }

    private companion object {
        const val TAG = "PhoneAgentApplication"
    }
}
