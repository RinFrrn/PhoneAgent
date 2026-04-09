package com.mobileagent.phoneagent.skill

import com.mobileagent.phoneagent.model.Message

class SkillPromptAugmentor {
    fun augment(messages: List<Message>, currentApp: String?, task: String?): List<Message> {
        val skillGuidance = SkillRegistry.buildSkillGuidance(currentApp, task) ?: return messages
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        return buildList {
            addAll(systemMessages)
            add(Message("system", skillGuidance))
            addAll(nonSystemMessages)
        }
    }
}
