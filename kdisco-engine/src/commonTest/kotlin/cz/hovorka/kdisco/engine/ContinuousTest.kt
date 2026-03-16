package cz.hovorka.kdisco.engine

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

class ContinuousTest {

    // --- Lifecycle tests ---

    @Test
    fun continuousIsInactiveByDefault() = runTest {
        val c = object : Continuous() {
            override fun derivatives() {}
        }
        assertThat(c.isActive()).isFalse()
    }

    @Test
    fun continuousStartMakesItActive() = runTest {
        val c = object : Continuous() {
            override fun derivatives() {}
        }
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    c.start()
                    assertThat(c.isActive()).isTrue()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun continuousStopMakesItInactive() = runTest {
        val c = object : Continuous() {
            override fun derivatives() {}
        }
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    c.start()
                    assertThat(c.isActive()).isTrue()
                    c.stop()
                    assertThat(c.isActive()).isFalse()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun continuousStartReturnsSelf() = runTest {
        val c = object : Continuous() {
            override fun derivatives() {}
        }
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    val returned = c.start()
                    assertThat(returned).isSameInstanceAs(c)
                }
            }
            Process.activate(p)
        }
    }

    // --- Numerical integration test ---

    /**
     * Simple linear ODE: dx/dt = 1.0, x(0) = 0.0.
     * Exact solution: x(t) = t.
     * After 5 time units, x should be ~5.0.
     */
    @Test
    fun linearIntegrationWithRKF45() = runTest {
        val x = Variable(0.0)
        var finalX = Double.NaN

        val dynamics = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
            }
        }

        runSimulation(endTime = 5.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(5.0)
                    finalX = x.state
                }
            }
            Process.activate(p)
        }

        assertThat(abs(finalX - 5.0)).isLessThan(1e-3)
    }

    /**
     * Exponential decay: dx/dt = -x, x(0) = 1.0.
     * Exact solution: x(t) = e^(-t).
     * After 2 time units, x should be ~e^(-2) ≈ 0.1353.
     */
    @Test
    fun exponentialDecayIntegration() = runTest {
        val x = Variable(1.0)
        var finalX = Double.NaN

        val dynamics = object : Continuous() {
            override fun derivatives() {
                x.rate = -x.state
            }
        }

        runSimulation(endTime = 2.0) {
            dtMax = 0.1
            maxAbsError = 1e-6
            maxRelError = 1e-6

            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(2.0)
                    finalX = x.state
                }
            })
        }

        val expected = 0.13533528323661270  // e^(-2)
        assertThat(abs(finalX - expected)).isLessThan(1e-4)
    }

    /**
     * Verifies that derivatives() is called by the monitor.
     */
    @Test
    fun derivativesIsCalledDuringIntegration() = runTest {
        var derivativeCallCount = 0
        val x = Variable(0.0)

        val dynamics = object : Continuous() {
            override fun derivatives() {
                derivativeCallCount++
                x.rate = 1.0
            }
        }

        runSimulation(endTime = 2.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(2.0)
                }
            }
            Process.activate(p)
        }

        assertThat(derivativeCallCount).isGreaterThan(0)
    }

    /**
     * Multiple simultaneous Continuous instances: both should have derivatives() called.
     */
    @Test
    fun multipleContinuousDerivativesCalled() = runTest {
        var count1 = 0
        var count2 = 0
        val x = Variable(0.0)
        val y = Variable(0.0)

        val dyn1 = object : Continuous() {
            override fun derivatives() {
                count1++
                x.rate = 1.0
            }
        }
        val dyn2 = object : Continuous() {
            override fun derivatives() {
                count2++
                y.rate = 2.0
            }
        }

        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    y.start()
                    dyn1.start()
                    dyn2.start()
                    hold(1.0)
                }
            }
            Process.activate(p)
        }

        assertThat(count1).isGreaterThan(0)
        assertThat(count2).isGreaterThan(0)
    }

    /**
     * Priority ordering: high-priority Continuous should have derivatives() called first.
     */
    @Test
    fun priorityOrderingHighFirst() = runTest {
        val callOrder = mutableListOf<String>()
        val x = Variable(0.0)

        val lowPriority = object : Continuous() {
            override fun derivatives() {
                callOrder.add("low")
                x.rate = 1.0
            }
        }
        val highPriority = object : Continuous() {
            override fun derivatives() {
                callOrder.add("high")
            }
        }

        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    // Start low priority first, then high priority
                    lowPriority.setPriority(0.0).start()
                    highPriority.setPriority(10.0).start()
                    hold(1.0)
                }
            }
            Process.activate(p)
        }

        // The very first pair of calls should have "high" before "low"
        assertThat(callOrder.size).isGreaterThanOrEqualTo(2)
        assertThat(callOrder[0]).isEqualTo("high")
        assertThat(callOrder[1]).isEqualTo("low")
    }

    /**
     * setPriority re-inserts an active Continuous at the correct position.
     */
    @Test
    fun setPriorityReordersActiveList() = runTest {
        val callOrder = mutableListOf<String>()
        val x = Variable(0.0)

        val c1 = object : Continuous() {
            override fun derivatives() {
                callOrder.add("c1")
                x.rate = 0.0
            }
        }
        val c2 = object : Continuous() {
            override fun derivatives() {
                callOrder.add("c2")
            }
        }

        runSimulation(endTime = 2.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    c1.setPriority(5.0).start()
                    c2.setPriority(1.0).start()
                    // At this point c1 (priority 5) should run before c2 (priority 1)
                    hold(1.0)
                    // Verify initial ordering was c1 first
                    val first = callOrder.first()
                    assertThat(first).isEqualTo("c1")

                    callOrder.clear()
                    // Now change c1 priority to below c2
                    c1.setPriority(0.0)
                    hold(1.0)
                    assertThat(callOrder.first()).isEqualTo("c2")
                }
            }
            Process.activate(p)
        }
    }

    /**
     * Variable starts inactive and stops affecting integration after stop().
     */
    @Test
    fun variableStopExcludesFromIntegration() = runTest {
        val x = Variable(10.0)
        var xAtStop = Double.NaN
        var xAtEnd = Double.NaN

        val dynamics = object : Continuous() {
            override fun derivatives() {
                x.rate = -x.state  // exponential decay
            }
        }

        runSimulation(endTime = 4.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(2.0)
                    x.stop()
                    xAtStop = x.state
                    hold(2.0) // x should not change further once stopped
                    xAtEnd = x.state
                }
            }
            Process.activate(p)
        }

        // After stop, state should not change (no more integration updates)
        assertThat(xAtEnd).isEqualTo(xAtStop)
    }

    /**
     * Linear ODE: dx/dt = 5.0, x(0) = 0.0, integrated using the Euler method.
     * Exact solution: x(t) = 5*t. After 2 time units, x should be ~10.0.
     */
    @Test
    fun linearIntegrationWithEuler() = runTest {
        val pos = Variable(0.0)

        val dynamics = object : Continuous() {
            override fun derivatives() {
                pos.rate = 5.0
            }
        }

        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    pos.start()
                    dynamics.start()
                    hold(2.0)
                    pos.stop()
                    dynamics.stop()
                }
            })
        }
        sim.integrator = EulerIntegrator()
        sim.run(5.0)

        // With Euler: x = 5.0 * 2.0 = 10.0 (linear rate 5.0 for 2 time units)
        assertThat(abs(pos.state - 10.0)).isLessThan(0.01)
    }

    /**
     * Regression test for Issue #14: when NO discrete processes are scheduled (event queue
     * is empty from the start), the scheduler loop must still drive continuous integration
     * all the way to endTime.
     *
     * Before the fix, `peek() ?: break` immediately exited the scheduler loop, leaving
     * currentTime at 0.0 and skipping all integration.
     */
    @Test
    fun continuousOnlySimulationRunsToEndTime() = runTest {
        val x = Variable(0.0)
        val sim = Simulation.create {
            object : Continuous() {
                override fun derivatives() { x.rate = 1.0 }
            }.start()
            x.start()
            // Deliberately no discrete Process.activate() calls — pure continuous simulation
        }
        sim.dtMax = 0.5
        sim.run(5.0)
        assertThat(x.state).isGreaterThanOrEqualTo(4.9)
    }

    /**
     * Small-step integration: linear growth with small dtMax for accuracy verification.
     */
    @Test
    fun smallStepIntegrationLinearTest() = runTest {
        val x = Variable(0.0)
        var finalX = Double.NaN

        val dynamics = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
            }
        }

        runSimulation(endTime = 5.0) {
            // Use a small fixed step to test step-size limiting
            dtMax = 0.1
            dtMin = 0.001

            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start()
                    dynamics.start()
                    hold(5.0)
                    finalX = x.state
                }
            })
        }

        // With dtMax=0.1, linear integration should be exact (or very close)
        assertThat(abs(finalX - 5.0)).isLessThan(1e-3)
    }
}
