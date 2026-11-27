// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * [Simulation.stop] is one of the two members of the threading contract that may be called from
 * another thread (see the "Threading" section of [Simulation]). These tests are functional checks:
 * no test can prove the `@Volatile` publication of `stopRequested`, which stays a review item.
 */
class CrossThreadStopTest {

    /** A process that never ends on its own: one event per simulated time unit, forever. */
    private class Ticker(private val started: CountDownLatch) : Process() {
        var ticks = 0 // engine thread only; read by the test after the run has ended

        override suspend fun actions() {
            while (true) {
                ticks++
                started.countDown()
                hold(1.0)
            }
        }
    }

    @Test
    fun stopFromAnotherThreadEndsTheRun() {
        val started = CountDownLatch(1)
        val ticker = Ticker(started)
        val sim = simulation { Process.activate(ticker) }
        val runner = EngineThread { sim.run(Double.MAX_VALUE) }
        try {
            runner.start()
            assertThat(started.await(THREAD_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()

            val stopper = Thread { sim.stop() }
            stopper.start()
            stopper.join(THREAD_TEST_TIMEOUT_MS)

            assertThat(runner.awaitDone()).isTrue()
            assertThat(runner.failure).isNull()
            assertThat(sim.isStopRequested()).isTrue()
            assertThat(sim.isRunning).isFalse()
        } finally {
            runner.abort()
        }
    }

    /**
     * `stopRequested` is read at the top of each event-loop iteration, before the `beforeEvent`
     * hook. A run parked in [SimulationController.beforeEvent] therefore does not end on [Simulation.stop]
     * alone: it ends after [SimulationController.resume], once the iteration it was paused in has
     * processed its one event.
     */
    @Test
    fun stopOnAPausedRunEndsOnlyAfterResume() {
        val started = CountDownLatch(1)
        val ticker = Ticker(started)
        val controller = SimulationController()
        controller.pause()
        val sim = simulation { Process.activate(ticker) }
        val runner = EngineThread { sim.run(Double.MAX_VALUE, controller) }
        try {
            runner.start()
            runner.awaitParked()

            sim.stop()
            // Bounded negative check: the paused run must still be running after stop().
            assertThat(runner.awaitDone(timeoutMs = 200)).isFalse()
            assertThat(sim.isRunning).isTrue()

            controller.resume()
            assertThat(runner.awaitDone()).isTrue()
            assertThat(runner.failure).isNull()
            // The iteration that was paused processes exactly its one event before the loop sees stop.
            assertThat(ticker.ticks).isEqualTo(1)
        } finally {
            runner.abort()
        }
    }
}
