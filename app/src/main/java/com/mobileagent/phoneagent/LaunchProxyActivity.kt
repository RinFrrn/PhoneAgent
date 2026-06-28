package com.mobileagent.phoneagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.mobileagent.phoneagent.harness.act.AppLaunchHandoffRegistry
import com.mobileagent.phoneagent.utils.ActivityVisibilityTracker

class LaunchProxyActivity : Activity() {
    override fun onStart() {
        super.onStart()
        ActivityVisibilityTracker.markStarted()
    }

    override fun onStop() {
        ActivityVisibilityTracker.markStopped()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val pending = AppLaunchHandoffRegistry.find(requestId)
        if (pending == null) {
            AppLaunchHandoffRegistry.complete(requestId, false)
            Toast.makeText(this, "启动请求已过期", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(pending.packageName)
        if (launchIntent == null) {
            AppLaunchHandoffRegistry.complete(requestId, false)
            Toast.makeText(this, "无法打开 ${pending.appLabel}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val started = runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
            true
        }.getOrDefault(false)
        AppLaunchHandoffRegistry.complete(requestId, started)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "launch_request_id"

        fun intentFor(context: android.content.Context, requestId: String): Intent {
            return Intent(context, LaunchProxyActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
        }
    }
}
