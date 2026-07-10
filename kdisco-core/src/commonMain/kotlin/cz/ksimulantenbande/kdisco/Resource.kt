// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * A simulation-time-aware resource with a fixed capacity.
 *
 * Processes call [reserve] to occupy units. If enough units are not available,
 * the calling process is passivated and placed in a FIFO wait queue. When a
 * process calls [release], waiters are reactivated in order until no more units
 * can be handed out.
 *
 * @param capacity total number of units this resource provides
 */
class Resource(capacity: Int = 1) {

    init {
        require(capacity > 0) { "Resource capacity must be positive, got $capacity" }
    }

    /** Total capacity of this resource. */
    val capacity: Int = capacity

    /** Number of units currently occupied. */
    var occupied: Int = 0
        private set

    /** FIFO queue of processes waiting for units. */
    private val waiters = Head()

    /**
     * Returns true if [amount] units can be reserved right now.
     */
    fun isAvailable(amount: Int = 1): Boolean {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        return occupied + amount <= capacity
    }

    /**
     * Atomically occupy [amount] units. If unavailable, the current process is
     * passivated and placed in the wait queue.
     *
     * Must only be called from a discrete process (not during continuous integration).
     *
     * @throws DiscoException if called during integration.
     */
    suspend fun reserve(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        if (ctx.monitorActive) throw DiscoException("Illegal call of reserve (class Resource)")
        val current = ctx.currentProcess as? Process
            ?: throw DiscoException("No current process")

        // Only take units immediately if no one is already waiting — otherwise a late arrival
        // needing fewer units could leapfrog a waiter that needs more than is currently free.
        if (isAvailable(amount) && waiters.empty()) {
            occupied += amount
            if (ctx.eventListeners.isEmpty()) return
            val event = SimulationEvent.ResourceReserved(ctx.currentTime, current, this, amount)
            ctx.eventListeners.forEach { it(event) }
            return
        }

        current.into(waiters)
        current.passivate()

        // After reactivation the process re-enters reserve() and tries again.
        reserve(amount)
    }

    /**
     * Release [amount] units and reactivate waiting processes as long as units
     * remain available.
     *
     * Must only be called from a discrete process (not during continuous integration).
     * Calling this from [Continuous.derivatives] would corrupt resource accounting:
     * the RKF45 adaptive integrator may call [Continuous.derivatives] multiple times
     * per step (including for rejected trial steps), so any side-effect here would be
     * replayed or undone in an uncontrolled way.
     *
     * @throws DiscoException if called during integration.
     */
    fun release(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= occupied) { "Cannot release $amount, only $occupied occupied" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        if (ctx.monitorActive) throw DiscoException("Illegal call of release (class Resource)")
        val current = ctx.currentProcess as? Process
            ?: throw DiscoException("No current process")
        occupied -= amount
        if (ctx.eventListeners.isNotEmpty()) {
            val event = SimulationEvent.ResourceReleased(ctx.currentTime, current, this, amount)
            ctx.eventListeners.forEach { it(event) }
        }

        while (occupied < capacity) {
            val next = waiters.first() as? Process ?: break
            next.out()
            Process.reactivate(next)
        }
    }
}
