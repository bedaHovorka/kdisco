package cz.hovorka.kdisco

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
                    log.add("A-${time()}")
                    hold(5.0)
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("B-${time()}")
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
                    log.add("holder-${time()}")
                    hold(3.0)
                    log.add("releasing-${time()}")
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    blocker = this
                    r.reserve()
                    log.add("waiter-${time()}")
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
                        log.add("W$i-${time()}")
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
                        log.add("H$i-${time()}")
                        hold(2.0)
                        r.release()
                    }
                })
            }
        }
        assertThat(log).containsExactly("H0-0.0", "H1-0.0", "H2-2.0")
    }
}
