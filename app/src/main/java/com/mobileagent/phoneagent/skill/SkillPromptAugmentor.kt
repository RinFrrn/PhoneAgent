package com.mobileagent.phoneagent.skill

import android.content.Context
import com.mobileagent.phoneagent.model.Message

class SkillPromptAugmentor {
    fun augment(context: Context, messages: List<Message>, currentApp: String?, task: String?): List<Message> {
        val skillGuidance = SkillRegistry.buildSkillGuidance(context, currentApp, task) ?: return messages
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        return buildList {
            addAll(systemMessages)
            add(Message("system", skillGuidance))
            addAll(nonSystemMessages)
        }
    }
}
