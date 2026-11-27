// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Pins the documented re-entry behaviour of [Resource.reserve] (kdisco#89). */
class ResourceReserveReentryTest {

    private class Waiter(private val name: String, private val r: Resource, private val grants: MutableList<String>) :
        Process() {
        override suspend fun actions() {
            r.reserve()
            grants.add(name)
            hold(1.0)
            r.release()
        }
    }

    @Test
    fun reactivatingQueuedWaiterMovesItToFifoTail() = runTest {
        val r = Resource()
        val grants = mutableListOf<String>()
        runSimulation(endTime = 100.0) {
            // Holder occupies the only unit until t=10.
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    hold(10.0)
                    r.release()
                }
            })
            val a = Waiter("A", r, grants)
            Process.activate(a, delay = 1.0)
            Process.activate(Waiter("B", r, grants), delay = 2.0)
            Process.activate(Waiter("C", r, grants), delay = 3.0)
            // Reactivates A while it is queued: it re-enters reserve() and re-queues at the tail.
            Process.activate(
                object : Process() {
                    override suspend fun actions() {
                        Process.reactivate(a)
                    }
                },
                delay = 5.0,
            )
        }
        assertThat(grants).containsExactly("B", "C", "A")
    }
}
