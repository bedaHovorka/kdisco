// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MultiProcessTest {

    @Test
    fun fiveConcurrentWorkersAdvanceDeterministically() = runTest {
        val seed = 12345L
        val logs = mutableListOf<List<String>>()

        repeat(10) {
            val log = mutableListOf<String>()
            runSimulation(endTime = 50.0, seed = seed) {
                repeat(5) { id ->
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(4) {
                                log.add("W$id-${time()}")
                                hold(random().uniform(1.0, 5.0))
                            }
                        }
                    }, delay = id * 0.5)
                }
            }
            logs.add(log)
        }

        val first = logs.first()
        for (log in logs) {
            assertThat(log).isEqualTo(first)
        }
        assertThat(first.size).isEqualTo(20)
    }

    @Test
    fun schedulerReportsMultipleActiveProcesses() = runTest {
        var peak = 0
        runSimulation(endTime = 10.0) {
            repeat(5) { id ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        peak = maxOf(peak, Process.scheduledEventCount() + 1)
                        hold(5.0)
                    }
                }, delay = id * 0.1)
            }
        }
        assertThat(peak).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun activeProcessCountIsZeroAfterSimulationCompletes() = runTest {
        val sim = Simulation.create {
            repeat(3) { i ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        hold(i * 1.0 + 1.0)
                    }
                })
            }
        }
        sim.run(100.0)
        assertThat(sim.activeProcessCount()).isEqualTo(0)
    }
}
