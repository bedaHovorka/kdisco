package cz.hovorka.kdisco.engine

/**
 * Event queue (replaces jDisco's SQS). Maintains scheduled events
 * sorted by time, with FIFO ordering for equal times.
 *
 * Uses ArrayList with binary search insertion. O(log n) search, O(n) insert.
 * Sufficient for typical simulations. Can be optimized to a heap if needed
 * for simulations with very large numbers of concurrent processes.
 */
internal class EventQueue {
    private val events = mutableListOf<ScheduledEvent>()
    private var normalCounter: Long = 0      // non-priority: 0, 1, 2, … (ascending)
    private var priorityCounter: Long = -1   // priority: -1, -2, -3, … (descending)

    fun schedule(process: Process, time: Double, priority: Boolean = false) {
        val order = if (priority) priorityCounter-- else normalCounter++
        val event = ScheduledEvent(process, time, order)
        val index = findInsertionPoint(time, order)
        events.add(index, event)
    }

    fun remove(process: Process) {
        events.removeAll { it.process === process }
    }

    fun removeFirst(): ScheduledEvent? {
        return if (events.isEmpty()) null else events.removeAt(0)
    }

    fun isEmpty(): Boolean = events.isEmpty()

    fun peek(): ScheduledEvent? = events.firstOrNull()

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
    val insertionOrder: Long
)
