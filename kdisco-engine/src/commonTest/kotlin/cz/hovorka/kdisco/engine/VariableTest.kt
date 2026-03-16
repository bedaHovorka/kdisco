package cz.hovorka.kdisco.engine

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

class VariableTest {

    @Test
    fun variableIsInactiveByDefault() = runTest {
        val v = Variable(5.0)
        assertThat(v.isActive()).isFalse()
    }

    @Test
    fun variableStartMakesItActive() = runTest {
        val v = Variable(5.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v.start()
                    assertThat(v.isActive()).isTrue()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun variableStopMakesItInactive() = runTest {
        val v = Variable(5.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v.start()
                    assertThat(v.isActive()).isTrue()
                    v.stop()
                    assertThat(v.isActive()).isFalse()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun variableStartReturnsSelf() = runTest {
        val v = Variable(3.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    val returned = v.start()
                    assertThat(returned).isSameInstanceAs(v)
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun startWhenAlreadyActiveIsNoOp() = runTest {
        val v = Variable(1.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v.start()
                    v.start() // second call should not throw or corrupt state
                    assertThat(v.isActive()).isTrue()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun stopWhenNotActiveIsNoOp() = runTest {
        val v = Variable(1.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    // Never started — stop should be a no-op
                    v.stop()
                    assertThat(v.isActive()).isFalse()
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun lastStateReturnsOldState() = runTest {
        // lastState() = oldState, which is set at the start of each integration step.
        // Without a Continuous process, oldState stays at initialState.
        val v = Variable(7.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v.start()
                    // oldState is initialised to initialState in the constructor
                    assertThat(v.lastState()).isEqualTo(7.0)
                    assertThat(v.oldState).isEqualTo(7.0)
                }
            }
            Process.activate(p)
        }
    }

    @Test
    fun multipleVariablesInActiveList() = runTest {
        val v1 = Variable(1.0)
        val v2 = Variable(2.0)
        val v3 = Variable(3.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v1.start()
                    v2.start()
                    v3.start()
                    assertThat(v1.isActive()).isTrue()
                    assertThat(v2.isActive()).isTrue()
                    assertThat(v3.isActive()).isTrue()

                    v2.stop()
                    assertThat(v1.isActive()).isTrue()
                    assertThat(v2.isActive()).isFalse()
                    assertThat(v3.isActive()).isTrue()
                }
            }
            Process.activate(p)
        }
    }

    /**
     * Verifies that [Variable.oldState] updates correctly across multiple integration steps.
     *
     * With dx/dt = 1.0 and dtMax = 1.0, multiple steps occur over 3 time units.
     * At the start of each step, oldState should equal the state from the previous step.
     * The first call must see oldState = 0.0 (initial), and later calls must see oldState > 0.0.
     */
    @Test
    fun oldStateUpdatesAcrossIntegrationSteps() = runTest {
        val pos = Variable(0.0)
        val oldStatesObserved = mutableListOf<Double>()

        val recorder = object : Continuous() {
            override fun derivatives() {
                oldStatesObserved.add(pos.oldState)
                pos.rate = 1.0
            }
        }

        runSimulation(endTime = 3.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    pos.start()
                    recorder.start()
                    hold(3.0)
                }
            })
        }

        assertThat(oldStatesObserved).isNotEmpty()
        // First call: oldState = 0.0 (initial)
        assertThat(abs(oldStatesObserved.first())).isLessThan(0.001)
        // Final state: approximately 3.0 (dx/dt = 1.0 for 3 time units)
        assertThat(abs(pos.state - 3.0)).isLessThan(0.1)
        // At some point during integration, oldState must have advanced from 0.0
        assertThat(
            oldStatesObserved.any { it > 0.1 },
            "oldState should have advanced during multi-step integration, observed: $oldStatesObserved"
        ).isTrue()
    }

    @Test
    fun removeFirstVariableFromList() = runTest {
        val v1 = Variable(1.0)
        val v2 = Variable(2.0)
        runSimulation(endTime = 1.0) {
            val p = object : Process() {
                override suspend fun actions() {
                    v1.start()
                    v2.start()
                    // v2 is inserted at head (newest first)
                    assertThat(v2.isActive()).isTrue()
                    assertThat(v1.isActive()).isTrue()

                    v2.stop() // remove head
                    assertThat(v2.isActive()).isFalse()
                    assertThat(v1.isActive()).isTrue()

                    // v1 should now be a valid sole member (_pred = self)
                    v1.stop()
                    assertThat(v1.isActive()).isFalse()
                }
            }
            Process.activate(p)
        }
    }
}
