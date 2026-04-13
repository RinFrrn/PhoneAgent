package com.mobileagent.phoneagent.harness.observe

import com.mobileagent.phoneagent.agent.ScreenObserver

interface ObservationCollector {
    suspend fun collect(): Observation
}

class DefaultObservationCollector(
    private val screenObserver: ScreenObserver
) : ObservationCollector {
    override suspend fun collect(): Observation {
        val observation = screenObserver.observe()
        return Observation(
            currentApp = observation.currentApp,
            contentItems = observation.contentItems,
            failureMessage = observation.failureMessage
        )
    }
}
