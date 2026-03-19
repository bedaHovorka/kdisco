package cz.hovorka.kdisco.engine

/**
 * Per-simulation-run state. Replaces jDisco's static globals.
 */
internal class SimulationContext {
    val eventQueue: EventQueue = EventQueue()
    var currentTime: Double = 0.0
    var currentProcess: Process? = null
    var isRunning: Boolean = false
    var stopRequested: Boolean = false
    val pendingActivations = mutableListOf<PendingActivation>()

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

    /**
     * Checks all pending wait conditions. Any process whose condition is now satisfied
     * is scheduled in the event queue at the current simulation time.
     *
     * Called after each discrete event and after each continuous integration step.
     */
    internal fun checkWaitNotices() {
        if (waitNotices.isEmpty()) return
        val satisfied = mutableListOf<WaitNotice>()
        val iter = waitNotices.iterator()
        while (iter.hasNext()) {
            val notice = iter.next()
            if (notice.condition.test()) {
                iter.remove()
                satisfied.add(notice)
            }
        }
        for (notice in satisfied) {
            eventQueue.schedule(notice.process, currentTime)
        }
    }
}

internal class WaitNotice(
    val process: Process,
    val condition: Condition
)
