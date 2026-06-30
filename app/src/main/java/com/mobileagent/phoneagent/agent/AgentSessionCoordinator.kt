package com.mobileagent.phoneagent.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object AgentSessionCoordinator {
    private const val TAG = "AgentSessionCoordinator"
    private val mutex = Mutex()

    private var stopAction: (() -> Unit)? = null
    private var currentTask: String? = null
    private var pendingUserAction: CompletableDeferred<UserActionResponse>? = null

    fun register(task: String, onStop: () -> Unit) {
        currentTask = task
        stopAction = onStop
    }

    fun clear() {
        currentTask = null
        stopAction = null
        pendingUserAction?.cancel()
        pendingUserAction = null
    }

    fun currentTask(): String? = currentTask

    fun stopCurrentTask() {
        Log.d(TAG, "请求停止当前任务")
        stopAction?.invoke()
    }

    suspend fun waitForUserConfirmation(timeoutMs: Long): UserActionResponse {
        val deferred = mutex.withLock {
            CompletableDeferred<UserActionResponse>().also { pendingUserAction = it }
        }

        val response = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: UserActionResponse.timeout()

        mutex.withLock {
            if (pendingUserAction === deferred) {
                pendingUserAction = null
            }
        }
        return response
    }

    fun confirmUserAction(answer: String? = null) {
        Log.d(TAG, "收到用户已处理确认")
        pendingUserAction?.complete(UserActionResponse(answer = answer?.trim().orEmpty()))
    }
}

data class UserActionResponse(
    val answer: String = "",
    val timedOut: Boolean = false
) {
    fun hasAnswer(): Boolean = answer.isNotBlank()

    companion object {
        fun timeout(): UserActionResponse = UserActionResponse(timedOut = true)
    }
}
