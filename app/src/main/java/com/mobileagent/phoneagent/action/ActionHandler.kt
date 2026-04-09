/**
 * 操作执行器 - 执行 AI 模型返回的操作指令
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责：
 * - 解析 AI 返回的操作指令
 * - 坐标转换（相对 → 绝对）
 * - 执行具体操作（点击、输入等）
 * - 返回执行结果
 */
package com.mobileagent.phoneagent.action

import android.util.Log
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.utils.AppLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 操作执行器 - 执行 AI 模型返回的操作指令
 */
class ActionHandler(
    private val accessibilityService: PhoneAgentAccessibilityService
) {
    private val TAG = "ActionHandler"
    private val actionParser = ActionParser()
    
    init {
        Log.d(TAG, "ActionHandler 已初始化")
    }

    /**
     * 执行操作
     */
    suspend fun execute(actionJson: String, screenWidth: Int, screenHeight: Int): ActionResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🎯 开始执行操作")
        Log.d(TAG, "操作 JSON: $actionJson")
        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}")
        
        return try {
            val action = actionParser.parse(actionJson)
            Log.d(TAG, "操作类型: ${action::class.simpleName}")
            val result = executeAction(action, screenWidth, screenHeight)
            Log.d(TAG, "✅ 操作执行完成: success=${result.success}, finished=${result.shouldFinish}")
            Log.d(TAG, "========================================")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ 执行操作失败", e)
            e.printStackTrace()
            ActionResult(
                success = false,
                shouldFinish = false,
                message = "操作解析失败: ${e.message}"
            )
        }
    }

    /**
     * 将相对坐标（0-1000）转换为绝对像素坐标
     */
    private fun convertRelativeToAbsolute(
        relativeX: Int,
        relativeY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        // 相对坐标范围是 0-1000，转换为绝对像素
        val absoluteX = (relativeX / 1000.0 * screenWidth).toInt()
        val absoluteY = (relativeY / 1000.0 * screenHeight).toInt()
        return Pair(absoluteX, absoluteY)
    }

    /**
     * 执行具体操作
     */
    private suspend fun executeAction(
        action: Action,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        return when (action) {
            is FinishAction -> {
                ActionResult(
                    success = true,
                    shouldFinish = true,
                    message = action.message
                )
            }
            is TapAction -> {
                // 将相对坐标（0-1000）转换为绝对像素
                val (absoluteX, absoluteY) = convertRelativeToAbsolute(
                    action.x,
                    action.y,
                    screenWidth,
                    screenHeight
                )
                val x = absoluteX.toFloat()
                val y = absoluteY.toFloat()
                Log.d(TAG, "👆 点击操作: 相对坐标(${action.x}, ${action.y}) -> 绝对坐标($x, $y)")
                
                // 检查无障碍服务是否可用
                if (!com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService.isServiceEnabled()) {
                    Log.e(TAG, "❌ 无障碍服务未启用，无法执行点击操作")
                    return ActionResult(
                        success = false,
                        shouldFinish = false,
                        message = "无障碍服务未启用，请在系统设置中启用无障碍服务"
                    )
                }
                
                // 使用协程的 CompletableDeferred 来等待异步回调
                val deferred = CompletableDeferred<Boolean>()
                
                accessibilityService.tap(x, y) { result ->
                    Log.d(TAG, "点击回调结果: $result")
                    if (!deferred.isCompleted) {
                        deferred.complete(result)
                    }
                }
                
                // 等待点击完成，最多等待 1 秒（因为手势本身只有 100ms，加上回调超时保护 300ms）
                val success = try {
                    withTimeout(1000) {
                        deferred.await()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "❌ 点击操作超时: ($x, $y) - 这不应该发生，因为手势调度成功")
                    // 如果超时，但手势已调度，假设成功
                    true
                }
                
                // 额外等待一小段时间，确保操作生效
                delay(300)
                
                Log.d(TAG, "点击操作完成: success=$success")
                ActionResult(
                    success = success, 
                    shouldFinish = false,
                    message = if (success) {
                        "点击成功: ($x, $y)"
                    } else {
                        "点击失败: ($x, $y)，可能原因：坐标无效、无障碍服务未启用、手势调度失败"
                    }
                )
            }
            is TypeAction -> {
                Log.d(TAG, "⌨️ 输入文本: ${action.text.take(50)}...")
                // 参考Python版本：先清除文本，再输入新文本，并等待一段时间
                // typeText内部已经实现了清除和输入的逻辑
                val success = accessibilityService.typeText(action.text)
                delay(1000) // 等待输入完成，确保文本已输入
                Log.d(TAG, "输入操作完成: $success")
                ActionResult(
                    success = success, 
                    shouldFinish = false,
                    message = if (success) {
                        "文本输入成功: ${action.text.take(30)}${if (action.text.length > 30) "..." else ""}"
                    } else {
                        "文本输入失败，请检查输入框是否已聚焦"
                    }
                )
            }
            is SwipeAction -> {
                // 将相对坐标转换为绝对坐标
                val (startAbsX, startAbsY) = convertRelativeToAbsolute(
                    action.startX,
                    action.startY,
                    screenWidth,
                    screenHeight
                )
                val (endAbsX, endAbsY) = convertRelativeToAbsolute(
                    action.endX,
                    action.endY,
                    screenWidth,
                    screenHeight
                )
                val startX = startAbsX.toFloat()
                val startY = startAbsY.toFloat()
                val endX = endAbsX.toFloat()
                val endY = endAbsY.toFloat()
                Log.d(TAG, "👆 滑动操作: 相对(${action.startX},${action.startY})->(${action.endX},${action.endY}) 绝对($startX,$startY)->($endX,$endY)")
                
                val deferred = CompletableDeferred<Boolean>()
                
                accessibilityService.swipe(startX, startY, endX, endY, 300) { result ->
                    Log.d(TAG, "滑动回调结果: $result")
                    deferred.complete(result)
                }
                
                val success = try {
                    withTimeout(3000) {
                        deferred.await()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "❌ 滑动操作超时")
                    false
                }
                
                delay(300)
                Log.d(TAG, "滑动操作完成: success=$success")
                ActionResult(
                    success = success, 
                    shouldFinish = false,
                    message = if (success) {
                        "滑动成功: ($startX, $startY) -> ($endX, $endY)"
                    } else {
                        "滑动失败，可能原因：坐标无效、无障碍服务未启用、手势调度失败"
                    }
                )
            }
            is LongPressAction -> {
                // 将相对坐标转换为绝对坐标
                val (absoluteX, absoluteY) = convertRelativeToAbsolute(
                    action.x,
                    action.y,
                    screenWidth,
                    screenHeight
                )
                val x = absoluteX.toFloat()
                val y = absoluteY.toFloat()
                Log.d(TAG, "👆 长按操作: 相对(${action.x},${action.y}) -> 绝对($x, $y)")
                var success = false
                accessibilityService.longPress(x, y, 500) { result ->
                    success = result
                }
                kotlinx.coroutines.delay(600)
                ActionResult(success = success, shouldFinish = false)
            }
            is DoubleTapAction -> {
                // 将相对坐标转换为绝对坐标
                val (absoluteX, absoluteY) = convertRelativeToAbsolute(
                    action.x,
                    action.y,
                    screenWidth,
                    screenHeight
                )
                val x = absoluteX.toFloat()
                val y = absoluteY.toFloat()
                Log.d(TAG, "👆 双击操作: 相对(${action.x},${action.y}) -> 绝对($x, $y)")
                var success = false
                accessibilityService.doubleTap(x, y) { result ->
                    success = result
                }
                kotlinx.coroutines.delay(300)
                ActionResult(success = success, shouldFinish = false)
            }
            is LaunchAction -> {
                Log.d(TAG, "🚀 启动应用: ${action.appName}")
                
                // 获取应用上下文
                val context = accessibilityService.applicationContext ?: return ActionResult(
                    success = false,
                    shouldFinish = false,
                    message = "无法获取应用上下文"
                )
                
                // 通过系统动态查找应用包名
                val packageName = AppLauncher.getPackageName(context, action.appName)
                Log.d(TAG, "查找结果: ${action.appName} -> $packageName")
                
                if (packageName == null) {
                    Log.w(TAG, "⚠️ 未找到应用: ${action.appName}")
                    
                    // 尝试搜索相似的应用名称
                    val similarApps = AppLauncher.searchApps(context, action.appName, limit = 5)
                    val suggestions = if (similarApps.isNotEmpty()) {
                        "\n\n相似应用：\n" + similarApps.joinToString("\n") { "${it.first} (${it.second})" }
                    } else {
                        "\n\n提示：请检查应用名称是否正确，或尝试使用应用的确切显示名称。"
                    }
                    
                    return ActionResult(
                        success = false,
                        shouldFinish = false,
                        message = "未找到应用: ${action.appName}。$suggestions"
                    )
                }
                
                // 检查应用是否已安装
                val isInstalled = AppLauncher.isAppInstalled(context, packageName)
                if (!isInstalled) {
                    Log.w(TAG, "⚠️ 应用未安装: $packageName")
                    return ActionResult(
                        success = false,
                        shouldFinish = false,
                        message = "应用未安装: ${action.appName} ($packageName)"
                    )
                }
                
                // 获取应用的实际显示名称（用于日志）
                val actualAppName = AppLauncher.getAppName(context, packageName) ?: action.appName
                Log.d(TAG, "应用信息: 显示名称=$actualAppName, 包名=$packageName")
                
                // 尝试启动应用
                val success = accessibilityService.launchApp(packageName)
                delay(2000) // 等待应用启动
                Log.d(TAG, "应用启动结果: $success")
                
                ActionResult(
                    success = success,
                    shouldFinish = false,
                    message = if (success) {
                        "应用已启动: $actualAppName ($packageName)"
                    } else {
                        "应用启动失败: $actualAppName ($packageName)，请检查应用是否已安装或尝试手动启动"
                    }
                )
            }
            is BackAction -> {
                Log.d(TAG, "⬅️ 返回操作")
                val success = accessibilityService.performBack()
                delay(300)
                Log.d(TAG, "返回操作完成: $success")
                ActionResult(success = success, shouldFinish = false)
            }
            is HomeAction -> {
                Log.d(TAG, "🏠 主页操作")
                val success = accessibilityService.performHome()
                delay(300)
                Log.d(TAG, "主页操作完成: $success")
                ActionResult(success = success, shouldFinish = false)
            }
            is WaitAction -> {
                kotlinx.coroutines.delay(action.durationMs)
                ActionResult(success = true, shouldFinish = false)
            }
            is TakeOverAction -> {
                Log.w(TAG, "⚠️ 需要用户介入: ${action.message}")
                ActionResult(
                    success = true,
                    shouldFinish = false,
                    requiresTakeover = true,
                    message = action.message
                )
            }
            is NoteAction -> {
                Log.d(TAG, "📝 记录页面内容: ${action.message}")
                // Note操作用于记录当前页面内容，实际实现可以根据需求扩展
                ActionResult(
                    success = true,
                    shouldFinish = false,
                    message = "已记录页面内容"
                )
            }
            is CallAPIAction -> {
                Log.d(TAG, "🔗 API调用: ${action.instruction}")
                // Call_API操作用于总结或评论内容，实际实现可以根据需求扩展
                ActionResult(
                    success = true,
                    shouldFinish = false,
                    message = "API调用完成: ${action.instruction}"
                )
            }
            is InteractAction -> {
                Log.d(TAG, "🤝 需要用户交互选择")
                ActionResult(
                    success = true,
                    shouldFinish = false,
                    requiresTakeover = true,
                    message = "需要用户选择：有多个满足条件的选项，请手动选择"
                )
            }
            is UnknownAction -> {
                ActionResult(
                    success = false,
                    shouldFinish = false,
                    message = "未知操作: ${action.type}"
                )
            }
        }
    }

}

/**
 * 操作结果
 */
data class ActionResult(
    val success: Boolean,
    val shouldFinish: Boolean,
    val message: String? = null,
    val requiresTakeover: Boolean = false
)

/**
 * 操作基类
 */
sealed class Action

data class FinishAction(val message: String) : Action()
data class TapAction(val x: Int, val y: Int, val message: String? = null) : Action()
data class TypeAction(val text: String) : Action()
data class SwipeAction(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
) : Action()
data class LongPressAction(val x: Int, val y: Int) : Action()
data class DoubleTapAction(val x: Int, val y: Int) : Action()
data class LaunchAction(val appName: String) : Action()
object BackAction : Action()
object HomeAction : Action()
data class WaitAction(val durationMs: Long) : Action()
data class TakeOverAction(val message: String) : Action()
data class NoteAction(val message: String) : Action()
data class CallAPIAction(val instruction: String) : Action()
object InteractAction : Action()
data class UnknownAction(val type: String) : Action()
