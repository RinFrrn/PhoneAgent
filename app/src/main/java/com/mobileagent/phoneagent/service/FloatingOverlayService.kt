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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import com.mobileagent.phoneagent.LaunchProxyActivity
import com.mobileagent.phoneagent.R
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.learn.TeachingRecorder
import com.mobileagent.phoneagent.harness.runtime.TaskRunController
import com.mobileagent.phoneagent.harness.runtime.TaskRunRequest
import com.mobileagent.phoneagent.harness.runtime.TaskRunSource

class FloatingOverlayService : Service() {
    companion object {
        private const val MIN_TEACHING_STEPS = 2
        private const val ACTION_SHOW = "com.mobileagent.phoneagent.overlay.SHOW"
        private const val ACTION_UPDATE = "com.mobileagent.phoneagent.overlay.UPDATE"
        private const val ACTION_HIDE = "com.mobileagent.phoneagent.overlay.HIDE"
        private const val ACTION_SHOW_QUICK_ASK = "com.mobileagent.phoneagent.overlay.SHOW_QUICK_ASK"
        private const val ACTION_SHOW_TEACHING = "com.mobileagent.phoneagent.overlay.SHOW_TEACHING"
        private const val ACTION_SHOW_TAP_MARKER = "com.mobileagent.phoneagent.overlay.SHOW_TAP_MARKER"
        private const val ACTION_SUPPRESS_STATUS = "com.mobileagent.phoneagent.overlay.SUPPRESS_STATUS"
        private const val ACTION_RESTORE_STATUS = "com.mobileagent.phoneagent.overlay.RESTORE_STATUS"

        private const val EXTRA_STATUS = "status"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_ACTIVITY_STATUS = "activity_status"
        private const val EXTRA_ACTIVITY_ANIMATING = "activity_animating"
        private const val EXTRA_INTERACTION_REQUIRED = "interaction_required"
        private const val EXTRA_LAUNCH_REQUEST_ID = "launch_request_id"
        private const val EXTRA_LAUNCH_APP_LABEL = "launch_app_label"
        private const val EXTRA_TAP_X = "tap_x"
        private const val EXTRA_TAP_Y = "tap_y"
        private const val EXTRA_TAP_LABEL = "tap_label"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_HAS_SCREEN_CAPTURE = "has_screen_capture"
        private const val EXTRA_INITIAL_GOAL = "initial_goal"

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
            interactionRequired: Boolean = false,
            activityStatus: String? = null,
            activityAnimating: Boolean = false
        ) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_INTERACTION_REQUIRED, interactionRequired)
                putExtra(EXTRA_ACTIVITY_STATUS, activityStatus)
                putExtra(EXTRA_ACTIVITY_ANIMATING, activityAnimating)
            }
            context.startService(intent)
        }

        fun update(
            context: Context,
            status: String,
            detail: String,
            task: String,
            interactionRequired: Boolean = false,
            activityStatus: String? = null,
            activityAnimating: Boolean = false
        ) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_INTERACTION_REQUIRED, interactionRequired)
                putExtra(EXTRA_ACTIVITY_STATUS, activityStatus)
                putExtra(EXTRA_ACTIVITY_ANIMATING, activityAnimating)
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

        fun showQuickAsk(context: Context, modeName: String, hasScreenCapture: Boolean) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW_QUICK_ASK
                putExtra(EXTRA_MODE, modeName)
                putExtra(EXTRA_HAS_SCREEN_CAPTURE, hasScreenCapture)
            }
            context.startService(intent)
        }

        fun showTeaching(context: Context, initialGoal: String) {
            if (!canDraw(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW_TEACHING
                putExtra(EXTRA_INITIAL_GOAL, initialGoal)
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
    private var activityText: TextView? = null
    private var detailText: TextView? = null
    private var taskText: TextView? = null
    private var confirmButton: Button? = null
    private var actionRow: LinearLayout? = null
    private var quickAskPanel: LinearLayout? = null
    private var quickAskInput: EditText? = null
    private var quickAskStartButton: Button? = null
    private var teachingPanel: LinearLayout? = null
    private var teachingInputGroup: LinearLayout? = null
    private var teachingGoalInput: EditText? = null
    private var teachingStepNoteInput: EditText? = null
    private var teachingRecordButton: Button? = null
    private var teachingEditButton: Button? = null
    private var teachingFinishButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayFocusable = false
    private var overlayInputMode = OverlayInputMode.QUICK_ASK
    private var quickAskMode = Mode.ACCESSIBILITY
    private var quickAskHasScreenCapture = false
    private var teachingGoalDraft = ""
    private var teachingStepNoteDraft = ""
    private var teachingDetailDraft = "在真实页面完成一步操作后，写下这一步做了什么，再点记录。"
    private var teachingEditing = true
    private var currentLaunchRequestId: String? = null
    private var lastOverlayState: OverlayState? = null
    private var statusSuppressed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityBaseStatus: String? = null
    private var activityAnimating = false
    private var activityDotCount = 0
    private val activityAnimationRunnable = object : Runnable {
        override fun run() {
            if (!activityAnimating) return
            activityDotCount = (activityDotCount % 3) + 1
            activityText?.text = "${activityBaseStatus.orEmpty()}${".".repeat(activityDotCount)}"
            mainHandler.postDelayed(this, 500L)
        }
    }

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
                    activityStatus = intent.getStringExtra(EXTRA_ACTIVITY_STATUS),
                    activityAnimating = intent.getBooleanExtra(EXTRA_ACTIVITY_ANIMATING, false),
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
                    activityStatus = state.activityStatus,
                    activityAnimating = state.activityAnimating,
                    launchRequestId = state.launchRequestId,
                    launchAppLabel = state.launchAppLabel
                )
            }
            ACTION_HIDE -> {
                statusSuppressed = false
                removeOverlay()
                stopSelf()
            }
            ACTION_SHOW_QUICK_ASK -> {
                if (!canDraw(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                quickAskMode = intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
                    ?: Mode.ACCESSIBILITY
                quickAskHasScreenCapture =
                    intent.getBooleanExtra(EXTRA_HAS_SCREEN_CAPTURE, false)
                statusSuppressed = false
                ensureOverlay(focusable = false)
                showQuickAskInput()
            }
            ACTION_SHOW_TEACHING -> {
                if (!canDraw(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                teachingGoalDraft = intent.getStringExtra(EXTRA_INITIAL_GOAL).orEmpty()
                teachingStepNoteDraft = ""
                teachingDetailDraft = "在真实页面完成一步操作后，写下这一步做了什么，再点记录。"
                teachingEditing = true
                TeachingRecorder.cancel()
                TeachingRecorder.start(teachingGoalDraft)
                statusSuppressed = false
                ensureOverlay(focusable = true)
                showTeachingInput()
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
                    activityStatus = state.activityStatus,
                    activityAnimating = state.activityAnimating,
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

    private fun ensureOverlay(focusable: Boolean = false) {
        if (overlayView != null) {
            setOverlayFocusable(focusable)
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            buildOverlayFlags(focusable),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_agent_status, null)
        statusText = view.findViewById(R.id.tvOverlayStatus)
        activityText = view.findViewById(R.id.tvOverlayActivity)
        detailText = view.findViewById(R.id.tvOverlayDetail)
        taskText = view.findViewById(R.id.tvOverlayTask)
        actionRow = view.findViewById(R.id.rowOverlayActions)
        quickAskPanel = view.findViewById(R.id.llOverlayQuickAsk)
        quickAskInput = view.findViewById(R.id.etOverlayTask)
        quickAskStartButton = view.findViewById(R.id.btnOverlayStart)
        teachingPanel = view.findViewById(R.id.llOverlayTeaching)
        teachingInputGroup = view.findViewById(R.id.groupTeachInputs)
        teachingGoalInput = view.findViewById(R.id.etTeachGoal)
        teachingStepNoteInput = view.findViewById(R.id.etTeachStepNote)
        teachingRecordButton = view.findViewById(R.id.btnTeachRecordStep)
        teachingEditButton = view.findViewById(R.id.btnTeachEdit)
        teachingFinishButton = view.findViewById(R.id.btnTeachFinish)
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
            if (TaskRunController.isRunning()) {
                TaskRunController.stop(this)
            } else {
                AgentSessionCoordinator.stopCurrentTask()
            }
            hide(this)
        }
        view.findViewById<Button>(R.id.btnOverlayClose).setOnClickListener {
            hideKeyboard()
            hide(this)
        }
        quickAskInput?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                enterQuickAskEditing()
            }
            false
        }
        quickAskStartButton?.setOnClickListener {
            when (overlayInputMode) {
                OverlayInputMode.QUICK_ASK -> startQuickAskFromOverlay()
                OverlayInputMode.USER_ANSWER -> submitUserAnswerFromOverlay()
            }
        }
        teachingRecordButton?.setOnClickListener {
            recordTeachingStepFromOverlay()
        }
        teachingEditButton?.setOnClickListener {
            toggleTeachingEditing()
        }
        teachingFinishButton?.setOnClickListener {
            finishTeachingFromOverlay()
        }
        view.findViewById<Button>(R.id.btnTeachCancel).setOnClickListener {
            TeachingRecorder.cancel()
            hideKeyboard()
            hide(this)
        }

        overlayView = view
        overlayParams = params
        overlayFocusable = focusable
        windowManager?.addView(view, params)
    }

    private fun buildOverlayFlags(focusable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return if (focusable) {
            base
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (overlayFocusable == focusable) return
        params.flags = buildOverlayFlags(focusable)
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        windowManager?.updateViewLayout(view, params)
        overlayFocusable = focusable
    }

    private fun updateOverlay(
        status: String,
        detail: String,
        task: String,
        interactionRequired: Boolean,
        activityStatus: String?,
        activityAnimating: Boolean,
        launchRequestId: String?,
        launchAppLabel: String?
    ) {
        quickAskPanel?.visibility = View.GONE
        teachingPanel?.visibility = View.GONE
        actionRow?.visibility = View.VISIBLE
        setOverlayFocusable(false)
        currentLaunchRequestId = launchRequestId
        statusText?.text = status
        detailText?.text = detail
        taskText?.text = task
        taskText?.visibility = View.VISIBLE
        updateActivityIndicator(activityStatus, activityAnimating, interactionRequired)
        if (interactionRequired && launchRequestId == null) {
            showUserAnswerInput(detail = detail, task = task)
            return
        }
        confirmButton?.text = if (launchRequestId != null) {
            "打开${launchAppLabel ?: "应用"}"
        } else {
            "已处理"
        }
        confirmButton?.visibility = if (interactionRequired) View.VISIBLE else View.GONE
    }

    private fun showQuickAskInput() {
        overlayInputMode = OverlayInputMode.QUICK_ASK
        currentLaunchRequestId = null
        statusText?.text = "一键问屏"
        detailText?.text = when (quickAskMode) {
            Mode.ACCESSIBILITY -> "输入你想让 Phone Agent 在当前页面完成的事。"
            Mode.VISION, Mode.HYBRID -> if (quickAskHasScreenCapture) {
                "当前模式将使用已授权的屏幕录制能力。"
            } else {
                "当前模式需要先回到主页完成屏幕录制授权。"
            }
        }
        taskText?.visibility = View.GONE
        activityText?.visibility = View.GONE
        confirmButton?.visibility = View.GONE
        actionRow?.visibility = View.GONE
        teachingPanel?.visibility = View.GONE
        quickAskPanel?.visibility = View.VISIBLE
        quickAskInput?.hint = "问当前页面..."
        quickAskStartButton?.text = "开始"
        quickAskInput?.setText("")
        quickAskInput?.clearFocus()
        hideKeyboard()
    }

    private fun showUserAnswerInput(detail: String, task: String) {
        if (overlayInputMode != OverlayInputMode.USER_ANSWER) {
            quickAskInput?.setText("")
        }
        overlayInputMode = OverlayInputMode.USER_ANSWER
        currentLaunchRequestId = null
        statusText?.text = "需要你的回答"
        detailText?.text = detail
        taskText?.text = task
        taskText?.visibility = View.VISIBLE
        activityText?.visibility = View.GONE
        confirmButton?.visibility = View.GONE
        actionRow?.visibility = View.VISIBLE
        teachingPanel?.visibility = View.GONE
        quickAskPanel?.visibility = View.VISIBLE
        quickAskInput?.hint = "输入回答；留空则表示已处理"
        quickAskStartButton?.text = "提交"
        setOverlayFocusable(true)
    }

    private fun showTeachingInput() {
        currentLaunchRequestId = null
        statusText?.text = "示教录制"
        detailText?.text = buildTeachingDetailText(teachingDetailDraft)
        taskText?.visibility = View.GONE
        activityText?.visibility = View.GONE
        confirmButton?.visibility = View.GONE
        actionRow?.visibility = View.GONE
        quickAskPanel?.visibility = View.GONE
        teachingPanel?.visibility = View.VISIBLE
        teachingGoalInput?.setText(teachingGoalDraft)
        teachingStepNoteInput?.setText(teachingStepNoteDraft)
        teachingRecordButton?.isEnabled = !TaskRunController.isRunning()
        teachingFinishButton?.isEnabled = !TaskRunController.isRunning() && canFinishTeaching()
        renderTeachingEditingState()
    }

    private fun renderTeachingEditingState() {
        teachingInputGroup?.visibility = if (teachingEditing) View.VISIBLE else View.GONE
        teachingEditButton?.text = if (teachingEditing) "操作" else "编辑"
        setOverlayFocusable(teachingEditing)
        if (teachingEditing) {
            teachingRecordButton?.text = "记录"
        } else {
            teachingRecordButton?.text = "记录"
            hideKeyboard()
        }
    }

    private fun toggleTeachingEditing() {
        if (teachingEditing) {
            saveTeachingDraftFromInputs(requireGoal = false)
            teachingDetailDraft = "现在可以操作底层页面；完成一步后点记录。"
            detailText?.text = buildTeachingDetailText(teachingDetailDraft)
            teachingEditing = false
        } else {
            teachingEditing = true
            teachingDetailDraft = "补充这一步说明，或直接点记录使用最近捕捉到的操作。"
            detailText?.text = buildTeachingDetailText(teachingDetailDraft)
        }
        renderTeachingEditingState()
    }

    private fun enterQuickAskEditing() {
        if (TaskRunController.isRunning()) return
        setOverlayFocusable(true)
        quickAskInput?.let { input ->
            input.requestFocus()
            mainHandler.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun startQuickAskFromOverlay() {
        val task = quickAskInput?.text?.toString()?.trim().orEmpty()
        if (task.isBlank()) {
            detailText?.text = "请输入任务描述。"
            return
        }
        if (TaskRunController.isRunning()) {
            detailText?.text = "任务执行中，请先停止当前任务。"
            return
        }
        if ((quickAskMode == Mode.VISION || quickAskMode == Mode.HYBRID) && !quickAskHasScreenCapture) {
            detailText?.text = "当前模式需要先回到主页完成屏幕录制授权。"
            return
        }

        hideKeyboard()
        quickAskPanel?.visibility = View.GONE
        actionRow?.visibility = View.VISIBLE
        setOverlayFocusable(false)
        statusText?.text = "任务启动中"
        detailText?.text = "正在读取当前页面状态。"
        taskText?.text = task
        taskText?.visibility = View.VISIBLE
        TaskRunController.start(
            this,
            TaskRunRequest(
                task = task,
                mode = quickAskMode,
                source = TaskRunSource.QUICK_ASK
            )
        )
    }

    private fun submitUserAnswerFromOverlay() {
        val answer = quickAskInput?.text?.toString()?.trim().orEmpty()
        hideKeyboard()
        quickAskPanel?.visibility = View.GONE
        actionRow?.visibility = View.VISIBLE
        setOverlayFocusable(false)
        detailText?.text = if (answer.isBlank()) {
            "已确认用户介入完成，正在继续。"
        } else {
            "已提交回答，正在继续。"
        }
        AgentSessionCoordinator.confirmUserAction(answer.takeIf { it.isNotBlank() })
    }

    private fun recordTeachingStepFromOverlay() {
        if (!saveTeachingDraftFromInputs(requireGoal = true)) {
            returnTeachingToEdit("请先填写示教目标。")
            return
        }
        if (TaskRunController.isRunning()) {
            detailText?.text = "任务执行中，请先停止当前任务。"
            return
        }

        teachingDetailDraft = "正在记录当前页面..."
        hideKeyboard()
        removeStatusOverlayOnly()

        mainHandler.postDelayed({
            val result = TeachingRecorder.recordStep(
                goal = teachingGoalDraft,
                note = teachingStepNoteDraft
            )
            teachingStepNoteDraft = ""
            teachingDetailDraft = if (result.success) {
                "${result.message}\n继续操作下一步，然后记录。"
            } else {
                result.message
            }
            teachingEditing = !result.success
            ensureOverlay(focusable = true)
            showTeachingInput()
        }, 350L)
    }

    private fun finishTeachingFromOverlay() {
        saveTeachingDraftFromInputs(requireGoal = false)
        val result = TeachingRecorder.finish(this)
        teachingDetailDraft = result.message
        if (result.success) {
            statusText?.text = "示教完成"
            detailText?.text = result.message
            teachingPanel?.visibility = View.GONE
            actionRow?.visibility = View.GONE
            setOverlayFocusable(false)
            mainHandler.postDelayed({ hide(this) }, 1_500L)
        } else {
            if (result.message.contains("没有正在进行") || result.message.contains("至少记录")) {
                teachingEditing = false
            } else {
                teachingEditing = true
            }
            showTeachingInput()
        }
    }

    private fun returnTeachingToEdit(message: String) {
        teachingDetailDraft = message
        teachingEditing = true
        detailText?.text = buildTeachingDetailText(message)
        renderTeachingEditingState()
    }

    private fun buildTeachingDetailText(message: String): String {
        val count = TeachingRecorder.currentStepCount()
        val progress = if (count < MIN_TEACHING_STEPS) {
            "已记录 $count 步，还需 ${MIN_TEACHING_STEPS - count} 步可生成技能。"
        } else {
            "已记录 $count 步，可以完成并生成技能。"
        }
        return if (message.isBlank()) {
            progress
        } else {
            "$message\n$progress"
        }
    }

    private fun canFinishTeaching(): Boolean {
        return TeachingRecorder.currentStepCount() >= MIN_TEACHING_STEPS
    }

    private fun saveTeachingDraftFromInputs(requireGoal: Boolean): Boolean {
        if (teachingEditing) {
            teachingGoalDraft = teachingGoalInput?.text?.toString()?.trim().orEmpty()
            teachingStepNoteDraft = teachingStepNoteInput?.text?.toString()?.trim().orEmpty()
        }
        if (requireGoal && teachingGoalDraft.isBlank()) {
            return false
        }
        TeachingRecorder.updateGoal(teachingGoalDraft)
        return true
    }

    private fun hideKeyboard() {
        quickAskInput?.let { input ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        teachingGoalInput?.let { input ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        teachingStepNoteInput?.let { input ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
    }

    private fun updateActivityIndicator(
        activityStatus: String?,
        shouldAnimate: Boolean,
        interactionRequired: Boolean
    ) {
        val visibleStatus = activityStatus?.takeUnless { it.isBlank() }
        if (interactionRequired || visibleStatus == null) {
            stopActivityAnimation()
            activityText?.visibility = View.GONE
            activityText?.text = ""
            return
        }

        activityText?.visibility = View.VISIBLE
        activityBaseStatus = visibleStatus
        if (shouldAnimate) {
            startActivityAnimation()
        } else {
            stopActivityAnimation()
            activityText?.text = visibleStatus
        }
    }

    private fun startActivityAnimation() {
        if (activityAnimating) return
        activityAnimating = true
        activityDotCount = 0
        mainHandler.removeCallbacks(activityAnimationRunnable)
        mainHandler.post(activityAnimationRunnable)
    }

    private fun stopActivityAnimation() {
        activityAnimating = false
        mainHandler.removeCallbacks(activityAnimationRunnable)
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
        activityText = null
        detailText = null
        taskText = null
        confirmButton = null
        actionRow = null
        quickAskPanel = null
        quickAskInput = null
        quickAskStartButton = null
        teachingPanel = null
        teachingInputGroup = null
        teachingGoalInput = null
        teachingStepNoteInput = null
        teachingRecordButton = null
        teachingEditButton = null
        teachingFinishButton = null
        overlayParams = null
        overlayFocusable = false
        currentLaunchRequestId = null
        stopActivityAnimation()
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
        val activityStatus: String?,
        val activityAnimating: Boolean,
        val launchRequestId: String?,
        val launchAppLabel: String?
    )

    private enum class OverlayInputMode {
        QUICK_ASK,
        USER_ANSWER
    }

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
