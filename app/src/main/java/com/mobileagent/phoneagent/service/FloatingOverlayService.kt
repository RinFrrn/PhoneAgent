package com.mobileagent.phoneagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.mobileagent.phoneagent.LaunchProxyActivity
import com.mobileagent.phoneagent.R
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator

class FloatingOverlayService : Service() {
    companion object {
        private const val ACTION_SHOW = "com.mobileagent.phoneagent.overlay.SHOW"
        private const val ACTION_UPDATE = "com.mobileagent.phoneagent.overlay.UPDATE"
        private const val ACTION_HIDE = "com.mobileagent.phoneagent.overlay.HIDE"
        private const val ACTION_SHOW_TAP_MARKER = "com.mobileagent.phoneagent.overlay.SHOW_TAP_MARKER"
        private const val ACTION_SUPPRESS_STATUS = "com.mobileagent.phoneagent.overlay.SUPPRESS_STATUS"
        private const val ACTION_RESTORE_STATUS = "com.mobileagent.phoneagent.overlay.RESTORE_STATUS"

        private const val EXTRA_STATUS = "status"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_INTERACTION_REQUIRED = "interaction_required"
        private const val EXTRA_LAUNCH_REQUEST_ID = "launch_request_id"
        private const val EXTRA_LAUNCH_APP_LABEL = "launch_app_label"
        private const val EXTRA_TAP_X = "tap_x"
        private const val EXTRA_TAP_Y = "tap_y"
        private const val EXTRA_TAP_LABEL = "tap_label"

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

        fun showLaunchHandoff(
            context: Context,
            status: String,
            detail: String,
            task: String,
            launchRequestId: String,
            appLabel: String
        ) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_INTERACTION_REQUIRED, true)
                putExtra(EXTRA_LAUNCH_REQUEST_ID, launchRequestId)
                putExtra(EXTRA_LAUNCH_APP_LABEL, appLabel)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun showTapMarker(context: Context, x: Float, y: Float, label: String) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW_TAP_MARKER
                putExtra(EXTRA_TAP_X, x)
                putExtra(EXTRA_TAP_Y, y)
                putExtra(EXTRA_TAP_LABEL, label)
            }
            context.startService(intent)
        }

        fun suppressStatusOverlay(context: Context) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SUPPRESS_STATUS
            }
            context.startService(intent)
        }

        fun restoreStatusOverlay(context: Context) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_RESTORE_STATUS
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tapMarkerView: View? = null
    private var statusText: TextView? = null
    private var detailText: TextView? = null
    private var taskText: TextView? = null
    private var confirmButton: Button? = null
    private var currentLaunchRequestId: String? = null
    private var lastOverlayState: OverlayState? = null
    private var statusSuppressed = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
                val state = OverlayState(
                    status = intent.getStringExtra(EXTRA_STATUS) ?: "等待中",
                    detail = intent.getStringExtra(EXTRA_DETAIL) ?: "",
                    task = intent.getStringExtra(EXTRA_TASK)
                        ?: (AgentSessionCoordinator.currentTask() ?: "未设置任务"),
                    interactionRequired = intent.getBooleanExtra(EXTRA_INTERACTION_REQUIRED, false),
                    launchRequestId = intent.getStringExtra(EXTRA_LAUNCH_REQUEST_ID),
                    launchAppLabel = intent.getStringExtra(EXTRA_LAUNCH_APP_LABEL)
                )
                lastOverlayState = state
                if (statusSuppressed) {
                    return START_STICKY
                }
                ensureOverlay()
                updateOverlay(
                    status = state.status,
                    detail = state.detail,
                    task = state.task,
                    interactionRequired = state.interactionRequired,
                    launchRequestId = state.launchRequestId,
                    launchAppLabel = state.launchAppLabel
                )
            }
            ACTION_HIDE -> {
                statusSuppressed = false
                removeOverlay()
                stopSelf()
            }
            ACTION_SHOW_TAP_MARKER -> {
                if (!canDraw(this)) {
                    return START_NOT_STICKY
                }
                showTapMarker(
                    x = intent.getFloatExtra(EXTRA_TAP_X, 0f),
                    y = intent.getFloatExtra(EXTRA_TAP_Y, 0f),
                    label = intent.getStringExtra(EXTRA_TAP_LABEL) ?: ""
                )
            }
            ACTION_SUPPRESS_STATUS -> {
                statusSuppressed = true
                removeStatusOverlayOnly()
            }
            ACTION_RESTORE_STATUS -> {
                statusSuppressed = false
                val state = lastOverlayState ?: return START_STICKY
                ensureOverlay()
                updateOverlay(
                    status = state.status,
                    detail = state.detail,
                    task = state.task,
                    interactionRequired = state.interactionRequired,
                    launchRequestId = state.launchRequestId,
                    launchAppLabel = state.launchAppLabel
                )
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
                val requestId = currentLaunchRequestId
                if (requestId != null) {
                    startActivity(LaunchProxyActivity.intentFor(this@FloatingOverlayService, requestId))
                } else {
                    AgentSessionCoordinator.confirmUserAction()
                }
                visibility = View.GONE
            }
        }

        view.findViewById<Button>(R.id.btnOverlayStop).setOnClickListener {
            AgentSessionCoordinator.stopCurrentTask()
            hide(this)
        }

        overlayView = view
        windowManager?.addView(view, params)
    }

    private fun updateOverlay(
        status: String,
        detail: String,
        task: String,
        interactionRequired: Boolean,
        launchRequestId: String?,
        launchAppLabel: String?
    ) {
        currentLaunchRequestId = launchRequestId
        statusText?.text = status
        detailText?.text = detail
        taskText?.text = task
        confirmButton?.text = if (launchRequestId != null) {
            "打开${launchAppLabel ?: "应用"}"
        } else {
            "已处理"
        }
        confirmButton?.visibility = if (interactionRequired) View.VISIBLE else View.GONE
    }

    private fun removeOverlay() {
        removeStatusOverlayOnly()
        removeTapMarker()
    }

    private fun removeStatusOverlayOnly() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
        }
        overlayView = null
        statusText = null
        detailText = null
        taskText = null
        confirmButton = null
        currentLaunchRequestId = null
    }

    private fun showTapMarker(x: Float, y: Float, label: String) {
        removeTapMarker()
        val marker = TapMarkerView(this, x, y, label)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = 0
            this.y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        tapMarkerView = marker
        windowManager?.addView(marker, params)
        mainHandler.postDelayed({ removeTapMarker() }, 1_200L)
    }

    private fun removeTapMarker() {
        tapMarkerView?.let { marker ->
            runCatching { windowManager?.removeView(marker) }
        }
        tapMarkerView = null
    }

    private data class OverlayState(
        val status: String,
        val detail: String,
        val task: String,
        val interactionRequired: Boolean,
        val launchRequestId: String?,
        val launchAppLabel: String?
    )

    private class TapMarkerView(
        context: Context,
        private val tapX: Float,
        private val tapY: Float,
        private val label: String
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val markerSizePx = (density * 96f).coerceAtLeast(96f)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 64, 64)
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 64, 64)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = tapX
            val cy = tapY
            val radius = markerSizePx * 0.22f
            canvas.drawCircle(cx, cy, radius * 1.8f, fillPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawLine(cx - radius * 1.6f, cy, cx + radius * 1.6f, cy, ringPaint)
            canvas.drawLine(cx, cy - radius * 1.6f, cx, cy + radius * 1.6f, ringPaint)
            if (label.isNotBlank()) {
                canvas.drawText(label, cx, cy + radius * 2.4f, textPaint)
            }
        }
    }
}
