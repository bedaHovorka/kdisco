// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ResourceTest {

    @Test
    fun singleProcessReserveAndRelease() = runTest {
        val r = Resource()
        var reservedAt = 0.0
        var releasedAt = 0.0
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    reservedAt = time()
                    hold(5.0)
                    r.release()
                    releasedAt = time()
                }
            })
        }
        assertThat(reservedAt).isEqualTo(0.0)
        assertThat(releasedAt).isEqualTo(5.0)
        assertThat(r.occupied).isEqualTo(0)
    }

    @Test
    fun twoProcessesCannotReserveSimultaneously() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("A-${fmt(time())}")
                    hold(5.0)
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("B-${fmt(time())}")
                    hold(1.0)
                    r.release()
                }
            })
        }
        assertThat(log).containsExactly("A-0.0", "B-5.0")
    }

    @Test
    fun blockedProcessResumesWhenReleased() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        lateinit var blocker: Process
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("holder-${fmt(time())}")
                    hold(3.0)
                    log.add("releasing-${fmt(time())}")
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    blocker = this
                    r.reserve()
                    log.add("waiter-${fmt(time())}")
                    r.release()
                }
            })
        }
        assertThat(log).containsExactly("holder-0.0", "releasing-3.0", "waiter-3.0")
    }

    @Test
    fun waitersResumeInFifoOrder() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        runSimulation(endTime = 20.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    hold(5.0)
                    r.release()
                }
            })
            repeat(3) { i ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        r.reserve()
                        log.add("W$i-${fmt(time())}")
                        hold(1.0)
                        r.release()
                    }
                }, delay = i * 0.1)
            }
        }
        assertThat(log).containsExactly("W0-5.0", "W1-6.0", "W2-7.0")
    }

    @Test
    fun capacityGreaterThanOneAllowsConcurrentHolders() = runTest {
        val r = Resource(capacity = 2)
        val log = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            repeat(3) { i ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        r.reserve()
                        log.add("H$i-${fmt(time())}")
                        hold(2.0)
                        r.release()
                    }
                })
            }
        }
        assertThat(log).containsExactly("H0-0.0", "H1-0.0", "H2-2.0")
    }

    /**
     * A late arrival needing fewer units must not jump ahead of a waiter that needs more
     * units than are currently free. Regression guard for the Resource queue-jumping fix:
     * `reserve()` only takes immediately when the wait queue is empty.
     */
    @Test
    fun lateArrivalDoesNotJumpWaitingProcess() = runTest {
        val r = Resource(capacity = 3)
        // Names of processes that completed reserve(), in completion order.
        val reservedOrder = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            // Holder occupies 2 of 3 units, leaving 1 free; releases 1 at t=3.
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve(2)
                    hold(3.0)
                    r.release(1)
                }
            })
            // W0 needs 2 units; only 1 is free, so it must wait (ahead of any later arrival).
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve(2)
                    reservedOrder.add("W0")
                }
            })
            // N arrives at t=1 needing only 1 unit. The 1 free unit could serve N, but W0 is
            // already queued — N must not leapfrog W0.
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve(1)
                    reservedOrder.add("N")
                }
            }, delay = 1.0)
        }
        // With the fix: W0 is served first (at t=3, once the holder frees a unit) and N waits.
        // Without the fix: N would grab the free unit at t=1, starving W0.
        assertThat(reservedOrder).containsExactly("W0")
    }

    /**
     * Formats a double with one trailing decimal place, matching JVM `Double.toString()`
     * behaviour on JS where whole numbers are rendered without `.0`.
     */
    private fun fmt(value: Double): String {
        val s = value.toString()
        return if ('.' in s) s else "$s.0"
    }
}
