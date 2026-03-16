package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * §4.2 — Fire-fighting simulation.
 *
 * Tests: stochastic simulation with seeded Random, Head/Link queues,
 * waitUntil with compound termination condition.
 *
 * Note: jDisco.Histogram has no kDisco wrapper yet (tracked as a follow-up issue).
 * Percent-damage observations are collected in a plain list.
 */
class FireFightingTest {

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `fire-fighting simulation collects damage statistics over one month`() {
        val rand      = Random(54521L)
        val material  = 100.0   // combustible material per house (arbitrary units)
        val fireRate  = 0.5     // mean fires per simulated-hour
        val simEnd    = 720.0   // one simulated month = 720 hours

        val alarmQ  = Head()   // Alarm Links waiting for a fire engine
        val idleQ   = Head()   // idle FireEngine processes
        val damages = mutableListOf<Double>()

        // Each fire alarm is a Link so it can sit in the alarmQ.
        class Alarm : Link()

        // HouseOnFire: local class; Burning ODE is an anonymous Continuous object.
        class HouseOnFire : Process() {
            val size   = Variable(1.0)
            val damage = Variable(0.0)

            override fun actions() {
                size.start(); damage.start()

                val burning = object : Continuous() {
                    override fun derivatives() {
                        size.rate   = 0.5 * size.state
                        damage.rate = size.state
                    }
                }
                burning.start()

                // Raise alarm and wake an idle engine if available
                val alarm = Alarm()
                synchronized(alarmQ) { alarm.into(alarmQ) }
                val engine = synchronized(idleQ) { idleQ.first() }
                if (engine != null) Process.reactivate(engine as Process)

                // Wait until extinguished or total loss
                waitUntil { size.state <= 0.0 || damage.state >= material }

                burning.stop(); size.stop(); damage.stop()

                val pct = (damage.state / material).coerceIn(0.0, 1.0) * 100.0
                synchronized(damages) { damages.add(pct) }
            }
        }

        class FireEngine : Process() {
            override fun actions() {
                while (time() < simEnd) {
                    val alarm = synchronized(alarmQ) { alarmQ.first() as? Alarm }
                    if (alarm == null) {
                        into(idleQ)
                        passivate()
                        out()
                        continue
                    }
                    synchronized(alarmQ) { alarm.out() }
                    hold(0.25)    // travel time
                    hold(0.5)     // extinguishing time (simplified)
                }
            }
        }

        class Incendiary : Process() {
            override fun actions() {
                while (time() < simEnd) {
                    hold(rand.negexp(fireRate))
                    if (time() < simEnd) {
                        Process.activate(HouseOnFire())
                    }
                }
            }
        }

        runSimulation(endTime = simEnd) {
            dtMin = 1.0e-4; dtMax = 0.5
            repeat(3) { Process.activate(FireEngine()) }
            Process.activate(Incendiary())
        }

        // At least ~10 fires occurred (Poisson: rate 0.5/h × 720 h ≈ 360 expected)
        assertThat(damages.size).isGreaterThan(10)
        // All damage percentages are valid
        for (pct in damages) {
            assertThat(pct).isBetween(0.0, 100.0)
        }
    }
}
