package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class JDiscoIntegrationTest {

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `simple process simulation`() {
        val arrivals = mutableListOf<Double>()
        val departures = mutableListOf<Double>()

        class Customer : Process() {
            override fun actions() {
                arrivals.add(time())
                hold(5.0)
                departures.add(time())
            }
        }

        runSimulation(endTime = 100.0) {
            repeat(3) { i ->
                Process.activate(Customer(), delay = i * 10.0)
            }
        }

        assertThat(arrivals).containsExactly(0.0, 10.0, 20.0)
        assertThat(departures).containsExactly(5.0, 15.0, 25.0)
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `continuous simulation with exponential decay`() {
        class Decay : Continuous() {
            val y = Variable(100.0)
            val decayRate = -0.1

            override fun actions() {
                y.start()  // Start continuous integration
                hold(50.0)
                y.stop()   // Stop integration
            }

            override fun derivatives() {
                y.rate = decayRate * y.state
            }
        }

        val decay = Decay()
        runSimulation(endTime = 50.0) {
            Process.activate(decay)
        }

        // Expected: y(50) = 100 * e^(-0.1 * 50) ≈ 0.67
        assertThat(decay.y.state).isCloseTo(0.67, 0.1)
    }

    @Test
    fun `head and link operations`() {
        val head = Head()
        val link1 = Link()
        val link2 = Link()
        val link3 = Link()

        assertThat(head.empty()).isTrue()
        assertThat(head.cardinal()).isEqualTo(0)

        link1.into(head)
        assertThat(head.empty()).isFalse()
        assertThat(head.cardinal()).isEqualTo(1)
        assertThat(head.first()).isEqualTo(link1)
        assertThat(head.last()).isEqualTo(link1)

        link2.into(head)
        link3.into(head)
        assertThat(head.cardinal()).isEqualTo(3)

        link2.out()
        assertThat(head.cardinal()).isEqualTo(2)

        head.clear()
        assertThat(head.empty()).isTrue()
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `process passivate and reactivate`() {
        val events = mutableListOf<String>()

        class Server : Process() {
            override fun actions() {
                events.add("Server started at ${time()}")
                passivate()
                events.add("Server resumed at ${time()}")
            }
        }

        class Client(val server: Server) : Process() {
            override fun actions() {
                events.add("Client arrived at ${time()}")
                hold(5.0)
                Process.reactivate(server)
                events.add("Client reactivated server at ${time()}")
            }
        }

        runSimulation(endTime = 100.0) {
            val server = Server()
            Process.activate(server)
            Process.activate(Client(server), delay = 10.0)
        }

        assertThat(events).hasSize(4)
        assertThat(events[0]).isEqualTo("Server started at 0.0")
        assertThat(events[1]).isEqualTo("Client arrived at 10.0")
        // jDisco's reactivate() uses direct_code which inserts the reactivated process at the
        // front of the SQS and calls resumeCurrent(), causing an immediate context switch.
        // Server therefore runs and adds its message BEFORE Client continues past the reactivate call.
        assertThat(events[2]).isEqualTo("Server resumed at 15.0")
        assertThat(events[3]).isEqualTo("Client reactivated server at 15.0")
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `extension functions work correctly`() {
        val activations = mutableListOf<Double>()

        class TestProcess : Process() {
            override fun actions() {
                activations.add(time())
            }
        }

        val sim = simulation {
            val p1 = TestProcess()
            p1.activate(5.0)

            val p2 = TestProcess()
            p2.activateIn(10.0)
        }

        sim.run(20.0)

        assertThat(activations).containsExactly(5.0, 10.0)
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `process terminate actually terminates`() {
        var terminateCalled = false
        var afterTerminate = false

        class TerminatingProcess : Process() {
            override fun actions() {
                hold(1.0)
                terminateCalled = true
                terminate()
                afterTerminate = true  // Should NOT execute
            }
        }

        val p = TerminatingProcess()
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }

        assertThat(terminateCalled).isTrue()
        assertThat(afterTerminate).isFalse()  // Verify terminate() stops execution
    }

    @Test
    fun `hold with negative duration throws exception`() {
        // jDisco coroutine threads silently swallow unhandled exceptions and deadlock.
        // We test the require guard directly from the test thread (not inside a simulation).
        // The guard fires before any jDisco call, so no simulation state is affected.
        class TestProcess : Process() {
            override fun actions() {}
        }
        val p = TestProcess()
        assertThrows<IllegalArgumentException> { p.hold(-1.0) }
    }

    @Test
    fun `activate with negative delay throws exception`() {
        // Process.activate() validation runs in the setup lambda (test thread),
        // before any jDisco coroutine starts, so assertThrows works correctly here.
        class TestProcess : Process() {
            override fun actions() {}
        }

        assertThrows<IllegalArgumentException> {
            runSimulation(endTime = 10.0) {
                Process.activate(TestProcess(), delay = -5.0)
            }
        }
    }

    @Test
    fun `activateAt with past time throws exception`() {
        // jDisco coroutine threads silently swallow unhandled exceptions and deadlock.
        // We test the guard from the test thread: time() returns 0 outside a simulation,
        // so activateAt(-5.0) always has a negative delay and must throw.
        class TestProcess : Process() {
            override fun actions() {}
        }
        val p = TestProcess()
        assertThrows<IllegalArgumentException> { p.activateAt(-5.0) }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `reactivate resumes passivated process correctly`() {
        var stage = 0

        class Worker : Process() {
            override fun actions() {
                stage = 1
                passivate()
                stage = 2  // Should execute after reactivation
            }
        }

        class Manager : Process() {
            lateinit var worker: Worker

            override fun actions() {
                hold(1.0)
                Process.reactivate(worker)  // Wake up worker
                hold(1.0)
            }
        }

        val worker = Worker()
        val manager = Manager().apply { this.worker = worker }

        runSimulation(endTime = 10.0) {
            Process.activate(worker)
            Process.activate(manager)
        }

        assertThat(stage).isEqualTo(2)  // Worker completed after reactivation
    }

    @Test
    fun `asSequence handles empty Head gracefully`() {
        val emptyHead = Head()
        val links = emptyHead.asSequence().toList()
        assertThat(links).isEmpty()
    }

    @Test
    fun `run with negative endTime throws exception`() {  // No @Timeout: throws before jDisco starts
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runSimulation(endTime = -10.0) {
                // Empty simulation
            }
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `Variable lifecycle methods work correctly`() {
        class VariableTest : Continuous() {
            val v = Variable(42.0)

            override fun actions() {
                assertThat(v.isActive()).isFalse()
                v.start()
                assertThat(v.isActive()).isTrue()
                hold(1.0)
                v.stop()
                assertThat(v.isActive()).isFalse()
            }

            override fun derivatives() {
                v.rate = 0.0  // No change
            }
        }

        val test = VariableTest()
        runSimulation(endTime = 10.0) {
            Process.activate(test)
        }

        assertThat(test.v.state).isCloseTo(42.0, 0.01)
    }
}
