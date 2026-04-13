package com.mobileagent.phoneagent.harness.plan

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.agent.ResponseActionParser
import com.mobileagent.phoneagent.agent.SessionMemory
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.model.ModelClient
import com.mobileagent.phoneagent.skill.SkillPromptAugmentor

interface Planner {
    suspend fun plan(
        taskSpec: TaskSpec,
        observation: Observation,
        sessionMemory: SessionMemory
    ): PlanDecision
}

class LlmPlanner(
    private val context: Context,
    private val modelClient: ModelClient,
    private val responseActionParser: ResponseActionParser,
    private val skillPromptAugmentor: SkillPromptAugmentor
) : Planner {
    private val tag = "LlmPlanner"

    override suspend fun plan(
        taskSpec: TaskSpec,
        observation: Observation,
        sessionMemory: SessionMemory
    ): PlanDecision {
        val messagesToSend = skillPromptAugmentor.augment(
            context = context,
            messages = sessionMemory.messagesForRequest(),
            currentApp = observation.currentApp,
            task = taskSpec.goal
        )
        Log.d(tag, "开始规划，任务=${taskSpec.goal}，应用=${observation.currentApp}，消息数=${messagesToSend.size}")

        val modelResponse = modelClient.request(messagesToSend)
        val actionJson = responseActionParser.parseActionFromResponse(modelResponse.action)
        val finishRequested = actionJson.contains("\"_metadata\":\"finish\"")

        return PlanDecision(
            thinking = modelResponse.thinking,
            rawResponse = modelResponse.rawContent,
            actionJson = actionJson,
            finishRequested = finishRequested
        )
    }
}
