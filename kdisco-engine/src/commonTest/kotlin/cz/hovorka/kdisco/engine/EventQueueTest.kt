package cz.hovorka.kdisco.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EventQueueTest {

    private class TestProcess : Process() {
        override suspend fun actions() {}
    }

    @Test
    fun emptyQueueReturnsNull() {
        val eq = EventQueue()
        assertTrue(eq.isEmpty())
        assertNull(eq.removeFirst())
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
        assertEquals(5.0, first.time)
        assertEquals(p2, first.process)

        val second = eq.removeFirst()!!
        assertEquals(10.0, second.time)
        assertEquals(p1, second.process)

        val third = eq.removeFirst()!!
        assertEquals(15.0, third.time)
        assertEquals(p3, third.process)

        assertTrue(eq.isEmpty())
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

        assertEquals(p1, eq.removeFirst()!!.process)
        assertEquals(p2, eq.removeFirst()!!.process)
        assertEquals(p3, eq.removeFirst()!!.process)
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

        assertEquals(p3, eq.removeFirst()!!.process)
        assertEquals(p1, eq.removeFirst()!!.process)
        assertEquals(p2, eq.removeFirst()!!.process)
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
        assertEquals(p1, eq.removeFirst()!!.process)
        assertEquals(p3, eq.removeFirst()!!.process)
        assertTrue(eq.isEmpty())
    }

    @Test
    fun peekDoesNotRemove() {
        val eq = EventQueue()
        val p1 = TestProcess()
        eq.schedule(p1, 5.0)

        val peeked = eq.peek()!!
        assertEquals(p1, peeked.process)
        assertFalse(eq.isEmpty())
    }
}
