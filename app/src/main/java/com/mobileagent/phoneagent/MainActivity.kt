/**
 * Phone Agent - 主界面 Activity
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责：
 * - UI 界面管理
 * - 权限请求（无障碍、屏幕录制、通知、录音）
 * - 任务输入和状态显示
 * - 语音输入支持
 */
package com.mobileagent.phoneagent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.agent.PhoneAgent
import com.mobileagent.phoneagent.agent.TaskOutcome
import com.mobileagent.phoneagent.databinding.ActivityMainBinding
import com.mobileagent.phoneagent.harness.eval.ActiveEvalRunner
import com.mobileagent.phoneagent.harness.eval.ActiveEvalReport
import com.mobileagent.phoneagent.harness.eval.EvalCase
import com.mobileagent.phoneagent.harness.eval.EvalRunner
import com.mobileagent.phoneagent.harness.runtime.RuntimePhase
import com.mobileagent.phoneagent.harness.runtime.RuntimeStatusUpdate
import com.mobileagent.phoneagent.harness.runtime.SystemPromptBuilder
import com.mobileagent.phoneagent.harness.runtime.TaskRunController
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.trace.FileTraceStore
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.model.ModelClient
import com.mobileagent.phoneagent.service.AgentForegroundService
import com.mobileagent.phoneagent.service.FloatingOverlayService
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.utils.ActivityVisibilityTracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var phoneAgent: PhoneAgent? = null
    private var foregroundService: AgentForegroundService? = null
    private var evalJob: Job? = null
    private var mainStatusMessage: String? = null
    private var mainStatusDetail: String? = null
    private var isTaskActive = false
    private var pendingQuickAskAfterScreenCapture = false
    private var recentOverlayStatus = "等待任务"
    private var recentOverlayDetail = "等待第一个执行结果"
    private val traceStore by lazy { FileTraceStore(this) }

    companion object {
        private const val REQUEST_CODE_ACCESSIBILITY = 100
        private const val REQUEST_CODE_SCREEN_CAPTURE = 101
        private const val REQUEST_CODE_NOTIFICATION = 102
        private const val REQUEST_CODE_VOICE_INPUT = 103
        private const val REQUEST_CODE_AUDIO = 104
        private const val REQUEST_CODE_OVERLAY = 105
        private const val ACCESSIBILITY_CONNECT_TIMEOUT_MS = 30_000L
        private const val EXAMPLE_TASK_COUNT = 3
        private const val MAX_LOG_LINES = 500
        private const val MAX_LOG_CHARS = 60_000
        private const val LOG_BOTTOM_THRESHOLD_PX = 48
        const val EXTRA_TASK_DESCRIPTION = "com.mobileagent.phoneagent.extra.TASK_DESCRIPTION"
    }
    
    private var isVoiceInputActive = false
    private var voiceActivityDetector: com.mobileagent.phoneagent.utils.VoiceActivityDetector? = null
    private var currentExampleTasks: List<String> = emptyList()
    private val logLines = ArrayDeque<String>()
    private var logTextLength = 0
    private var isLogAutoScrollEnabled = true
    private var isLogTouching = false
    private var isLogScrollToBottomPending = false
    private val exampleTaskPool = listOf(
        "打开微信",
        "打开小红书并搜索咖啡",
        "打开淘宝并搜索手机支架",
        "打开美团并搜索附近咖啡",
        "打开抖音并搜索今日热点",
        "打开系统设置查看 Wi-Fi 状态"
    )

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        checkPermissions()
        loadTaskFromPrefs() // 加载上次保存的任务
        handleTaskIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskIntent(intent)
    }

    private fun setupViews() {
        binding.btnStart.setOnClickListener {
            startTask()
        }

        binding.btnStop.setOnClickListener {
            stopTask()
        }

        binding.btnRunEval.setOnClickListener {
            startEvalSuite()
        }

        binding.btnOpenTestTools.setOnClickListener {
            startActivity(Intent(this, TestToolsActivity::class.java))
        }

        binding.btnOpenLearnedSkills.setOnClickListener {
            startActivity(Intent(this, LearnedSkillsActivity::class.java))
        }

        binding.btnStartTeaching.setOnClickListener {
            showTeachingOverlay()
        }

        binding.btnOpenSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnQuickAsk.setOnClickListener {
            showQuickAskOverlay()
        }

        binding.btnRefreshExampleTasks.setOnClickListener {
            refreshExampleTasks()
        }

        exampleTaskButtons().forEach { button ->
            button.setOnClickListener {
                val task = button.tag as? String ?: return@setOnClickListener
                startExampleTask(task)
            }
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        }

        binding.modeToggleGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                mainStatusMessage = null
                mainStatusDetail = null
                renderMainUiState()
            }
        }

        setupLogControls()
        refreshExampleTasks()
    }

    private fun setupLogControls() {
        binding.switchLogAutoScroll.isChecked = isLogAutoScrollEnabled
        binding.switchLogAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            isLogAutoScrollEnabled = isChecked
            if (isChecked) {
                scrollLogToBottom()
            }
        }

        binding.btnClearLog.setOnClickListener {
            clearLog()
        }

        binding.btnCopyLog.setOnClickListener {
            copyLogToClipboard()
        }

        binding.svLog.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isLogTouching = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isLogTouching = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun checkPermissions() {
        renderMainUiState()

        // Android 13+ 需要请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.d("MainActivity", "请求通知权限...")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            } else {
                android.util.Log.d("MainActivity", "✅ 通知权限已授予")
            }
        }

        // 不在这里请求屏幕录制权限，等用户点击"开始任务"时再请求
        // 这样可以避免应用启动时立即弹出权限对话框
    }

    private fun setMainStatus(message: String, detail: String? = null) {
        mainStatusMessage = message
        mainStatusDetail = detail
        renderMainUiState()
    }

    private fun renderMainUiState() {
        if (!::binding.isInitialized) return

        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val selectedMode = getSelectedMode()
        val visualMode = selectedMode != Mode.ACCESSIBILITY
        val running = isTaskActive ||
            phoneAgent?.isTaskRunning() == true ||
            TaskRunController.isRunning() ||
            evalJob != null

        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) "无障碍：已开启" else "无障碍：未开启"
        binding.tvOverlayStatus.text = if (overlayEnabled) "悬浮窗：已授权" else "悬浮窗：未授权"
        binding.btnAccessibilityPermission.text = if (accessibilityEnabled) "已开启" else "去开启"
        binding.btnOverlayPermission.text = if (overlayEnabled) "已授权" else "去授权"
        binding.btnAccessibilityPermission.isEnabled = !accessibilityEnabled
        binding.btnOverlayPermission.isEnabled = !overlayEnabled

        binding.rowAccessibilityPermission.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE
        binding.rowOverlayPermission.visibility = if (overlayEnabled) View.GONE else View.VISIBLE
        binding.cardPermissionGuide.visibility =
            if (accessibilityEnabled && overlayEnabled) View.GONE else View.VISIBLE
        binding.tvPermissionHint.text = when {
            !accessibilityEnabled && !overlayEnabled -> "开启无障碍和悬浮窗后，就可以开始执行任务。"
            !accessibilityEnabled -> "无障碍服务用于读取界面内容并执行点击、滑动等操作。"
            !overlayEnabled -> "悬浮窗用于在任务后台运行时显示状态和等待处理。"
            else -> "基础权限已完成。"
        }

        binding.tvModeHint.text = when (selectedMode) {
            Mode.ACCESSIBILITY -> "仅使用无障碍结构化内容，不会请求屏幕录制。"
            Mode.VISION -> "开始任务时会请求屏幕录制权限，用于截图分析。"
            Mode.HYBRID -> "开始任务时会请求屏幕录制权限，并结合无障碍内容分析。"
        }

        val modelConfig = SettingsActivity.getActiveModelConfig(this)
        val modelName = modelConfig.modelName.ifBlank { "未配置模型" }
        binding.tvModelSummary.text = if (modelConfig.displayName == modelName) {
            "模型：${modelConfig.provider.displayName} · $modelName"
        } else {
            "模型：${modelConfig.displayName} · ${modelConfig.provider.displayName} · $modelName"
        }
        renderExampleTasks(modelConfig.isConfigured, running)

        val defaultStatus = when {
            running -> "任务执行中"
            !accessibilityEnabled -> "需要开启无障碍服务"
            !overlayEnabled -> "需要授权悬浮窗"
            else -> "已就绪"
        }
        val defaultDetail = when {
            running -> "可以回到手机继续操作，或在这里停止任务。"
            !accessibilityEnabled -> "点击下方按钮进入系统设置，找到 Phone Agent 并开启。"
            !overlayEnabled -> "点击下方按钮进入系统设置，允许显示在其他应用上层。"
            visualMode -> "当前模式会在开始时请求一次屏幕录制权限。"
            else -> "输入任务描述后即可开始。"
        }

        binding.tvStatus.text = mainStatusMessage ?: defaultStatus
        binding.tvStatusDetail.text = mainStatusDetail ?: defaultDetail

        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnStart.visibility = if (running) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnQuickAsk.isEnabled = !running
        binding.btnStartTeaching.isEnabled = !running
        binding.btnRunEval.isEnabled = !running

        binding.advancedContent.visibility = View.VISIBLE
        renderTaskHistory()
    }

    private fun refreshExampleTasks() {
        currentExampleTasks = exampleTaskPool.shuffled().take(EXAMPLE_TASK_COUNT)
        val running = isTaskActive ||
            phoneAgent?.isTaskRunning() == true ||
            TaskRunController.isRunning() ||
            evalJob != null
        val modelConfigured = SettingsActivity.getActiveModelConfig(this).isConfigured
        renderExampleTasks(modelConfigured, running)
    }

    private fun renderExampleTasks(modelConfigured: Boolean, running: Boolean) {
        binding.sectionExampleTasks.visibility = if (modelConfigured) View.VISIBLE else View.GONE
        if (!modelConfigured) {
            return
        }

        if (currentExampleTasks.size < EXAMPLE_TASK_COUNT) {
            currentExampleTasks = exampleTaskPool.shuffled().take(EXAMPLE_TASK_COUNT)
        }

        exampleTaskButtons().forEachIndexed { index, button ->
            val task = currentExampleTasks.getOrNull(index)
            button.visibility = if (task == null) View.GONE else View.VISIBLE
            button.isEnabled = !running
            if (task != null) {
                button.text = task
                button.tag = task
            }
        }
        binding.btnRefreshExampleTasks.isEnabled = !running
    }

    private fun exampleTaskButtons(): List<MaterialButton> {
        return listOf(
            binding.btnExampleTask1,
            binding.btnExampleTask2,
            binding.btnExampleTask3
        )
    }

    private fun startExampleTask(task: String) {
        val running = isTaskActive ||
            phoneAgent?.isTaskRunning() == true ||
            TaskRunController.isRunning() ||
            evalJob != null
        if (running) {
            Toast.makeText(this, "任务执行中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        binding.etTask.setText(task)
        binding.etTask.setSelection(task.length)
        binding.tvVoiceStatus.text = ""
        startTask()
    }

    private fun showQuickAskOverlay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "需要通知权限才能显示任务状态", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION
            )
            return
        }

        val modelConfig = SettingsActivity.getActiveModelConfig(this)
        if (!modelConfig.isConfigured) {
            Toast.makeText(this, "请先完成模型配置", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val selectedMode = getSelectedMode()
        if ((selectedMode == Mode.VISION || selectedMode == Mode.HYBRID) && mediaProjection == null) {
            pendingQuickAskAfterScreenCapture = true
            Toast.makeText(this, "问屏需要先授权屏幕录制", Toast.LENGTH_SHORT).show()
            setMainStatus("准备请求屏幕录制权限", "授权后会自动打开一键问屏悬浮窗。")
            requestScreenCapturePermission()
            return
        }

        TaskRunController.setMediaProjection(mediaProjection)
        FloatingOverlayService.showQuickAsk(
            context = this,
            modeName = selectedMode.name,
            hasScreenCapture = mediaProjection != null
        )
        moveTaskToBack(true)
    }

    private fun showTeachingOverlay() {
        if (isTaskActive || phoneAgent?.isTaskRunning() == true || TaskRunController.isRunning() || evalJob != null) {
            Toast.makeText(this, "任务执行中，无法开始示教录制", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "示教录制需要先开启无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "示教录制需要悬浮窗权限", Toast.LENGTH_LONG).show()
            FloatingOverlayService.requestPermission(this)
            return
        }

        FloatingOverlayService.showTeaching(
            context = this,
            initialGoal = binding.etTask.text?.toString()?.trim().orEmpty()
        )
        setMainStatus("示教录制中", "请在悬浮窗中记录每一步页面状态，完成后会生成动态技能。")
        moveTaskToBack(true)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, PhoneAgentAccessibilityService::class.java)
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = accessibilityManager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()

        if (enabledServices.any { serviceInfo ->
                val enabledComponent = ComponentName(
                    serviceInfo.resolveInfo.serviceInfo.packageName,
                    serviceInfo.resolveInfo.serviceInfo.name
                )
                enabledComponent == expectedComponent
            }
        ) {
            return true
        }

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServicesSetting
            .split(':')
            .any { ComponentName.unflattenFromString(it) == expectedComponent }
    }

    private suspend fun awaitAccessibilityServiceConnection(
        timeoutMs: Long = ACCESSIBILITY_CONNECT_TIMEOUT_MS
    ): PhoneAgentAccessibilityService? {
        PhoneAgentAccessibilityService.getInstance()?.let { return it }
        if (!isAccessibilityServiceEnabled()) {
            return null
        }

        setMainStatus("正在等待无障碍服务连接", "系统服务已开启，正在等待 Phone Agent 建立连接。")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: (PhoneAgentAccessibilityService?) -> Unit
                listener = { service ->
                    if (service != null && continuation.isActive) {
                        PhoneAgentAccessibilityService.removeConnectionListener(listener)
                        android.util.Log.d("MainActivity", "✅ 无障碍服务已连接，继续启动任务")
                        continuation.resume(service)
                    }
                }
                PhoneAgentAccessibilityService.addConnectionListener(listener)
                continuation.invokeOnCancellation {
                    PhoneAgentAccessibilityService.removeConnectionListener(listener)
                }
                PhoneAgentAccessibilityService.getInstance()?.let(listener)
            }
        }.also { service ->
            if (service == null) {
                android.util.Log.w("MainActivity", "⚠️ 无障碍服务已授权但未连接")
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY)
    }

    private fun requestScreenCapturePermission() {
        // Android 14+ 要求：必须先启动前台服务才能使用 MediaProjection
        // 启动前台服务
        val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = "PREPARE_SCREEN_CAPTURE"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // 等待服务启动后再请求权限
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
        }, 500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            REQUEST_CODE_NOTIFICATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.d("MainActivity", "✅ 通知权限已授予")
                    Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    android.util.Log.w("MainActivity", "❌ 通知权限被拒绝")
                    Toast.makeText(
                        this,
                        "需要通知权限才能显示任务状态，请在设置中手动开启",
                        Toast.LENGTH_LONG
                    ).show()
                    // 引导用户到设置页面
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    }
                }
            }
            REQUEST_CODE_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startVoiceInput()
                } else {
                    Toast.makeText(this, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    try {
                        // 确保前台服务正在运行（Android 14+ 要求）
                        val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
                            action = "PREPARE_SCREEN_CAPTURE"
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        }
                        
                        // 等待服务启动
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                                TaskRunController.setMediaProjection(mediaProjection)
                                
                                // Android 14+ 要求：必须先注册回调才能使用 MediaProjection
                                mediaProjection?.registerCallback(
                                    object : android.media.projection.MediaProjection.Callback() {
                                        override fun onStop() {
                                            android.util.Log.d("MainActivity", "MediaProjection 已停止")
                                            // MediaProjection 已停止，清空引用，下次需要重新请求
                                            mediaProjection = null
                                            TaskRunController.setMediaProjection(null)
                                            checkPermissions()
                                        }
                                    },
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                )
                                
                                android.util.Log.d("MainActivity", "✅ MediaProjection 创建成功，回调已注册")
                                Toast.makeText(this, "屏幕录制权限已授予", Toast.LENGTH_SHORT).show()
                                checkPermissions()

                                if (pendingQuickAskAfterScreenCapture) {
                                    pendingQuickAskAfterScreenCapture = false
                                    binding.root.post {
                                        showQuickAskOverlay()
                                    }
                                    return@postDelayed
                                }
                                
                                // 如果用户之前点击了开始任务，现在自动开始
                                val task = binding.etTask.text.toString().trim()
                                if (task.isNotEmpty()) {
                                    // 立即开始，避免 MediaProjection 过期
                                    // MediaProjection 必须在创建后尽快使用
                                    binding.root.post {
                                        startTaskInternal()
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "❌ 创建 MediaProjection 失败", e)
                                Toast.makeText(this, "创建屏幕录制失败: ${e.message}", Toast.LENGTH_LONG).show()
                                setMainStatus("屏幕录制初始化失败", e.message)
                            }
                        }, 500)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "❌ 处理屏幕录制权限失败", e)
                        Toast.makeText(this, "处理权限失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    pendingQuickAskAfterScreenCapture = false
                    Toast.makeText(this, "需要屏幕录制权限才能截图，请重新点击开始任务", Toast.LENGTH_LONG).show()
                    isTaskActive = false
                    setMainStatus("屏幕录制未授权", "视觉或混合模式需要截图权限；也可以切换到无障碍模式。")
                    checkPermissions()
                }
            }
            REQUEST_CODE_ACCESSIBILITY -> {
                checkPermissions()
            }
            REQUEST_CODE_VOICE_INPUT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    if (results != null && results.isNotEmpty()) {
                        val spokenText = results[0]
                        binding.etTask.setText(spokenText)
                        binding.tvVoiceStatus.text = "✅ 识别成功"
                        
                        // 保存到SharedPreferences
                        saveTaskToPrefs(spokenText)
                        
                        // 如果任务正在执行，更新任务
                        updateTask(spokenText)
                    }
                    } else {
                        binding.tvVoiceStatus.text = "❌ 识别失败"
                    }
                    isVoiceInputActive = false
                    stopVADDetection()
            }
            REQUEST_CODE_OVERLAY -> {
                checkPermissions()
            }
        }
    }

    /**
     * 获取当前选择的运行模式
     */
    private fun getSelectedMode(): Mode {
        return when (binding.modeToggleGroup.checkedButtonId) {
            binding.btnModeVision.id -> Mode.VISION
            binding.btnModeAccessibility.id -> Mode.ACCESSIBILITY
            binding.btnModeHybrid.id -> Mode.HYBRID
            else -> Mode.ACCESSIBILITY // 默认无障碍模式
        }
    }

    private fun startTask() {
        val task = binding.etTask.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, "请输入任务描述", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限以显示任务状态", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            return
        }

        // Android 13+ 需要通知权限才能显示前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "需要通知权限才能显示任务状态", Toast.LENGTH_LONG).show()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
                return
            }
        }

        // 检查模式要求
        val selectedMode = getSelectedMode()
        if (selectedMode == Mode.VISION || selectedMode == Mode.HYBRID) {
            // 视觉模式和混合模式需要屏幕录制权限
            if (mediaProjection == null) {
                Toast.makeText(this, "正在请求屏幕录制权限...", Toast.LENGTH_SHORT).show()
                setMainStatus("准备请求屏幕录制权限", "系统会弹出确认窗口，用于本次任务截图分析。")
                requestScreenCapturePermission()
                return
            }
        }

        // 所有权限都已就绪，开始任务
        startTaskInternal()
    }

    private fun startTaskInternal() {
        lifecycleScope.launch {
            isTaskActive = true
            setMainStatus("正在连接无障碍服务", "请稍等，连接成功后会自动开始任务。")
            val accessibilityService = awaitAccessibilityServiceConnection()
            if (accessibilityService == null) {
                val message = if (isAccessibilityServiceEnabled()) {
                    "无障碍服务已授权但尚未连接，请稍等后重试；若仍失败，请在系统无障碍设置中关闭后重新开启"
                } else {
                    "无障碍服务未启用，请在系统设置中启用无障碍服务"
                }
                isTaskActive = false
                setMainStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }

            startTaskInternal(accessibilityService)
        }
    }

    private fun startTaskInternal(accessibilityService: PhoneAgentAccessibilityService) {
        val task = binding.etTask.text.toString().trim()
        if (task.isEmpty()) {
            return
        }
        
        // 保存任务到SharedPreferences
        saveTaskToPrefs(task)

        // 获取选择的模式
        val selectedMode = getSelectedMode()
        
        // 只在视觉模式和混合模式下检查 MediaProjection
        if (selectedMode == Mode.VISION || selectedMode == Mode.HYBRID) {
            // 检查 MediaProjection 是否有效
            if (mediaProjection == null) {
                android.util.Log.w("MainActivity", "⚠️ MediaProjection 为 null，需要重新请求权限（模式: $selectedMode）")
                Toast.makeText(this, "屏幕录制权限未授予，正在重新请求...", Toast.LENGTH_SHORT).show()
                isTaskActive = false
                setMainStatus("准备请求屏幕录制权限", "系统会弹出确认窗口，用于本次任务截图分析。")
                requestScreenCapturePermission()
                return
            }
            
            // MediaProjection 在任务完成后可能会过期，每次启动任务前都重新请求权限
            // 这样可以确保 MediaProjection 始终有效
            android.util.Log.d("MainActivity", "启动任务前检查 MediaProjection，如果已过期将重新请求权限（模式: $selectedMode）")
        } else {
            android.util.Log.d("MainActivity", "无障碍模式，无需 MediaProjection")
        }

        // 使用无障碍手势坐标基准，确保观察坐标和点击执行坐标一致
        val gestureBounds = accessibilityService.getGestureDisplayBounds()
        val screenWidth = gestureBounds.width
        val screenHeight = gestureBounds.height
        android.util.Log.d(
            "MainActivity",
            "使用手势坐标基准: ${gestureBounds.width}x${gestureBounds.height} (${gestureBounds.source})"
        )

        // 从SharedPreferences读取模型配置
        val modelConfig = SettingsActivity.getActiveModelConfig(this)
        val provider = modelConfig.provider
        val baseUrl = modelConfig.baseUrl
        val modelName = modelConfig.modelName
        val apiKey = modelConfig.apiKey
        val temperature = modelConfig.temperature
        val topP = modelConfig.topP

        android.util.Log.d("MainActivity", "使用模型配置: name=${modelConfig.displayName}, provider=${provider.displayName}, baseUrl=$baseUrl, modelName=$modelName, temperature=$temperature, topP=$topP")

        val modelClient = ModelClient(baseUrl, modelName, apiKey, provider, temperature, topP)
        val systemPrompt = getSystemPrompt() // 从资源文件读取
        // selectedMode 已在上面获取

        startTaskForegroundService(task, baseUrl, modelName, selectedMode)

        // 根据模式决定是否传递 mediaProjection
        // 无障碍模式下传递 null，视觉模式和混合模式下传递实际的 mediaProjection
        val mediaProjectionForAgent = if (selectedMode == Mode.ACCESSIBILITY) {
            null
        } else {
            mediaProjection
        }

        // 创建 Agent（支持通知回调）
        phoneAgent = PhoneAgent(
            context = this,
            modelClient = modelClient,
            modelDisplayName = modelConfig.displayName,
            accessibilityService = accessibilityService,
            mediaProjection = mediaProjectionForAgent, // 无障碍模式下为 null
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            maxSteps = Int.MAX_VALUE, // 移除最大步数限制，只有任务完成才停止
            systemPrompt = systemPrompt,
            mode = selectedMode, // 传递模式参数
            onStepCallback = { stepResult ->
                runOnUiThread {
                    updateStepInfo(stepResult)
                }
                updateOverlayResultFromStep(stepResult, task)
                // 增强通知显示：显示每一步的详细信息
                val notificationContent = buildNotificationContent(stepResult, currentStepCount)
                updateNotification(notificationContent)
                // 在日志中也输出
                android.util.Log.d("MainActivity", "步骤回调: ${stepResult.thinking.take(100)}")
            },
            onRuntimeStatusCallback = { statusUpdate ->
                updateRuntimeStatus(statusUpdate, task)
            },
            onUserInterventionCallback = { message ->
                // 显示用户介入通知
                showUserInterventionNotification(message)
                setRecentOverlayResult("等待用户处理", message)
                FloatingOverlayService.update(
                    context = this,
                    status = recentOverlayStatus,
                    detail = recentOverlayDetail,
                    task = task,
                    interactionRequired = true
                )
            }
        )

        // 更新 UI
        isTaskActive = true
        setMainStatus("任务执行中", "应用会进入后台继续执行，可通过悬浮窗观察状态。")
        clearLog()
        resetStepCount()
        setRecentOverlayResult("任务启动中", "等待第一个执行结果")

        AgentSessionCoordinator.register(task) {
            runOnUiThread {
                stopTask()
            }
        }
        FloatingOverlayService.show(
            context = this,
            status = "任务启动中",
            detail = recentOverlayDetail,
            task = task
        )

        // 运行任务
        appendLog("🚀 开始执行任务: $task")
        appendLog("模型: ${modelConfig.displayName} · $modelName")
        appendLog("API: $baseUrl")
        appendLog("")
        
        // 延迟一小段时间后进入后台，确保任务已启动
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500) // 延迟500ms，确保任务已启动
            moveTaskToBack(true) // 将应用移到后台
            android.util.Log.d("MainActivity", "应用已进入后台，任务继续在后台执行")
        }
        
        phoneAgent?.run(task) { result ->
            android.util.Log.d("MainActivity", "任务结束回调: $result")
            runOnUiThread {
                appendLog("")
                appendLog("========================================")
                if (result.success) {
                    isTaskActive = false
                    setMainStatus("任务完成", result.message)
                    appendLog("✅ 任务完成: ${result.message}")
                    android.util.Log.d("MainActivity", "任务完成，清理 MediaProjection，下次启动时将重新请求权限")
                    mediaProjection = null
                    TaskRunController.setMediaProjection(null)
                } else {
                    isTaskActive = false
                    setMainStatus("任务失败", result.message)
                    appendLog("❌ 任务失败: ${result.message}")
                    android.util.Log.w("MainActivity", "任务失败，保留 MediaProjection 以便用户修正后重试")
                }
                appendLog("========================================")

                phoneAgent = null
                AgentSessionCoordinator.clear()
                FloatingOverlayService.hide(this@MainActivity)
                renderMainUiState()
            }
            // 停止前台服务
            val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_STOP_TASK
            }
            stopService(stopIntent)
        }
    }

    private fun stopTask() {
        if (TaskRunController.isRunning()) {
            TaskRunController.stop(this)
        }
        evalJob?.cancel()
        evalJob = null
        phoneAgent?.stop()
        phoneAgent = null
        AgentSessionCoordinator.clear()
        FloatingOverlayService.hide(this)
        isTaskActive = false
        
        stopTaskForegroundService()
        setMainStatus("任务已停止", "可以修改任务描述后重新开始。")
        appendLog("任务已停止")
        
        // 停止VAD检测
        stopVADDetection()
    }

    private fun startEvalSuite() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            return
        }

        val evalRunner = EvalRunner(this)
        val cases = evalRunner.loadDefaultCases()
        if (cases.isEmpty()) {
            Toast.makeText(this, "未找到评测用例", Toast.LENGTH_SHORT).show()
            return
        }

        val requiresScreenCapture = cases.any {
            parseModeOrDefault(it.taskMode ?: it.mode ?: "HYBRID") != Mode.ACCESSIBILITY
        }
        if (requiresScreenCapture && mediaProjection == null) {
            Toast.makeText(this, "评测包含视觉任务，先授权屏幕录制", Toast.LENGTH_LONG).show()
            requestScreenCapturePermission()
            return
        }

        lifecycleScope.launch {
            val accessibilityService = awaitAccessibilityServiceConnection()
            if (accessibilityService == null) {
                val message = if (isAccessibilityServiceEnabled()) {
                    "无障碍服务已授权但尚未连接，请稍等后重试；若仍失败，请在系统无障碍设置中关闭后重新开启"
                } else {
                    "无障碍服务未启用，请在系统设置中启用无障碍服务"
                }
                setMainStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }

            startEvalSuiteInternal(evalRunner, cases, accessibilityService)
        }
    }

    private fun startEvalSuiteInternal(
        evalRunner: EvalRunner,
        cases: List<EvalCase>,
        accessibilityService: PhoneAgentAccessibilityService
    ) {
        val gestureBounds = accessibilityService.getGestureDisplayBounds()
        val screenWidth = gestureBounds.width
        val screenHeight = gestureBounds.height
        android.util.Log.d(
            "MainActivity",
            "评测使用手势坐标基准: ${gestureBounds.width}x${gestureBounds.height} (${gestureBounds.source})"
        )
        val modelConfig = SettingsActivity.getActiveModelConfig(this)
        val provider = modelConfig.provider
        val baseUrl = modelConfig.baseUrl
        val modelName = modelConfig.modelName
        val apiKey = modelConfig.apiKey
        val temperature = modelConfig.temperature
        val topP = modelConfig.topP
        val systemPrompt = getSystemPrompt()

        val activeEvalRunner = ActiveEvalRunner(evalRunner)
        isTaskActive = true
        setMainStatus("评测执行中", "正在按默认用例运行回归评测。")
        binding.tvLog.text = ""
        resetStepCount()
        setRecentOverlayResult("评测启动中", "等待第一个执行结果")
        appendLog("🧪 开始运行评测，共 ${cases.size} 个用例")

        evalJob = lifecycleScope.launch {
            val report = activeEvalRunner.runCases(cases) { taskSpec ->
                executeEvalTask(
                    taskSpec = taskSpec,
                    accessibilityService = accessibilityService,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    baseUrl = baseUrl,
                    modelName = modelName,
                    modelDisplayName = modelConfig.displayName,
                    apiKey = apiKey,
                    provider = provider,
                    temperature = temperature,
                    topP = topP,
                    systemPrompt = systemPrompt
                )
            }

            runOnUiThread {
                isTaskActive = false
                evalJob = null
                setMainStatus("评测完成", "通过 ${report.passedCases}/${report.totalCases} 个用例。")
                appendEvalReport(report)
            }
        }
    }

    private suspend fun executeEvalTask(
        taskSpec: TaskSpec,
        accessibilityService: PhoneAgentAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        baseUrl: String,
        modelName: String,
        modelDisplayName: String,
        apiKey: String,
        provider: com.mobileagent.phoneagent.model.ModelProvider,
        temperature: Float,
        topP: Float,
        systemPrompt: String
    ): TaskOutcome = suspendCancellableCoroutine { continuation ->
        val taskMode = parseModeOrDefault(taskSpec.mode)
        val modelClient = ModelClient(baseUrl, modelName, apiKey, provider, temperature, topP)
        val mediaProjectionForAgent = if (taskMode == Mode.ACCESSIBILITY) null else mediaProjection

        runOnUiThread {
            appendLog("")
            appendLog("========================================")
            appendLog("🧪 执行评测用例: ${taskSpec.id}")
            appendLog("任务: ${taskSpec.goal}")
            appendLog("模式: ${taskSpec.mode}")
            appendLog("========================================")
        }

        startTaskForegroundService(taskSpec.goal, baseUrl, modelName, taskMode)
        setRecentOverlayResult("评测用例启动中", "等待第一个执行结果")
        FloatingOverlayService.show(
            context = this,
            status = recentOverlayStatus,
            detail = recentOverlayDetail,
            task = taskSpec.goal
        )
        phoneAgent = PhoneAgent(
            context = this,
            modelClient = modelClient,
            modelDisplayName = modelDisplayName,
            accessibilityService = accessibilityService,
            mediaProjection = mediaProjectionForAgent,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            maxSteps = taskSpec.maxSteps,
            systemPrompt = systemPrompt,
            mode = taskMode,
            onStepCallback = { stepResult ->
                runOnUiThread {
                    updateStepInfo(stepResult)
                }
                updateOverlayResultFromStep(stepResult, taskSpec.goal)
            },
            onRuntimeStatusCallback = { statusUpdate ->
                updateRuntimeStatus(statusUpdate, taskSpec.goal)
            },
            onUserInterventionCallback = { message ->
                showUserInterventionNotification(message)
                setRecentOverlayResult("等待用户处理", message)
                FloatingOverlayService.update(
                    context = this,
                    status = recentOverlayStatus,
                    detail = recentOverlayDetail,
                    task = taskSpec.goal,
                    interactionRequired = true
                )
            }
        )

        continuation.invokeOnCancellation {
            runOnUiThread {
                phoneAgent?.stop()
                phoneAgent = null
                renderMainUiState()
                stopTaskForegroundService()
            }
        }

        phoneAgent?.run(taskSpec) { result ->
            runOnUiThread {
                appendLog("评测用例结果: ${if (result.success) "✅" else "❌"} ${result.message}")
            }
            phoneAgent = null
            stopTaskForegroundService()
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    private fun appendEvalReport(report: ActiveEvalReport) {
        appendLog("")
        appendLog("========================================")
        appendLog("🧪 评测报告")
        appendLog("通过: ${report.passedCases}/${report.totalCases}")
        report.results.forEach { result ->
            appendLog("- ${result.caseName}: ${if (result.evaluation.passed) "PASS" else "FAIL"}")
            appendLog("  outcome: ${result.taskOutcomeMessage}")
            appendLog("  trace: ${result.traceSessionId ?: "N/A"}")
            appendLog("  reasons: ${result.evaluation.reasons.joinToString(" | ")}")
        }
        appendLog("========================================")
    }

    private fun parseModeOrDefault(modeName: String): Mode {
        return runCatching { Mode.valueOf(modeName.uppercase()) }.getOrDefault(Mode.HYBRID)
    }

    private fun startTaskForegroundService(task: String, baseUrl: String, modelName: String, mode: Mode) {
        val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_START_TASK
            putExtra(AgentForegroundService.EXTRA_TASK, task)
            putExtra(AgentForegroundService.EXTRA_BASE_URL, baseUrl)
            putExtra(AgentForegroundService.EXTRA_MODEL_NAME, modelName)
            putExtra(AgentForegroundService.EXTRA_MODE, mode.name)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopTaskForegroundService() {
        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP_TASK
        }
        stopService(stopIntent)
    }

    private fun updateNotification(content: String) {
        // 通过广播或直接调用服务更新通知
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            action = "UPDATE_NOTIFICATION"
            putExtra("content", content)
        }
        startService(intent)
    }

    private fun updateRuntimeStatus(statusUpdate: RuntimeStatusUpdate, task: String) {
        runOnUiThread {
            setMainStatus(statusUpdate.status, statusUpdate.detail)
        }
        val isGenerating = statusUpdate.phase == RuntimePhase.MODEL_GENERATING
        FloatingOverlayService.update(
            context = this,
            status = recentOverlayStatus,
            detail = recentOverlayDetail,
            task = task,
            activityStatus = if (isGenerating) "AI 生成中" else null,
            activityAnimating = isGenerating
        )
        updateNotification("${statusUpdate.status}: ${statusUpdate.detail}")
    }

    private fun updateOverlayResultFromStep(
        stepResult: com.mobileagent.phoneagent.agent.StepResult,
        task: String
    ) {
        val overlayStatus = when {
            stepResult.finished -> "任务完成"
            stepResult.success -> "步骤成功"
            else -> "步骤失败"
        }
        val purpose = stepResult.purpose.orEmpty().ifBlank {
            TaskLogFormatter.summarizeStepPurpose(
                actionJson = stepResult.action,
                thinking = stepResult.thinking,
                executionMessage = stepResult.message
            )
        }
        val failureMessage = stepResult.message
            ?.takeIf { !stepResult.success && it.isNotBlank() }
            ?.take(60)
        val overlayDetail = if (failureMessage == null) {
            purpose
        } else {
            "$purpose 失败信息: $failureMessage"
        }
        setRecentOverlayResult(overlayStatus, overlayDetail)
        FloatingOverlayService.update(
            context = this,
            status = recentOverlayStatus,
            detail = recentOverlayDetail,
            task = task
        )
    }

    private fun setRecentOverlayResult(status: String, detail: String) {
        recentOverlayStatus = status
        recentOverlayDetail = detail.ifBlank { "等待下一步执行结果" }
    }

    private fun showUserInterventionNotification(message: String) {
        // 通过服务显示用户介入通知
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            action = "SHOW_USER_INTERVENTION"
            putExtra("message", message)
        }
        startService(intent)
        
        // 也在 UI 上显示
        runOnUiThread {
            Toast.makeText(this, "需要用户介入: $message", Toast.LENGTH_LONG).show()
            appendLog("⚠️ 需要用户介入: $message")
        }
    }

    private var currentStepCount = 0
    
    private fun updateStepInfo(stepResult: com.mobileagent.phoneagent.agent.StepResult) {
        // 正常步骤更新
        currentStepCount++
        android.util.Log.d("MainActivity", "📝 更新步骤信息: $currentStepCount")
        val displayStep = stepResult.copy(stepIndex = stepResult.stepIndex ?: currentStepCount)
        appendLog(TaskLogFormatter.formatLiveStep(displayStep))
        appendLog("")
    }

    private fun resetStepCount() {
        currentStepCount = 0
    }

    private fun renderTaskHistory() {
        binding.llTaskHistory.removeAllViews()
        val history = traceStore.loadRecentHistory(limit = 5)
        if (history.isEmpty()) {
            binding.llTaskHistory.addView(
                TextView(this).apply {
                    text = "暂无历史任务"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                }
            )
            return
        }

        history.forEach { entry ->
            binding.llTaskHistory.addView(createTaskHistoryView(entry))
        }
    }

    private fun createTaskHistoryView(entry: TaskHistoryEntry): TextView {
        return TextView(this).apply {
            text = buildTaskHistoryText(entry)
            textSize = 12f
            setLineSpacing(2f, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.main_surface_variant))
            isClickable = true
            setOnClickListener {
                openTaskTrace(entry)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun openTaskTrace(entry: TaskHistoryEntry) {
        val intent = Intent(this, TaskTraceActivity::class.java).apply {
            putExtra(TaskTraceActivity.EXTRA_TRACE_SESSION_ID, entry.traceSessionId)
            putExtra(TaskTraceActivity.EXTRA_TASK_GOAL, entry.taskGoal)
        }
        startActivity(intent)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun buildTaskHistoryText(entry: TaskHistoryEntry): String {
        val outcome = entry.outcomeMessage?.take(80) ?: "运行中"
        return "${formatHistoryTime(entry.startedAt)} · ${formatHistoryStatus(entry.status)} · ${entry.mode}\n" +
            "模型：${formatHistoryModel(entry)}\n" +
            "${entry.taskGoal}\n" +
            "$outcome · 步骤 ${entry.totalSteps} · trace ${entry.traceSessionId.take(8)}"
    }

    private fun formatHistoryTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun formatHistoryStatus(status: TaskHistoryStatus): String {
        return when (status) {
            TaskHistoryStatus.RUNNING -> "运行中"
            TaskHistoryStatus.SUCCEEDED -> "成功"
            TaskHistoryStatus.FAILED -> "失败"
            TaskHistoryStatus.STOPPED -> "已停止"
        }
    }

    private fun formatHistoryModel(entry: TaskHistoryEntry): String {
        val displayName = entry.modelDisplayName.orEmpty().ifBlank { entry.modelProvider.orEmpty() }
        val modelName = entry.modelName.orEmpty()
        return when {
            displayName.isBlank() && modelName.isBlank() -> "未记录"
            displayName.isBlank() -> modelName
            modelName.isBlank() || displayName == modelName -> displayName
            else -> "$displayName · $modelName"
        }
    }

    private fun appendLog(text: String) {
        val shouldFollowBottom = shouldAutoScrollLog()
        val newLines = text.split('\n')
        if (newLines.isEmpty()) {
            appendLogLine("")
        } else {
            newLines.forEach(::appendLogLine)
        }
        trimLogBuffer()
        binding.tvLog.text = currentLogText()
        if (shouldFollowBottom) {
            scrollLogToBottom()
        }
    }

    private fun appendLogLine(line: String) {
        logLines.addLast(line)
        logTextLength += line.length + 1
    }

    private fun trimLogBuffer() {
        while (logLines.size > MAX_LOG_LINES || logTextLength > MAX_LOG_CHARS) {
            val removed = logLines.removeFirst()
            logTextLength -= removed.length + 1
        }
    }

    private fun clearLog() {
        logLines.clear()
        logTextLength = 0
        binding.tvLog.text = ""
        binding.svLog.scrollTo(0, 0)
    }

    private fun copyLogToClipboard() {
        val logText = currentLogText()
        if (logText.isBlank()) {
            Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("PhoneAgent 执行日志", logText))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun currentLogText(): String {
        if (logLines.isEmpty()) {
            return ""
        }
        return buildString(logTextLength) {
            logLines.forEach { line ->
                append(line)
                append('\n')
            }
        }
    }

    private fun shouldAutoScrollLog(): Boolean {
        return isLogAutoScrollEnabled &&
            !isLogTouching &&
            (isLogNearBottom() || isLogScrollToBottomPending)
    }

    private fun isLogNearBottom(): Boolean {
        val child = binding.svLog.getChildAt(0) ?: return true
        val visibleBottom = binding.svLog.scrollY + binding.svLog.height - binding.svLog.paddingBottom
        return child.bottom - visibleBottom <= LOG_BOTTOM_THRESHOLD_PX
    }

    private fun scrollLogToBottom() {
        isLogScrollToBottomPending = true
        binding.svLog.post {
            binding.svLog.fullScroll(View.FOCUS_DOWN)
            isLogScrollToBottomPending = false
        }
    }

    private fun getSystemPrompt(): String {
        return SystemPromptBuilder.build(this, getSelectedMode())
    }

    /**
     * 启动语音输入（支持VAD检测）
     */
    private fun startVoiceInput() {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_CODE_AUDIO
            )
            return
        }

        // 检查设备是否支持语音识别
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说出任务描述（支持语音活动检测）")
        }

        // 使用更宽松的方式检查是否有应用可以处理语音识别 Intent
        // 先尝试检查默认应用，如果没有则检查所有可用应用
        var activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (activities.isEmpty()) {
            // 如果默认应用检查失败，尝试检查所有可用应用（包括未设置为默认的应用）
            activities = packageManager.queryIntentActivities(intent, 0)
            android.util.Log.d("MainActivity", "默认语音识别服务未找到，尝试查找所有可用服务，找到 ${activities.size} 个")
        }
        
        // 如果仍然没有找到，尝试使用更基础的语音识别 Intent
        if (activities.isEmpty()) {
            // 尝试使用更基础的语音识别 Intent（不指定语言模型）
            val basicIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说出任务描述")
            }
            activities = packageManager.queryIntentActivities(basicIntent, 0)
            if (activities.isNotEmpty()) {
                android.util.Log.d("MainActivity", "找到基础语音识别服务，使用基础 Intent")
                // 使用基础 Intent
                try {
                    startVADDetection()
                    startActivityForResult(basicIntent, REQUEST_CODE_VOICE_INPUT)
                    isVoiceInputActive = true
                    binding.tvVoiceStatus.text = "🎤 等待语音输入..."
                    return
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "启动基础语音识别失败", e)
                }
            }
        }

        // 如果所有检查都失败，提供详细的错误提示
        if (activities.isEmpty()) {
            val errorMessage = buildString {
                append("设备未检测到语音识别服务\n\n")
                append("解决方案：\n")
                append("1. 安装 Google 语音服务（Google Play 搜索 \"Google\"）\n")
                append("2. 或使用系统输入法的语音输入功能\n")
                append("3. 或直接使用文本输入")
            }
            
            // 尝试打开 Google Play 搜索页面
            try {
                val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://search?q=Google")
                    setPackage("com.android.vending")
                }
                // 检查是否可以打开 Play Store
                if (playStoreIntent.resolveActivity(packageManager) != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("语音识别不可用")
                        .setMessage(errorMessage)
                        .setPositiveButton("打开 Google Play") { _, _ ->
                            try {
                                startActivity(playStoreIntent)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "无法打开 Play Store", e)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "显示错误对话框失败", e)
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
            
            android.util.Log.w("MainActivity", "设备不支持语音识别，没有找到可用的语音识别服务")
            binding.tvVoiceStatus.text = "❌ 语音识别不可用"
            return
        }

        // 启动VAD检测
        startVADDetection()

        try {
            startActivityForResult(intent, REQUEST_CODE_VOICE_INPUT)
            isVoiceInputActive = true
            binding.tvVoiceStatus.text = "🎤 等待语音输入..."
        } catch (e: ActivityNotFoundException) {
            // 如果启动失败，提供更详细的错误信息
            val errorMessage = "无法启动语音识别服务\n\n可能原因：\n1. 语音识别服务未安装\n2. 服务被禁用\n3. 设备不支持\n\n建议：请安装 Google 语音服务或使用文本输入"
            android.app.AlertDialog.Builder(this)
                .setTitle("语音识别启动失败")
                .setMessage(errorMessage)
                .setPositiveButton("确定", null)
                .show()
            android.util.Log.e("MainActivity", "语音识别失败", e)
            binding.tvVoiceStatus.text = "❌ 启动失败"
            stopVADDetection()
        } catch (e: Exception) {
            Toast.makeText(this, "语音识别失败: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "语音识别失败", e)
            binding.tvVoiceStatus.text = "❌ 识别失败"
            stopVADDetection()
        }
    }

    /**
     * 启动VAD检测
     */
    private fun startVADDetection() {
        stopVADDetection() // 先停止之前的检测
        
        voiceActivityDetector = com.mobileagent.phoneagent.utils.VoiceActivityDetector(
            onVoiceDetected = {
                // 检测到语音活动
                binding.tvVoiceStatus.text = "🎤 检测到语音，正在录音..."
                android.util.Log.d("MainActivity", "VAD: 检测到语音活动")
            },
            onSilenceDetected = {
                // 检测到静音
                if (isVoiceInputActive) {
                    binding.tvVoiceStatus.text = "🎤 等待语音输入..."
                }
                android.util.Log.d("MainActivity", "VAD: 检测到静音")
            }
        )
        
        voiceActivityDetector?.start()
    }

    /**
     * 停止VAD检测
     */
    private fun stopVADDetection() {
        voiceActivityDetector?.stop()
        voiceActivityDetector = null
    }

    /**
     * 更新当前任务（允许在任务执行中更新）
     */
    fun updateTask(newTask: String) {
        if (phoneAgent != null && phoneAgent?.isTaskRunning() == true) {
            // 任务正在执行，更新任务
            android.util.Log.d("MainActivity", "更新任务: $newTask")
            phoneAgent?.updateTask(newTask)
            appendLog("📝 任务已更新: $newTask")
        } else {
            // 任务未执行，直接更新输入框
            binding.etTask.setText(newTask)
        }
    }

    /**
     * 构建通知内容，显示每一步的详细信息
     * 包括：思考过程、任务目标、执行步骤、操作指令等
     */
    private fun buildNotificationContent(stepResult: com.mobileagent.phoneagent.agent.StepResult, stepCount: Int): String {
        val task = binding.etTask.text.toString().trim()
        val builder = StringBuilder()
        
        builder.append("━━━━━━━━━━━━━━━━━━━━\n")
        builder.append("📋 步骤 $stepCount\n")
        builder.append("━━━━━━━━━━━━━━━━━━━━\n\n")
        
        // 任务目标
        builder.append("🎯 任务目标:\n")
        builder.append("${task.take(100)}${if (task.length > 100) "..." else ""}\n\n")
        
        // 思考过程（完整显示，但限制长度避免通知过长）
        if (stepResult.thinking.isNotEmpty()) {
            builder.append("💭 思考过程:\n")
            val thinking = if (stepResult.thinking.length > 200) {
                stepResult.thinking.take(200) + "..."
            } else {
                stepResult.thinking
            }
            builder.append("$thinking\n\n")
        }
        
        // 操作指令
        if (stepResult.action.isNotEmpty() && stepResult.action != "分析中...") {
            builder.append("🎯 操作指令:\n")
            val action = if (stepResult.action.length > 100) {
                stepResult.action.take(100) + "..."
            } else {
                stepResult.action
            }
            builder.append("$action\n\n")
        }
        
        // 执行结果
        if (stepResult.message != null && stepResult.message.isNotEmpty()) {
            builder.append("📋 执行结果:\n")
            val message = if (stepResult.message.length > 100) {
                stepResult.message.take(100) + "..."
            } else {
                stepResult.message
            }
            builder.append("$message\n\n")
        }
        
        // 状态
        builder.append("━━━━━━━━━━━━━━━━━━━━\n")
        builder.append("状态: ${if (stepResult.success) "✅ 成功" else "❌ 失败"} | ${if (stepResult.finished) "✅ 任务完成" else "🔄 进行中"}")
        
        return builder.toString()
    }


    /**
     * 保存任务到SharedPreferences
     */
    private fun saveTaskToPrefs(task: String) {
        val prefs = getSharedPreferences("phone_agent_settings", MODE_PRIVATE)
        prefs.edit().putString("last_task", task).apply()
    }

    private fun handleTaskIntent(intent: Intent?) {
        val task = intent
            ?.getStringExtra(EXTRA_TASK_DESCRIPTION)
            ?.trim()
            .orEmpty()
        if (task.isBlank()) {
            return
        }

        binding.etTask.setText(task)
        binding.etTask.setSelection(task.length)
        saveTaskToPrefs(task)
        Toast.makeText(this, "已填入任务描述", Toast.LENGTH_SHORT).show()
        intent?.removeExtra(EXTRA_TASK_DESCRIPTION)
    }

    /**
     * 从SharedPreferences加载任务
     */
    private fun loadTaskFromPrefs() {
        val prefs = getSharedPreferences("phone_agent_settings", MODE_PRIVATE)
        val lastTask = prefs.getString("last_task", "") ?: ""
        if (lastTask.isNotEmpty()) {
            binding.etTask.setText(lastTask)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放VAD资源
        stopVADDetection()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }
}
