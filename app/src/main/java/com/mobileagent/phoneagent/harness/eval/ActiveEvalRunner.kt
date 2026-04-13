package com.mobileagent.phoneagent.harness.eval

import com.mobileagent.phoneagent.agent.TaskOutcome
import com.mobileagent.phoneagent.harness.spec.TaskSpec

fun interface EvalTaskExecutor {
    suspend fun execute(taskSpec: TaskSpec): TaskOutcome
}

class ActiveEvalRunner(
    private val evalRunner: EvalRunner
) {
    suspend fun runCases(
        cases: List<EvalCase>,
        executor: EvalTaskExecutor
    ): ActiveEvalReport {
        val results = mutableListOf<ActiveEvalCaseResult>()

        for (evalCase in cases) {
            val taskSpec = evalRunner.buildTaskSpec(evalCase)
            if (taskSpec == null) {
                results += ActiveEvalCaseResult(
                    caseId = evalCase.id,
                    caseName = evalCase.name,
                    executed = false,
                    taskOutcomeMessage = "缺少 taskGoal，无法主动执行",
                    traceSessionId = null,
                    evaluation = EvalCaseResult(
                        caseId = evalCase.id,
                        caseName = evalCase.name,
                        matchedSessionId = null,
                        passed = false,
                        reasons = listOf("缺少 taskGoal，无法主动执行")
                    )
                )
                continue
            }

            val outcome = executor.execute(taskSpec)
            val session = outcome.traceSessionId?.let { evalRunner.loadSessionById(it) }
            val evaluation = if (session != null) {
                evalRunner.evaluateSession(evalCase, session)
            } else {
                EvalCaseResult(
                    caseId = evalCase.id,
                    caseName = evalCase.name,
                    matchedSessionId = null,
                    passed = false,
                    reasons = listOf("执行完成，但未找到对应 trace")
                )
            }

            results += ActiveEvalCaseResult(
                caseId = evalCase.id,
                caseName = evalCase.name,
                executed = true,
                taskOutcomeMessage = outcome.message,
                traceSessionId = outcome.traceSessionId,
                evaluation = evaluation
            )
        }

        val passedCases = results.count { it.evaluation.passed }
        return ActiveEvalReport(
            generatedAt = System.currentTimeMillis(),
            totalCases = results.size,
            passedCases = passedCases,
            failedCases = results.size - passedCases,
            results = results
        )
    }
}
