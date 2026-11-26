// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Upper bound for every wait in the threading tests, so a failing test cannot hang. */
internal const val THREAD_TEST_TIMEOUT_MS = 10_000L

/**
 * Runs [body] under `runBlocking` on a dedicated thread — the engine thread of the threading
 * tests. Every wait on it is bounded; [abort] cancels the run if it did not end in time.
 */
internal class EngineThread(private val body: suspend () -> Unit) {
    private val job = Job()
    private val done = CountDownLatch(1)

    @Volatile
    var failure: Throwable? = null
        private set

    val thread: Thread = Thread {
        try {
            runBlocking(job) { body() }
        } catch (
            // Any failure of the run is recorded for the test thread to assert on.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            failure = t
        } finally {
            done.countDown()
        }
    }

    fun start() = thread.start()

    /** `true` if the run ended within [timeoutMs]. */
    fun awaitDone(timeoutMs: Long = THREAD_TEST_TIMEOUT_MS): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Cancels a run that did not end, so the test fails instead of leaking a spinning thread. */
    fun abort() {
        job.cancel()
        thread.join(THREAD_TEST_TIMEOUT_MS)
    }

    /** Waits (bounded) until the engine thread is parked, i.e. the run is suspended. */
    fun awaitParked() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(THREAD_TEST_TIMEOUT_MS)
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "engine thread never parked (state ${thread.state})" }
            Thread.yield()
        }
    }
}
