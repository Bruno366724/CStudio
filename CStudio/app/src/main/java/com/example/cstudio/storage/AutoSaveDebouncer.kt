package com.example.cstudio.storage

// Ejecuta un guardado después de un periodo sin cambios.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AutoSaveDebouncer(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val action: suspend () -> Unit
) {
    private var pendingJob: Job? = null
    private var runningJob: Job? = null
    private var rerun = false

    fun schedule() {
        if (runningJob?.isActive == true) {
            rerun = true
            return
        }
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMillis)
            pendingJob = null
            runningJob = currentCoroutineContext()[Job]
            try {
                action()
            } finally {
                runningJob = null
                if (rerun) {
                    rerun = false
                    schedule()
                }
            }
        }
    }

    fun cancel() {
        rerun = false
        if (runningJob == null) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }

    suspend fun cancelAndJoin() {
        rerun = false
        val waitingJob = pendingJob
        pendingJob = null
        waitingJob?.cancelAndJoin()
        runningJob?.join()
    }
}
