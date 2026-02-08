package cz.hovorka.kdisco

/**
 * JVM actual for [Continuous].
 *
 * jDisco keeps process execution and continuous integration in SEPARATE class hierarchies:
 * - [jDisco.Process] (extends Link) — drives actions()/hold()/passivate()
 * - [jDisco.Continuous] (extends Link) — drives derivatives() via the monitor during hold()
 *
 * A kDisco [Continuous] therefore needs TWO delegates:
 * 1. [jDiscoProcess] — a [jDisco.Process] that runs actions()
 * 2. [jDiscoContinuous] — a [jDisco.Continuous] whose derivatives() calls this.derivatives()
 *
 * [jDiscoContinuous] is automatically started before actions() runs (so derivatives() is called
 * during every hold()), and automatically stopped when actions() returns or terminate() is called.
 */
actual abstract class Continuous actual constructor() : Process() {

    /** Drives the Runge-Kutta integration by calling this class's derivatives(). */
    private val jDiscoContinuous: jDisco.Continuous = object : jDisco.Continuous() {
        override fun derivatives() {
            this@Continuous.derivatives()
        }
    }

    /** Drives the discrete-event lifecycle (actions / hold / passivate). */
    private val jDiscoProcess: jDisco.Process = object : jDisco.Process() {
        override fun actions() {
            // Register this Continuous with the monitor so derivatives() is called during hold().
            jDiscoContinuous.start()
            try {
                this@Continuous.actions()
            } catch (e: SelfTerminateException) {
                // Process called terminate() on itself; jDisco already removed it from the SQS.
                // Returning here lets the coroutine thread exit cleanly without deadlocking.
            } finally {
                // Always deregister from the monitor when actions() ends.
                if (jDiscoContinuous.isActive()) {
                    jDiscoContinuous.stop()
                }
            }
        }

        override fun derivatives() {
            // No-op: handled by jDiscoContinuous above.
        }
    }

    final override val jDiscoProcessDelegate: jDisco.Process
        get() = jDiscoProcess

    actual protected override fun actions() {}

    actual override fun start(): Continuous {
        if (!jDiscoContinuous.isActive()) jDiscoContinuous.start()
        return this
    }

    actual override fun stop() {
        if (jDiscoContinuous.isActive()) jDiscoContinuous.stop()
    }

    actual val isActive: Boolean
        get() = jDiscoContinuous.isActive()

    actual protected abstract fun derivatives()
}
