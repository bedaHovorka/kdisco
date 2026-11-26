// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 *
 * Creation stack traces are disabled before installing: they would make every coroutine creation
 * capture a stack trace — distorting the benchmarks — and bloat the dump with frames the hang
 * diagnosis does not need.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeoutDiagnosticsExtension : TestExecutionExceptionHandler {
    init {
        DebugProbes.enableCreationStackTraces = false
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

    // Thread.id is deprecated since Java 19 in favour of threadId(), which does not exist on the
    // jvmTarget 11 this module compiles for.
    @Suppress("DEPRECATION")
    private fun threadDump(): String = buildString {
        for ((thread, stack) in Thread.getAllStackTraces()) {
            val daemon = if (thread.isDaemon) "daemon " else ""
            appendLine("\"${thread.name}\" ${daemon}prio=${thread.priority} tid=${thread.id} ${thread.state}")
            stack.forEach { appendLine("\tat $it") }
            appendLine()
        }
    }
}
