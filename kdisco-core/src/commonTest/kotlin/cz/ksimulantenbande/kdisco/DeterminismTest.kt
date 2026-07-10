// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DeterminismTest {

    @Test
    fun seededMultiProcessSimulationIsReproducible() = runTest {
        val seed = 42L
        val logs = mutableListOf<List<String>>()

        repeat(100) {
            val log = mutableListOf<String>()
            runSimulation(endTime = 20.0, seed = seed) {
                class Worker(private val id: Int) : Process() {
                    override suspend fun actions() {
                        repeat(3) {
                            log.add("$id-${time()}-${random().uniform(0.0, 1.0)}")
                            hold(random().uniform(1.0, 3.0))
                        }
                    }
                }
                Process.activate(Worker(0))
                Process.activate(Worker(1), delay = 0.5)
                Process.activate(Worker(2), delay = 1.0)
            }
            logs.add(log)
        }

        val first = logs.first()
        for (log in logs) {
            assertThat(log).isEqualTo(first)
        }
    }
}
