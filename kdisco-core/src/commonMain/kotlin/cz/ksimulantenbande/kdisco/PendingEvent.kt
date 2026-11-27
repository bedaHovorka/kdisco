package cz.ksimulantenbande.kdisco

/**
 * A read-only snapshot of a single scheduled event waiting in the simulation's event queue.
 *
 * Instances are returned by [Simulation.pendingEvents] in the same order that
 * [cz.ksimulantenbande.kdisco.EventQueue.removeFirst] would deliver them, preserving the queue's
 * deterministic FIFO/LIFO ordering guarantees.
 *
 * @property process The process that is scheduled to be activated.
 * @property time The simulation time at which the process will be activated.
 * @property priority `true` if this was scheduled as a priority event (LIFO ordering for
 *   equal-time events); `false` for normal events (FIFO ordering for equal-time events).
 * @property insertionOrder Internal scheduling counter that determines relative ordering
 *   within the same simulation time. Non-negative for normal (FIFO) events; negative for
 *   priority (LIFO) events.
 */
data class PendingEvent(val process: Process, val time: Double, val priority: Boolean, val insertionOrder: Long)
