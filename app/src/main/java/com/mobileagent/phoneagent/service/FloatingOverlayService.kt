package com.mobileagent.phoneagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mobileagent.phoneagent.MainActivity
import com.mobileagent.phoneagent.R
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator

class FloatingOverlayService : Service() {
    companion object {
        private const val ACTION_SHOW = "com.mobileagent.phoneagent.overlay.SHOW"
        private const val ACTION_UPDATE = "com.mobileagent.phoneagent.overlay.UPDATE"
        private const val ACTION_HIDE = "com.mobileagent.phoneagent.overlay.HIDE"

        private const val EXTRA_STATUS = "status"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_INTERACTION_REQUIRED = "interaction_required"

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestPermission(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun show(
            context: Context,
            status: String,
            detail: String,
            task: String,
            interactionRequired: Boolean = false
        ) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_INTERACTION_REQUIRED, interactionRequired)
            }
            context.startService(intent)
        }

        fun update(
            context: Context,
            status: String,
            detail: String,
            task: String,
            interactionRequired: Boolean = false
        ) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_INTERACTION_REQUIRED, interactionRequired)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var detailText: TextView? = null
    private var taskText: TextView? = null
    private var confirmButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW, ACTION_UPDATE -> {
                if (!canDraw(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureOverlay()
                updateOverlay(
                    status = intent.getStringExtra(EXTRA_STATUS) ?: "等待中",
                    detail = intent.getStringExtra(EXTRA_DETAIL) ?: "",
                    task = intent.getStringExtra(EXTRA_TASK)
                        ?: (AgentSessionCoordinator.currentTask() ?: "未设置任务"),
                    interactionRequired = intent.getBooleanExtra(EXTRA_INTERACTION_REQUIRED, false)
                )
            }
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_agent_status, null)
        statusText = view.findViewById(R.id.tvOverlayStatus)
        detailText = view.findViewById(R.id.tvOverlayDetail)
        taskText = view.findViewById(R.id.tvOverlayTask)
        confirmButton = view.findViewById<Button?>(R.id.btnOverlayConfirm).apply {
            this?.setOnClickListener {
                AgentSessionCoordinator.confirmUserAction()
                visibility = View.GONE
            }
        }

        view.findViewById<Button>(R.id.btnOverlayOpen).setOnClickListener {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(openIntent)
        }

        view.findViewById<Button>(R.id.btnOverlayStop).setOnClickListener {
            AgentSessionCoordinator.stopCurrentTask()
            hide(this)

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(openIntent)
        }

        overlayView = view
        windowManager?.addView(view, params)
    }

    private fun updateOverlay(status: String, detail: String, task: String, interactionRequired: Boolean) {
        statusText?.text = status
        detailText?.text = detail
        taskText?.text = task
        confirmButton?.visibility = if (interactionRequired) View.VISIBLE else View.GONE
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
        }
        overlayView = null
        statusText = null
        detailText = null
        taskText = null
        confirmButton = null
    }
}
