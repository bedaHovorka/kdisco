// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Per-simulation-run state. Replaces jDisco's static globals.
 */
internal class SimulationContext {
    val eventQueue: EventQueue = EventQueue()
    var currentTime: Double = 0.0
    var currentProcess: Process? = null
    var isRunning: Boolean = false
    var stopRequested: Boolean = false

    /** Seeded random number generator for this simulation run. */
    var random: Random = Random()

    val pendingActivations = mutableListOf<PendingActivation>()

    /** Registered event listeners. Empty list = zero-overhead path (guard is isEmpty()). */
    val eventListeners: MutableList<(SimulationEvent) -> Unit> = mutableListOf()

    /**
     * Emits an event to every registered listener, in registration order.
     *
     * [factory] runs only when at least one listener is registered, so a run with no listeners
     * never allocates a [SimulationEvent] — this is the single implementation of the zero-overhead
     * path documented above, replacing the guard-and-dispatch block that used to be hand-written at
     * every emit site. `inline` keeps it exactly as cheap as those copies were.
     */
    internal inline fun emit(factory: () -> SimulationEvent) {
        if (eventListeners.isEmpty()) return
        val event = factory()
        eventListeners.forEach { it(event) }
    }

    // --- Continuous simulation state ---

    /** Head of the active [Continuous] list, ordered by descending priority. */
    internal var firstCont: Continuous? = null

    /** Tail of the active [Continuous] list. */
    internal var lastCont: Continuous? = null

    /** Head of the active [Variable] list. */
    internal var firstVar: Variable? = null

    /**
     * True while the [ContinuousMonitor] is running an integration step.
     * Used to enforce that [Variable.start]/[Variable.stop]/[Continuous.start]/[Continuous.stop]
     * are only called from discrete processes.
     */
    internal var monitorActive: Boolean = false

    /** Minimum integration step size. Must be > 0 and <= [dtMax]. Default: 1e-5. */
    var dtMin: Double = 1e-5

    /** Maximum integration step size. Must be >= [dtMin]. Default: 1.0. */
    var dtMax: Double = 1.0

    /** Maximum absolute integration error per step. Default: 1e-5. */
    var maxAbsError: Double = 1e-5

    /** Maximum relative integration error per step. Default: 1e-5. */
    var maxRelError: Double = 1e-5

    /** The continuous integration driver. Created once per simulation run. */
    internal val monitor: ContinuousMonitor = ContinuousMonitor(this)

    // Wait-until registry: processes suspended waiting for a condition to become true
    internal val waitNotices = mutableListOf<WaitNotice>()

    // State-event registry: processes suspended waiting for a guard function to cross zero.
    // Located precisely within an integration step by the ContinuousMonitor (root-finding).
    internal val crossingNotices = mutableListOf<CrossingNotice>()

    /**
     * Checks all pending wait conditions. Any process whose condition is now satisfied
     * is scheduled in the event queue at the current simulation time.
     *
     * Called after each discrete event and after each continuous integration step.
     *
     * [Condition.test] is user code evaluated while [waitNotices] is being iterated, so a condition
     * that mutates the registry — by calling [Process.reactivate] or [Process.terminate] on any
     * process — can throw a concurrent-modification error. Conditions are expected to be pure.
     */
    internal fun checkWaitNotices() {
        if (waitNotices.isEmpty()) return
        // Allocated lazily: a model with one live waitUntil runs this on every discrete event and
        // every integration step, and almost none of those calls have anything to release.
        var satisfied: MutableList<WaitNotice>? = null
        val iter = waitNotices.iterator()
        while (iter.hasNext()) {
            val notice = iter.next()
            if (notice.condition.test()) {
                iter.remove()
                val list = satisfied ?: mutableListOf<WaitNotice>().also { satisfied = it }
                list.add(notice)
            }
        }
        val released = satisfied ?: return
        for (notice in released) {
            // Scheduled unconditionally. A satisfied notice and a queued event are two distinct
            // resumes owed to the same process (issue #73): the notice says "your wait is over",
            // an independent Process.activate says "here is another turn". Letting a queued event
            // stand in for the notice's wake-up silently spends one intent on the other. A surplus
            // event is harmless — Simulation.run resumes a stored continuation when there is one
            // and refuses to relaunch a terminated process.
            eventQueue.schedule(notice.process, currentTime)
        }
    }

    /**
     * Checks all pending *level-triggered* crossing notices (see [Process.waitUntilCrossing]).
     * Any notice whose guard is now satisfied (`guard() <= 0`) is removed and its process is
     * scheduled at the current simulation time.
     *
     * Called after each discrete event and after each continuous integration step — the same
     * cadence as [checkWaitNotices]. This is what makes [Process.waitUntilCrossing] safe for
     * threshold-reach conditions on variables that may come to rest: a guard that became
     * satisfied without a sign change being observed at integration-step endpoints (e.g. via
     * a discrete state change, or a stalled variable already past the threshold) still
     * releases the waiting process.
     *
     * @return true if at least one notice fired (integration should stop so the scheduler
     *   can process the newly-scheduled event), false otherwise.
     */
    internal fun checkLevelCrossings(): Boolean {
        if (crossingNotices.isEmpty()) return false
        var satisfied: MutableList<CrossingNotice>? = null
        val iter = crossingNotices.iterator()
        while (iter.hasNext()) {
            val notice = iter.next()
            if (notice.levelTriggered && notice.guard() <= 0.0) {
                iter.remove()
                val list = satisfied ?: mutableListOf<CrossingNotice>().also { satisfied = it }
                list.add(notice)
            }
        }
        val released = satisfied ?: return false
        for (notice in released) {
            // Unconditional, for the same reason as checkWaitNotices (issue #73).
            eventQueue.schedule(notice.process, currentTime)
        }
        return true
    }
}

/**
 * A pending condition wait: [process] is parked until [condition] tests true.
 *
 * Deliberately *not* a data class. [Process.waitUntil] identifies its own notice by reference when
 * it re-parks after a spurious wake-up, so structural equality would let it match a different
 * process's notice over the same condition. The same holds for [CrossingNotice].
 */
internal class WaitNotice(val process: Process, val condition: Condition)

/**
 * A pending state event: a suspended [process] waiting for the [guard] function `g(state, t)`
 * to change sign. The [ContinuousMonitor] evaluates [guard] across each integration step and,
 * on a sign change, locates the crossing time by root-finding and resumes [process] there.
 *
 * @param tolerance the absolute value below which `|g|` is considered to be on the boundary,
 *   used to terminate root-finding early.
 * @param levelTriggered when true (see [Process.waitUntilCrossing]), the notice is
 *   *level-triggered*: it fires as soon as `guard() <= 0` holds — re-tested after every
 *   discrete event and every accepted integration step by
 *   [SimulationContext.checkLevelCrossings] — and only downward (positive → non-positive)
 *   transitions within a step are root-found. When false (default, [Process.waitCrossing]),
 *   the notice is *edge-triggered*: it fires only on a strict sign change of the guard at
 *   accepted integration-step endpoints.
 */
internal class CrossingNotice(
    val process: Process,
    val guard: () -> Double,
    val tolerance: Double,
    val levelTriggered: Boolean = false,
)
