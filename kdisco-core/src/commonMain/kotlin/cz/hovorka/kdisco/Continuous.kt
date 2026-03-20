package cz.hovorka.kdisco

/**
 * Base class for describing the continuous processes of a simulation model.
 *
 * Override [derivatives] to compute the current derivatives (or difference equations)
 * of state variables:
 * ```kotlin
 * class Dynamics : Continuous() {
 *     override fun derivatives() {
 *         x.rate = -x.state * time()
 *     }
 * }
 * ```
 *
 * Active continuous processes are executed in descending priority order (high-value-first).
 * Processes with equal priority are executed in the order they became active (FIFO).
 *
 * Since [Continuous] extends [Process], every continuous process also participates in
 * the discrete-event scheduler. The default [actions] simply [passivate]s until reactivated,
 * which is correct for "pure integrator" subclasses (e.g. [SimpleIntegration]). Override
 * [actions] when the continuous process also needs discrete scheduling behaviour (e.g. a
 * motor control loop).
 *
 * @see Variable
 * @see Process
 */
abstract class Continuous : Process() {

    internal var _priority: Double = 0.0

    // Intrusive linked list for the active-continuous list in SimulationContext.
    // Separate from Link.predRef / sucRef which are used for Head-list membership.
    // Mirrors jDisco's Continuous.pred / Continuous.suc.
    // When first in the list: _pred == this.
    // When not in any list: _pred == null && _suc == null.
    internal var _pred: Continuous? = null
    internal var _suc: Continuous? = null

    /**
     * Computes the current derivatives (rates) and/or difference equations for active [Variable]s.
     * Called by the integration monitor during each time step.
     *
     * Implement this method to set [Variable.rate] for each state variable this process governs.
     */
    abstract fun derivatives()

    /**
     * Default process behaviour: passivate and wait to be reactivated.
     *
     * "Pure integrator" subclasses (those that only provide [derivatives]) do not need
     * discrete scheduling; they can simply wait forever once activated.
     * Override this method when the continuous process also needs a discrete control loop
     * (e.g. a motor that alternates between accelerating and passivating).
     */
    override suspend fun actions() {
        passivate()
    }

    /**
     * Returns true if this continuous process is currently in the active-continuous list.
     */
    fun isActive(): Boolean = _pred != null

    /**
     * Returns the current priority of this continuous process.
     */
    fun getPriority(): Double = _priority

    /**
     * Inserts this continuous process into the active list, ordered by descending priority.
     * Processes with equal priority are positioned after existing equal-priority processes (FIFO).
     *
     * Calling [start] when already active has no effect.
     * If called outside an active simulation context, this is a no-op.
     * Must only be called from a discrete process (not during integration).
     *
     * @return this (for chaining: `Dynamics().setPriority(2.0).start()`)
     * @throws DiscoException if called during integration.
     */
    open fun start(): Continuous {
        val ctx = Process.activeContext ?: return this  // no-op outside simulation (matches jDisco behaviour)
        if (ctx.monitorActive) throw DiscoException("Illegal call of start (class Continuous)")
        if (_pred == null) {
            if (ctx.firstCont == null) {
                // First element: pred points to self
                ctx.lastCont = this
                ctx.firstCont = this
                _pred = this
            } else if (_priority > ctx.firstCont!!._priority) {
                // Higher priority than current first: insert at head
                _suc = ctx.firstCont
                ctx.firstCont!!._pred = this
                ctx.firstCont = this
                _pred = this
            } else {
                // Search from the end for the correct insertion point (descending priority)
                var p = ctx.lastCont!!
                while (_priority > p._priority) {
                    p = p._pred!!
                }
                // Insert after p (so we appear after equal-priority processes — FIFO)
                _pred = p
                _suc = p._suc
                p._suc = this
                if (_suc == null) {
                    ctx.lastCont = this
                } else {
                    _suc!!._pred = this
                }
            }
        }
        return this
    }

    /**
     * Removes this continuous process from the active list.
     *
     * Calling [stop] when not active has no effect.
     * If called outside an active simulation context, this is a no-op.
     * Must only be called from a discrete process (not during integration).
     *
     * @throws DiscoException if called during integration.
     */
    open fun stop() {
        val ctx = Process.activeContext ?: return  // no-op outside simulation (matches jDisco behaviour)
        if (ctx.monitorActive) throw DiscoException("Illegal call of stop (class Continuous)")
        if (_pred != null) {
            if (_pred !== this) {
                // Not the first element
                _pred!!._suc = _suc
            } else {
                // First element: firstCont advances to successor.
                // _pred is set to _suc so the block below assigns suc._pred = suc (new-first self-ref).
                ctx.firstCont = _suc
                _pred = _suc
            }
            if (_suc == null) {
                ctx.lastCont = _pred
            } else {
                _suc!!._pred = _pred
            }
            _pred = null
            _suc = null
        }
    }

    /**
     * Sets the priority of this continuous process.
     *
     * The active list is ordered by descending priority. If this process is currently active,
     * it is re-inserted at the correct position.
     *
     * Must only be called from a discrete process (not during integration).
     *
     * @return this (for chaining)
     * @throws DiscoException if called during integration.
     */
    open fun setPriority(p: Double): Continuous {
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        if (ctx.monitorActive) throw DiscoException("Illegal call of setPriority (class Continuous)")
        _priority = p
        if (_pred != null) {
            stop()
            start()
        }
        return this
    }
}
