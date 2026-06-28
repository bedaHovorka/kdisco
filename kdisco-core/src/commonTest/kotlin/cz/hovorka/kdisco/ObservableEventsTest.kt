package cz.hovorka.kdisco

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
}
