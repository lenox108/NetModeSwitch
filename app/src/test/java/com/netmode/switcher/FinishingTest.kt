package com.netmode.switcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FinishingTest {
    @Test fun aCancelledJobStillFinishesItsWorkAndReportsNoFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var result: Result<String>? = null
        val job = launch(Dispatchers.Default) {
            result = finishing {
                started.complete(Unit)
                release.await()
                "готово"
            }
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("готово", result?.getOrNull())
    }

    @Test fun aRealFailureIsStillReported() = runBlocking {
        val result = finishing<String> { error("нет связи") }
        assertEquals("нет связи", result.exceptionOrNull()?.message)
    }
}
