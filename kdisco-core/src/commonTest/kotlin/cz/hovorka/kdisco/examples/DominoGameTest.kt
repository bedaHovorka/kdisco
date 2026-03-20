package cz.hovorka.kdisco.examples

import assertk.assertThat
import assertk.assertions.*
import cz.hovorka.kdisco.*
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

class DominoGameTest {

    @Test
    fun dominoChainWaveSpeedIsInExpectedRange() = runTest {
        val nbrStones = 55
        val d = 0.0204   // spacing between stones (m)
        val g = 9.81     // gravity (m/s²)
        val m = 0.01     // stone mass (kg)
        val x = 0.008    // stone half-thickness (m)
        val z = 0.046    // stone height (m)

        val inertia = m * (4.0 * z * z + x * x) / 12.0
        val phiPush = asin(d / z)
        val h = sqrt(z * z - d * d)
        val mh2 = m * h * h
        val ko  = mh2 / (inertia + mh2)
        val kr  = inertia / (inertia + mh2)

        val fallingStones = Head()
        var stones   = 0
        var waveSpeed = 0.0

        class Stone(val initialOmega: Double) : Process() {
            val phi   = Variable(0.0)
            val omega = Variable(initialOmega)

            override suspend fun actions() {
                phi.start(); omega.start()

                val fall = object : Continuous() {
                    override fun derivatives() {
                        phi.rate   = omega.state
                        omega.rate = m * g * (z / 2.0) * sin(phi.state) / inertia
                    }
                }
                fall.start()

                into(fallingStones)
                stones++

                if (stones < nbrStones) {
                    waitUntil { phi.state >= phiPush }
                    Process.activate(Stone(ko * omega.state))
                    omega.state *= kr
                }

                waitUntil { phi.state >= PI / 2.0 }
                out()
                fall.stop(); phi.stop(); omega.stop()
            }
        }

        class DominoGame : Process() {
            override suspend fun actions() {
                Process.activate(Stone(0.001))
                // Wait until at least one stone has started AND all have finished falling.
                // The `stones > 0` guard prevents the condition firing at t=0 before Stone(0.001) runs.
                waitUntil { stones > 0 && fallingStones.empty() }
                waveSpeed = d * (stones - 1) / time()
            }
        }

        runSimulation(endTime = 300.0) {
            dtMin = 1.0e-6; dtMax = 0.1
            Process.activate(DominoGame())
        }

        assertThat(stones).isEqualTo(nbrStones)
        assertThat(waveSpeed).isBetween(0.30, 1.50)
    }
}
