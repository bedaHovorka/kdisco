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
    fun snapshotMatchesRemoveFirstOrder() {
        val eq = EventQueue()
        val pEarly = TestProcess()
        val pLate = TestProcess()
        val pNormal1 = TestProcess()
        val pNormal2 = TestProcess()
        val pPriority = TestProcess()

        // Mixed: two different times, two FIFO at same time, one priority at same time
        eq.schedule(pEarly, 3.0)
        eq.schedule(pNormal1, 5.0)
        eq.schedule(pNormal2, 5.0)
        eq.schedule(pPriority, 5.0, priority = true)
        eq.schedule(pLate, 10.0)

        // Snapshot must reflect the same order as removeFirst() would yield
        val snapshot = eq.snapshot()
        assertThat(snapshot.size).isEqualTo(5)

        // Verify each entry's process, time, and priority flag match removal order
        val removed = mutableListOf<ScheduledEvent>()
        while (true) removed.add(eq.removeFirst() ?: break)

        assertThat(snapshot.map { it.process }).isEqualTo(removed.map { it.process })
        assertThat(snapshot.map { it.time }).isEqualTo(removed.map { it.time })
        assertThat(snapshot.map { it.priority }).isEqualTo(removed.map { it.priority })
        assertThat(snapshot.map { it.insertionOrder }).isEqualTo(removed.map { it.insertionOrder })
    }

    @Test
    fun snapshotDoesNotMutateQueue() {
        val eq = EventQueue()
        val p1 = TestProcess()
        val p2 = TestProcess()
        eq.schedule(p1, 5.0)
        eq.schedule(p2, 10.0)

        val before = eq.size()
        eq.snapshot()
        assertThat(eq.size()).isEqualTo(before)
    }

    @Test
    fun snapshotPriorityFlagReflectsScheduleCall() {
        val eq = EventQueue()
        val pNormal = TestProcess()
        val pPriority = TestProcess()
        eq.schedule(pNormal, 5.0, priority = false)
        eq.schedule(pPriority, 5.0, priority = true)

        val snapshot = eq.snapshot()
        // Priority event runs first (LIFO), so it appears first in the snapshot
        assertThat(snapshot[0].process).isEqualTo(pPriority)
        assertThat(snapshot[0].priority).isTrue()
        assertThat(snapshot[1].process).isEqualTo(pNormal)
        assertThat(snapshot[1].priority).isFalse()
    }
}
