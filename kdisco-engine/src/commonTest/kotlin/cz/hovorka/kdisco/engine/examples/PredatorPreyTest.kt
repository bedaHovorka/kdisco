package cz.hovorka.kdisco.engine.examples

import assertk.assertThat
import assertk.assertions.*
import cz.hovorka.kdisco.engine.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PredatorPreyTest {

    @Test
    fun predatorPreyOscillationHasMeasurablePeriod() = runTest {
        // Variables held as properties of the outer process so they are accessible
        // from both the dynamics anonymous-object and the waitUntil lambdas.
        class PredatorPreySystem : Process() {
            val predator = Variable(1000.0)
            val prey     = Variable(100000.0)
            var period   = 0.0

            override suspend fun actions() {
                predator.start(); prey.start()

                // Anonymous object replaces inner class (inner classes of local classes
                // are not supported in Kotlin).
                val dynamics = object : Continuous() {
                    override fun derivatives() {
                        predator.rate = (-0.3 + 3.0e-7 * prey.state) * predator.state
                        prey.rate     = ( 0.3 - 3.0e-4 * predator.state) * prey.state
                    }
                }
                dynamics.start()

                // Find first prey maximum (rate > 0 → ≤ 0 transition)
                waitUntil { prey.rate > 0 }
                waitUntil { prey.rate <= 0 }
                val t1 = time()
                // Find second prey maximum
                waitUntil { prey.rate > 0 }
                waitUntil { prey.rate <= 0 }
                period = time() - t1

                dynamics.stop(); predator.stop(); prey.stop()
            }
        }

        val sys = PredatorPreySystem()
        runSimulation(endTime = 200.0) {
            dtMin = 1.0e-5; dtMax = 1.0; maxRelError = 1.0e-5
            Process.activate(sys)
        }
        assertThat(sys.period).isBetween(15.0, 35.0)
        assertThat(sys.predator.state).isGreaterThan(0.0)
        assertThat(sys.prey.state).isGreaterThan(0.0)
    }
}
