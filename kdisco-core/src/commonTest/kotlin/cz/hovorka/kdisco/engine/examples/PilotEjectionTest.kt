package cz.hovorka.kdisco.engine.examples

import assertk.assertThat
import assertk.assertions.*
import cz.hovorka.kdisco.engine.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * §4.5 — Pilot ejection seat simulation.
 *
 * Tests: Continuous subclass inheritance (Phase2 : Phase1), phase-switch pattern
 * (stop Phase1, start Phase2), inline atmospheric density table interpolation,
 * waitUntil for state-event detection.
 *
 * Units: feet, seconds, ft/s.
 */

// Atmospheric density table: altitude (ft) → density (slug/ft³)
private val DENSITY_TABLE = listOf(
    0.0     to 2377e-6,
    1000.0  to 2308e-6,
    2000.0  to 2241e-6,
    4000.0  to 2117e-6,
    6000.0  to 1987e-6,
    10000.0 to 1755e-6,
    15000.0 to 1497e-6,
    20000.0 to 1267e-6,
    30000.0 to  891e-6,
    40000.0 to  587e-6,
    50000.0 to  364e-6,
    60000.0 to 2238e-7
)

private fun linearInterpolate(table: List<Pair<Double, Double>>, xVal: Double): Double {
    if (xVal <= table.first().first) return table.first().second
    if (xVal >= table.last().first)  return table.last().second
    val i = table.indexOfFirst { it.first > xVal } - 1
    val (x0, y0) = table[i]
    val (x1, y1) = table[i + 1]
    return y0 + (y1 - y0) * (xVal - x0) / (x1 - x0)
}

class PilotEjectionTest {

    private val GRAVITY  = 32.17    // ft/s²
    private val RAIL_VEL = 65.0     // ejection rail exit velocity (ft/s, vertical)
    private val Y1       = 5.0      // rail length (ft)
    private val RHO0     = 2377e-6  // sea-level density (slug/ft³)
    private val DRAG_K   = 0.3      // aerodynamic drift scale

    /**
     * Combines safety and Phase2-execution checks in one simulation run.
     */
    @Test
    fun ejectionIsSafeAtSlowSpeedAndPhase2DerivativesExecute() = runTest {
        val result = simulateEjection(vA = 100.0, h = 0.0)

        // Safety: pilot cleared both stabilisers
        assertThat(result.safe, "Ejection should be safe at vA=100 ft/s").isTrue()

        // Phase2 ran: pilot moved horizontally (x ODE was integrated)
        assertThat(result.xFinal).isLessThan(-20.0)

        // Phase2 ran: pilot climbed above rail-exit height (y/vy ODEs were integrated)
        assertThat(result.yFinal).isGreaterThan(Y1)
    }

    private data class EjectionResult(val safe: Boolean, val xFinal: Double, val yFinal: Double)

    /**
     * Phase1 and Phase2 are declared as local classes inside this function so that:
     *  - Phase2 can inherit from Phase1 (subclass of a Continuous, the key §4.5 feature)
     *  - Both can capture the shared Variable state without needing 'inner' (which is
     *    unsupported for local classes in Kotlin)
     */
    private suspend fun simulateEjection(vA: Double, h: Double): EjectionResult {
        val yVar  = Variable(h)
        val vyVar = Variable(RAIL_VEL)
        val xVar  = Variable(0.0)
        var safe  = false
        var capturedX = 0.0
        var capturedY = 0.0

        // Phase 1: seat constrained to rail — constant vertical velocity, no horizontal.
        open class Phase1 : Continuous() {
            override fun derivatives() {
                yVar.rate  = vyVar.state
                vyVar.rate = 0.0   // rail constrains: no gravity acceleration in Phase 1
            }
        }

        // Phase 2: free flight — inherits Phase1 structure, overrides all derivatives
        // to add horizontal drift and gravitational deceleration.
        // This subclass relationship is the core API feature tested by §4.5.
        class Phase2 : Phase1() {
            override fun derivatives() {
                val rho  = linearInterpolate(DENSITY_TABLE, yVar.state.coerceAtLeast(0.0))
                val rhoF = rho / RHO0          // density ratio (1.0 at sea level)
                xVar.rate  = -vA * DRAG_K * rhoF   // aerodynamic backward drift
                yVar.rate  = vyVar.state
                vyVar.rate = -GRAVITY              // gravity decelerates vertical climb
            }
        }

        class PilotEjection : Process() {
            override suspend fun actions() {
                yVar.start(); vyVar.start()

                val phase1 = Phase1()
                phase1.start()
                waitUntil { yVar.state >= Y1 }   // seat disengages from rail
                phase1.stop()

                xVar.start()
                val phase2 = Phase2()
                phase2.start()
                waitUntil { xVar.state <= -30.0 } // cleared stabilizer (30 ft behind cockpit)
                capturedX = xVar.state
                capturedY = yVar.state
                safe = capturedY >= 20.0
                phase2.stop(); xVar.stop(); yVar.stop(); vyVar.stop()
            }
        }

        runSimulation(endTime = 60.0) {
            dtMin = 1.0e-5; dtMax = 0.01
            Process.activate(PilotEjection())
        }
        return EjectionResult(safe, capturedX, capturedY)
    }
}
