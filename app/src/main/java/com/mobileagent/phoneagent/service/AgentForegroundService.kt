/**
 * 前台服务 - 用于在后台运行 AI 任务
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责：
 * - 在后台持续运行任务
 * - 显示任务状态通知
 * - 处理用户介入通知
 */
package com.mobileagent.phoneagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobileagent.phoneagent.LaunchProxyActivity
import com.mobileagent.phoneagent.MainActivity
import com.mobileagent.phoneagent.R
import com.mobileagent.phoneagent.agent.Mode

/**
 * 前台服务 - 用于在后台运行 AI 任务
 */
class AgentForegroundService : Service() {
    companion object {
        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "phone_agent_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_USER_INTERVENTION = 1002
        
        const val ACTION_START_TASK = "com.mobileagent.phoneagent.START_TASK"
        const val ACTION_STOP_TASK = "com.mobileagent.phoneagent.STOP_TASK"
        const val EXTRA_TASK = "task"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_MODE = "mode"
        private const val ACTION_SHOW_LAUNCH_HANDOFF = "com.mobileagent.phoneagent.SHOW_LAUNCH_HANDOFF"
        private const val EXTRA_LAUNCH_REQUEST_ID = "launch_request_id"
        private const val EXTRA_LAUNCH_APP_LABEL = "launch_app_label"

        fun showLaunchHandoffNotification(
            context: Context,
            launchRequestId: String,
            appLabel: String
        ) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_SHOW_LAUNCH_HANDOFF
                putExtra(EXTRA_LAUNCH_REQUEST_ID, launchRequestId)
                putExtra(EXTRA_LAUNCH_APP_LABEL, appLabel)
            }
            context.startService(intent)
        }
    }

    private var isRunning = false
    private var currentTask: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "前台服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PREPARE_SCREEN_CAPTURE" -> {
                // 为屏幕录制准备前台服务
                Log.d(TAG, "准备屏幕录制，启动前台服务")
                val notificationIntent = Intent(this, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🤖 Phone Agent")
                    .setContentText("准备屏幕录制...")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setShowWhen(false)
                    .build()

                // Android 14+ 需要指定前台服务类型
                // 获取 MediaProjection 时必须使用 mediaProjection 类型
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // 使用 mediaProjection 类型，因为 MediaProjection API 要求
                        startForeground(NOTIFICATION_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                        Log.d(TAG, "前台服务通知已显示（准备屏幕录制）- 使用 mediaProjection 类型")
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                        Log.d(TAG, "前台服务通知已显示（准备屏幕录制）")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ 使用 mediaProjection 类型失败，尝试 specialUse", e)
                    // 如果 mediaProjection 失败，尝试 specialUse（虽然可能无法使用 MediaProjection）
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(NOTIFICATION_ID, notification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                            Log.w(TAG, "⚠️ 使用 specialUse 类型，MediaProjection 可能无法工作")
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ 启动前台服务完全失败", e2)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 启动前台服务失败", e)
                    e.printStackTrace()
                }
            }
            ACTION_START_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK)
                val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                val modeName = intent.getStringExtra(EXTRA_MODE)
                if (task != null) {
                    startTask(task, baseUrl, modelName, modeName)
                }
            }
            ACTION_STOP_TASK -> {
                stopTask()
            }
            "UPDATE_NOTIFICATION" -> {
                val content = intent?.getStringExtra("content") ?: "任务执行中..."
                updateNotification(content)
            }
            "SHOW_USER_INTERVENTION" -> {
                val message = intent?.getStringExtra("message") ?: "需要用户介入"
                showUserInterventionNotification(message)
            }
            ACTION_SHOW_LAUNCH_HANDOFF -> {
                val requestId = intent.getStringExtra(EXTRA_LAUNCH_REQUEST_ID)
                val appLabel = intent.getStringExtra(EXTRA_LAUNCH_APP_LABEL) ?: "目标应用"
                if (requestId != null) {
                    showLaunchHandoffNotification(requestId, appLabel)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // 普通任务通知渠道
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Agent 任务执行",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 AI 任务执行状态"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "通知渠道已创建: $CHANNEL_ID")
            
            // 用户介入通知渠道（高优先级）
            val interventionChannel = NotificationChannel(
                "${CHANNEL_ID}_intervention",
                "用户介入提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "需要用户介入时的提醒"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(interventionChannel)
            Log.d(TAG, "用户介入通知渠道已创建: ${CHANNEL_ID}_intervention")
        }
    }

    private fun startForegroundService(task: String, requiresMediaProjection: Boolean = false) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Phone Agent 运行中")
            .setContentText("任务: ${task.take(30)}${if (task.length > 30) "..." else ""}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

        // Android 14+ 需要明确指定前台服务类型
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val foregroundType = if (requiresMediaProjection) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, foregroundType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "前台服务已启动，通知已显示")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动前台服务失败", e)
            e.printStackTrace()
        }
        isRunning = true
        currentTask = task
    }

    fun updateNotification(content: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用BigTextStyle显示详细信息
        val style = NotificationCompat.BigTextStyle()
            .bigText(content)
            .setSummaryText("任务执行中...")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Phone Agent 运行中")
            .setContentText(content.take(50) + if (content.length > 50) "..." else "") // 简短标题
            .setStyle(style) // 详细内容
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setAutoCancel(false)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showUserInterventionNotification(message: String) {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "${CHANNEL_ID}_intervention"
        } else {
            CHANNEL_ID
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ 需要用户介入")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_USER_INTERVENTION, notification)
    }

    private fun showLaunchHandoffNotification(requestId: String, appLabel: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestId.hashCode(),
            LaunchProxyActivity.intentFor(this, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "${CHANNEL_ID}_intervention"
        } else {
            CHANNEL_ID
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("需要打开应用")
            .setContentText("点击继续打开 $appLabel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "打开$appLabel", pendingIntent)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_USER_INTERVENTION, notification)
    }

    private fun startTask(task: String, baseUrl: String?, modelName: String?, modeName: String?) {
        Log.d(TAG, "开始任务: $task")
        val mode = modeName?.let {
            runCatching { Mode.valueOf(it) }.getOrNull()
        } ?: Mode.VISION
        val requiresMediaProjection = mode == Mode.VISION || mode == Mode.HYBRID
        startForegroundService(task, requiresMediaProjection)
        // 实际任务执行逻辑会在 MainActivity 中通过回调触发
    }

    private fun stopTask() {
        Log.d(TAG, "停止任务")
        isRunning = false
        currentTask = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "前台服务已销毁")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
