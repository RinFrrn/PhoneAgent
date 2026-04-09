package com.mobileagent.phoneagent.skill

object SkillRegistry {
    private val builtInSkills = listOf(
        AppSkill(
            id = "wechat",
            displayName = "微信",
            appKeywords = listOf("微信", "wechat"),
            launchAliases = listOf("微信", "WeChat", "wechat"),
            guidance = """
                当前在微信环境：
                1. 优先识别顶部搜索框、聊天列表、通讯录、发现、我这些固定导航。
                2. 给联系人或群发消息前，先确认进入了正确会话。
                3. 小程序、公众号、视频号页面经常有自定义返回按钮，Back 无效时优先点击左上角返回区域。
                4. 遇到权限弹窗、确认发送、登录验证等步骤，优先请求用户接管。
            """.trimIndent()
        ),
        AppSkill(
            id = "xiaohongshu",
            displayName = "小红书",
            appKeywords = listOf("小红书", "red", "rednote"),
            launchAliases = listOf("小红书", "RED"),
            guidance = """
                当前在小红书环境：
                1. 搜索结果优先区分图文、视频、商品、用户等标签，必要时先切到正确标签页。
                2. 笔记详情页返回时，优先点击左上角返回而不是盲目 Back。
                3. 如果任务是总结内容，优先进入图文笔记并记录文本信息。
            """.trimIndent()
        ),
        AppSkill(
            id = "food_delivery",
            displayName = "外卖",
            appKeywords = listOf("美团", "饿了么", "eleme", "meituan"),
            launchAliases = listOf("美团", "美团外卖", "饿了么", "eleme"),
            guidance = """
                当前在外卖环境：
                1. 下单前优先确认地址、配送时间、规格、购物车状态。
                2. 商品规格弹窗一定要确认必选项都已选择，再加入购物车。
                3. 如果购物车已有无关商品，先清理再继续执行用户任务。
            """.trimIndent()
        ),
        AppSkill(
            id = "ecommerce",
            displayName = "电商",
            appKeywords = listOf("淘宝", "京东", "拼多多", "tmall", "jd", "taobao"),
            launchAliases = listOf("淘宝", "手机淘宝", "京东", "拼多多"),
            guidance = """
                当前在电商环境：
                1. 搜索、筛选、规格、购物车、提交订单是高频路径，先确认页面所在层级再操作。
                2. 规格选择弹窗如果未完成，禁止直接提交或加入购物车。
                3. 与价格、支付、隐私相关按钮属于敏感操作，必要时通过 message 标记重要操作。
            """.trimIndent()
        ),
        AppSkill(
            id = "douyin",
            displayName = "抖音",
            appKeywords = listOf("抖音", "douyin", "tik tok", "tiktok"),
            launchAliases = listOf("抖音", "Douyin", "TikTok"),
            guidance = """
                当前在短视频环境：
                1. 先区分首页推荐、搜索页、直播间、个人主页，避免把滑动当成返回。
                2. 搜索结果页优先利用顶部筛选标签，不要在推荐流里盲目查找。
                3. 直播间、广告和购物页面容易拦截返回，必要时多尝试关闭按钮和返回按钮。
            """.trimIndent()
        )
    )

    fun buildSkillGuidance(currentApp: String?, task: String?): String? {
        val appText = currentApp.orEmpty().lowercase()
        val taskText = task.orEmpty().lowercase()
        val matches = builtInSkills.filter { skill ->
            skill.appKeywords.any { keyword ->
                val normalized = keyword.lowercase()
                appText.contains(normalized) || taskText.contains(normalized)
            }
        }

        if (matches.isEmpty()) return null

        return buildString {
            append("以下是当前任务匹配到的应用技能，请优先遵循：\n")
            matches.forEach { skill ->
                append("- ${skill.displayName} 技能\n")
                append(skill.guidance)
                append("\n")
            }
        }.trim()
    }

    fun expandLaunchCandidates(appName: String): List<String> {
        val normalized = appName.trim().lowercase()
        val matched = builtInSkills.filter { skill ->
            skill.appKeywords.any { normalized.contains(it.lowercase()) } ||
                skill.launchAliases.any { normalized.contains(it.lowercase()) }
        }
        return (listOf(appName) + matched.flatMap { it.launchAliases }).distinct()
    }
}
