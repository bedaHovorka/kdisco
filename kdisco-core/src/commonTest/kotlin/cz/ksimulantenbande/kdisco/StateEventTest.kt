// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Tests for state-event (zero-crossing) detection with root-finding.
 *
 * The engine locates a guard sign-change *within* an accepted integration step, so hybrid
 * models can leave `dtMax` at its natural value instead of forcing a tiny step to keep the
 * boundary overshoot negligible.
 */
class StateEventTest {

    /**
     * Linear motion x(t) = v*t reaches the boundary at t = boundary/v exactly.
     * With a large dtMax the plain `waitUntil` would only resolve the crossing to within one
     * whole step; `waitCrossing` must locate it to high precision.
     */
    @Test
    fun locatesLinearCrossingPrecisely() = runTest {
        val x = Variable(0.0)
        val v = 10.0
        val boundary = 100.0
        var crossTime = Double.NaN
        var crossState = Double.NaN

        val motion = object : Continuous() {
            override fun derivatives() { x.rate = v }
        }

        runSimulation(endTime = 100.0) {
            dtMax = 1.0  // natural step size — no tiny-dtMax workaround
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { boundary - x.state }
                    crossTime = time()
                    crossState = x.state
                    motion.stop(); x.stop()
                }
            })
        }

        // Exact crossing is t = 10.0, x = 100.0.
        assertThat(abs(crossTime - 10.0)).isLessThan(1e-6)
        assertThat(abs(crossState - boundary)).isLessThan(1e-4)
    }

    /**
     * Projectile y(t) = v0*t - 0.5*g*t^2 lands (y crosses 0) at t = 2*v0/g.
     * Nonlinear guard, still located accurately with a large step.
     */
    @Test
    fun locatesNonlinearCrossingPrecisely() = runTest {
        val y = Variable(0.0)
        val vy = Variable(20.0)
        val g = 9.81
        var landTime = Double.NaN

        val ballistics = object : Continuous() {
            override fun derivatives() {
                y.rate = vy.state
                vy.rate = -g
            }
        }

        runSimulation(endTime = 10.0) {
            dtMax = 1.0
            maxAbsError = 1e-8
            maxRelError = 1e-8
            Process.activate(object : Process() {
                override suspend fun actions() {
                    y.start(); vy.start(); ballistics.start()
                    hold(0.1)  // let it leave the launch point (y=0) first
                    waitCrossing { y.state }
                    landTime = time()
                    ballistics.stop(); y.stop(); vy.stop()
                }
            })
        }

        val expected = 2.0 * 20.0 / g  // ≈ 4.077...
        assertThat(abs(landTime - expected)).isLessThan(1e-4)
    }

    /**
     * Crossing detection with a large dtMax must use dramatically fewer derivative
     * evaluations than the tiny-dtMax workaround, while landing on the same crossing time.
     */
    @Test
    fun crossingUsesFarFewerDerivativeEvaluationsThanTinyDtMax() = runTest {
        val boundary = 100.0
        val v = 10.0

        // Reference: the "tiny dtMax" workaround, resolving the crossing by hold-stepping.
        suspend fun runTinyDtMax(): Pair<Double, Int> {
            val x = Variable(0.0)
            var evals = 0
            var crossTime = Double.NaN
            val motion = object : Continuous() {
                override fun derivatives() { evals++; x.rate = v }
            }
            runSimulation(endTime = 100.0) {
                dtMax = 1e-3
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        x.start(); motion.start()
                        waitUntil { x.state >= boundary }
                        crossTime = time()
                        motion.stop(); x.stop()
                    }
                })
            }
            return crossTime to evals
        }

        // Crossing detection: large natural step + root-finding.
        suspend fun runCrossing(): Pair<Double, Int> {
            val x = Variable(0.0)
            var evals = 0
            var crossTime = Double.NaN
            val motion = object : Continuous() {
                override fun derivatives() { evals++; x.rate = v }
            }
            runSimulation(endTime = 100.0) {
                dtMax = 1.0
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        x.start(); motion.start()
                        waitCrossing { boundary - x.state }
                        crossTime = time()
                        motion.stop(); x.stop()
                    }
                })
            }
            return crossTime to evals
        }

        val (tinyTime, tinyEvals) = runTinyDtMax()
        val (crossingTime, crossingEvals) = runCrossing()

        // Both land on the true crossing time t = 10.0.
        assertThat(abs(crossingTime - 10.0)).isLessThan(1e-6)
        // The crossing approach costs far fewer derivative evaluations.
        assertThat(crossingEvals).isLessThan(tinyEvals / 10)
        // Sanity: they agree on the crossing time.
        assertThat(abs(crossingTime - tinyTime)).isLessThan(1e-2)
    }

    /**
     * A process can wait for successive crossings (e.g. reaching consecutive block boundaries).
     */
    @Test
    fun handlesSuccessiveCrossings() = runTest {
        // x(t) = t; cross levels 3.0 then 7.0.
        val x = Variable(0.0)
        val times = mutableListOf<Double>()
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { 3.0 - x.state }
                    times.add(time())
                    waitCrossing { 7.0 - x.state }
                    times.add(time())
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(times.size).isEqualTo(2)
        assertThat(abs(times[0] - 3.0)).isLessThan(1e-6)
        assertThat(abs(times[1] - 7.0)).isLessThan(1e-6)
    }

    /**
     * A guard that never changes sign leaves the process suspended (no spurious firing).
     */
    @Test
    fun noCrossingLeavesProcessSuspended() = runTest {
        val x = Variable(0.0)
        var fired = false
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }

        runSimulation(endTime = 5.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    // Boundary at 1000 is never reached within endTime=5.
                    waitCrossing { 1000.0 - x.state }
                    fired = true
                }
            })
        }

        assertThat(fired).isFalse()
        // Integration still advanced normally.
        assertThat(x.state).isGreaterThanOrEqualTo(4.9)
    }
}
