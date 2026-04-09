package com.mobileagent.phoneagent.agent

class AgentStateMachine {
    private var state: AgentRuntimeState = AgentRuntimeState.IDLE

    fun currentState(): AgentRuntimeState = state

    fun start() {
        state = AgentRuntimeState.RUNNING
    }

    fun markWaitingForUser() {
        state = AgentRuntimeState.WAITING_FOR_USER
    }

    fun resumeAfterUserIntervention() {
        state = AgentRuntimeState.RUNNING
    }

    fun markCompleted() {
        state = AgentRuntimeState.COMPLETED
    }

    fun markFailed() {
        state = AgentRuntimeState.FAILED
    }

    fun stop() {
        state = AgentRuntimeState.STOPPED
    }

    fun isActive(): Boolean {
        return state == AgentRuntimeState.RUNNING || state == AgentRuntimeState.WAITING_FOR_USER
    }

    fun shouldFinishLoop(stepResult: StepResult): Boolean {
        if (!stepResult.finished) {
            return false
        }

        return stepResult.action.contains("\"_metadata\":\"finish\"") ||
            stepResult.action.contains("finish(") ||
            (stepResult.message != null && stepResult.message.contains("任务完成"))
    }
}
