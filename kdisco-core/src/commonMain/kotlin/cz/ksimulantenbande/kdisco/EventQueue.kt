// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Event queue. Maintains scheduled events sorted by time.
 *
 * Ordering is fully deterministic: equal-time normal events are ordered by
 * ascending insertion counter (FIFO), and equal-time priority events by
 * descending insertion counter (LIFO). No thread-scheduling dependency exists
 * because the engine runs on a single coroutine dispatcher.
 *
 * For equal times:
 * - Normal events (`priority = false`): FIFO — earlier-scheduled events run first
 *   (ascending insertion counter, so lower order runs first).
 * - Priority events (`priority = true`): LIFO — later-scheduled events run first
 *   (descending insertion counter, so higher/less-negative order runs first).
 *   This matches jDisco behaviour where higher-priority activations take precedence.
 *
 * Uses ArrayList with binary search insertion: O(log n) search, O(n) insert.
 * Sufficient for typical simulations; can be replaced with a heap for very large
 * process counts.
 */
internal class EventQueue {
    private val events = mutableListOf<ScheduledEvent>()
    private var normalCounter: Long = 0      // FIFO: ascending — lower order runs first
    private var priorityCounter: Long = -1   // LIFO: descending — higher (less negative) order runs first

    fun schedule(process: Process, time: Double, priority: Boolean = false) {
        val order = if (priority) priorityCounter-- else normalCounter++
        val event = ScheduledEvent(process, time, order, priority)
        val index = findInsertionPoint(time, order)
        events.add(index, event)
    }

    fun remove(process: Process) {
        events.removeAll { it.process === process }
    }

    fun contains(process: Process): Boolean = events.any { it.process === process }

    fun removeFirst(): ScheduledEvent? {
        return if (events.isEmpty()) null else events.removeAt(0)
    }

    fun isEmpty(): Boolean = events.isEmpty()

    fun peek(): ScheduledEvent? = events.firstOrNull()

    fun size(): Int = events.size

    /** Returns an ordered snapshot of all pending events without mutating the queue. */
    fun snapshot(): List<PendingEvent> = events.map { PendingEvent(it.process, it.time, it.priority, it.insertionOrder) }

    /**
     * Re-inserts an event captured by [snapshot], keeping its original [insertionOrder]
     * so the equal-time ordering of the captured run is reproduced exactly.
     *
     * [schedule] cannot be used for this: it allocates a fresh counter value, which would
     * renumber the restored events and reverse equal-time priority (LIFO) groups. This
     * also advances the queue's counters past the restored value, so events scheduled
     * after a restore still order correctly relative to the restored ones.
     */
    fun restore(process: Process, time: Double, priority: Boolean, insertionOrder: Long) {
        if (priority) {
            if (insertionOrder <= priorityCounter) priorityCounter = insertionOrder - 1
        } else {
            if (insertionOrder >= normalCounter) normalCounter = insertionOrder + 1
        }
        events.add(findInsertionPoint(time, insertionOrder), ScheduledEvent(process, time, insertionOrder, priority))
    }

    private fun findInsertionPoint(time: Double, order: Long): Int {
        var low = 0
        var high = events.size
        while (low < high) {
            val mid = (low + high) / 2
            val e = events[mid]
            if (e.time < time || (e.time == time && e.insertionOrder < order)) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }
}

/**
 * A scheduled event in the event queue.
 */
internal class ScheduledEvent(
    val process: Process,
    val time: Double,
    val insertionOrder: Long,
    val priority: Boolean
)
