package com.example.cstudio.storage

// Ejecuta un guardado después de un periodo sin cambios.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AutoSaveDebouncer(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val action: suspend () -> Unit
) {
    private var pendingJob: Job? = null

    fun schedule() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMillis)
            action()
        }
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }

    suspend fun cancelAndJoin() {
        pendingJob?.cancelAndJoin()
        pendingJob = null
    }
}
