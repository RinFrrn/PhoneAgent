package com.mobileagent.phoneagent.skill

import android.content.Context
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.promptcard.PromptCardRepository

class SkillPromptAugmentor {
    fun augment(context: Context, messages: List<Message>, currentApp: String?, task: String?): List<Message> {
        val skillGuidance = SkillRegistry.buildSkillGuidance(context, currentApp, task)
        val promptCardGuidance = PromptCardRepository(context).buildGuidance(currentApp, task)
        if (skillGuidance == null && promptCardGuidance == null) {
            return messages
        }
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        return buildList {
            addAll(systemMessages)
            skillGuidance?.let { add(Message("system", it)) }
            promptCardGuidance?.let { add(Message("system", it)) }
            addAll(nonSystemMessages)
        }
    }
}
