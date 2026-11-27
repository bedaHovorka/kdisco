package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PendingEventsTest {

    private abstract class NamedProcess(val name: String) : Process()

    /**
     * Runs [sim] and captures the [Simulation.pendingEvents] snapshot on the very first
     * [beforeEvent] invocation — i.e. after pending activations have been moved into the
     * event queue but before any event has been processed.
     */
    private suspend fun captureFirstSnapshot(sim: Simulation, endTime: Double): List<PendingEvent> {
        var captured: List<PendingEvent>? = null
        sim.run(endTime) {
            if (captured == null) {
                captured = sim.pendingEvents()
            }
        }
        return captured ?: emptyList()
    }

    @Test
    fun pendingEventsReturnsEmptyAfterQueueDrained() = runTest {
        val sim = Simulation.create {
            Process.activate(
                object : Process() {
                    override suspend fun actions() {
                        Unit
                    }
                },
            )
        }
        sim.run(10.0)
        assertThat(sim.pendingEvents()).isEmpty()
    }

    @Test
    fun pendingEventsOrderMatchesSchedulerDeliveryOrder() = runTest {
        // Schedule: early@t=3, normal1@t=5, normal2@t=5 (FIFO after normal1), late@t=10.
        // Expected snapshot order (= delivery order): early, normal1, normal2, late.
        val deliveredOrder = mutableListOf<String>()

        val sim = Simulation.create {
            Process.activate(
                object : NamedProcess("early") {
                    override suspend fun actions() {
                        deliveredOrder.add(name)
                    }
                },
                delay = 3.0,
            )
            Process.activate(
                object : NamedProcess("normal1") {
                    override suspend fun actions() {
                        deliveredOrder.add(name)
                    }
                },
                delay = 5.0,
            )
            Process.activate(
                object : NamedProcess("normal2") {
                    override suspend fun actions() {
                        deliveredOrder.add(name)
                    }
                },
                delay = 5.0,
            )
            Process.activate(
                object : NamedProcess("late") {
                    override suspend fun actions() {
                        deliveredOrder.add(name)
                    }
                },
                delay = 10.0,
            )
        }

        val snapshot = captureFirstSnapshot(sim, 20.0)

        assertThat(snapshot).hasSize(4)
        assertThat(snapshot.map { (it.process as NamedProcess).name })
            .isEqualTo(listOf("early", "normal1", "normal2", "late"))
        assertThat(snapshot.map { it.time })
            .isEqualTo(listOf(3.0, 5.0, 5.0, 10.0))
        // All activated via Process.activate (non-priority)
        assertThat(snapshot.all { !it.priority }).isTrue()

        // Verify the scheduler actually delivers them in the same order the snapshot predicted
        assertThat(deliveredOrder).isEqualTo(listOf("early", "normal1", "normal2", "late"))
    }

    @Test
    fun pendingEventsSnapshotDoesNotMutateQueue() = runTest {
        val sim = Simulation.create {
            repeat(3) { i ->
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            Unit
                        }
                    },
                    delay = (i + 1).toDouble(),
                )
            }
        }

        var countBefore = -1
        var countAfter = -1
        sim.run(10.0) {
            countBefore = sim.scheduledEventCount()
            sim.pendingEvents() // must not mutate the queue
            sim.pendingEvents() // repeated calls must also be safe
            countAfter = sim.scheduledEventCount()
            sim.stop() // stop after first iteration
        }

        assertThat(countAfter).isEqualTo(countBefore)
    }

    @Test
    fun pendingEventsShrinksAsEventsAreProcessed() = runTest {
        // beforeEvent is invoked before each event and once more when the queue empties.
        // With 3 events scheduled at t=1, t=2, t=3 the snapshot sizes should be 3, 2, 1, 0.
        val snapshotSizes = mutableListOf<Int>()
        val sim = Simulation.create {
            repeat(3) { i ->
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            Unit
                        }
                    },
                    delay = (i + 1).toDouble(),
                )
            }
        }
        sim.run(10.0) {
            snapshotSizes.add(sim.pendingEvents().size)
        }
        assertThat(snapshotSizes).isEqualTo(listOf(3, 2, 1, 0))
    }

    @Test
    fun pendingEventsEachEntryHasCorrectFields() = runTest {
        val p = object : NamedProcess("target") {
            override suspend fun actions() {
                Unit
            }
        }
        val sim = Simulation.create {
            Process.activate(p, delay = 7.0)
        }
        val snapshot = captureFirstSnapshot(sim, 20.0)

        assertThat(snapshot).hasSize(1)
        val entry = snapshot[0]
        assertThat(entry.process).isSameInstanceAs(p)
        assertThat(entry.time).isEqualTo(7.0)
        assertThat(entry.priority).isFalse()
        // insertionOrder is a non-negative Long for normal events
        assertThat(entry.insertionOrder).isGreaterThanOrEqualTo(0L)
    }
}
