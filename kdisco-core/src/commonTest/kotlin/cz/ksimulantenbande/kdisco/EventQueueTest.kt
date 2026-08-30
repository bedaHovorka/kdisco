// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlin.test.Test

class EventQueueTest {

    private class TestProcess : Process() {
        override suspend fun actions() {}
    }

    @Test
    fun emptyQueueReturnsNull() {
        val eq = EventQueue()
        assertThat(eq.isEmpty()).isTrue()
        assertThat(eq.removeFirst()).isNull()
    }

    @Test
    fun scheduledEventsReturnedInTimeOrder() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        val p3 = TestProcess()
        eq.schedule(p1, 10.0)
        eq.schedule(p2, 5.0)
        eq.schedule(p3, 15.0)

        val first = eq.removeFirst()!!
        assertThat(first.time).isEqualTo(5.0)
        assertThat(first.process).isEqualTo(p2)

        val second = eq.removeFirst()!!
        assertThat(second.time).isEqualTo(10.0)
        assertThat(second.process).isEqualTo(p1)

        val third = eq.removeFirst()!!
        assertThat(third.time).isEqualTo(15.0)
        assertThat(third.process).isEqualTo(p3)

        assertThat(eq.isEmpty()).isTrue()
    }

    @Test
    fun sameTimeFIFOOrder() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        val p3 = TestProcess()
        eq.schedule(p1, 5.0)
        eq.schedule(p2, 5.0)
        eq.schedule(p3, 5.0)

        assertThat(eq.removeFirst()!!.process).isEqualTo(p1)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p2)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p3)
    }

    @Test
    fun priorityInsertsBeforeSameTime() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        val p3 = TestProcess()
        eq.schedule(p1, 5.0)
        eq.schedule(p2, 5.0)
        eq.schedule(p3, 5.0, priority = true)  // should go before p1 and p2

        assertThat(eq.removeFirst()!!.process).isEqualTo(p3)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p1)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p2)
    }

    @Test
    fun removeProcessFromQueue() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        val p3 = TestProcess()
        eq.schedule(p1, 5.0)
        eq.schedule(p2, 10.0)
        eq.schedule(p3, 15.0)

        eq.remove(p2)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p1)
        assertThat(eq.removeFirst()!!.process).isEqualTo(p3)
        assertThat(eq.isEmpty()).isTrue()
    }

    @Test
    fun peekDoesNotRemove() {
        val eq = EventQueue()
        val p1 = TestProcess()
        eq.schedule(p1, 5.0)

        val peeked = eq.peek()!!
        assertThat(peeked.process).isEqualTo(p1)
        assertThat(eq.isEmpty()).isFalse()
    }

    @Test
    fun containsTracksEveryQueuedEventOfAProcess() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        assertThat(eq.contains(p1)).isFalse()

        eq.schedule(p1, 5.0)
        eq.schedule(p1, 10.0)
        eq.schedule(p2, 7.0)
        assertThat(eq.contains(p1)).isTrue()
        assertThat(eq.contains(p2)).isTrue()

        eq.removeFirst() // p1 at t=5
        // p1 still has its t=10 event
        assertThat(eq.contains(p1)).isTrue()

        eq.remove(p1)
        assertThat(eq.contains(p1)).isFalse()
        assertThat(eq.contains(p2)).isTrue()

        eq.removeFirst() // p2 at t=7
        assertThat(eq.contains(p2)).isFalse()
        assertThat(eq.isEmpty()).isTrue()
    }
}
