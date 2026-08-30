// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * A simulation-time-aware resource with a fixed capacity.
 *
 * Processes call [reserve] to occupy units. If enough units are not available,
 * the calling process is passivated and placed in a FIFO wait queue. When a
 * process calls [release], waiters are reactivated in FIFO order as long as the
 * head waiter's request fits within the truly available capacity.
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

    /** Amount each queued process is waiting to reserve. */
    private val waitAmounts = HashMap<Process, Int>()

    /**
     * Units pre-granted by [release] to reactivated processes that have not yet
     * physically incremented [occupied].  These units are logically unavailable
     * for any new reservation until the grantee runs and calls [reserve] again.
     */
    private val granted = HashMap<Process, Int>()

    /** Running sum of [granted] values — avoids iterating the map on every call. */
    private var totalGranted: Int = 0

    /**
     * Removes any [granted] entries whose grantee terminated before it could re-enter
     * [reserve] to collect its units. This happens when a waiter is granted units by
     * [release] (which schedules its reactivation) but something else terminates it
     * before its reactivation event runs — e.g. another process scheduled for the same
     * simulated instant with a lower event-queue insertion order calls [Process.terminate]
     * on it first, which removes its pending reactivation event entirely. Without this
     * reclaim, those units would stay booked in [totalGranted] forever.
     */
    private fun reclaimTerminatedGrants() {
        val dead = granted.keys.filter { it.terminated() }
        for (p in dead) {
            totalGranted -= granted.remove(p)!!
        }
    }

    /**
     * Returns true if [amount] units can be reserved right now (ignoring queue state).
     */
    fun isAvailable(amount: Int = 1): Boolean {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        return occupied + amount <= capacity
    }

    /**
     * Atomically occupy [amount] units. If unavailable, the current process is
     * passivated and placed in the wait queue.
     */
    suspend fun reserve(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        val current = ctx.currentProcess
            ?: throw DiscoException("No current process")
        if (ctx.monitorActive) throw DiscoException("Illegal call of reserve (class Resource)")

        // A process reactivated by release() has already been pre-granted its units.
        val alreadyGranted = granted.remove(current)
        if (alreadyGranted != null) {
            totalGranted -= alreadyGranted
            occupied += alreadyGranted
            if (ctx.eventListeners.isNotEmpty()) {
                val event = SimulationEvent.ResourceReserved(ctx.currentTime, current, this, alreadyGranted)
                ctx.eventListeners.forEach { it(event) }
            }
            return
        }

        reclaimTerminatedGrants()

        // Only take units immediately if:
        //  - no process is already waiting (fairness: no leapfrogging), and
        //  - truly-free capacity (excluding units pre-granted to reactivated waiters) suffices.
        if (waiters.empty() && occupied + totalGranted + amount <= capacity) {
            occupied += amount
            if (ctx.eventListeners.isEmpty()) return
            val event = SimulationEvent.ResourceReserved(ctx.currentTime, current, this, amount)
            ctx.eventListeners.forEach { it(event) }
            return
        }

        waitAmounts[current] = amount
        current.into(waiters)
        current.passivate()

        // After reactivation the process re-enters reserve() and collects its pre-grant.
        reserve(amount)
    }

    /**
     * Release [amount] units and reactivate head-of-queue waiters in FIFO order,
     * stopping as soon as the next waiter's request exceeds the truly available capacity.
     */
    fun release(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= occupied) { "Cannot release $amount, only $occupied occupied" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        val current = ctx.currentProcess
            ?: throw DiscoException("No current process")
        if (ctx.monitorActive) throw DiscoException("Illegal call of release (class Resource)")
        occupied -= amount
        if (ctx.eventListeners.isNotEmpty()) {
            val event = SimulationEvent.ResourceReleased(ctx.currentTime, current, this, amount)
            ctx.eventListeners.forEach { it(event) }
        }

        reclaimTerminatedGrants()

        // Reactivate waiters in FIFO order, pre-granting each one's requested units.
        // Stop at the first head waiter whose request exceeds the truly free capacity so
        // that we never wake more processes than can actually be served: this prevents
        // capacity from being stranded when waiters request different amounts.
        var free = capacity - occupied - totalGranted
        while (true) {
            val next = waiters.first() as? Process ?: break
            if (next.terminated()) {
                // Process.terminate() doesn't unlink its Head/Link membership, so a waiter
                // can be terminated while still queued here. Discard it without granting or
                // counting it against `free` — Process.reactivate() would no-op on it anyway,
                // and granting it first would strand its units in `totalGranted` forever
                // since a terminated process never re-enters reserve() to collect them.
                waitAmounts.remove(next)
                next.out()
                continue
            }
            if (free <= 0) break
            val needed = waitAmounts[next]
                ?: error("Invariant violation: process $next is in the waiters queue but has no recorded reserve amount")
            if (needed > free) break
            free -= needed
            totalGranted += needed
            waitAmounts.remove(next)
            granted[next] = needed
            next.out()
            Process.reactivate(next)
        }
    }
}
