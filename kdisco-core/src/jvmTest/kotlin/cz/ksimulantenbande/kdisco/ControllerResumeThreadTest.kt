// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Pins point 3 of the "Threading" section of [Simulation]: under `runBlocking`, a run paused in
 * [SimulationController.beforeEvent] and resumed from another thread continues on the thread
 * that called [Simulation.run], not on the thread that called [SimulationController.resume].
 */
class ControllerResumeThreadTest {

    @Test
    fun processBodiesRunOnTheRunThreadBeforeAndAfterPauseResume() {
        val controller = SimulationController()
        val paused = CountDownLatch(1)
        val bodyThreads = mutableListOf<Thread>() // engine thread only; read after the run ended
        val sim = simulation {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    bodyThreads += Thread.currentThread()
                    hold(1.0)
                    bodyThreads += Thread.currentThread()
                    controller.pause()
                    paused.countDown()
                    hold(1.0) // the run parks in beforeEvent before delivering this wake-up
                    bodyThreads += Thread.currentThread()
                    hold(1.0)
                    bodyThreads += Thread.currentThread()
                }
            })
        }
        val runner = EngineThread { sim.run(10.0, controller) }
        runner.start()
        assertThat(paused.await(THREAD_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
        runner.awaitParked()

        val resumer = Thread { controller.resume() }
        resumer.start()
        resumer.join(THREAD_TEST_TIMEOUT_MS)

        val ended = runner.awaitDone()
        if (!ended) runner.abort()
        assertThat(ended).isTrue()
        assertThat(runner.failure).isNull()
        assertThat(resumer).isNotSameInstanceAs(runner.thread)
        val runThread = runner.thread
        assertThat(bodyThreads).containsExactly(runThread, runThread, runThread, runThread)
        assertThat(sim.time()).isEqualTo(3.0)
    }
}
