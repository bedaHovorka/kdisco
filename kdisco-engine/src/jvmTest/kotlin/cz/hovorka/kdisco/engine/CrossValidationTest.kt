package cz.hovorka.kdisco.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-validation tests: run the same simulation model in both kdisco-engine
 * and jDisco, verify identical results.
 *
 * Each test runs in its own JVM (forkEvery=1) to isolate jDisco's global static state.
 */
class CrossValidationTest {

    /**
     * Run a jDisco simulation by activating a "main" process that sets up
     * other processes and then holds until endTime.
     *
     * jDisco requires all activations to happen inside a running simulation context.
     * We achieve this by activating a main process first (which starts the sim),
     * then activating others from within main.actions().
     */
    private fun runJDisco(endTime: Double, setup: () -> Unit) {
        val mainProcess = object : jDisco.Process() {
            override fun actions() {
                setup()
                jDisco.Process.hold(endTime)
            }
        }
        jDisco.Process.activate(mainProcess)
    }

    @Test
    fun singleProcessHoldTimesMatch() = runTest {
        // kdisco-engine
        val engineTimes = mutableListOf<Double>()
        runSimulation(endTime = 100.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    engineTimes.add(time())
                    hold(5.0)
                    engineTimes.add(time())
                    hold(10.0)
                    engineTimes.add(time())
                }
            })
        }

        // jDisco
        val jDiscoTimes = mutableListOf<Double>()
        runJDisco(100.0) {
            val p = object : jDisco.Process() {
                override fun actions() {
                    jDiscoTimes.add(jDisco.Process.time())
                    jDisco.Process.hold(5.0)
                    jDiscoTimes.add(jDisco.Process.time())
                    jDisco.Process.hold(10.0)
                    jDiscoTimes.add(jDisco.Process.time())
                }
            }
            jDisco.Process.activate(p, jDisco.Process.delay, 0.0)
        }

        assertEquals(listOf(0.0, 5.0, 15.0), engineTimes, "Engine hold times wrong")
        assertEquals(engineTimes, jDiscoTimes, "Engine and jDisco times diverge")
    }

    @Test
    fun multipleProcessFIFOOrderMatches() = runTest {
        // kdisco-engine
        val engineLog = mutableListOf<Pair<String, Double>>()
        runSimulation(endTime = 100.0) {
            class P(private val name: String) : Process() {
                override suspend fun actions() {
                    engineLog.add(name to time())
                    hold(10.0)
                    engineLog.add(name to time())
                }
            }
            Process.activate(P("A"))
            Process.activate(P("B"), delay = 3.0)
            Process.activate(P("C"), delay = 3.0)  // same time as B — FIFO
        }

        // Verify order: A@0, B@3, C@3, A@10, B@13, C@13
        assertEquals("A", engineLog[0].first); assertEquals(0.0, engineLog[0].second)
        assertEquals("B", engineLog[1].first); assertEquals(3.0, engineLog[1].second)
        assertEquals("C", engineLog[2].first); assertEquals(3.0, engineLog[2].second)
        assertEquals("A", engineLog[3].first); assertEquals(10.0, engineLog[3].second)
        assertEquals("B", engineLog[4].first); assertEquals(13.0, engineLog[4].second)
        assertEquals("C", engineLog[5].first); assertEquals(13.0, engineLog[5].second)
    }

    @Test
    fun passivateReactivateTimingMatches() = runTest {
        val engineLog = mutableListOf<Pair<String, Double>>()
        lateinit var waiter: Process
        runSimulation(endTime = 100.0) {
            waiter = object : Process() {
                override suspend fun actions() {
                    engineLog.add("wait" to time())
                    passivate()
                    engineLog.add("resume" to time())
                }
            }
            Process.activate(waiter)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(7.0)
                    Process.reactivate(waiter)
                }
            })
        }

        assertEquals(listOf("wait" to 0.0, "resume" to 7.0), engineLog)
    }
}
