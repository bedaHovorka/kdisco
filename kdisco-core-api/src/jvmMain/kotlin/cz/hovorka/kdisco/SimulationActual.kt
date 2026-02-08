package cz.hovorka.kdisco

actual class Simulation actual constructor() {
    @Volatile
    private var stopRequested = false

    /**
     * Activations queued during [Simulation.create]'s setup lambda.
     *
     * jDisco's Process.activate() called from the test thread (outside a running simulation)
     * triggers resumeCurrent() → the test thread blocks waiting for each process's mini-simulation
     * to finish. We defer all setup activations here and fire them from within mainProcess.actions()
     * so they all run inside a single, shared simulation.
     */
    internal val pendingActivations = mutableListOf<Pair<jDisco.Process, Double>>()

    actual fun run(endTime: Double) {
        require(endTime >= 0.0) { "End time must be non-negative, got $endTime" }
        stopRequested = false

        val activations = pendingActivations.toList()
        pendingActivations.clear()

        val mainProcess = object : jDisco.Process() {
            override fun actions() {
                // Activate all processes queued during setup, in order, from within the simulation.
                //
                // We use delay_code (jDisco.Process.delay) for ALL activations, including delay=0,
                // instead of direct_code (the no-arg form). Reason: direct_code inserts at the SQS
                // head and calls resumeCurrent(), causing an IMMEDIATE context switch to the activated
                // process. Activating multiple processes one by one with direct_code means they each
                // run (and potentially passivate) before the next one is activated, preventing them
                // from seeing each other in the SQS.
                //
                // With delay_code, each process is inserted at its scheduled time WITHOUT interrupting
                // mainProcess, so the entire activation loop runs before any process executes.
                //
                // Iterating in REVERSE restores the original FIFO order: delay_code appends each
                // new process after existing processes at the same simulation time. Reversing the
                // input list means the first-queued process ends up first in the SQS, exactly
                // as if the user's setup lambda had activated them in that order.
                for ((proc, delay) in activations.reversed()) {
                    jDisco.Process.activate(proc, jDisco.Process.delay, delay)
                }
                // Hold until the requested end time, then let the simulation finish.
                while (time() < endTime && !stopRequested) {
                    val remaining = endTime - time()
                    if (remaining > 0) {
                        hold(remaining)
                    }
                }
            }
        }

        jDisco.Process.activate(mainProcess)
    }

    actual fun time(): Double {
        return jDisco.Process.time()
    }

    actual fun stop() {
        stopRequested = true
    }

    actual companion object {
        /**
         * ThreadLocal set to the current [Simulation] while the [create] setup lambda runs.
         *
         * [Process.activate] checks this to decide whether to queue the activation (setup phase)
         * or call jDisco immediately (inside a running simulation).
         */
        internal val setupSim: ThreadLocal<Simulation?> = ThreadLocal.withInitial { null }

        actual fun create(setup: Simulation.() -> Unit): Simulation {
            val simulation = Simulation()
            setupSim.set(simulation)
            try {
                simulation.setup()
            } finally {
                setupSim.set(null)
            }
            return simulation
        }
    }
}
