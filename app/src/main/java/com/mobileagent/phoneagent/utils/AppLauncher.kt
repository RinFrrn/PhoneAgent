/**
 * 应用启动器 - 通过系统动态获取应用信息
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责：
 * - 根据应用名称查找包名
 * - 启动应用
 * - 应用信息缓存
 */
package com.mobileagent.phoneagent.utils

import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.mobileagent.phoneagent.skill.SkillRegistry

/**
 * 应用启动器 - 通过系统动态获取应用信息
 */
object AppLauncher {
    private const val TAG = "AppLauncher"
    
    // 缓存：应用名称 -> 包名（避免重复查询）
    private val appNameCache = mutableMapOf<String, String?>()
    
    /**
     * 根据应用名称获取包名（通过系统动态查询）
     * 支持精确匹配和模糊匹配
     */
    fun getPackageName(context: Context, appName: String): String? {
        val candidateNames = SkillRegistry.expandLaunchCandidates(context, appName)
        candidateNames.forEach { candidate ->
            val packageName = getPackageNameInternal(context, candidate)
            if (packageName != null) {
                return packageName
            }
        }
        return null
    }

    private fun getPackageNameInternal(context: Context, appName: String): String? {
        // 先检查缓存
        val cacheKey = appName.lowercase().trim()
        if (appNameCache.containsKey(cacheKey)) {
            val cached = appNameCache[cacheKey]
            Log.d(TAG, "从缓存获取: $appName -> $cached")
            return cached
        }
        
        Log.d(TAG, "开始查找应用: $appName")
        
        val packageManager = context.packageManager
        val launcherApps = queryLauncherApps(packageManager)
        
        val normalizedAppName = appName.trim().lowercase()
        
        // 精确匹配
        for (resolveInfo in launcherApps) {
            try {
                val label = resolveInfo.loadLabel(packageManager).toString()
                
                if (label.equals(appName, ignoreCase = true) || 
                    label.lowercase() == normalizedAppName) {
                    val packageName = resolveInfo.activityInfo.packageName
                    Log.d(TAG, "✅ 精确匹配: $appName -> $packageName (显示名称: $label)")
                    appNameCache[cacheKey] = packageName
                    return packageName
                }
            } catch (e: Exception) {
                // 忽略无法获取标签的应用
                continue
            }
        }
        
        // 模糊匹配：包含关键词
        var bestMatch: String? = null
        var bestScore = 0
        
        for (resolveInfo in launcherApps) {
            try {
                val label = resolveInfo.loadLabel(packageManager).toString()
                val labelLower = label.lowercase()
                val packageName = resolveInfo.activityInfo.packageName
                
                // 计算匹配分数
                var score = 0
                if (labelLower.contains(normalizedAppName)) {
                    score += 10
                    // 如果开头匹配，分数更高
                    if (labelLower.startsWith(normalizedAppName)) {
                        score += 5
                    }
                    // 如果完全包含，分数更高
                    if (normalizedAppName.length >= 3 && labelLower.contains(normalizedAppName)) {
                        score += normalizedAppName.length
                    }
                }
                
                // 检查包名是否包含关键词（作为备选）
                if (packageName.lowercase().contains(normalizedAppName.replace(" ", ""))) {
                    score += 2
                }
                
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = packageName
                    Log.d(TAG, "找到模糊匹配: $appName -> $packageName (显示名称: $label, 分数: $score)")
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        if (bestMatch != null && bestScore >= 5) {
            Log.d(TAG, "✅ 模糊匹配成功: $appName -> $bestMatch (分数: $bestScore)")
            appNameCache[cacheKey] = bestMatch
            return bestMatch
        }
        
        Log.w(TAG, "❌ 未找到应用: $appName")
        appNameCache[cacheKey] = null
        return null
    }
    
    /**
     * 根据包名获取应用名称
     */
    fun getAppName(context: Context, packageName: String): String? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "无法找到包名对应的应用: $packageName", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "获取应用名称失败: $packageName", e)
            null
        }
    }
    
    /**
     * 检查应用是否已安装
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * 搜索应用（返回匹配的应用列表，供AI选择）
     */
    fun searchApps(context: Context, keyword: String, limit: Int = 10): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val packageManager = context.packageManager
        val launcherApps = queryLauncherApps(packageManager)
        val normalizedKeyword = keyword.trim().lowercase()
        
        for (resolveInfo in launcherApps) {
            try {
                val label = resolveInfo.loadLabel(packageManager).toString()
                val labelLower = label.lowercase()
                val packageName = resolveInfo.activityInfo.packageName
                
                if (labelLower.contains(normalizedKeyword) || 
                    packageName.lowercase().contains(normalizedKeyword)) {
                    results.add(Pair(label, packageName))
                    if (results.size >= limit) {
                        break
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        return results
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        appNameCache.clear()
        Log.d(TAG, "应用名称缓存已清除")
    }

    private fun queryLauncherApps(packageManager: PackageManager): List<ResolveInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
    }
}
