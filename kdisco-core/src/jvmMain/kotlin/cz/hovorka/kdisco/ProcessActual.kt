package cz.hovorka.kdisco

/**
 * Thrown by [Process.terminate] to stop the process's coroutine thread cleanly.
 *
 * jDisco's Coroutine.Runner.run() only catches TerminateException. Any other exception that
 * escapes the process body causes a deadlock (detach() is never called, main thread waits
 * forever on Runner.class.wait()). We catch this exception in the delegate's actions() so
 * the thread returns normally and jDisco cleans up properly.
 *
 * Internal so [ContinuousActual] can catch it in its own delegate wrapper.
 */
internal class SelfTerminateException : Throwable()

actual abstract class Process actual constructor() : Link() {
    internal open val jDiscoProcessDelegate: jDisco.Process = object : jDisco.Process() {
        override fun actions() {
            try {
                this@Process.actions()
            } catch (e: SelfTerminateException) {
                // Process called terminate() on itself; jDisco already cancelled it from the SQS.
                // Returning here lets the coroutine thread exit cleanly without deadlocking.
            }
        }

        override fun derivatives() {
            // No-op for discrete processes
        }
    }

    final override val jDiscoDelegate: jDisco.Link
        get() = jDiscoProcessDelegate

    actual protected abstract fun actions()

    actual fun hold(duration: Double) {
        require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
        jDisco.Process.hold(duration)
    }

    actual fun passivate() {
        jDisco.Process.passivate()
    }

    actual open fun terminate() {
        jDisco.Process.cancel(jDiscoProcessDelegate)
        throw SelfTerminateException()
    }

    actual fun time(): Double {
        return jDisco.Process.time()
    }

    actual fun waitUntil(condition: Condition) {
        // Adapt kDisco Condition (fun interface) to jDisco Condition (Java SAM interface)
        jDiscoProcessDelegate.waitUntil { condition.test() }
    }

    actual fun terminated(): Boolean = jDiscoProcessDelegate.terminated()

    actual open fun start(): Process = this

    actual open fun stop() {}

    actual companion object {
        actual fun activate(process: Process, delay: Double) {
            require(delay >= 0.0) { "Delay must be non-negative, got $delay" }
            val sim = Simulation.setupSim.get()
            if (sim != null) {
                // Inside Simulation.create() setup lambda — queue for deferred activation
                sim.pendingActivations.add(process.jDiscoProcessDelegate to delay)
            } else {
                // Inside a running simulation — activate immediately via jDisco
                if (delay > 0.0) {
                    jDisco.Process.activate(process.jDiscoProcessDelegate, jDisco.Process.delay, delay)
                } else {
                    jDisco.Process.activate(process.jDiscoProcessDelegate)
                }
            }
        }

        actual fun reactivate(process: Process) {
            jDisco.Process.reactivate(process.jDiscoProcessDelegate)
        }

        actual fun wait(queue: Head) {
            jDisco.Process.wait(queue.jDiscoDelegate)
        }

        actual fun time(): Double = jDisco.Process.time()

        actual var dtMin: Double
            get() = jDisco.Process.dtMin
            set(value) { jDisco.Process.dtMin = value }

        actual var dtMax: Double
            get() = jDisco.Process.dtMax
            set(value) { jDisco.Process.dtMax = value }

        actual var maxAbsError: Double
            get() = jDisco.Process.maxAbsError
            set(value) { jDisco.Process.maxAbsError = value }

        actual var maxRelError: Double
            get() = jDisco.Process.maxRelError
            set(value) { jDisco.Process.maxRelError = value }
    }
}
