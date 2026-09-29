// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProcessFailureUncaughtTest {

    @Test
    fun unhandledFailureStillReachesUncaughtExceptionHandlerAndRunContinues() {
        val boom = IllegalStateException("nobody listens")
        val events = mutableListOf<SimulationEvent>()
        var survivorFinishedAt = -1.0
        var completed = false
        val sim = simulation {
            onEvent { if (it is SimulationEvent.ProcessFailed) events += it }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    throw boom
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(4.0)
                    survivorFinishedAt = time()
                }
            })
        }
        // With no onProcessFailure listener and stopOnProcessFailure off, the failure is not
        // swallowed: it goes to the uncaught-exception handler, which runTest turns into a failure.
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(boom)
        assertThat(completed).isTrue()
        assertThat(events).hasSize(1)
        assertThat(survivorFinishedAt).isEqualTo(4.0)
    }
}
