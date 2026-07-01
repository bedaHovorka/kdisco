// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Integration tests for [Simulation.resume] — verifying that a simulation can be
 * checkpointed mid-run and resumed from a fresh [Simulation] instance, producing
 * event-for-event identical output to an uninterrupted baseline run.
 */
class SimulationResumeTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Simple discrete process that logs its firing times.
     * The loop condition (`time() < stopBefore`) allows each instance to naturally
     * start from wherever the simulation clock currently is, making reconstruction
     * straightforward: a fresh instance with the same [interval] and [stopBefore]
     * scheduled at the right absolute time resumes seamlessly.
     */
    private inner class Timer(
        val name: String,
        val interval: Double,
        val stopBefore: Double,
        val log: MutableList<String>
    ) : Process() {
        override suspend fun actions() {
            while (time() < stopBefore) {
                log.add("$name:${time()}")
                hold(interval)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    /**
     * Scenario: two timers with different intervals, no random draws.
     *
     * The test:
     * 1. Runs a baseline simulation to completion.
     * 2. Runs the same scenario to a capture point (t=5), then checks out the pending
     *    events, clock time, and RNG state.
     * 3. Creates fresh Timer instances (representing the same logical processes at their
     *    scheduled activation times) and resumes via [Simulation.resume].
     * 4. Asserts the combined log (partial + resumed) equals the baseline log.
     */
    @Test
    fun resumedSimulationProducesIdenticalEventsToBaseline() = runTest {
        val log1 = mutableListOf<String>()   // baseline
        val log2 = mutableListOf<String>()   // partial + resumed

        val stopBefore = 15.0
        val endTime = 20.0

        // --- Baseline run ---
        runSimulation(endTime = endTime) {
            Process.activate(Timer("A", 2.0, stopBefore, log1))
            Process.activate(Timer("B", 3.0, stopBefore, log1))
        }

        // --- Partial run (same parameters, up to captureTime) ---
        val captureTime = 5.0

        val timerA = Timer("A", 2.0, stopBefore, log2)
        val timerB = Timer("B", 3.0, stopBefore, log2)

        val partialSim = simulation {
            Process.activate(timerA)
            Process.activate(timerB)
        }
        partialSim.run(captureTime)

        val capturedEvents = partialSim.pendingEvents()
        val capturedClock  = partialSim.time()
        val capturedRng    = partialSim.captureRandom()

        // --- Reconstruct processes and resume ---
        // Map each captured process reference to a freshly constructed equivalent.
        val processMap: Map<Process, Process> = mapOf(
            timerA to Timer("A", 2.0, stopBefore, log2),
            timerB to Timer("B", 3.0, stopBefore, log2)
        )
        val resumedEvents = capturedEvents.map { e ->
            PendingEvent(e.time, processMap.getValue(e.process))
        }

        val resumedSim = Simulation.resume(resumedEvents, capturedClock, capturedRng)
        resumedSim.run(endTime)

        assertThat(log2).isEqualTo(log1)
    }

    /**
     * Scenario: capture and resume with a seeded RNG, verifying that random draws
     * after the resume point are identical to the baseline draws at the same times.
     */
    @Test
    fun resumedSimulationWithRandomProducesIdenticalDraws() = runTest {
        val seed = 12345L
        val log1 = mutableListOf<String>()
        val log2 = mutableListOf<String>()

        val captureTime = 4.0
        val endTime = 12.0

        // A process that logs each random draw with its simulation time.
        // After resume, the process restarts from its scheduled activation time;
        // the RNG is restored so subsequent draws match exactly.
        // (No step counter — resumed instances restart at 0 while the baseline
        // increments continuously, so including it would cause a spurious mismatch.)
        class RandomWorker(val id: Int, log: MutableList<String>) : Process() {
            val localLog = log
            override suspend fun actions() {
                while (time() < endTime - 1.0) {
                    val draw = random().uniform(0.0, 1.0)
                    localLog.add("$id@${time()}=${draw}")
                    hold(random().uniform(1.0, 2.0))
                }
            }
        }

        // --- Baseline ---
        runSimulation(endTime = endTime, seed = seed) {
            Process.activate(RandomWorker(0, log1))
            Process.activate(RandomWorker(1, log1), delay = 0.5)
        }

        // --- Partial run ---
        val w0 = RandomWorker(0, log2)
        val w1 = RandomWorker(1, log2)
        val partialSim = simulation(seed = seed) {
            Process.activate(w0)
            Process.activate(w1, delay = 0.5)
        }
        partialSim.run(captureTime)

        val capturedEvents = partialSim.pendingEvents()
        val capturedClock  = partialSim.time()
        val capturedRng    = partialSim.captureRandom()

        // --- Resume ---
        val processMap: Map<Process, Process> = mapOf(
            w0 to RandomWorker(0, log2),
            w1 to RandomWorker(1, log2)
        )
        val resumedEvents = capturedEvents.map { e ->
            PendingEvent(e.time, processMap.getValue(e.process))
        }

        Simulation.resume(resumedEvents, capturedClock, capturedRng).run(endTime)

        assertThat(log2).isEqualTo(log1)
    }

    /**
     * Verifies that the clock time of the resumed simulation is correctly set to the
     * captured clock time from the moment [run] is entered.
     */
    @Test
    fun resumedSimulationClockStartsAtCaptureTime() = runTest {
        var observedTime = -1.0

        val sentinel = object : Process() {
            override suspend fun actions() {
                observedTime = time()
            }
        }

        val rngState = Random().captureState()
        val resumedSim = Simulation.resume(
            events = listOf(PendingEvent(time = 42.0, process = sentinel)),
            clockTime = 42.0,
            randomState = rngState
        )
        resumedSim.run(100.0)

        assertThat(observedTime).isEqualTo(42.0)
    }

    /**
     * [Simulation.resume] with an optional [block] lets callers register event listeners
     * or activate additional processes alongside the restored queue.
     */
    @Test
    fun resumeBlockCanRegisterListenerAndActivateProcess() = runTest {
        val log = mutableListOf<String>()

        val processA = object : Process() {
            // Use toInt() to avoid JS vs JVM Double.toString() difference ("10" vs "10.0")
            override suspend fun actions() { log.add("A@${time().toInt()}") }
        }
        val processB = object : Process() {
            override suspend fun actions() { log.add("B@${time().toInt()}") }
        }

        val rngState = Random().captureState()
        val resumedSim = Simulation.resume(
            events   = listOf(PendingEvent(10.0, processA)),
            clockTime = 10.0,
            randomState = rngState
        ) {
            // additional process activated in the block, scheduled at t=10 + 5 = 15
            Process.activate(processB, delay = 5.0)
        }
        resumedSim.run(20.0)

        assertThat(log).isEqualTo(listOf("A@10", "B@15"))
    }

    /**
     * Verifies that [Random.captureState] / [Random.restoreState] replay an identical
     * sequence of draws.
     */
    @Test
    fun randomStateCaptureAndRestoreReplayIdenticalSequence() {
        val rng = Random(99L)
        repeat(10) { rng.uniform(0.0, 1.0) }  // advance state

        val state = rng.captureState()
        val before = List(20) { rng.uniform(0.0, 1.0) }

        rng.restoreState(state)
        val after = List(20) { rng.uniform(0.0, 1.0) }

        assertThat(after).isEqualTo(before)
    }

    /**
     * Verifies that [Random.captureState] also captures the cached Gaussian variate,
     * so [normal] draws are reproducible across restore.
     */
    @Test
    fun randomStateCapturePreservesGaussianCache() {
        val rng = Random(77L)
        // First normal() call computes and caches a second variate (Marsaglia polar).
        rng.normal(0.0, 1.0)
        // Capture while the cache may hold the second variate.
        val state = rng.captureState()
        val before = List(10) { rng.normal(0.0, 1.0) }

        rng.restoreState(state)
        val after = List(10) { rng.normal(0.0, 1.0) }

        assertThat(after).isEqualTo(before)
    }
}
