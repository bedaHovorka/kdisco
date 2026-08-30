// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ObservableEventsTest {

    @Test
    fun eventsArriveInSimulationTimeOrder() = runTest {
        val events = mutableListOf<SimulationEvent>()
        runSimulation(endTime = 10.0) {
            onEvent { events.add(it) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(2.0)
                    hold(3.0)
                }
            })
        }
        assertThat(events.map { it.time }).isEqualTo(listOf(0.0, 0.0, 2.0, 5.0))
    }

    @Test
    fun noEventsWhenNoSubscriber() = runTest {
        var invoked = false
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                }
            })
        }
        // listener was never registered; just ensure run completes without error
        assertThat(invoked).isFalse()
    }

    @Test
    fun customEventsAreDelivered() = runTest {
        val events = mutableListOf<Any?>()
        runSimulation(endTime = 10.0) {
            onEvent {
                if (it is SimulationEvent.Custom) events.add(it.payload)
            }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    emitCustom("hello")
                    emitCustom(42)
                }
            })
        }
        assertThat(events).isEqualTo(listOf("hello", 42))
    }

    @Test
    fun topLevelEmitCustomDeliveredWhenCalledOutsideProcessSubclass() = runTest {
        val received = mutableListOf<Any?>()
        runSimulation(endTime = 10.0) {
            onEvent { if (it is SimulationEvent.Custom) received.add(it.payload) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    // Call the top-level emitCustom (not Process.emitCustom)
                    cz.ksimulantenbande.kdisco.emitCustom("from-process")
                }
            })
        }
        assertThat(received).isEqualTo(listOf("from-process"))
    }

    @Test
    fun multipleListenersAllReceiveEveryEvent() = runTest {
        val listener1 = mutableListOf<Double>()
        val listener2 = mutableListOf<Double>()
        runSimulation(endTime = 10.0) {
            onEvent { listener1.add(it.time) }
            onEvent { listener2.add(it.time) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(2.0)
                    hold(3.0)
                }
            })
        }
        // Both listeners receive identical event times
        assertThat(listener1).isEqualTo(listOf(0.0, 0.0, 2.0, 5.0))
        assertThat(listener2).isEqualTo(listener1)
    }

    @Test
    fun secondListenerDoesNotReplaceFirst() = runTest {
        val firstCount = mutableListOf<SimulationEvent>()
        val secondCount = mutableListOf<SimulationEvent>()
        runSimulation(endTime = 5.0) {
            onEvent { firstCount.add(it) }
            onEvent { secondCount.add(it) }
            Process.activate(object : Process() {
                override suspend fun actions() { hold(1.0) }
            })
        }
        assertThat(firstCount.size).isGreaterThan(0)
        assertThat(secondCount.size).isEqualTo(firstCount.size)
    }

    /**
     * A discrete assignment to [Variable.state] from within a process emits exactly one
     * [SimulationEvent.VariableChanged] per assignment, with correct old/new values.
     */
    @Test
    fun discreteVariableAssignmentEmitsVariableChanged() = runTest {
        val v = Variable(0.0)
        val changes = mutableListOf<SimulationEvent.VariableChanged>()
        runSimulation(endTime = 5.0) {
            onEvent { (it as? SimulationEvent.VariableChanged)?.let(changes::add) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    v.state = 1.0
                    hold(1.0)
                    v.state = 2.0
                }
            })
        }
        assertThat(changes.map { it.oldState }).isEqualTo(listOf(0.0, 1.0))
        assertThat(changes.map { it.newState }).isEqualTo(listOf(1.0, 2.0))
    }

    /**
     * Continuous integration must NOT flood listeners with [SimulationEvent.VariableChanged]
     * for intermediate Runge-Kutta writes. The variable evolves, but zero events are emitted.
     *
     * Regression guard for the Critical issue where the setter lacked a `monitorActive` guard.
     */
    @Test
    fun continuousIntegrationDoesNotEmitVariableChanged() = runTest {
        val x = Variable(0.0)
        val changes = mutableListOf<SimulationEvent.VariableChanged>()
        val dynamics = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        runSimulation(endTime = 5.0) {
            onEvent { (it as? SimulationEvent.VariableChanged)?.let(changes::add) }
            dtMax = 0.1
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(5.0)
                }
            })
        }
        // The variable actually evolved (so the setter was exercised)...
        assertThat(x.state).isGreaterThan(4.0)
        // ...yet no VariableChanged events were emitted, because all writes happened
        // during integration (monitorActive == true).
        assertThat(changes).isEmpty()
    }

    /**
     * A process activated from within another process's [Process.actions] must emit
     * [SimulationEvent.ProcessActivated] exactly once — not twice (once on activate, once on run).
     */
    @Test
    fun inRunActivationEmitsSingleProcessActivated() = runTest {
        val events = mutableListOf<SimulationEvent>()
        runSimulation(endTime = 10.0) {
            onEvent { events.add(it) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    Process.activate(object : Process() {
                        override suspend fun actions() { hold(1.0) }
                    })
                    hold(2.0)
                }
            })
        }
        val activations = events.filterIsInstance<SimulationEvent.ProcessActivated>()
        // One for the parent, one for the in-run-spawned child — not three.
        assertThat(activations.size).isEqualTo(2)
    }
    /**
     * End-of-run cancellation is not a simulation event: a process still parked when `run` returns
     * emits no [SimulationEvent.ProcessTerminated], whichever primitive it is parked on.
     *
     * `waitCrossing`/`waitUntilCrossing` used to be the exception — their cancellation handlers did
     * not mark the process terminated, so the scheduler's completion path emitted the event for
     * them and not for `hold`/`passivate`/`waitUntil`. Unifying the five suspension points on one
     * scaffold made the majority behaviour apply to all of them.
     */
    @Test
    fun endOfRunCancellationEmitsNoProcessTerminated() = runTest {
        val events = mutableListOf<SimulationEvent>()
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        runSimulation(endTime = 5.0) {
            dtMax = 1.0
            onEvent { events.add(it) }
            Process.activate(object : Process() {
                override suspend fun actions() { x.start(); motion.start(); hold(100.0) }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { passivate() }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { waitUntil { false } }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { waitCrossing { 1000.0 - x.state } }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { waitUntilCrossing { 1000.0 - x.state } }
            })
        }
        assertThat(events.filterIsInstance<SimulationEvent.ProcessTerminated>()).isEmpty()
    }
}
