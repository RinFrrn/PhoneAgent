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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.agent.PhoneAgent
import com.mobileagent.phoneagent.databinding.ActivityMainBinding
import com.mobileagent.phoneagent.model.ModelClient
import com.mobileagent.phoneagent.service.AgentForegroundService
import com.mobileagent.phoneagent.service.FloatingOverlayService
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var phoneAgent: PhoneAgent? = null
    private var foregroundService: AgentForegroundService? = null

    companion object {
        private const val REQUEST_CODE_ACCESSIBILITY = 100
        private const val REQUEST_CODE_SCREEN_CAPTURE = 101
        private const val REQUEST_CODE_NOTIFICATION = 102
        private const val REQUEST_CODE_VOICE_INPUT = 103
        private const val REQUEST_CODE_AUDIO = 104
        private const val REQUEST_CODE_OVERLAY = 105
    }
    
    private var isVoiceInputActive = false
    private var voiceActivityDetector: com.mobileagent.phoneagent.utils.VoiceActivityDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        checkPermissions()
        loadTaskFromPrefs() // 加载上次保存的任务
    }

    private fun setupViews() {
        binding.btnStart.setOnClickListener {
            startTask()
        }

        binding.btnStop.setOnClickListener {
            stopTask()
        }

        binding.btnSettings.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnOpenSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnVoiceInput.setOnClickListener {
            startVoiceInput()
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnScreenCapturePermission.setOnClickListener {
            requestScreenCapturePermission()
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        }
    }

    private fun checkPermissions() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val screenCaptureEnabled = mediaProjection != null

        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) "无障碍：已授权" else "无障碍：未授权"
        binding.tvScreenCaptureStatus.text = if (screenCaptureEnabled) "屏幕录制：已授权" else "屏幕录制：未授权"
        binding.tvOverlayStatus.text = if (overlayEnabled) "悬浮窗：已授权" else "悬浮窗：未授权"

        binding.btnAccessibilityPermission.text = if (accessibilityEnabled) "已开启" else "去开启"
        binding.btnScreenCapturePermission.text = if (screenCaptureEnabled) "已授权" else "去授权"
        binding.btnOverlayPermission.text = if (overlayEnabled) "已授权" else "去授权"

        binding.btnAccessibilityPermission.isEnabled = !accessibilityEnabled
        binding.btnScreenCapturePermission.isEnabled = !screenCaptureEnabled
        binding.btnOverlayPermission.isEnabled = !overlayEnabled

        if (!accessibilityEnabled) {
            binding.tvStatus.text = "请先启用无障碍服务"
//            binding.btnSettings.visibility = View.VISIBLE
        } else if (!overlayEnabled) {
            binding.tvStatus.text = "请授予悬浮窗权限"
            binding.btnSettings.visibility = View.GONE
        } else if (!screenCaptureEnabled && getSelectedMode() != Mode.ACCESSIBILITY) {
            binding.tvStatus.text = "当前模式需要屏幕录制权限"
            binding.btnSettings.visibility = View.GONE
        } else {
            binding.tvStatus.text = "权限已就绪"
            binding.btnSettings.visibility = View.GONE
        }

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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = accessibilityManager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()

        if (enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }) {
            return true
        }

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServicesSetting.contains("$packageName/")
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
                                
                                // Android 14+ 要求：必须先注册回调才能使用 MediaProjection
                                mediaProjection?.registerCallback(
                                    object : android.media.projection.MediaProjection.Callback() {
                                        override fun onStop() {
                                            android.util.Log.d("MainActivity", "MediaProjection 已停止")
                                            // MediaProjection 已停止，清空引用，下次需要重新请求
                                            mediaProjection = null
                                            checkPermissions()
                                        }
                                    },
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                )
                                
                                android.util.Log.d("MainActivity", "✅ MediaProjection 创建成功，回调已注册")
                                Toast.makeText(this, "屏幕录制权限已授予", Toast.LENGTH_SHORT).show()
                                checkPermissions()
                                
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
                                binding.tvStatus.text = "屏幕录制初始化失败"
                            }
                        }, 500)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "❌ 处理屏幕录制权限失败", e)
                        Toast.makeText(this, "处理权限失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "需要屏幕录制权限才能截图，请重新点击开始任务", Toast.LENGTH_LONG).show()
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
        return when (binding.rgMode.checkedRadioButtonId) {
            binding.rbVisionMode.id -> Mode.VISION
            binding.rbAccessibilityMode.id -> Mode.ACCESSIBILITY
            binding.rbHybridMode.id -> Mode.HYBRID
            else -> Mode.VISION // 默认视觉模式
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
                requestScreenCapturePermission()
                return
            }
        }

        // 所有权限都已就绪，开始任务
        startTaskInternal()
    }

    private fun startTaskInternal() {
        val task = binding.etTask.text.toString().trim()
        if (task.isEmpty()) {
            return
        }
        
        // 保存任务到SharedPreferences
        saveTaskToPrefs(task)

        val accessibilityService = PhoneAgentAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Toast.makeText(this, "无障碍服务未启动，请重启应用", Toast.LENGTH_LONG).show()
            return
        }

        // 获取选择的模式
        val selectedMode = getSelectedMode()
        
        // 只在视觉模式和混合模式下检查 MediaProjection
        if (selectedMode == Mode.VISION || selectedMode == Mode.HYBRID) {
            // 检查 MediaProjection 是否有效
            if (mediaProjection == null) {
                android.util.Log.w("MainActivity", "⚠️ MediaProjection 为 null，需要重新请求权限（模式: $selectedMode）")
                Toast.makeText(this, "屏幕录制权限未授予，正在重新请求...", Toast.LENGTH_SHORT).show()
                requestScreenCapturePermission()
                return
            }
            
            // MediaProjection 在任务完成后可能会过期，每次启动任务前都重新请求权限
            // 这样可以确保 MediaProjection 始终有效
            android.util.Log.d("MainActivity", "启动任务前检查 MediaProjection，如果已过期将重新请求权限（模式: $selectedMode）")
        } else {
            android.util.Log.d("MainActivity", "无障碍模式，无需 MediaProjection")
        }

        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 从SharedPreferences读取模型配置
        val provider = SettingsActivity.getProvider(this)
        val baseUrl = SettingsActivity.getBaseUrl(this)
        val modelName = SettingsActivity.getModelName(this)
        val apiKey = SettingsActivity.getApiKey(this)
        val temperature = SettingsActivity.getTemperature(this)
        val topP = SettingsActivity.getTopP(this)

        android.util.Log.d("MainActivity", "使用模型配置: provider=${provider.displayName}, baseUrl=$baseUrl, modelName=$modelName, temperature=$temperature, topP=$topP")

        val modelClient = ModelClient(baseUrl, modelName, apiKey, provider, temperature, topP)
        val systemPrompt = getSystemPrompt() // 从资源文件读取
        // selectedMode 已在上面获取

        // 启动前台服务
        val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_START_TASK
            putExtra(AgentForegroundService.EXTRA_TASK, task)
            putExtra(AgentForegroundService.EXTRA_BASE_URL, baseUrl)
            putExtra(AgentForegroundService.EXTRA_MODEL_NAME, modelName)
            putExtra(AgentForegroundService.EXTRA_MODE, selectedMode.name)
        }
        startForegroundService(serviceIntent)

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
                val overlayStatus = when {
                    stepResult.action == "分析中..." -> "AI 分析中"
                    stepResult.message == "正在执行操作..." -> "执行操作中"
                    stepResult.finished -> "任务完成"
                    stepResult.success -> "步骤成功"
                    else -> "步骤失败"
                }
                FloatingOverlayService.update(
                    context = this,
                    status = overlayStatus,
                    detail = stepResult.message ?: stepResult.thinking.take(120),
                    task = task
                )
                // 增强通知显示：显示每一步的详细信息
                val notificationContent = buildNotificationContent(stepResult, currentStepCount)
                updateNotification(notificationContent)
                // 在日志中也输出
                android.util.Log.d("MainActivity", "步骤回调: ${stepResult.thinking.take(100)}")
            },
            onUserInterventionCallback = { message ->
                // 显示用户介入通知
                showUserInterventionNotification(message)
                FloatingOverlayService.update(
                    context = this,
                    status = "等待用户处理",
                    detail = message,
                    task = task,
                    interactionRequired = true
                )
            }
        )

        // 更新 UI
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.tvStatus.text = "任务执行中（后台运行）..."
        binding.tvLog.text = ""
        resetStepCount()

        AgentSessionCoordinator.register(task) {
            runOnUiThread {
                stopTask()
            }
        }
        FloatingOverlayService.show(
            context = this,
            status = "任务启动中",
            detail = "正在初始化代理与权限",
            task = task
        )

        // 运行任务
        appendLog("🚀 开始执行任务: $task")
        appendLog("模型: $modelName")
        appendLog("API: $baseUrl")
        appendLog("")
        
        // 延迟一小段时间后进入后台，确保任务已启动
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500) // 延迟500ms，确保任务已启动
            moveTaskToBack(true) // 将应用移到后台
            android.util.Log.d("MainActivity", "应用已进入后台，任务继续在后台执行")
        }
        
        phoneAgent?.run(task) { result ->
            android.util.Log.d("MainActivity", "任务完成回调: $result")
            runOnUiThread {
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.tvStatus.text = "任务完成: $result"
                appendLog("")
                appendLog("========================================")
                appendLog("✅ 任务完成: $result")
                appendLog("========================================")
                
                // 任务完成后清理 MediaProjection，下次启动任务时会自动重新请求权限
                // 这样可以避免 MediaProjection 过期的问题
                android.util.Log.d("MainActivity", "任务完成，清理 MediaProjection，下次启动时将重新请求权限")
                mediaProjection = null
                phoneAgent = null // 清理 Agent 实例
                AgentSessionCoordinator.clear()
                FloatingOverlayService.hide(this@MainActivity)
                checkPermissions()
            }
            // 停止前台服务
            val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_STOP_TASK
            }
            stopService(stopIntent)
        }
    }

    private fun stopTask() {
        phoneAgent?.stop()
        phoneAgent = null
        AgentSessionCoordinator.clear()
        FloatingOverlayService.hide(this)
        
        // 停止前台服务
        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP_TASK
        }
        stopService(stopIntent)
        
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        checkPermissions()
        appendLog("任务已停止")
        
        // 停止VAD检测
        stopVADDetection()
    }

    private fun updateNotification(content: String) {
        // 通过广播或直接调用服务更新通知
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            action = "UPDATE_NOTIFICATION"
            putExtra("content", content)
        }
        startService(intent)
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
        // 如果是分析中的状态，不增加步骤计数
        if (stepResult.action == "分析中...") {
            appendLog("========================================")
            appendLog("🤖 AI 分析中...")
            appendLog("💭 思考过程:")
            appendLog(stepResult.thinking)
            appendLog("========================================")
            return
        }
        
        // 如果是执行中的状态，不增加步骤计数
        if (stepResult.message == "正在执行操作...") {
            appendLog("🎯 执行操作:")
            appendLog(stepResult.action)
            return
        }
        
        // 正常步骤更新
        currentStepCount++
        android.util.Log.d("MainActivity", "📝 更新步骤信息: $currentStepCount")
        appendLog("========================================")
        appendLog("步骤 $currentStepCount")
        appendLog("💭 思考过程:")
        appendLog(stepResult.thinking)
        appendLog("")
        appendLog("🎯 操作指令:")
        appendLog(stepResult.action)
        appendLog("")
        if (stepResult.message != null) {
            appendLog("📋 执行结果: ${stepResult.message}")
        }
        appendLog("")
        appendLog("状态: ${if (stepResult.success) "✅ 成功" else "❌ 失败"} | ${if (stepResult.finished) "✅ 任务完成" else "🔄 继续执行"}")
        appendLog("========================================")
        appendLog("")
    }
    
    private fun resetStepCount() {
        currentStepCount = 0
    }

    private fun appendLog(text: String) {
        binding.tvLog.append("$text\n")
        // 自动滚动到底部
        val scrollView = binding.svLog
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun getSystemPrompt(): String {
        // 获取屏幕尺寸用于提示词
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // 获取当前日期
        val calendar = java.util.Calendar.getInstance()
        val weekdayNames = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val weekday = weekdayNames[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 2]
        val formattedDate = "${calendar.get(java.util.Calendar.YEAR)}年${calendar.get(java.util.Calendar.MONTH) + 1}月${calendar.get(java.util.Calendar.DAY_OF_MONTH)}日 $weekday"
        
        // 获取当前选择的模式
        val selectedMode = getSelectedMode()
        val modeDescription = when (selectedMode) {
            Mode.VISION -> "视觉模式：你将收到屏幕截图，通过分析图片内容来理解屏幕状态。"
            Mode.ACCESSIBILITY -> "无障碍模式：你将收到屏幕的结构化文本内容（包括所有可见文本、按钮、输入框等控件信息及其坐标），通过分析这些文本和控件信息来理解屏幕状态。注意：坐标是相对坐标（0-1000），可以直接使用。"
            Mode.HYBRID -> "混合模式：你将同时收到屏幕截图和结构化文本内容，结合两种信息来理解屏幕状态。"
        }
        
        return """
            日期: $formattedDate | 屏幕: ${screenWidth}x${screenHeight} | 坐标: 0-1000(相对)
            
            运行模式: $modeDescription
            
            你是一个Android操作助手，可以根据操作历史和当前屏幕状态执行一系列操作来完成任务。
            你必须严格按照要求输出以下格式：
            <answer>{action}</answer>
            其中：
            - {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

            操作指令及其作用如下：
            - do(action="Launch", app="xxx")  
                Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Tap", element=[x,y])  
                Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Tap", element=[x,y], message="重要操作")  
                基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
            - do(action="Type", text="xxx")  
                Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。
            - do(action="Type_Name", text="xxx")  
                Type_Name是输入人名的操作，基本功能同Type。
            - do(action="Interact")  
                Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
            - do(action="Swipe", start=[x1,y1], end=[x2,y2])  
                Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Note", message="True")  
                记录当前页面内容以便后续总结。
            - do(action="Call_API", instruction="xxx")  
                总结或评论当前页面或已记录的内容。
            - do(action="Long Press", element=[x,y])  
                Long Pres是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
            - do(action="Double Tap", element=[x,y])  
                Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Take_over", message="xxx")  
                Take_over是接管操作，表示在登录和验证阶段需要用户协助。
            - do(action="Back")  
                导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Home") 
                Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
            - do(action="Wait", duration="x seconds")  
                等待页面加载，x为需要等待多少秒。
            - finish(message="xxx")  
                finish是结束任务的操作，表示准确完整完成任务，message是终止信息。 

            必须遵循的规则：
            1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
            2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
            3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
            4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
            5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
            6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
            7. 在做小红书总结类任务时一定要筛选图文笔记。
            8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
            9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
            10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
            11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
            12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
            13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
            14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
            15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
            16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
            17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
            18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
            19. 必须确认用户的最终目标完成才可以使用finish，否则禁止使用finish
            20. 禁止输出<answer>{action}</answer>以外的任何内容
        """.trimIndent()
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
