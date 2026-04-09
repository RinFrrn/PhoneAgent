package com.mobileagent.phoneagent.agent

import android.graphics.Bitmap
import android.util.Log
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.ImageUrl
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.utils.ScreenshotUtils

data class ScreenObservation(
    val contentItems: List<ContentItem>,
    val currentApp: String,
    val failureMessage: String? = null
)

class ScreenObserver(
    private val mode: Mode,
    private val accessibilityService: PhoneAgentAccessibilityService,
    private val screenshotProvider: suspend () -> Bitmap?
) {
    private val tag = "ScreenObserver"

    suspend fun observe(): ScreenObservation {
        Log.d(tag, "当前模式: $mode")
        val contentItems = mutableListOf<ContentItem>()

        if (mode == Mode.VISION || mode == Mode.HYBRID) {
            Log.d(tag, "正在截图...")
            val screenshot = screenshotProvider()
            if (screenshot == null) {
                Log.e(tag, "截图失败")
                if (mode == Mode.VISION) {
                    return ScreenObservation(
                        contentItems = emptyList(),
                        currentApp = accessibilityService.getCurrentAppName(),
                        failureMessage = "截图失败"
                    )
                }
                Log.w(tag, "混合模式截图失败，继续使用无障碍内容")
            } else {
                Log.d(tag, "截图成功: ${screenshot.width}x${screenshot.height}")
                val imageBase64 = ScreenshotUtils.bitmapToBase64(screenshot)
                val imageUrl = "data:image/png;base64,$imageBase64"
                Log.d(tag, "图片 Base64 长度: ${imageBase64.length}")
                contentItems.add(
                    ContentItem(
                        type = "image_url",
                        imageUrl = ImageUrl(url = imageUrl)
                    )
                )
            }
        }

        if (mode == Mode.ACCESSIBILITY || mode == Mode.HYBRID) {
            Log.d(tag, "正在获取无障碍屏幕内容...")
            val screenContent = accessibilityService.getScreenContent()
            Log.d(tag, "获取屏幕内容成功，长度: ${screenContent.length}")
            contentItems.add(
                ContentItem(
                    type = "text",
                    text = screenContent
                )
            )
        }

        val currentApp = accessibilityService.getCurrentAppName()
        if (contentItems.isEmpty()) {
            Log.e(tag, "未获取到任何屏幕数据")
            return ScreenObservation(
                contentItems = emptyList(),
                currentApp = currentApp,
                failureMessage = "未获取到屏幕数据"
            )
        }

        return ScreenObservation(
            contentItems = contentItems,
            currentApp = currentApp
        )
    }
}
