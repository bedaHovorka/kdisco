package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt

class DominoGameTest {

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `domino chain wave speed is in expected range`() {
        val nbrStones = 55
        val d = 0.0204   // spacing between stones (m)
        val g = 9.81     // gravity (m/s²)
        val m = 0.01     // stone mass (kg)
        val x = 0.008    // stone half-thickness (m)
        val z = 0.046    // stone height (m)

        // Moment of inertia of rectangular slab about bottom edge
        val inertia = m * (4.0 * z * z + x * x) / 12.0
        // Push angle: when top of stone i reaches stone i+1
        val phiPush = asin(d / z)
        // Contact height on stone i+1 at push moment
        val h = sqrt(z * z - d * d)
        // Angular-impulse velocity transfer coefficients
        val mh2 = m * h * h
        val ko  = mh2 / (inertia + mh2)
        val kr  = inertia / (inertia + mh2)

        val fallingStones = Head()
        var stones   = 0
        var waveSpeed = 0.0

        // Stone is a local class; StoneFall uses an anonymous Continuous object
        // to avoid the Kotlin restriction "inner classes of local classes are unsupported".
        class Stone(val initialOmega: Double) : Process() {
            val phi   = Variable(0.0)
            val omega = Variable(initialOmega)

            override fun actions() {
                dtMin = 1.0e-6; dtMax = 0.1
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
                phi.stop(); omega.stop()
            }
        }

        class DominoGame : Process() {
            override fun actions() {
                Process.activate(Stone(0.001))
                waitUntil { fallingStones.empty() }
                waveSpeed = d * (stones - 1) / time()
            }
        }

        runSimulation(endTime = 300.0) { Process.activate(DominoGame()) }

        assertThat(stones).isEqualTo(nbrStones)
        assertThat(waveSpeed).isBetween(0.30, 1.50)
    }
}
