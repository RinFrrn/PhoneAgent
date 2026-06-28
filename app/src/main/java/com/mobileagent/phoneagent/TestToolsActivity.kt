package com.mobileagent.phoneagent

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mobileagent.phoneagent.databinding.ActivityTestToolsBinding
import com.mobileagent.phoneagent.service.AgentForegroundService
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.utils.AppLauncher
import com.mobileagent.phoneagent.utils.ScreenshotManager
import com.mobileagent.phoneagent.utils.ScreenshotUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestToolsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTestToolsBinding
    private var mediaProjection: MediaProjection? = null
    private var screenshotManager: ScreenshotManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    override fun onDestroy() {
        screenshotManager?.cleanup()
        screenshotManager = null
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    private fun setupViews() {
        binding.btnTestLaunchApp.setOnClickListener { testLaunchApp() }
        binding.btnReadScreenContent.setOnClickListener { readScreenContent() }
        binding.btnRequestCapture.setOnClickListener { requestScreenCapturePermission() }
        binding.btnCaptureScreenshot.setOnClickListener { captureScreenshot() }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun testLaunchApp() {
        val appName = binding.etAppName.text.toString().trim()
        if (appName.isEmpty()) {
            Toast.makeText(this, "请输入应用名", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("开始测试应用启动: $appName")
        val packageName = AppLauncher.getPackageName(this, appName)
        if (packageName == null) {
            val suggestions = AppLauncher.searchApps(this, appName, limit = 8)
            appendLog("未找到应用: $appName")
            if (suggestions.isNotEmpty()) {
                appendLog("相似应用:")
                suggestions.forEach { appendLog("- ${it.first} (${it.second})") }
            }
            updateStatus("应用匹配失败")
            return
        }

        val appLabel = AppLauncher.getAppName(this, packageName) ?: packageName
        appendLog("匹配结果: $appName -> $appLabel ($packageName)")

        val service = PhoneAgentAccessibilityService.getInstance()
        val success = service?.launchApp(packageName) ?: launchAppFromActivity(packageName)
        appendLog("启动结果: ${if (success) "成功" else "失败"}")
        updateStatus(if (success) "应用启动成功" else "应用启动失败")
    }

    private fun launchAppFromActivity(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            appendLog("Activity 启动 fallback 失败: ${e.message}")
            false
        }
    }

    private fun readScreenContent() {
        val service = PhoneAgentAccessibilityService.getInstance()
        if (service == null) {
            appendLog("无障碍服务未连接，无法读取屏幕内容")
            updateStatus("无障碍未连接")
            return
        }

        val currentApp = service.getCurrentAppName()
        val packageName = service.getCurrentPackageName() ?: "未知包名"
        val content = service.getScreenContent()

        appendLog("当前应用: $currentApp ($packageName)")
        appendLog("屏幕内容长度: ${content.length}")
        appendLog(content.take(4000))
        if (content.length > 4000) {
            appendLog("内容过长，仅显示前 4000 字符")
        }
        updateStatus("屏幕内容读取完成")
    }

    private fun requestScreenCapturePermission() {
        val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = "PREPARE_SCREEN_CAPTURE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        android.os.Handler(mainLooper).postDelayed({
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
        }, 500)
    }

    private fun captureScreenshot() {
        val projection = mediaProjection
        if (projection == null) {
            appendLog("请先授权屏幕录制")
            updateStatus("缺少屏幕录制授权")
            requestScreenCapturePermission()
            return
        }

        lifecycleScope.launch {
            updateStatus("正在截图...")
            val displayMetrics = resources.displayMetrics
            val manager = try {
                screenshotManager ?: ScreenshotManager(
                    projection,
                    displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    displayMetrics.densityDpi
                ).also {
                    it.initialize()
                    screenshotManager = it
                }
            } catch (e: Exception) {
                appendLog("截图管理器初始化失败: ${e.message}")
                updateStatus("截图初始化失败")
                return@launch
            }

            val bitmap = withContext(Dispatchers.IO) { manager.captureScreen() }
            if (bitmap == null) {
                appendLog("截图失败，未获取到图像")
                updateStatus("截图失败")
                return@launch
            }

            binding.ivScreenshot.setImageBitmap(bitmap)
            val base64Length = withContext(Dispatchers.IO) {
                ScreenshotUtils.bitmapToBase64(bitmap).length
            }
            appendLog("截图成功: ${bitmap.width}x${bitmap.height}, base64 长度: $base64Length")
            updateStatus("截图读取完成")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) {
            return
        }

        if (resultCode == RESULT_OK && data != null) {
            try {
                screenshotManager?.cleanup()
                screenshotManager = null
                mediaProjection?.stop()
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(resultCode, data)
                appendLog("屏幕录制授权成功")
                updateStatus("屏幕录制已授权")
            } catch (e: Exception) {
                appendLog("创建 MediaProjection 失败: ${e.message}")
                updateStatus("屏幕录制授权失败")
            }
        } else {
            appendLog("屏幕录制授权被取消")
            updateStatus("屏幕录制未授权")
        }
    }

    private fun updateStatus(status: String) {
        binding.tvTestStatus.text = "状态：$status"
    }

    private fun appendLog(text: String) {
        binding.tvTestLog.append("$text\n")
    }

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 201
    }
}
