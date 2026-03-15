package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class SimulationBridgeTest {

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `kotlin actions method is called by jDisco runtime`() {
        var actionsCalled = false

        class TestProcess : Process() {
            override fun actions() {
                actionsCalled = true
            }
        }

        runSimulation(endTime = 1.0) {
            Process.activate(TestProcess())
        }

        assertThat(actionsCalled).isTrue()
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `kotlin derivatives method is called by jDisco runtime`() {
        var derivativesCalled = false

        class TestContinuous : Continuous() {
            val x = Variable(1.0)

            override fun actions() {
                hold(1.0)
            }

            override fun derivatives() {
                derivativesCalled = true
                x.rate = 0.0
            }
        }

        runSimulation(endTime = 1.0) {
            Process.activate(TestContinuous())
        }

        assertThat(derivativesCalled).isTrue()
    }

    @Test
    fun `link registry enables type-safe traversal`() {
        class CustomLink : Link()

        val head = Head()
        val links = List(5) { CustomLink() }

        links.forEach { it.into(head) }

        val traversed = mutableListOf<Link>()
        var current = head.first()
        while (current != null) {
            traversed.add(current)
            if (traversed.size == head.cardinal()) break
            current = current.suc()
        }

        assertThat(traversed).hasSize(5)
        traversed.forEach { link ->
            assertThat(link).isInstanceOf(CustomLink::class)
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `multiple simulations are independent`() {
        class Counter : Process() {
            var count = 0
            override fun actions() {
                count++
            }
        }

        val counter1 = Counter()
        runSimulation(endTime = 1.0) {
            Process.activate(counter1)
        }

        val counter2 = Counter()
        runSimulation(endTime = 1.0) {
            Process.activate(counter2)
        }

        assertThat(counter1.count).isEqualTo(1)
        assertThat(counter2.count).isEqualTo(1)
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `process time reflects simulation clock`() {
        val times = mutableListOf<Double>()

        class TimeTracker : Process() {
            override fun actions() {
                times.add(time())
                hold(10.0)
                times.add(time())
                hold(20.0)
                times.add(time())
            }
        }

        runSimulation(endTime = 100.0) {
            Process.activate(TimeTracker())
        }

        assertThat(times).containsExactly(0.0, 10.0, 30.0)
    }
}
