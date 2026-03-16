package cz.hovorka.kdisco.engine.examples

import assertk.assertThat
import assertk.assertions.*
import cz.hovorka.kdisco.engine.*
import kotlinx.coroutines.test.runTest
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test

class ThreeStageRocketTest {

    @Test
    fun threeStageRocketReachesExpectedAltitudeAndVelocity() = runTest {
        class ThreeStageRocket : Process() {
            lateinit var mass: Variable
            lateinit var velocity: Variable
            lateinit var altitude: Variable
            var massFlow = 0.0
            var flowVelocity = 0.0
            var area = 0.0

            override suspend fun actions() {
                // Stage 1
                massFlow = 930.0; flowVelocity = 8060.0; area = 510.0
                mass     = Variable(189162.0).start()
                velocity = Variable(0.0).start()
                altitude = Variable(0.0).start()

                // Anonymous object: accesses ThreeStageRocket's mutable fields directly.
                // (Inner classes of local classes are not supported in Kotlin.)
                val motion = object : Continuous() {
                    override fun derivatives() {
                        val thrust  = massFlow * flowVelocity
                        val drag    = area * 0.00119 *
                                      exp(-altitude.state / 24000.0) *
                                      velocity.state.pow(2)
                        val gravity = mass.state * 32.17 /
                                      (1.0 + altitude.state / 20908800.0).pow(2)
                        mass.rate     = -massFlow
                        velocity.rate = (thrust - drag - gravity) / mass.state
                        altitude.rate = velocity.state
                    }
                }
                motion.start()
                hold(150.0)

                // Stage 2 — discrete Variable.state mutation (key §4.1 feature)
                mass.state = 40342.0; massFlow = 81.49; flowVelocity = 13805.0; area = 460.0
                hold(359.0)

                // Stage 3
                mass.state = 8137.0; massFlow = 14.75; flowVelocity = 15250.0; area = 360.0
                hold(479.0)

                motion.stop(); mass.stop(); velocity.stop(); altitude.stop()
            }
        }

        val rocket = ThreeStageRocket()
        runSimulation(endTime = 1000.0) {
            dtMin = 0.00001; dtMax = 100.0
            maxAbsError = 0.00001; maxRelError = 0.00001
            Process.activate(rocket)
        }
        assertThat(rocket.altitude.state).isGreaterThan(100_000.0)
        assertThat(rocket.velocity.state).isGreaterThan(10_000.0)
    }
}
