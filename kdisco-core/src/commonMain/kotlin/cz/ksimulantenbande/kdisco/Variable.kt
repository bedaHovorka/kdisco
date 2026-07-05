// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Represents a state variable that varies continuously between discrete events
 * according to ordinary first-order differential equations.
 *
 * The differential equations are expressed in subclasses of [Continuous] via
 * their [Continuous.derivatives] method.
 *
 * @param initialState the initial value of the variable.
 * @see Continuous
 */
class Variable(initialState: Double = 0.0) : Link() {

    /**
     * The current value of the variable.
     *
     * Assigning to this property from within a discrete process (between integration steps)
     * emits a [SimulationEvent.VariableChanged] to registered listeners. Writes performed by
     * the continuous integrator during a step do **not** emit — those are intermediate Runge-Kutta
     * values, not discrete events, and emitting them would flood listeners with meaningless events.
     */
    var state: Double = initialState
        set(value) {
            if (field != value) {
                val old = field
                field = value
                val ctx = Process.activeContext ?: return
                if (ctx.monitorActive) return  // RKF45 intermediate/committed writes — not a discrete event
                if (ctx.eventListeners.isEmpty()) return
                val event = SimulationEvent.VariableChanged(ctx.currentTime, this, old, value)
                ctx.eventListeners.forEach { it(event) }
            }
        }

    /** The derivative with respect to time. Reset to 0.0 at the start of each integration step. */
    var rate: Double = 0.0

    /** The value of [state] at the start of the current integration step. */
    val oldState: Double get() = _oldState

    internal var _oldState: Double = initialState

    // Runge-Kutta stage coefficients
    internal var k1: Double = 0.0
    internal var k2: Double = 0.0
    internal var k3: Double = 0.0
    internal var k4: Double = 0.0
    internal var k5: Double = 0.0
    internal var k6: Double = 0.0

    // 5th-order step accumulator (used by RKF45)
    internal var ds: Double = 0.0

    // Intrusive linked list for the active-variable list in SimulationContext.
    // Separate from Link.predRef / sucRef which are used for Head-list membership.
    // Mirrors jDisco's Variable.pred / Variable.suc.
    // When first in the list: _pred == this.
    // When not in any list: _pred == null && _suc == null.
    internal var _pred: Variable? = null
    internal var _suc: Variable? = null

    /**
     * Returns true if this variable is currently in the active-variable list.
     */
    fun isStarted(): Boolean = _pred != null

    /**
     * Returns the value of [state] at the start of the current integration step.
     * Equivalent to [oldState]. Intended for difference-equation descriptions.
     */
    fun lastState(): Double = _oldState

    /**
     * Inserts this variable into the active-variable list maintained by the simulation.
     *
     * Calling [start] when already active has no effect.
     * If called outside an active simulation context, this is a no-op.
     * Must only be called from a discrete process (not during integration).
     *
     * @return this (for chaining: `Variable(0.0).start()`)
     * @throws DiscoException if called during integration.
     */
    fun start(): Variable {
        val ctx = Process.activeContext ?: return this  // no-op outside simulation (matches jDisco behaviour)
        if (ctx.monitorActive) throw DiscoException("Illegal call of start (class Variable)")
        if (_pred == null) {
            val first = ctx.firstVar
            if (first == null) {
                // Only element: _pred points to self (sentinel pattern from jDisco)
                ctx.firstVar = this
                _pred = this
            } else {
                // Insert at head of list
                _suc = first
                _pred = this
                first._pred = this
                ctx.firstVar = this
            }
        }
        return this
    }

    /**
     * Removes this variable from the active-variable list.
     *
     * Calling [stop] when not active has no effect.
     * If called outside an active simulation context, this is a no-op.
     * Must only be called from a discrete process (not during integration).
     *
     * @throws DiscoException if called during integration.
     */
    fun stop() {
        val ctx = Process.activeContext ?: return  // no-op outside simulation (matches jDisco behaviour)
        if (ctx.monitorActive) throw DiscoException("Illegal call of stop (class Variable)")
        if (_pred != null) {
            if (_pred !== this) {
                // Not the first element: link predecessor to successor
                _pred!!._suc = _suc
            } else {
                // This is the first element: advance firstVar.
                // Mirrors jDisco: firstVar = pred = suc (sets pred=suc as a tmp value for subsequent suc.pred assignment)
                ctx.firstVar = _suc
                _pred = _suc  // temporarily set _pred = _suc so the block below sets suc._pred = suc (self-ref as new first)
            }
            if (_suc != null) {
                _suc!!._pred = _pred  // if we removed first: new first's _pred = itself (self-ref)
                _suc = null
            }
            _pred = null
            rate = 0.0
        }
    }
}
