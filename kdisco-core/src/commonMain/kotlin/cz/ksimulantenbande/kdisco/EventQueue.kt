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
 * Every mutation keeps [Process.queuedEvents] in step, so a process can answer "do I have a turn
 * queued?" in O(1) without scanning the queue. That count, not the process state alone, is what
 * [Process.isActive] and [Process.activate] consult — a notice-driven resume can change a parked
 * process's state while an independently activated turn is still queued for it.
 *
 * Uses ArrayList with binary search insertion: O(log n) search, O(n) insert.
 * Sufficient for typical simulations; can be replaced with a heap for very large
 * process counts.
 */
internal class EventQueue {
    private val events = mutableListOf<ScheduledEvent>()
    private var normalCounter: Long = 0 // FIFO: ascending — lower order runs first
    private var priorityCounter: Long = -1 // LIFO: descending — higher (less negative) order runs first

    /**
     * Bumped by every structural change to the queue. Lets a caller ask "did anything schedule or
     * cancel while I was running code I do not control?" without diffing the queue — [size] cannot
     * answer it, because a [Process.reactivate] both removes and adds.
     *
     * Used by [ContinuousMonitor] to detect user [Continuous.derivatives] and guard code queueing
     * turns at speculative probe times during crossing location.
     */
    var mutations: Long = 0
        private set

    /**
     * @param noticeRelease true when this event is a notice's wake-up ([Process.waitUntil],
     *   [Process.waitCrossing], [Process.waitUntilCrossing]) rather than a turn the process owns —
     *   a [Process.hold], [Process.activate] or [Process.reactivate]. The two are counted
     *   separately: see [Process.noticeReleases].
     */
    fun schedule(process: Process, time: Double, priority: Boolean = false, noticeRelease: Boolean = false) {
        val order = if (priority) priorityCounter-- else normalCounter++
        val event = ScheduledEvent(process, time, order, noticeRelease, priority)
        val index = findInsertionPoint(time, order)
        events.add(index, event)
        process.queuedEvents++
        if (noticeRelease) process.noticeReleases++
        mutations++
    }

    fun remove(process: Process) {
        events.removeAll { it.process === process }
        process.queuedEvents = 0
        process.noticeReleases = 0
        mutations++
    }

    fun removeFirst(): ScheduledEvent? {
        if (events.isEmpty()) return null
        val event = events.removeAt(0)
        event.process.queuedEvents--
        // Each event carries which channel it came from, so the two counts stay exact. Inferring
        // it here would go wrong whenever a process holds one of each at the same instant: FIFO
        // pops the owned turn first, and charging that to the release would leave the release
        // counted as an owned turn and suppress a later, legitimate activate.
        if (event.noticeRelease) event.process.noticeReleases--
        mutations++
        return event
    }

    fun isEmpty(): Boolean = events.isEmpty()

    /**
     * The process of every queued event, in queue order and with repeats — a process holding two
     * events appears twice. Used by [Simulation.run] at end of run to find the processes whose
     * turns will never be delivered.
     */
    fun scheduledProcesses(): List<Process> = events.map { it.process }

    /**
     * Drops every scheduled event. Called once when [Simulation.run] returns: the run is over and
     * cannot be restarted, so anything still queued is unreachable.
     */
    fun clear() {
        for (event in events) {
            event.process.queuedEvents = 0
            event.process.noticeReleases = 0
        }
        events.clear()
        mutations++
    }

    fun peek(): ScheduledEvent? = events.firstOrNull()

    fun size(): Int = events.size

    /** Returns an ordered snapshot of all pending events without mutating the queue. */
    fun snapshot(): List<PendingEvent> =
        events.map { PendingEvent(it.process, it.time, it.priority, it.insertionOrder) }

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
    /** True when this event is a notice's wake-up rather than a turn [process] owns. */
    val noticeRelease: Boolean = false,
    val priority: Boolean = false,
)
