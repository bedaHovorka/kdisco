package cz.hovorka.kdisco

/**
 * Returns a Kotlin [Sequence] that iterates over all links in this [Head].
 */
fun Head.asSequence(): Sequence<Link> = sequence {
    var current = first()
    while (current != null) {
        yield(current)
        current = current.suc()
    }
}

/**
 * Returns a type-filtered [Sequence] over links of a specific type.
 */
inline fun <reified T : Link> Head.asSequenceOf(): Sequence<T> =
    asSequence().filterIsInstance<T>()

/**
 * Minimum integration step size for the active simulation.
 * Must be called from within a simulation (e.g. from a Process.actions() or startAction()).
 */
var dtMin: Double
    get() = Process.activeContext?.dtMin ?: throw DiscoException("Not inside a simulation")
    set(value) {
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        require(value > 0.0) { "dtMin must be positive, got $value" }
        require(value <= ctx.dtMax) { "dtMin ($value) must be <= dtMax (${ctx.dtMax})" }
        ctx.dtMin = value
    }

/** Maximum integration step size for the active simulation. */
var dtMax: Double
    get() = Process.activeContext?.dtMax ?: throw DiscoException("Not inside a simulation")
    set(value) {
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        require(value > 0.0) { "dtMax must be positive, got $value" }
        require(value >= ctx.dtMin) { "dtMax ($value) must be >= dtMin (${ctx.dtMin})" }
        ctx.dtMax = value
    }

/** Maximum absolute integration error per step (RKF45). */
var maxAbsError: Double
    get() = Process.activeContext?.maxAbsError ?: throw DiscoException("Not inside a simulation")
    set(value) {
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        require(value >= 0.0) { "maxAbsError must be non-negative, got $value" }
        ctx.maxAbsError = value
    }

/** Maximum relative integration error per step (RKF45). */
var maxRelError: Double
    get() = Process.activeContext?.maxRelError ?: throw DiscoException("Not inside a simulation")
    set(value) {
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        require(value >= 0.0) { "maxRelError must be non-negative, got $value" }
        ctx.maxRelError = value
    }
