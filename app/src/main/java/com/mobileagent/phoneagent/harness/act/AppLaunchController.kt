package com.mobileagent.phoneagent.harness.act

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.service.AgentForegroundService
import com.mobileagent.phoneagent.service.FloatingOverlayService
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.utils.ActivityVisibilityTracker
import com.mobileagent.phoneagent.utils.AppLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AppLaunchStrategy {
    DIRECT_VISIBLE,
    USER_VISIBLE_HANDOFF
}

enum class AppLaunchStatus {
    STARTED,
    TARGET_REACHED,
    APP_NOT_FOUND,
    NO_LAUNCHER_ACTIVITY,
    BLOCKED,
    CONFIRMATION_REQUIRED,
    FAILED
}

enum class PopupConfirmationStatus {
    NOT_DETECTED,
    CONFIRMED,
    UNSAFE_TO_CONFIRM
}

data class PopupConfirmationResult(
    val status: PopupConfirmationStatus,
    val buttonText: String? = null,
    val popupPackage: String? = null,
    val reason: String? = null
)

data class AppLaunchTrace(
    val targetAppName: String,
    val targetPackage: String? = null,
    val actualAppName: String? = null,
    val strategy: AppLaunchStrategy? = null,
    val status: AppLaunchStatus,
    val popupStatus: PopupConfirmationStatus = PopupConfirmationStatus.NOT_DETECTED,
    val popupButtonText: String? = null,
    val beforePackage: String? = null,
    val beforeApp: String? = null,
    val afterPackage: String? = null,
    val afterApp: String? = null,
    val handoffRequestId: String? = null,
    val handoffShown: Boolean = false,
    val handoffClicked: Boolean = false,
    val message: String? = null
)

data class AppLaunchRequest(
    val appName: String,
    val actionJson: String,
    val currentTask: String?
)

data class PendingAppLaunch(
    val requestId: String,
    val packageName: String,
    val appLabel: String,
    val createdAt: Long,
    val completion: CompletableDeferred<Boolean> = CompletableDeferred()
)

object AppLaunchHandoffRegistry {
    private val pending = ConcurrentHashMap<String, PendingAppLaunch>()

    fun register(packageName: String, appLabel: String): PendingAppLaunch {
        val request = PendingAppLaunch(
            requestId = UUID.randomUUID().toString(),
            packageName = packageName,
            appLabel = appLabel,
            createdAt = SystemClock.elapsedRealtime()
        )
        pending[request.requestId] = request
        return request
    }

    fun find(requestId: String?): PendingAppLaunch? {
        if (requestId.isNullOrBlank()) return null
        pruneExpired()
        return pending[requestId]
    }

    fun complete(requestId: String?, success: Boolean) {
        val request = find(requestId) ?: return
        if (!request.completion.isCompleted) {
            request.completion.complete(success)
        }
        pending.remove(request.requestId)
    }

    fun remove(requestId: String?) {
        if (!requestId.isNullOrBlank()) {
            pending.remove(requestId)
        }
    }

    private fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeIf { (_, request) -> now - request.createdAt > 10 * 60 * 1000L }
    }
}

