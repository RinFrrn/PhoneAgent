package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.harness.spec.TaskSpec

data class HarnessSession(
    val taskSpec: TaskSpec,
    var stepCount: Int = 0
) {
    fun nextStepIndex(): Int {
        stepCount += 1
        return stepCount
    }
}
