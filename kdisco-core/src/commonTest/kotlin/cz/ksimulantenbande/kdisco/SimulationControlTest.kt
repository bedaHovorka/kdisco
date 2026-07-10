// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test

class SimulationControlTest {

    @Test
    fun pauseBeforeNextEvent() = runTest {
        val controller = SimulationController()
        val log = mutableListOf<Double>()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    log.add(time())
                    hold(1.0)
                    log.add(time())
                }
            })
        }
        controller.pause()
        val job = launch { sim.run(10.0, controller) }
        yield()
        assertThat(controller.isPaused()).isTrue()
        assertThat(log).isEmpty()
        controller.resume()
        job.join()
    }

    @Test
    fun stepAdvancesOneEvent() = runTest {
        val controller = SimulationController()
        val log = mutableListOf<Double>()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    repeat(3) {
                        log.add(time())
                        hold(1.0)
                    }
                }
            })
        }
        controller.pause()
        val job = launch { sim.run(10.0, controller) }
        yield()
        controller.step()
        yield()
        assertThat(log).containsExactly(0.0)
        controller.step()
        yield()
        assertThat(log).containsExactly(0.0, 1.0)
        controller.resume()
        job.join()
    }

    @Test
    fun throttleApproximatesRealTimeFactor() = runTest {
        val controller = SimulationController()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    repeat(3) { hold(1.0) }
                }
            })
        }
        controller.setThrottle(1.0)
        val start = currentTime
        sim.run(10.0, controller)
        val elapsed = currentTime - start
        // 3 events each holding ~1.0s sim time -> ~3s virtual time at factor 1.0
        assertThat(elapsed).isBetween(2500L, 4500L)
    }
}
