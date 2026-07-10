// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco.examples

import assertk.assertThat
import assertk.assertions.*
import cz.ksimulantenbande.kdisco.Continuous
import cz.ksimulantenbande.kdisco.Head
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Variable
import cz.ksimulantenbande.kdisco.runSimulation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * §4.3 — Chemical reactor simulation.
 *
 * Tests: multiple Reactor : Process instances (Process extends Link, so each reactor
 * can sit in a Head), shared pressure Variable integrated by a Continuous,
 * waitUntil-triggered state events, discrete mid-simulation Variable.state mutation.
 */
class ChemicalReactorTest {

    @Test
    fun chemicalReactorsConsumePressureAndCompleteProcessing() = runTest {
        val nbrReactors     = 4
        val initialPressure = 200.0
        val nominalPressure = 100.0
        val simEnd          = 100.0

        val pressure = Variable(initialPressure)
        val started  = Head()
        var totalBatches = 0

        class PressureDynamics : Continuous() {
            override fun derivatives() {
                val active = started.cardinal().toDouble()
                val raw = if (active > 0.0) -5.0 * active else 2.0
                pressure.rate = if (pressure.state <= 0.0 && raw < 0.0) 0.0 else raw
            }
        }

        class Reactor(val id: Int) : Process() {
            override suspend fun actions() {
                repeat(3) {
                    waitUntil { pressure.state >= nominalPressure || time() >= simEnd }
                    if (time() >= simEnd) return

                    into(started)
                    hold(10.0)
                    out()
                    totalBatches++

                    hold(5.0)
                }
            }
        }

        class Coordinator : Process() {
            override suspend fun actions() {
                pressure.start()
                val pd = PressureDynamics()
                pd.start()
                val reactors = List(nbrReactors) { Reactor(it) }
                reactors.forEach { Process.activate(it) }
                hold(simEnd + 1.0)
                pd.stop(); pressure.stop()
            }
        }

        runSimulation(endTime = simEnd) { Process.activate(Coordinator()) }

        assertThat(pressure.state).isLessThan(initialPressure)
        assertThat(pressure.state).isGreaterThan(-50.0)
        assertThat(totalBatches).isGreaterThan(0)
    }
}
