package cz.hovorka.kdisco.koin

import assertk.assertThat
import assertk.assertions.*
import cz.hovorka.kdisco.engine.Continuous
import cz.hovorka.kdisco.engine.Head
import cz.hovorka.kdisco.engine.Process
import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Integration tests for the kDisco–Koin bridge using kdisco-engine.
 */
class KoinSimulationTest {

    // ── Shared simulation infrastructure ──────────────────────

    /** Simple FIFO queue backed by kDisco's Head. */
    class ServiceQueue {
        val head = Head()
        val served = mutableListOf<Double>()
        var server: Server? = null

        fun enqueue(p: Process) {
            p.into(head)
            // Only wake the server if it is actually passivated (idle), not while it is in hold().
            // Reactivating during hold() would remove its scheduled event and shorten the
            // current service time.
            server?.let { srv -> if (!srv.terminated() && srv.idle) Process.reactivate(srv) }
        }

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

        override suspend fun actions() {
            stats.arrivals.add(time())
            queue.enqueue(this)
            passivate() // wait for server
            stats.departures.add(time())
        }
    }

    class Server(private val serviceTime: Double) : KoinProcess() {
        private val queue: ServiceQueue by inject()
        var idle = false

        override suspend fun actions() {
            while (true) {
                if (queue.head.empty()) {
                    idle = true
                    passivate()
                    idle = false
                }
                val next = queue.dequeue() ?: continue
                next.out()
                hold(serviceTime)
                Process.reactivate(next)
            }
        }
    }

    // ── KoinContinuous test helper ────────────────────────────

    /** A simple Continuous process that uses Koin injection. */
    class TrackedContinuous : KoinContinuous() {
        private val stats: SimStats by inject()
        var derivativesCallCount = 0

        override fun derivatives() {
            derivativesCallCount++
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
    fun koinSimulationInjectsSharedState() = runTest {
        var stats: SimStats? = null

        koinSimulation(shopModule, endTime = 100.0) {
            stats = get()

            val server: Server = get { parametersOf(3.0) }
            // Register server in queue so customers can wake it
            val queue: ServiceQueue = get()
            queue.server = server

            Process.activate(server)

            repeat(5) { i ->
                val customer: Customer = get { parametersOf(i) }
                Process.activate(customer, delay = i * 2.0)
            }
        }

        // All 5 customers should have arrived and been served
        assertThat(stats!!.arrivals.size).isEqualTo(5)
        assertThat(stats!!.departures.size).isEqualTo(5)
    }

    @Test
    fun eachSimulationGetsIsolatedKoinContext() = runTest {
        val statsPerRun = mutableListOf<SimStats>()

        repeat(3) {
            koinSimulation(shopModule, endTime = 10.0) {
                val stats: SimStats = get()
                statsPerRun.add(stats)

                val server: Server = get { parametersOf(1.0) }
                val queue: ServiceQueue = get()
                queue.server = server

                Process.activate(server)

                val customer: Customer = get { parametersOf(0) }
                Process.activate(customer)
            }
        }

        // Each run should have produced its own SimStats instance
        assertThat(statsPerRun.size).isEqualTo(3)
        assertThat(statsPerRun[0]).isNotSameInstanceAs(statsPerRun[1])
        assertThat(statsPerRun[1]).isNotSameInstanceAs(statsPerRun[2])
    }

    @Test
    fun koinSimulationSweepCreatesIsolatedContexts() = runTest {
        val statsList = mutableListOf<SimStats>()

        koinSimulationSweep(shopModule, params = listOf(1.0, 2.0, 3.0), endTime = 50.0) { serviceTime ->
            val stats: SimStats = get()
            statsList.add(stats)

            val server: Server = get { parametersOf(serviceTime) }
            val queue: ServiceQueue = get()
            queue.server = server
            Process.activate(server)

            repeat(3) { i ->
                val customer: Customer = get { parametersOf(i) }
                Process.activate(customer, delay = i * 5.0)
            }
        }

        assertThat(statsList.size).isEqualTo(3)
        assertThat(statsList[0]).isNotSameInstanceAs(statsList[1])
        assertThat(statsList[1]).isNotSameInstanceAs(statsList[2])
    }

    @Test
    fun koinContinuousCanInjectDependencies() = runTest {
        val continuousModule = module {
            single { SimStats() }
            single { TrackedContinuous() }
        }

        var tc: TrackedContinuous? = null
        koinSimulation(continuousModule, endTime = 5.0) {
            tc = get<TrackedContinuous>()
            tc!!.start()
            // Activate a minimal process to drive simulation time forward
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(5.0)
                }
            })
        }

        assertThat(tc!!.derivativesCallCount).isGreaterThan(0)
    }
}
