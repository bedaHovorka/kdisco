// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.debug.DebugProbes
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeoutException

/**
 * Auto-registered JUnit extension (kdisco#86). Installs [DebugProbes] once per test JVM and, when a
 * test fails by timeout, prints a coroutine dump and a jstack-style dump of all threads to stderr
 * so the CI log shows where the test hung. The original failure is rethrown unchanged.
 */
class TimeoutDiagnosticsExtension : TestExecutionExceptionHandler {
    init {
        DebugProbes.install()
    }

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        if (throwable is TimeoutException) {
            System.err.println(timeoutReport(context.displayName))
        }
        throw throwable
    }

    private fun timeoutReport(testName: String): String = buildString {
        appendLine("=== kdisco timeout diagnostics for '$testName' ===")
        appendLine("--- coroutine dump (DebugProbes.dumpCoroutines) ---")
        appendLine(captureCoroutineDump())
        appendLine("--- thread dump (all threads) ---")
        append(threadDump())
        appendLine("=== end of kdisco timeout diagnostics ===")
    }

    private fun captureCoroutineDump(): String {
        val out = ByteArrayOutputStream()
        PrintStream(out, true, Charsets.UTF_8).use { DebugProbes.dumpCoroutines(it) }
        return out.toString(Charsets.UTF_8)
    }

    private fun threadDump(): String = buildString {
        for ((thread, stack) in Thread.getAllStackTraces()) {
            val daemon = if (thread.isDaemon) "daemon " else ""
            appendLine("\"${thread.name}\" ${daemon}prio=${thread.priority} tid=${thread.id} ${thread.state}")
            stack.forEach { appendLine("\tat $it") }
            appendLine()
        }
    }
}
