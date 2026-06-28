package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class ScaleBenchmark {

    data class Result(
        val n: Int,
        val seed: Long,
        val events: Int,
        val wallMs: Long,
        val simTime: Double
    ) {
        val eventsPerSecond: Double = events / (wallMs / 1000.0)
        val realTimeFactor: Double = simTime / (wallMs / 1000.0)
    }

    suspend fun run(n: Int, seed: Long = 42L, cycles: Int = 20): Result {
        var eventCount = 0
        val simEnd = 100_000.0
        val wallMs = measureTimeMillis {
            runSimulation(endTime = simEnd, seed = seed) {
                val resource = Resource(capacity = 3)
                repeat(n) { id ->
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(cycles) {
                                hold(random().uniform(0.5, 2.0))
                                resource.reserve()
                                eventCount++
                                hold(random().uniform(0.1, 0.5))
                                resource.release()
                                eventCount++
                            }
                        }
                    }, delay = id * 0.1)
                }
            }
        }
        return Result(n, seed, eventCount, wallMs, simEnd)
    }

    @Test
    fun benchmarkReportsStableResults() = runTest {
        val sizes = listOf(1, 5, 10, 20, 50)
        val results = sizes.map { run(it) }

        for (r in results) {
            println("N=${r.n} events=${r.events} wall=${r.wallMs}ms " +
                    "events/s=${r.eventsPerSecond.format()} RTF=${r.realTimeFactor.format()}")
        }

        // Sanity checks
        for (r in results) {
            assertThat(r.events).isGreaterThan(0)
            assertThat(r.eventsPerSecond).isGreaterThan(0.0)
        }

        // 50 workers should still process all events
        val r50 = results.last()
        assertThat(r50.events).isEqualTo(50 * 20 * 2)
    }

    @Test
    fun repeatedRunsAreStable() = runTest {
        val runs = List(5) { run(20) }
        val events = runs.map { it.events }.toSet()
        assertThat(events.size).isEqualTo(1)
    }

    private fun Double.format(): String = (kotlin.math.round(this * 100) / 100.0).toString()
}
