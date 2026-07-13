package com.mobileagent.phoneagent.harness.spec

data class TaskSpec(
    val id: String,
    val goal: String,
    val mode: String,
    val maxSteps: Int = 30,
    val modelProvider: String? = null,
    val modelDisplayName: String? = null,
    val modelName: String? = null,
    val modelBaseUrl: String? = null,
    val resumeContext: TaskResumeContext? = null
)

data class TaskResumeContext(
    val sourceSessionId: String,
    val completedStepCount: Int,
    val lastKnownApp: String? = null,
    val lastVerifiedSummary: String? = null
) {
    fun toMemoryText(): String {
        return buildString {
            append("这是一次异常中断后的安全续跑，新任务与旧 Trace ${sourceSessionId.take(8)} 关联。\n")
            append("旧任务已记录 $completedStepCount 个步骤。")
            lastKnownApp?.takeIf { it.isNotBlank() }?.let { append(" 中断前最后已知应用：$it。") }
            lastVerifiedSummary?.takeIf { it.isNotBlank() }?.let { append(" 最后验证摘要：$it。") }
            append("\n必须先重新观察当前屏幕并基于新观察规划；不得重放旧坐标、旧输入内容或旧截图。")
            append(" 不得假设旧动作已经生效；涉及登录、验证码、支付、隐私授权或其他敏感步骤时必须重新请求用户确认。")
        }
    }
}
