package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * §4.6 — Bouncing balls simulation (headless, no AWT/Swing).
 *
 * Tests: Continuous ODE for position, waitUntil wall-hit state events,
 * discrete velocity inversion on bounce (Variable.state mutation).
 *
 * Design: each Ball is a Continuous process that handles its own bouncing
 * in actions(), using waitUntil to detect wall crossings.  This avoids
 * race conditions that would occur if a separate BounceHandler process
 * modified the velocity asynchronously.
 */
class BouncingBallsTest {

    private val W      = 250.0
    private val H      = 150.0
    private val RADIUS = 10.0
    private val END_T  = 10.0

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `bouncing balls stay within bounds after collisions`() {
        // Capture initial positions to verify movement later
        val x0 = doubleArrayOf(50.0, 130.0, 200.0)
        val y0 = doubleArrayOf(40.0,  80.0, 120.0)

        class Ball(xi: Double, yi: Double, var vx: Double, var vy: Double) : Continuous() {
            val x = Variable(xi)
            val y = Variable(yi)

            override fun derivatives() {
                x.rate = vx
                y.rate = vy
            }

            override fun actions() {
                x.start(); y.start()

                val endT = time() + END_T
                while (time() < endT) {
                    // Wait for any wall hit or end of simulation
                    waitUntil {
                        x.state <= RADIUS || x.state >= W - RADIUS ||
                        y.state <= RADIUS || y.state >= H - RADIUS ||
                        time() >= endT
                    }
                    if (time() >= endT) break

                    // Reflect velocity and clamp position
                    if (x.state <= RADIUS)          { vx =  kotlin.math.abs(vx); x.state = RADIUS }
                    else if (x.state >= W - RADIUS) { vx = -kotlin.math.abs(vx); x.state = W - RADIUS }
                    if (y.state <= RADIUS)          { vy =  kotlin.math.abs(vy); y.state = RADIUS }
                    else if (y.state >= H - RADIUS) { vy = -kotlin.math.abs(vy); y.state = H - RADIUS }

                    // Wait until clear of the wall before checking the next hit
                    waitUntil {
                        (x.state > RADIUS && x.state < W - RADIUS &&
                         y.state > RADIUS && y.state < H - RADIUS) ||
                        time() >= endT
                    }
                }

                x.stop(); y.stop()
            }
        }

        val ball1 = Ball(x0[0], y0[0],  18.0,  12.0)
        val ball2 = Ball(x0[1], y0[1], -15.0,  10.0)
        val ball3 = Ball(x0[2], y0[2],   8.0, -20.0)
        val balls  = listOf(ball1, ball2, ball3)

        val savedDtMin = dtMin; val savedDtMax = dtMax
        runSimulation(endTime = END_T) {
            dtMin = 1.0e-4; dtMax = 0.05   // small step for accurate event detection
            balls.forEach { Process.activate(it) }
        }
        dtMin = savedDtMin; dtMax = savedDtMax

        // All balls must be within the box
        for (ball in balls) {
            assertThat(ball.x.state).isBetween(RADIUS, W - RADIUS)
            assertThat(ball.y.state).isBetween(RADIUS, H - RADIUS)
        }

        // At least one ball must have moved (simulation ran)
        val anyMoved = balls.indices.any { i ->
            val dx = balls[i].x.state - x0[i]
            val dy = balls[i].y.state - y0[i]
            dx * dx + dy * dy > 1.0
        }
        assertThat(anyMoved).isTrue()
    }
}
