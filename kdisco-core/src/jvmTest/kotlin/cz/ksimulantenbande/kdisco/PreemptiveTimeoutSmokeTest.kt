// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Manual check for kdisco#86: a body that spins without suspending must fail by timeout, and the
 * failure output must hold a coroutine dump and a thread dump ([TimeoutDiagnosticsExtension]).
 * Remove [Disabled] to run it; it is expected to FAIL.
 */
@Disabled("manual check for kdisco#86 — run by removing @Disabled")
class PreemptiveTimeoutSmokeTest {
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun spinningBodyFailsByTimeout() {
        @Suppress("ControlFlowWithEmptyBody")
        while (true) {
            Thread.onSpinWait()
        }
    }
}
