package cz.hovorka.kdisco

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
     */
    suspend fun reserve(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        val current = ctx.currentProcess as? Process
            ?: throw DiscoException("No current process")

        if (isAvailable(amount)) {
            occupied += amount
            val ctx0 = Process.activeContext
        if (ctx0 != null && ctx0.eventListeners.isNotEmpty()) ctx0.eventListeners.forEach { it(SimulationEvent.ResourceReserved(ctx0.currentTime, current, this, amount)) }
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
     */
    fun release(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= occupied) { "Cannot release $amount, only $occupied occupied" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        val current = ctx.currentProcess as? Process
            ?: throw DiscoException("No current process")
        occupied -= amount
        val ctx1 = Process.activeContext
        if (ctx1 != null && ctx1.eventListeners.isNotEmpty()) ctx1.eventListeners.forEach { it(SimulationEvent.ResourceReleased(ctx1.currentTime, current, this, amount)) }

        while (occupied < capacity) {
            val next = waiters.first() as? Process ?: break
            next.out()
            Process.reactivate(next)
        }
    }
}
