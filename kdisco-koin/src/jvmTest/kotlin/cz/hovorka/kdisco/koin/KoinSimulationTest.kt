package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.Head
import cz.hovorka.kdisco.Process
import org.junit.jupiter.api.Timeout
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the kDisco–Koin bridge.
 *
 * Requires jdisco-1.2.0.jar on the classpath.
 */
class KoinSimulationTest {

    // ── Shared simulation infrastructure ──────────────────────

    /** Simple FIFO queue backed by kDisco's Head. */
    class ServiceQueue {
        val head = Head()
        val served = mutableListOf<Double>()

        fun enqueue(p: Process) = p.into(head)
        fun dequeue(): Process? = head.first() as? Process
    }

    /** Collects statistics. */
    class SimStats {
        val arrivals = mutableListOf<Double>()
        val departures = mutableListOf<Double>()
    }

    // ── Processes using KoinProcess ───────────────────────────

    class Customer(private val id: Int) : KoinProcess() {
        private val queue: ServiceQueue by inject()
        private val stats: SimStats by inject()

        override fun actions() {
            stats.arrivals.add(time())
            queue.enqueue(this)
            passivate() // wait for server
            stats.departures.add(time())
        }
    }

    class Server(private val serviceTime: Double) : KoinProcess() {
        private val queue: ServiceQueue by inject()

        override fun actions() {
            while (true) {
                // waitUntil returns immediately if queue is already non-empty,
                // or suspends until a customer arrives (condition re-evaluated after each event).
                // During MAIN cleanup, jDisco throws TerminateException which exits the loop cleanly.
                waitUntil { !queue.head.empty() }
                val next = queue.dequeue() ?: return
                next.out()
                hold(serviceTime)
                Process.activate(next)
            }
        }
    }

    // ── Koin module ──────────────────────────────────────────

    private val shopModule = simulationModule {
        single { ServiceQueue() }
        single { SimStats() }
        factory { params -> Customer(params.get()) }
        factory { params -> Server(params.get<Double>()) }
    }

    // ── Tests ────────────────────────────────────────────────

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun koinSimulationInjectsSharedState() {
        var stats: SimStats? = null

        koinSimulation(shopModule) {
            stats = get()

            val server: Server = get { parametersOf(3.0) }
            Process.activate(server)

            repeat(5) { i ->
                val customer: Customer = get { parametersOf(i) }
                Process.activate(customer, delay = i * 2.0)
            }

            simulation.run(100.0)
        }

        // All 5 customers should have arrived and been served
        assertEquals(5, stats!!.arrivals.size, "All customers should arrive")
        assertEquals(5, stats!!.departures.size, "All customers should depart")
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun eachSimulationGetsIsolatedKoinContext() {
        val statsPerRun = mutableListOf<SimStats>()

        repeat(3) {
            koinSimulation(shopModule) {
                val stats: SimStats = get()
                statsPerRun.add(stats)

                val server: Server = get { parametersOf(1.0) }
                Process.activate(server)

                val customer: Customer = get { parametersOf(0) }
                Process.activate(customer)

                simulation.run(10.0)
            }
        }

        // Each run should have produced its own SimStats instance
        assertEquals(3, statsPerRun.size)
        assertTrue(
            statsPerRun[0] !== statsPerRun[1] && statsPerRun[1] !== statsPerRun[2],
            "Each simulation run should have an isolated Koin context"
        )
    }
}
