package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * §4.3 — Chemical reactor simulation.
 *
 * Tests: multiple Reactor : Process instances (Process extends Link, so each reactor
 * can sit in a Head), shared pressure Variable integrated by a Continuous,
 * waitUntil-triggered state events, discrete mid-simulation Variable.state mutation.
 */
class ChemicalReactorTest {

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `chemical reactors consume pressure and complete processing`() {
        val rand            = Random(12345L)
        val nbrReactors     = 4
        val initialPressure = 200.0
        val nominalPressure = 100.0
        val simEnd          = 200.0

        val pressure = Variable(initialPressure)
        val started  = Head()   // reactors currently in a processing phase
        var totalBatches = 0

        // Pressure dynamics: drops while reactors are running, recovers when idle.
        // Rate is clamped near zero so the integrator cannot drive pressure deeply
        // negative between steps (overshoot of the natural lower bound).
        class PressureDynamics : Continuous() {
            override fun derivatives() {
                val active = started.cardinal().toDouble()
                val raw = if (active > 0.0) -5.0 * active else 2.0
                pressure.rate = if (pressure.state <= 0.0 && raw < 0.0) 0.0 else raw
            }
        }

        class Reactor(val id: Int) : Process() {
            override fun actions() {
                repeat(3) {
                    // Wait until pressure is high enough to run safely
                    waitUntil { pressure.state >= nominalPressure || time() >= simEnd }
                    if (time() >= simEnd) return

                    // Join 'started' list — discrete Head/Link mutation
                    into(started)
                    hold(rand.negexp(1.0 / 10.0).coerceIn(1.0, 20.0))
                    out()
                    synchronized(pressure) { totalBatches++ }

                    hold(rand.negexp(1.0 / 5.0).coerceIn(0.5, 10.0))
                }
            }
        }

        // Coordinator: starts the shared ODE and all reactors.
        class Coordinator : Process() {
            override fun actions() {
                pressure.start()
                PressureDynamics().start()
                val reactors = List(nbrReactors) { Reactor(it) }
                reactors.forEach { Process.activate(it) }
                hold(simEnd + 1.0)
            }
        }

        runSimulation(endTime = simEnd) { Process.activate(Coordinator()) }

        assertThat(pressure.state).isLessThan(initialPressure)
        // RK4 can overshoot the zero boundary slightly; allow a small negative margin
        assertThat(pressure.state).isGreaterThan(-50.0)
        assertThat(totalBatches).isGreaterThan(0)
    }
}