class AppLaunchController(
    private val context: Context,
    private val accessibilityService: PhoneAgentAccessibilityService
) {
    private val tag = "AppLaunchController"

    suspend fun launch(request: AppLaunchRequest): ExecutionResult {
        val beforePackage = accessibilityService.getCurrentPackageName()
        val beforeApp = accessibilityService.getCurrentAppName()
        val packageName = AppLauncher.getPackageName(context, request.appName)
        if (packageName == null) {
            val trace = AppLaunchTrace(
                targetAppName = request.appName,
                status = AppLaunchStatus.APP_NOT_FOUND,
                beforePackage = beforePackage,
                beforeApp = beforeApp,
                message = "未找到应用: ${request.appName}"
            )
            return trace.toExecutionResult(request.actionJson, false, FailureType.APP_NOT_FOUND)
        }

        if (!AppLauncher.isAppInstalled(context, packageName)) {
            val trace = AppLaunchTrace(
                targetAppName = request.appName,
                targetPackage = packageName,
                status = AppLaunchStatus.APP_NOT_FOUND,
                beforePackage = beforePackage,
                beforeApp = beforeApp,
                message = "应用未安装: ${request.appName} ($packageName)"
            )
            return trace.toExecutionResult(request.actionJson, false, FailureType.APP_NOT_FOUND)
        }

        val actualAppName = AppLauncher.getAppName(context, packageName) ?: request.appName
        val launchIntent = buildLaunchIntent(packageName)
        if (launchIntent == null) {
            val trace = AppLaunchTrace(
                targetAppName = request.appName,
                targetPackage = packageName,
                actualAppName = actualAppName,
                status = AppLaunchStatus.NO_LAUNCHER_ACTIVITY,
                beforePackage = beforePackage,
                beforeApp = beforeApp,
                message = "未找到可启动的 Launcher Activity: $actualAppName ($packageName)"
            )
            return trace.toExecutionResult(request.actionJson, false, FailureType.APP_LAUNCH_TARGET_NOT_REACHED)
        }

        val strategy = if (ActivityVisibilityTracker.isAppVisible()) {
            AppLaunchStrategy.DIRECT_VISIBLE
        } else {
            AppLaunchStrategy.USER_VISIBLE_HANDOFF
        }

        val handoff = if (strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF) {
            runHandoff(packageName, actualAppName, request.currentTask)
        } else {
            null
        }

        val started = when (strategy) {
            AppLaunchStrategy.DIRECT_VISIBLE -> startDirect(launchIntent)
            AppLaunchStrategy.USER_VISIBLE_HANDOFF -> handoff?.completion
                ?.let { withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { it.await() } }
                ?: false
        }

        if (!started) {
            AppLaunchHandoffRegistry.remove(handoff?.requestId)
            val trace = AppLaunchTrace(
                targetAppName = request.appName,
                targetPackage = packageName,
                actualAppName = actualAppName,
                strategy = strategy,
                status = if (strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF) {
                    AppLaunchStatus.CONFIRMATION_REQUIRED
                } else {
                    AppLaunchStatus.BLOCKED
                },
                beforePackage = beforePackage,
                beforeApp = beforeApp,
                handoffRequestId = handoff?.requestId,
                handoffShown = handoff != null,
                handoffClicked = false,
                message = if (strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF) {
                    "需要点击悬浮窗或通知来打开 $actualAppName"
                } else {
                    "系统阻止启动应用: $actualAppName ($packageName)"
                }
            )
            return trace.toExecutionResult(
                request.actionJson,
                success = false,
                failureType = if (strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF) {
                    FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED
                } else {
                    FailureType.APP_LAUNCH_BLOCKED
                },
                requiresTakeover = strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF
            )
        }

        delay(POST_LAUNCH_SETTLE_MS)
        val popupResult = accessibilityService.confirmLaunchPopupIfPresent(targetPackage = packageName)
        if (popupResult.status == PopupConfirmationStatus.CONFIRMED) {
            delay(POST_POPUP_CONFIRM_SETTLE_MS)
        }

        val afterPackage = accessibilityService.getCurrentPackageName()
        val afterApp = accessibilityService.getCurrentAppName()
        val reachedTarget = afterPackage == packageName
        val status = when {
            reachedTarget -> AppLaunchStatus.TARGET_REACHED
            popupResult.status == PopupConfirmationStatus.UNSAFE_TO_CONFIRM -> AppLaunchStatus.CONFIRMATION_REQUIRED
            else -> AppLaunchStatus.STARTED
        }
        val failureType = when {
            reachedTarget -> null
            popupResult.status == PopupConfirmationStatus.UNSAFE_TO_CONFIRM -> FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED
            else -> FailureType.APP_LAUNCH_TARGET_NOT_REACHED
        }
        val trace = AppLaunchTrace(
            targetAppName = request.appName,
            targetPackage = packageName,
            actualAppName = actualAppName,
            strategy = strategy,
            status = status,
            popupStatus = popupResult.status,
            popupButtonText = popupResult.buttonText,
            beforePackage = beforePackage,
            beforeApp = beforeApp,
            afterPackage = afterPackage,
            afterApp = afterApp,
            handoffRequestId = handoff?.requestId,
            handoffShown = handoff != null,
            handoffClicked = strategy == AppLaunchStrategy.USER_VISIBLE_HANDOFF,
            message = buildMessage(actualAppName, packageName, status, popupResult, afterPackage)
        )

        return trace.toExecutionResult(
            actionJson = request.actionJson,
            success = reachedTarget || status == AppLaunchStatus.STARTED,
            failureType = failureType,
            requiresTakeover = status == AppLaunchStatus.CONFIRMATION_REQUIRED
        )
    }

    private fun buildLaunchIntent(packageName: String): Intent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            return launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val activity = context.packageManager
            .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .firstOrNull()
            ?: return null

        return Intent(launcherIntent).apply {
            setClassName(packageName, activity.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    private suspend fun startDirect(intent: Intent): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.w(tag, "直接启动目标应用失败", error)
        }.getOrDefault(false)
    }

    private fun runHandoff(
        packageName: String,
        appLabel: String,
        currentTask: String?
    ): PendingAppLaunch {
        val handoff = AppLaunchHandoffRegistry.register(packageName, appLabel)
        FloatingOverlayService.showLaunchHandoff(
            context = context,
            status = "等待打开应用",
            detail = "点击“打开目标应用”继续: $appLabel",
            task = currentTask ?: AgentSessionCoordinator.currentTask() ?: "当前任务",
            launchRequestId = handoff.requestId,
            appLabel = appLabel
        )
        AgentForegroundService.showLaunchHandoffNotification(
            context = context,
            launchRequestId = handoff.requestId,
            appLabel = appLabel
        )
        return handoff
    }

    private fun buildMessage(
        actualAppName: String,
        packageName: String,
        status: AppLaunchStatus,
        popupResult: PopupConfirmationResult,
        afterPackage: String?
    ): String {
        val popupMessage = when (popupResult.status) {
            PopupConfirmationStatus.CONFIRMED -> "，已自动确认弹窗: ${popupResult.buttonText}"
            PopupConfirmationStatus.UNSAFE_TO_CONFIRM -> "，检测到启动确认弹窗但未安全确认"
            PopupConfirmationStatus.NOT_DETECTED -> ""
        }
        return when (status) {
            AppLaunchStatus.TARGET_REACHED -> "应用已启动: $actualAppName ($packageName)$popupMessage"
            AppLaunchStatus.CONFIRMATION_REQUIRED -> "应用启动需要确认: $actualAppName ($packageName)$popupMessage"
            else -> "已发送启动请求: $actualAppName ($packageName)，当前包名: ${afterPackage ?: "未知"}$popupMessage"
        }
    }

    private fun AppLaunchTrace.toExecutionResult(
        actionJson: String,
        success: Boolean,
        failureType: FailureType?,
        requiresTakeover: Boolean = false
    ): ExecutionResult {
        return ExecutionResult(
            success = success,
            shouldFinish = false,
            message = message,
            actionJson = actionJson,
            requiresTakeover = requiresTakeover,
            failureType = failureType,
            launchTrace = this
        )
    }

    private companion object {
        private const val HANDOFF_TIMEOUT_MS = 180_000L
        private const val POST_LAUNCH_SETTLE_MS = 1_500L
        private const val POST_POPUP_CONFIRM_SETTLE_MS = 1_000L
    }
}
