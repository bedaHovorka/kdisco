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
     * never allocates a [SimulationEvent] — the zero-overhead path documented above. `inline`
     * keeps this exactly as cheap as a hand-written guard-and-dispatch block at each emit site.
     *
     * [eventListeners] is iterated while the event is dispatched, so a listener that registers or
     * unregisters a listener from inside [Simulation.onEvent]'s callback can throw a
     * concurrent-modification error — the same structural-mutation hazard [checkWaitNotices]
     * documents for wait conditions. Listeners are expected not to mutate the registry.
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
     * Removes every notice of [notices] that satisfies [isSatisfied] and returns them, or null
     * when none did. The result is read-only: callers only iterate it to schedule.
     *
     * The release list is allocated lazily, only when something actually fires: the notice checks
     * run after every discrete event and every accepted integration step, and almost none of those
     * calls have anything to release.
     *
     * [isSatisfied] is user code — a wait [Condition] or a crossing guard — evaluated while
     * [notices] is being iterated, so one that mutates the registry, by calling
     * [Process.reactivate] or [Process.terminate] on any process, can throw a
     * concurrent-modification error. Conditions are expected to be pure.
     */
    private inline fun <N> takeSatisfied(notices: MutableList<N>, isSatisfied: (N) -> Boolean): List<N>? {
        if (notices.isEmpty()) return null
        var satisfied: MutableList<N>? = null
        val iter = notices.iterator()
        while (iter.hasNext()) {
            val notice = iter.next()
            if (isSatisfied(notice)) {
                iter.remove()
                if (satisfied == null) {
                    satisfied = mutableListOf(notice)
                } else {
                    satisfied.add(notice)
                }
            }
        }
        return satisfied
    }

    /**
     * Checks all pending wait conditions. Any process whose condition is now satisfied
     * is scheduled in the event queue at the current simulation time.
     *
     * Called after each discrete event and after each continuous integration step.
     *
     * @return how many processes were scheduled — one per released notice. The
     *   [ContinuousMonitor] compares this against [EventQueue.mutations] to tell its own
     *   scheduling apart from anything a condition did as a side effect.
     */
    internal fun checkWaitNotices(): Int {
        val released = takeSatisfied(waitNotices) { it.condition.test() } ?: return 0
        for (notice in released) {
            // Scheduled unconditionally. A satisfied notice and a queued event are two distinct
            // resumes owed to the same process (issue #73): the notice says "your wait is over",
            // an independent Process.activate says "here is another turn". Letting a queued event
            // stand in for the notice's wake-up silently spends one intent on the other. A surplus
            // event is harmless — Simulation.run resumes a stored continuation when there is one,
            // drops the event when it would cut a hold short (see Process.holdDue), and refuses to
            // relaunch a terminated process.
            eventQueue.schedule(notice.process, currentTime, noticeRelease = true)
        }
        return released.size
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
     * @return how many processes were scheduled — one per released notice. Non-zero means
     *   integration should stop so the scheduler can process the newly-scheduled events.
     */
    internal fun checkLevelCrossings(): Int {
        val released = takeSatisfied(crossingNotices) { it.levelTriggered && it.guard() <= 0.0 } ?: return 0
        for (notice in released) {
            // Unconditional, for the same reason as checkWaitNotices (issue #73).
            eventQueue.schedule(notice.process, currentTime, noticeRelease = true)
        }
        return released.size
    }

    /**
     * Drops every outstanding wake-up owned by [process]: a pending pre-run activation, a queued
     * event, a wait notice and a crossing notice. [Process.terminate] and [Process.reactivate]
     * call this so the process cannot be woken by a stale one afterwards.
     *
     * All four sources must be dropped together, which is why this is a single chokepoint rather
     * than a line at each call site. [pendingActivations] is the one that is easy to forget: it
     * holds activations registered before [Simulation.run] starts, and `run` converts each into an
     * event. A process reactivated or terminated during `Simulation.create` setup therefore kept a
     * pending entry that became a *second* event — resuming it mid-[Process.hold], or advancing
     * the clock on behalf of a process that was already dead.
     */
    internal fun dropWakeUpsOf(process: Process) {
        pendingActivations.removeAll { it.process === process }
        eventQueue.remove(process)
        waitNotices.removeAll { it.process === process }
        crossingNotices.removeAll { it.process === process }
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
