// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Pins the engine-level semantics that [Simulation.resume] depends on.
 *
 * [SimulationResumeTest] covers the end-to-end "resumed run matches the baseline" property.
 * These tests instead pin the individual guarantees that make it hold, each of which was
 * changed to support checkpointing and would otherwise regress silently:
 *
 * - the captured queue survives a bounded run (`run()` no longer discards the first event
 *   past `endTime`),
 * - process counts after a run still behave as issue #59 requires,
 * - equal-time ordering — both FIFO and priority/LIFO — round-trips through a resume,
 * - activation delays inside [Simulation.resume]'s block are relative to the restored clock.
 */
class SimulationCheckpointSemanticsTest {

    private class Recorder(val name: String, val log: MutableList<String>) : Process() {
        override suspend fun actions() {
            log.add(name)
        }
    }

    private fun noop() = object : Process() {
        override suspend fun actions() {}
    }

    // ---------------------------------------------------------------------------
    // run() must not discard the first event past endTime
    // ---------------------------------------------------------------------------

    /**
     * A bounded run stops *before* consuming the first event past `endTime`, leaving it in
     * the queue. Without this, a checkpoint taken after a bounded run silently omits exactly
     * one event — the next one due — and the resumed run diverges with no error.
     */
    @Test
    fun boundedRunLeavesTheFirstEventPastEndTimeQueued() = runTest {
        val sim = Simulation.create {
            Process.activate(noop(), delay = 15.0)
        }
        sim.run(10.0)

        assertThat(sim.scheduledEventCount()).isEqualTo(1)
        val pending = sim.pendingEvents()
        assertThat(pending.size).isEqualTo(1)
        assertThat(pending[0].time).isEqualTo(15.0)
    }

    /**
     * The counterpart to the above: a run whose queue drains naturally must still end with
     * nothing queued and no active processes. This is the invariant from issue #59
     * ("activeProcessCount() never returns to 0 after a simulation completes"), re-pinned
     * here because the `hold()` cancellation handler no longer clears the queue itself.
     */
    @Test
    fun naturallyCompletedRunEndsWithNothingQueued() = runTest {
        val sim = Simulation.create {
            repeat(3) { i -> Process.activate(noop(), delay = (i + 1).toDouble()) }
        }
        sim.run(100.0)

        assertThat(sim.scheduledEventCount()).isEqualTo(0)
        assertThat(sim.activeProcessCount()).isEqualTo(0)
    }

    // ---------------------------------------------------------------------------
    // Equal-time ordering round-trips through a resume
    // ---------------------------------------------------------------------------

    /**
     * Priority events are LIFO among equal-time events and run ahead of normal ones.
     * Restoring must preserve that.
     *
     * This is the case a `PendingEvent` carrying only `(time, process)` cannot express: with
     * the priority flag dropped, every restored event becomes a normal event and this order
     * silently flattens to insertion order. Re-scheduling via `EventQueue.schedule` is not
     * enough either — it allocates fresh counters, which reverses the LIFO group — hence
     * `EventQueue.restore`.
     */
    @Test
    fun resumePreservesPriorityAndFifoOrderingAtEqualTimes() = runTest {
        val log = mutableListOf<String>()

        // Delivery order for four events all at t=5.0:
        //   priority events first, LIFO among themselves (most-negative order first),
        //   then normal events, FIFO among themselves.
        val captured = listOf(
            PendingEvent(Recorder("prio2", log), 5.0, priority = true, insertionOrder = -2L),
            PendingEvent(Recorder("prio1", log), 5.0, priority = true, insertionOrder = -1L),
            PendingEvent(Recorder("norm1", log), 5.0, priority = false, insertionOrder = 0L),
            PendingEvent(Recorder("norm2", log), 5.0, priority = false, insertionOrder = 1L),
        )

        // Supplied deliberately shuffled: restore is position-independent.
        val resumed = Simulation.resume(captured.shuffled(), clockTime = 5.0, randomState = Random(1L).captureState())

        assertThat(resumed.pendingEvents().map { (it.process as Recorder).name })
            .isEqualTo(listOf("prio2", "prio1", "norm1", "norm2"))

        resumed.run(50.0)
        assertThat(log).isEqualTo(listOf("prio2", "prio1", "norm1", "norm2"))
    }

    /**
     * A capture taken from a live simulation, restored into a fresh one, must present the
     * queue in the same order — including the `insertionOrder` values themselves, so a
     * second capture of the resumed run is identical to the first.
     */
    @Test
    fun captureRestoreCaptureIsStable() = runTest {
        val sim = Simulation.create {
            repeat(4) { i -> Process.activate(noop(), delay = 10.0 + i) }
        }
        sim.run(1.0)

        val first = sim.pendingEvents()
        val resumed = Simulation.resume(first, clockTime = sim.time(), randomState = sim.captureRandom())
        val second = resumed.pendingEvents()

        assertThat(second.map { it.time }).isEqualTo(first.map { it.time })
        assertThat(second.map { it.priority }).isEqualTo(first.map { it.priority })
        assertThat(second.map { it.insertionOrder }).isEqualTo(first.map { it.insertionOrder })
    }

    // ---------------------------------------------------------------------------
    // Activation inside resume()'s block is relative to the restored clock
    // ---------------------------------------------------------------------------

    /**
     * A resumed simulation starts at a non-zero clock, so a pending activation's `delay` must
     * be applied relative to that clock, not treated as an absolute time. On a fresh
     * simulation the two are indistinguishable because the clock starts at 0.0, which is why
     * this only shows up once resume exists.
     */
    @Test
    fun activationDelayInResumeBlockIsRelativeToRestoredClock() = runTest {
        val firedAt = mutableListOf<Double>()

        val resumed = Simulation.resume(
            events = emptyList(),
            clockTime = 100.0,
            randomState = Random(7L).captureState(),
        ) {
            Process.activate(
                object : Process() {
                    override suspend fun actions() {
                        firedAt.add(time())
                    }
                },
                delay = 5.0,
            )
        }
        resumed.run(200.0)

        assertThat(firedAt).isEqualTo(listOf(105.0))
    }
}
