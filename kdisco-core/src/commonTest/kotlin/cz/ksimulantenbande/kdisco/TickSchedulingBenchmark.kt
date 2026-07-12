package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Micro-benchmark isolating kDisco per-tick scheduling cost, modelled on the
 * interlockSim fast-sim tick pattern (a driver process advancing simulation time
 * in fixed `hold(1.0)` ticks).
 *
 * Companion to kDisco issue #55 / interlockSim SP0.13 (interlockSim#738):
 * quantifies the engine-side share (event loop, `hold`, `Process.activate`,
 * `Process.reactivate`, `Continuous` bookkeeping) of a per-tick simulation so
 * it can be compared against interlockSim's tick-level instrumentation numbers.
 *
 * Results are printed as ns/tick; see
 * docs/analysis/2026-07-09-issue-55-per-tick-scheduling-analysis.md for the
 * recorded numbers and analysis.
 */
class TickSchedulingBenchmark {

    data class Result(val label: String, val ticks: Int, val wallMs: Long) {
        val nsPerTick: Double = wallMs * 1_000_000.0 / ticks
        val ticksPerSecond: Double = ticks / (wallMs / 1000.0)
        override fun toString(): String =
            "$label: ticks=$ticks wall=${wallMs}ms ns/tick=${nsPerTick.fmt()} ticks/s=${ticksPerSecond.fmt()}"
    }

    /** Pattern 1: single ticker doing hold(1.0) per tick — the fast-sim main loop shape. */
    private suspend fun runSingleTicker(ticks: Int): Result {
        var executed = 0
        val wallMs = measureTime {
            runSimulation(endTime = ticks + 1.0) {
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        repeat(ticks) {
                            hold(1.0)
                            executed++
                        }
                    }
                })
            }
        }.inWholeMilliseconds
        assertThat(executed).isEqualTo(ticks)
        return Result("single-ticker hold(1.0)", ticks, wallMs)
    }

    /** Pattern 2: N concurrent processes each holding 1.0 per tick (co-scheduled entities). */
    private suspend fun runMultiTicker(processes: Int, ticksEach: Int): Result {
        var executed = 0
        val wallMs = measureTime {
            runSimulation(endTime = ticksEach + 1.0) {
                repeat(processes) {
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(ticksEach) {
                                hold(1.0)
                                executed++
                            }
                        }
                    })
                }
            }
        }.inWholeMilliseconds
        val total = processes * ticksEach
        assertThat(executed).isEqualTo(total)
        return Result("multi-ticker x$processes hold(1.0)", total, wallMs)
    }

    /** Pattern 3: ticker spawning one short-lived process per tick (activate churn). */
    private suspend fun runActivateChurn(ticks: Int): Result {
        var spawnedRuns = 0
        val wallMs = measureTime {
            runSimulation(endTime = ticks + 1.0) {
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        repeat(ticks) {
                            Process.activate(object : Process() {
                                override suspend fun actions() {
                                    spawnedRuns++
                                }
                            })
                            hold(1.0)
                        }
                    }
                })
            }
        }.inWholeMilliseconds
        assertThat(spawnedRuns).isEqualTo(ticks)
        return Result("activate-churn (spawn per tick)", ticks, wallMs)
    }

    /** Pattern 4: ticker reactivating a passivated worker every tick (reactivate churn). */
    private suspend fun runReactivateChurn(ticks: Int): Result {
        var wakes = 0
        val wallMs = measureTime {
            runSimulation(endTime = ticks + 1.0) {
                val worker = object : Process() {
                    override suspend fun actions() {
                        while (true) {
                            passivate()
                            wakes++
                        }
                    }
                }
                Process.activate(worker)
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        repeat(ticks) {
                            hold(1.0)
                            Process.reactivate(worker)
                        }
                    }
                })
            }
        }.inWholeMilliseconds
        assertThat(wakes).isGreaterThanOrEqualTo(ticks - 1)
        return Result("reactivate-churn (wake per tick)", ticks, wallMs)
    }

    /** Pattern 5: ticker with one active Continuous + Variable (RKF45 bookkeeping per tick). */
    private suspend fun runContinuousTicker(ticks: Int): Result {
        var executed = 0
        val wallMs = measureTime {
            runSimulation(endTime = ticks + 1.0) {
                Process.activate(object : Process() {
                    val x = Variable(1.0)
                    val dynamics = object : Continuous() {
                        override fun derivatives() {
                            x.rate = -0.01 * x.state
                        }
                    }

                    override suspend fun actions() {
                        x.start()
                        dynamics.start()
                        repeat(ticks) {
                            hold(1.0)
                            executed++
                        }
                        dynamics.stop()
                        x.stop()
                    }
                })
            }
        }.inWholeMilliseconds
        assertThat(executed).isEqualTo(ticks)
        return Result("continuous-ticker (1 Continuous, 1 Variable)", ticks, wallMs)
    }

    /**
     * Pattern 6: deep-queue cost — N resident processes each holding 1.0 per tick, so the
     * event queue stays at depth ~N throughout the run. Every tick then pays both
     * `EventQueue.removeFirst()` and the re-`schedule()` from `hold()` — each an O(depth)
     * ArrayList array shift (report §6 candidate #1) — while the pop is a *resume* from
     * `hold`, not a fresh coroutine launch, so the per-tick cost is not dominated by the
     * fixed process-launch/terminate overhead the way a preload-drain would be.
     *
     * `ticksPerProcess` is sized so the total event count (`n * ticksPerProcess`) is held
     * roughly constant across the N sweep; `ticks` is that total, so `ns/tick` is the
     * steady-state per-pop cost at queue depth N. Compare across N: ns/tick rises with N
     * (the O(depth) shift), once that term exceeds the fixed per-tick `hold()` cost.
     */
    private suspend fun runDeepQueue(n: Int, ticksPerProcess: Int): Result {
        var executed = 0
        val wallMs = measureTime {
            runSimulation(endTime = n * ticksPerProcess + 1.0) {
                repeat(n) {
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(ticksPerProcess) {
                                hold(1.0)
                                executed++
                            }
                        }
                    })
                }
            }
        }.inWholeMilliseconds
        val totalTicks = n * ticksPerProcess
        assertThat(executed).isEqualTo(totalTicks)
        return Result("deep-queue (N=$n, resident)", totalTicks, wallMs)
    }

    @Test
    fun perTickSchedulingCostBreakdown() = runTest(timeout = 120.seconds) {
        // Warm-up pass so JIT compilation does not dominate the measured runs.
        runSingleTicker(50_000)

        val results = listOf(
            runSingleTicker(1_000_000),
            runMultiTicker(processes = 10, ticksEach = 100_000),
            runActivateChurn(500_000),
            runReactivateChurn(500_000),
            runContinuousTicker(50_000),
            runDeepQueue(n = 100, ticksPerProcess = 2_000),
            runDeepQueue(n = 1_000, ticksPerProcess = 200),
            runDeepQueue(n = 10_000, ticksPerProcess = 20)
        )
        results.forEach { println(it) }

        for (r in results) {
            assertThat(r.ticks).isGreaterThan(0)
            assertThat(r.nsPerTick).isGreaterThan(0.0)
        }
    }
}

private fun Double.fmt(): String = (kotlin.math.round(this * 100) / 100.0).toString()
