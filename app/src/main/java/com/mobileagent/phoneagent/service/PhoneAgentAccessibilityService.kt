/**
 * 无障碍服务 - 用于模拟用户操作
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 通过 AccessibilityService 实现点击、输入、滑动等操作
 * 负责：
 * - 模拟用户操作（点击、输入、滑动等）
 * - 获取当前应用信息
 * - 启动应用
 * - 文本输入（支持多种输入方式）
 * - 获取屏幕结构化内容（无障碍模式）
 */
package com.mobileagent.phoneagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.mobileagent.phoneagent.harness.act.PopupConfirmationResult
import com.mobileagent.phoneagent.harness.act.PopupConfirmationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

data class GestureDisplayBounds(
    val width: Int,
    val height: Int,
    val source: String
) {
    fun centerToRelative(bounds: Rect): Pair<Int, Int> {
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        return Pair(pixelToRelativeX(centerX), pixelToRelativeY(centerY))
    }

    private fun pixelToRelativeX(pixel: Double): Int {
        return (pixel / width.coerceAtLeast(1) * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun pixelToRelativeY(pixel: Double): Int {
        return (pixel / height.coerceAtLeast(1) * 1000).roundToInt().coerceIn(0, 1000)
    }
}

/**
 * 无障碍服务 - 用于模拟用户操作
 * 通过 AccessibilityService 实现点击、输入、滑动等操作
 */
class PhoneAgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAgentAccessibility"
        
        // 单例实例
        @Volatile
        private var instance: PhoneAgentAccessibilityService? = null
        private val connectionListeners = CopyOnWriteArraySet<(PhoneAgentAccessibilityService?) -> Unit>()
        
        fun getInstance(): PhoneAgentAccessibilityService? = instance

        fun addConnectionListener(listener: (PhoneAgentAccessibilityService?) -> Unit) {
            connectionListeners.add(listener)
            instance?.let(listener)
        }

        fun removeConnectionListener(listener: (PhoneAgentAccessibilityService?) -> Unit) {
            connectionListeners.remove(listener)
        }
        
        /**
         * 检查无障碍服务是否已启用并可用
         */
        fun isServiceEnabled(): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "⚠️ 无障碍服务未连接")
                return false
            }
            Log.d(TAG, "✅ 无障碍服务已连接并可用")
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectionListeners.forEach { it(this) }
        Log.d(TAG, "✅ 无障碍服务已连接")
        Log.d(TAG, "服务状态: 已启用，可以执行手势操作")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        connectionListeners.forEach { it(null) }
        Log.d(TAG, "无障碍服务已销毁")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里监听界面变化
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    fun getGestureDisplayBounds(): GestureDisplayBounds {
        val windowBounds = runCatching {
            val windowManager = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return@runCatching GestureDisplayBounds(
                        width = bounds.width(),
                        height = bounds.height(),
                        source = "maximumWindowMetrics"
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return@runCatching GestureDisplayBounds(
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        source = "realDisplayMetrics"
                    )
                }
            }
            null
        }.getOrNull()
        if (windowBounds != null) {
            return windowBounds
        }

        val metrics = resources.displayMetrics
        return GestureDisplayBounds(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
            source = "resourceDisplayMetrics"
        )
    }

    /**
     * 点击指定坐标
     * 重要：坐标系统必须与截图坐标系统完全一致
     * MediaProjection 截图使用的是 displayMetrics 的尺寸（不包括导航栏，但包括状态栏）
     * 坐标 (0,0) 对应屏幕左上角（状态栏下方）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun tap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        Log.d(TAG, "准备点击坐标: ($x, $y)")
        
        // 检查服务是否可用
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "❌ Android 版本过低，不支持手势操作 (需要 API 24+)")
            callback(false)
            return
        }
        
        // 获取显示区域尺寸（与截图使用的尺寸完全一致）
        // 这是 MediaProjection 截图使用的坐标系统
        val gestureBounds = getGestureDisplayBounds()
        val screenshotWidth = gestureBounds.width.toFloat()
        val screenshotHeight = gestureBounds.height.toFloat()
        
        // 验证坐标是否在截图范围内
        if (x < 0 || x >= screenshotWidth || y < 0 || y >= screenshotHeight) {
            Log.e(TAG, "❌ 坐标超出截图范围: ($x, $y), 截图尺寸: ${screenshotWidth}x${screenshotHeight}")
            callback(false)
            return
        }
        
        // 直接使用输入的坐标，不做任何调整
        // 因为截图和点击使用相同的坐标系统
        val finalX = x
        val finalY = y
        
        Log.d(TAG, "坐标系统信息:")
        Log.d(TAG, "  - 坐标基准: ${gestureBounds.width}x${gestureBounds.height} (${gestureBounds.source})")
        Log.d(TAG, "  - 输入坐标: ($x, $y)")
        Log.d(TAG, "  - 最终坐标: ($finalX, $finalY)")
        Log.d(TAG, "  - 坐标系统: 从屏幕左上角(0,0)开始，与截图完全一致")
        
        val path = Path().apply {
            moveTo(finalX, finalY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        // 使用原子变量确保线程安全
        val callbackInvoked = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // dispatchGesture 返回 false 表示调度失败
        // 注意：第三个参数传 null 表示回调在主线程执行（系统默认行为）
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                if (callbackInvoked.compareAndSet(false, true)) {
                    Log.d(TAG, "✅ 点击手势完成回调触发: ($finalX, $finalY)")
                    callback(true)
                } else {
                    Log.w(TAG, "⚠️ 点击手势完成回调重复触发: ($finalX, $finalY)")
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                if (callbackInvoked.compareAndSet(false, true)) {
                    Log.w(TAG, "⚠️ 点击手势被取消回调触发: ($finalX, $finalY)")
                    callback(false)
                } else {
                    Log.w(TAG, "⚠️ 点击手势取消回调重复触发: ($finalX, $finalY)")
                }
            }
        }, null) // 传 null 让系统在主线程执行回调
        
        if (!dispatched) {
            Log.e(TAG, "❌ 点击手势调度失败: ($x, $y)")
            Log.e(TAG, "可能原因：1. 无障碍服务未启用 2. 手势队列已满 3. canPerformGestures 未配置")
            callback(false)
            return
        }
        
        Log.d(TAG, "✅ 点击手势已调度: ($finalX, $finalY)")
        Log.d(TAG, "等待手势回调... (手势执行时间: 100ms，超时保护: 500ms)")
        
        // 如果调度成功，但回调在合理时间内没有触发，假设手势已执行
        // 点击手势执行时间是 100ms，我们等待 500ms 后如果回调还没触发，假设成功
        // 注意：某些 Android 设备上，即使手势执行成功，回调也可能不会被调用
        // 这是 Android 系统的已知问题，所以我们需要超时保护机制
        handler.postDelayed({
            if (callbackInvoked.compareAndSet(false, true)) {
                Log.w(TAG, "⚠️ 点击手势回调超时（500ms），假设手势已执行: ($finalX, $finalY)")
                Log.w(TAG, "提示：如果手势确实执行了，这是正常的。如果手势未执行，请检查无障碍服务配置中的 canPerformGestures 属性")
                callback(true) // 假设成功，因为调度已经成功
            } else {
                Log.d(TAG, "✅ 点击手势回调已正常触发，无需超时保护")
            }
        }, 500) // 500ms 超时保护
    }

    fun performNodeClickAt(x: Float, y: Float): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "节点点击跳过：rootInActiveWindow 为 null")
            return false
        }

        val targetX = x.roundToInt()
        val targetY = y.roundToInt()
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var bestBounds = Rect()

        fun maybeSelectActionNode(node: AccessibilityNodeInfo) {
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            if (!nodeBounds.contains(targetX, targetY) || !node.isVisibleToUser) {
                return
            }

            val actionNode = findClickableActionNodeCopy(node) ?: return
            val actionBounds = Rect()
            actionNode.getBoundsInScreen(actionBounds)
            if (actionBounds.isEmpty || !actionBounds.contains(targetX, targetY)) {
                actionNode.recycleSafely()
                return
            }

            val area = actionBounds.width().toLong() * actionBounds.height().toLong()
            if (area in 1 until bestArea) {
                bestNode.recycleSafely()
                bestNode = actionNode
                bestArea = area
                bestBounds = actionBounds
            } else {
                actionNode.recycleSafely()
            }
        }

        fun traverse(node: AccessibilityNodeInfo) {
            maybeSelectActionNode(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child)
                } finally {
                    child.recycleSafely()
                }
            }
        }

        traverse(root)
        val targetNode = bestNode ?: run {
            Log.d(TAG, "未找到坐标下可点击节点，回退手势点击: ($targetX, $targetY)")
            return false
        }

        return try {
            val clickSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val focusSuccess = if (!clickSuccess && (targetNode.isFocusable || targetNode.isEditable)) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } else {
                false
            }
            val success = clickSuccess || focusSuccess
            Log.d(
                TAG,
                "节点点击${if (success) "成功" else "失败"}: ($targetX, $targetY), bounds=$bestBounds, click=$clickSuccess, focus=$focusSuccess"
            )
            success
        } finally {
            targetNode.recycleSafely()
        }
    }

    private fun findClickableActionNodeCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cursor: AccessibilityNodeInfo? = node.copySafely()
        while (cursor != null) {
            if (cursor.isVisibleToUser && cursor.isEnabled && (cursor.isClickable || cursor.isEditable)) {
                return cursor
            }
            val parent = cursor.parent
            cursor.recycleSafely()
            cursor = parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.copySafely(): AccessibilityNodeInfo? {
        return runCatching {
            @Suppress("DEPRECATION")
            AccessibilityNodeInfo.obtain(this)
        }.getOrNull()
    }

    private fun AccessibilityNodeInfo?.recycleSafely() {
        if (this == null) return
        runCatching {
            @Suppress("DEPRECATION")
            recycle()
        }
    }

    /**
     * 长按指定坐标
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun longPress(x: Float, y: Float, duration: Long = 500, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback(false)
            }
        }, null)
    }

    /**
     * 双击指定坐标
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun doubleTap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .addStroke(GestureDescription.StrokeDescription(path, 200, 100))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback(false)
            }
        }, null)
    }

    /**
     * 滑动操作
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300,
        callback: (Boolean) -> Unit
    ) {
        Log.d(TAG, "准备滑动: ($startX, $startY) -> ($endX, $endY), 时长: ${duration}ms")
        
        // 检查坐标是否有效
        val gestureBounds = getGestureDisplayBounds()
        val screenWidth = gestureBounds.width.toFloat()
        val screenHeight = gestureBounds.height.toFloat()
        
        if (startX < 0 || startX >= screenWidth || startY < 0 || startY >= screenHeight ||
            endX < 0 || endX >= screenWidth || endY < 0 || endY >= screenHeight) {
            Log.e(TAG, "❌ 滑动坐标超出屏幕范围: ($startX, $startY) -> ($endX, $endY), 坐标基准: ${gestureBounds.width}x${gestureBounds.height} (${gestureBounds.source})")
            callback(false)
            return
        }
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var callbackInvoked = false
        
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                if (!callbackInvoked) {
                    callbackInvoked = true
                    Log.d(TAG, "✅ 滑动手势完成")
                    handler.post { callback(true) }
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                if (!callbackInvoked) {
                    callbackInvoked = true
                    Log.w(TAG, "⚠️ 滑动手势被取消")
                    handler.post { callback(false) }
                }
            }
        }, handler)
        
        if (!dispatched) {
            Log.e(TAG, "❌ 滑动手势调度失败 - 可能是无障碍服务未启用或手势队列已满")
            callback(false)
        } else {
            Log.d(TAG, "✅ 滑动手势已调度")
            // 如果调度成功，但回调在合理时间内没有触发，假设手势已执行
            handler.postDelayed({
                if (!callbackInvoked) {
                    callbackInvoked = true
                    Log.w(TAG, "⚠️ 滑动手势回调超时，假设手势已执行")
                    callback(true)
                }
            }, duration + 200) // 手势时间 + 200ms 缓冲
        }
    }

    /**
     * 输入文本
     * 改进版本：查找聚焦的输入框节点，使用多种方法输入文本
     */
    fun typeText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: run {
                Log.e(TAG, "❌ rootInActiveWindow 为 null")
                return false
            }
            
            // 方法1：查找当前聚焦的输入框节点
            var focusedNode: android.view.accessibility.AccessibilityNodeInfo? = null
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            
            // 递归查找所有可编辑的节点
            fun findEditableNodes(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                
                // 检查是否是聚焦的节点
                if (node.isFocused) {
                    focusedNode = node
                }
                
                // 检查是否是可编辑的节点
                if (node.isEditable || 
                    (node.className?.toString()?.contains("EditText", ignoreCase = true) == true)) {
                    nodes.add(node)
                }
                
                // 递归查找子节点
                for (i in 0 until node.childCount) {
                    findEditableNodes(node.getChild(i))
                }
            }
            
            findEditableNodes(root)
            
            // 优先使用聚焦的节点，否则使用第一个可编辑节点
            val targetNode = if (focusedNode != null) {
                // 使用聚焦的节点，它不在 nodes 列表中
                focusedNode
            } else if (nodes.isNotEmpty()) {
                // 使用第一个可编辑节点，需要从列表中移除避免重复回收
                nodes.removeAt(0)
            } else {
                null
            }
            
            if (targetNode == null) {
                Log.e(TAG, "❌ 未找到可编辑的输入框节点")
                // 释放已收集的节点资源
                nodes.forEach { node ->
                    try {
                        node.recycle()
                    } catch (e: Exception) {
                        // 忽略已回收的节点
                    }
                }
                return false
            }
            
            Log.d(TAG, "找到输入框节点: focused=${targetNode.isFocused}, editable=${targetNode.isEditable}")
            
            // 先清除现有文本
            val clearArguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val clearSuccess = targetNode.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                clearArguments
            )
            
            if (!clearSuccess) {
                Log.w(TAG, "清除文本失败，尝试继续输入")
            }
            
            // 等待清除操作完成
            Thread.sleep(300)
            
            // 输入新文本
            val arguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = targetNode.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
            
            // 释放节点资源（先释放 targetNode，再释放剩余的 nodes）
            try {
                targetNode.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "回收 targetNode 失败（可能已回收）", e)
            }
            
            // 释放剩余的节点（不包括已使用的 targetNode）
            nodes.forEach { node ->
                try {
                    node.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "回收节点失败（可能已回收）", e)
                }
            }
            
            if (success) {
                Log.d(TAG, "✅ 文本输入成功: ${text.take(30)}${if (text.length > 30) "..." else ""}")
            } else {
                Log.e(TAG, "❌ 文本输入失败，尝试使用剪贴板方法")
                // 备选方案：使用剪贴板
                return typeTextWithClipboard(text)
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            e.printStackTrace()
            // 尝试使用剪贴板作为备选方案
            return typeTextWithClipboard(text)
        }
    }
    
    /**
     * 使用剪贴板输入文本（备选方案）
     */
    private fun typeTextWithClipboard(text: String): Boolean {
        return try {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)
            
            // 粘贴操作
            val root = rootInActiveWindow ?: return false
            val pasteArguments = android.os.Bundle().apply {
                putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 
                    android.view.accessibility.AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
            }
            
            // 查找可编辑节点并粘贴
            fun findAndPaste(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                
                if (node.isEditable || 
                    (node.className?.toString()?.contains("EditText", ignoreCase = true) == true)) {
                    // 先全选
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT)
                    Thread.sleep(100)
                    // 粘贴
                    val success = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                    node.recycle()
                    return success
                }
                
                for (i in 0 until node.childCount) {
                    if (findAndPaste(node.getChild(i))) {
                        return true
                    }
                }
                return false
            }
            
            val success = findAndPaste(root)
            if (success) {
                Log.d(TAG, "✅ 使用剪贴板输入成功")
            } else {
                Log.e(TAG, "❌ 剪贴板输入也失败")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "剪贴板输入失败", e)
            false
        }
    }

    /**
     * 清除文本（保留方法以兼容旧代码）
     */
    fun clearText(): Boolean {
        return typeText("") // 通过设置空文本来清除
    }

    /**
     * 返回键
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 主页键
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 获取当前前台应用的包名
     */
    fun getCurrentPackageName(): String? {
        return try {
            // 方法1: 使用 ActivityManager 获取前台应用（最可靠）
            val activityManager = getSystemService(ActivityManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 getRunningTasks 已被废弃，改用其他方法
                val runningTasks = activityManager.appTasks
                if (runningTasks.isNotEmpty()) {
                    // 尝试从 rootInActiveWindow 获取
                    rootInActiveWindow?.packageName?.toString()
                } else {
                    rootInActiveWindow?.packageName?.toString()
                }
            } else {
                // Android 9 及以下可以使用 getRunningTasks
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    runningTasks[0].topActivity?.packageName
                } else {
                    rootInActiveWindow?.packageName?.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取当前应用包名失败，使用备用方法", e)
            // 备用方法：使用 AccessibilityService 的 rootInActiveWindow
            rootInActiveWindow?.packageName?.toString()
        }
    }
    
    /**
     * 获取当前前台应用的名称（而不是包名）
     */
    fun getCurrentAppName(): String {
        val packageName = getCurrentPackageName() ?: return "未知应用"
        val myPackageName = this.packageName
        
        // 如果是应用自己的包名，尝试获取真实的前台应用
        if (packageName == myPackageName) {
            Log.d(TAG, "检测到应用自己的窗口，尝试获取真实前台应用...")
            return try {
                val activityManager = getSystemService(ActivityManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val runningAppProcesses = activityManager.runningAppProcesses
                    runningAppProcesses?.firstOrNull { 
                        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.pkgList?.firstOrNull() != myPackageName
                    }?.let { processInfo ->
                        val realPackageName = processInfo.pkgList?.firstOrNull()
                        if (realPackageName != null) {
                            Log.d(TAG, "找到真实前台应用: $realPackageName")
                            getAppNameFromPackage(realPackageName) ?: realPackageName
                        } else {
                            "系统桌面"
                        }
                    } ?: run {
                        // 如果找不到其他前台应用，可能是桌面
                        Log.d(TAG, "未找到其他前台应用，可能是桌面")
                        "系统桌面"
                    }
                } else {
                    "系统桌面"
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取真实前台应用失败", e)
                "系统桌面"
            }
        }
        
        // 将包名转换为应用名称
        val appName = getAppNameFromPackage(packageName) ?: packageName
        Log.d(TAG, "当前应用: $appName ($packageName)")
        return appName
    }
    
    /**
     * 根据包名获取应用名称
     */
    private fun getAppNameFromPackage(packageName: String): String? {
        return try {
            val packageManager = packageManager
            val applicationInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            Log.d(TAG, "包名 $packageName 对应应用: $appName")
            appName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "无法直接获取包名对应的应用，尝试从 Launcher 回退: $packageName", e)
            getLauncherLabelFromPackage(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "获取应用名称失败: $packageName", e)
            null
        }
    }

    private fun getLauncherLabelFromPackage(packageName: String): String? {
        return try {
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfo = packageManager
                .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .firstOrNull { it.activityInfo?.packageName == packageName }

            val label = resolveInfo?.loadLabel(packageManager)?.toString()
            if (label != null) {
                Log.d(TAG, "通过 Launcher 查询到应用名称: $packageName -> $label")
            } else {
                Log.w(TAG, "Launcher 查询仍未找到应用名称: $packageName")
            }
            label
        } catch (e: Exception) {
            Log.w(TAG, "通过 Launcher 查询应用名称失败: $packageName", e)
            null
        }
    }

    /**
     * 获取屏幕内容（无障碍模式）
     * 返回屏幕上的所有文本、控件信息等结构化数据
     */
    fun getScreenContent(): String {
        return try {
            val root = rootInActiveWindow ?: return "无法获取屏幕内容：rootInActiveWindow 为 null"
            
            val content = StringBuilder()
            content.append("=== 屏幕内容 ===\n\n")
            
            // 获取当前应用信息
            val currentApp = getCurrentAppName()
            val packageName = getCurrentPackageName() ?: "未知"
            content.append("当前应用: $currentApp ($packageName)\n\n")
            val gestureBounds = getGestureDisplayBounds()
            content.append("坐标基准: ${gestureBounds.width}x${gestureBounds.height} (${gestureBounds.source})，坐标范围 0-1000\n\n")
            
            // 递归遍历所有节点，提取文本和控件信息
            fun traverseNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null) return
                
                val indent = "  ".repeat(depth)
                val className = node.className?.toString() ?: "未知"
                val text = node.text?.toString()?.trim()
                val contentDescription = node.contentDescription?.toString()?.trim()
                val viewId = node.viewIdResourceName
                val isClickable = node.isClickable
                val isFocusable = node.isFocusable
                val isEditable = node.isEditable
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                
                // 只输出有意义的节点（有文本、可点击、可编辑等）
                val hasContent = text != null && text.isNotEmpty() ||
                                contentDescription != null && contentDescription.isNotEmpty() ||
                                isClickable || isEditable || viewId != null
                
                if (hasContent) {
                    content.append("$indent- ")
                    
                    // 节点类型
                    when {
                        isEditable -> content.append("[输入框] ")
                        isClickable -> content.append("[按钮] ")
                        className.contains("TextView", ignoreCase = true) -> content.append("[文本] ")
                        className.contains("ImageView", ignoreCase = true) -> content.append("[图片] ")
                        else -> content.append("[控件] ")
                    }
                    
                    // 文本内容
                    if (text != null && text.isNotEmpty()) {
                        content.append("文本: $text")
                    } else if (contentDescription != null && contentDescription.isNotEmpty()) {
                        content.append("描述: $contentDescription")
                    }
                    
                    // 坐标信息（相对坐标 0-1000）
                    val (centerX, centerY) = gestureBounds.centerToRelative(bounds)
                    content.append(" | 坐标: [$centerX, $centerY]")
                    content.append(" | bounds: [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
                    
                    // 可操作信息
                    val actions = mutableListOf<String>()
                    if (isClickable) actions.add("可点击")
                    if (isEditable) actions.add("可编辑")
                    if (isFocusable) actions.add("可聚焦")
                    if (actions.isNotEmpty()) {
                        content.append(" | ${actions.joinToString(", ")}")
                    }
                    
                    content.append("\n")
                }
                
                // 递归处理子节点
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverseNode(child, depth + 1)
                        child.recycle()
                    }
                }
            }
            
            traverseNode(root)
            content.append("\n=== 内容结束 ===\n")
            
            val result = content.toString()
            Log.d(TAG, "获取屏幕内容成功，长度: ${result.length}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕内容失败", e)
            "获取屏幕内容失败: ${e.message}"
        }
    }

    /**
     * 启动应用
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            Log.d(TAG, "尝试启动应用: $packageName")
            
            // 方法1: 使用 getLaunchIntentForPackage（推荐）
            var intent = packageManager.getLaunchIntentForPackage(packageName)
            
            if (intent != null) {
                Log.d(TAG, "找到启动 Intent")
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                Log.d(TAG, "✅ 应用启动成功（方法1）")
                return true
            }
            
            // 方法2: 如果方法1失败，尝试使用主 Activity
            Log.d(TAG, "方法1失败，尝试方法2...")
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val launcherActivities = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            val launcherActivity = launcherActivities.firstOrNull()
            if (launcherActivity != null) {
                intent = android.content.Intent(launcherIntent).apply {
                    setClassName(packageName, launcherActivity.activityInfo.name)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                startActivity(intent)
                Log.d(TAG, "✅ 应用启动成功（方法2: Launcher Activity）")
                return true
            }
            
            // 方法3: 使用系统启动器
            Log.d(TAG, "方法2失败，尝试方法3...")
            intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved != null) {
                intent.setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                startActivity(intent)
                Log.d(TAG, "✅ 应用启动成功（方法3: resolveActivity）")
                true
            } else {
                Log.e(TAG, "❌ 未找到可启动的 Launcher Activity: $packageName")
                false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "❌ 应用未安装: $packageName", e)
            false
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "❌ 找不到启动 Activity: $packageName", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 启动应用权限不足: $packageName", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动应用失败: $packageName", e)
            e.printStackTrace()
            false
        }
    }

    fun confirmLaunchPopupIfPresent(targetPackage: String): PopupConfirmationResult {
        val root = rootInActiveWindow ?: return PopupConfirmationResult(
            status = PopupConfirmationStatus.NOT_DETECTED,
            reason = "rootInActiveWindow 为空"
        )
        val popupPackage = root.packageName?.toString()
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            nodes.add(node)
            for (i in 0 until node.childCount) {
                collect(node.getChild(i))
            }
        }

        return try {
            collect(root)
            val visibleText = nodes
                .mapNotNull { node -> node.text?.toString() ?: node.contentDescription?.toString() }
                .joinToString(" ")
            val looksLikeLaunchDialog = containsAny(
                visibleText,
                listOf("打开", "启动", "允许", "继续", "跳转", "前往", "应用", "Phone Agent")
            )
            if (!looksLikeLaunchDialog) {
                return PopupConfirmationResult(
                    status = PopupConfirmationStatus.NOT_DETECTED,
                    popupPackage = popupPackage,
                    reason = "未发现启动确认弹窗"
                )
            }

            val buttonNode = nodes.firstOrNull { node ->
                node.isClickable &&
                    isPositiveLaunchConfirmation(node.text?.toString(), node.contentDescription?.toString())
            }
            if (buttonNode == null) {
                return PopupConfirmationResult(
                    status = PopupConfirmationStatus.UNSAFE_TO_CONFIRM,
                    popupPackage = popupPackage,
                    reason = "发现疑似启动弹窗，但没有明确确认按钮"
                )
            }

            val label = buttonNode.text?.toString()
                ?: buttonNode.contentDescription?.toString()
                ?: "确认"
            val clicked = buttonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                PopupConfirmationResult(
                    status = PopupConfirmationStatus.CONFIRMED,
                    buttonText = label,
                    popupPackage = popupPackage
                )
            } else {
                PopupConfirmationResult(
                    status = PopupConfirmationStatus.UNSAFE_TO_CONFIRM,
                    buttonText = label,
                    popupPackage = popupPackage,
                    reason = "确认按钮点击失败"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理启动确认弹窗失败: $targetPackage", e)
            PopupConfirmationResult(
                status = PopupConfirmationStatus.UNSAFE_TO_CONFIRM,
                popupPackage = popupPackage,
                reason = e.message
            )
        }
    }

    private fun isPositiveLaunchConfirmation(text: String?, contentDescription: String?): Boolean {
        val label = listOfNotNull(text, contentDescription).joinToString(" ").trim()
        if (label.isBlank()) return false
        if (containsAny(label, listOf("取消", "拒绝", "不允许", "禁止", "稍后", "否"))) {
            return false
        }
        return containsAny(
            label,
            listOf("允许", "打开", "继续", "确认", "确定", "始终允许", "前往", "去开启", "启动")
        )
    }

    private fun containsAny(text: String, tokens: List<String>): Boolean {
        val normalized = text.lowercase()
        return tokens.any { normalized.contains(it.lowercase()) }
    }
}
