# Headless Simulation Control API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose an external control API so a host application can throttle, pause, and single-step a running kDisco simulation.

**Architecture:** Build a `SimulationController` around the existing `beforeEvent` hook and `nextEventTime()`. The controller maintains paused/step/throttle state and is invoked once per event loop iteration.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-25-headless-control`
- Base branch: `toVer0.6.0`; worktree branch: `issue-25-headless-control`
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationController.kt` | Create | Pause/resume/step/throttle logic |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | Add convenience `run(endTime, controller)` and state queries |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/SimulationControlTest.kt` | Create | Control API tests |

---

## Task 1: Implement SimulationController

**Files:**
- Create: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationController.kt`

**Interfaces:**
- Produces: `class SimulationController`
- Produces: `SimulationController.beforeEvent()`, `pause()`, `resume()`, `step()`, `setThrottle()`
- Consumes: `kotlinx.coroutines.delay`

- [ ] **Step 1: Create the controller file**

```kotlin
package cz.hovorka.kdisco

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * External control surface for a running [Simulation].
 *
 * Pass [beforeEvent] to [Simulation.run]:
 *
 * ```kotlin
 * val controller = SimulationController()
 * simulation { ... }.run(endTime, beforeEvent = controller::beforeEvent)
 * controller.pause()
 * controller.step()
 * controller.resume()
 * ```
 */
class SimulationController {

    private var paused: Boolean = false
    private var stepsRequested: Int = 0
    private var throttleFactor: Double = 0.0

    private val wallClockStart = TimeSource.Monotonic.markNow()
    private var simTimeAtStart: Double = 0.0

    /** True when the simulation is currently paused. */
    fun isPaused(): Boolean = paused && stepsRequested == 0

    /** Pause before processing the next event. */
    fun pause() {
        paused = true
        stepsRequested = 0
    }

    /** Resume normal execution. */
    fun resume() {
        paused = false
        stepsRequested = 0
    }

    /**
     * Advance exactly one event and then pause again.
     * Call only while paused or from outside the simulation.
     */
    fun step() {
        paused = true
        stepsRequested++
    }

    /**
     * Set target real-time factor. 0.0 disables throttling.
     * A factor of 1.0 means simulation time advances at wall-clock speed.
     */
    fun setThrottle(realTimeFactor: Double) {
        require(realTimeFactor >= 0.0) { "Throttle factor must be non-negative, got $realTimeFactor" }
        throttleFactor = realTimeFactor
    }

    /**
     * Hook invoked by [Simulation.run] before each event.
     */
    suspend fun beforeEvent() {
        if (stepsRequested > 0) {
            stepsRequested--
            if (stepsRequested == 0) paused = true
            return
        }

        if (paused) {
            // Busy-wait-ish pause: suspend briefly and let the caller decide when to step/resume.
            // In a real GUI loop the host calls step()/resume() from another coroutine.
            delay(10)
            // Re-invoke next iteration; if still paused we delay again.
            return
        }

        applyThrottle()
    }

    private suspend fun applyThrottle() {
        if (throttleFactor == 0.0) return
        val wallElapsed = wallClockStart.elapsedNow().inWholeMilliseconds / 1000.0
        val simElapsed = SimulationContextHolder.context?.currentTime?.minus(simTimeAtStart) ?: return
        val targetWall = simElapsed / throttleFactor
        val lagMs = (targetWall - wallElapsed) * 1000
        if (lagMs > 5) delay(lagMs.toLong())
    }
}
```

Wait: `SimulationContextHolder.context` is internal; the controller is in the same package so it's accessible. However, the controller needs to know the current simulation time. A cleaner approach is to pass the current simulation time into `beforeEvent`. The existing `Simulation.run` hook is `beforeEvent: (suspend () -> Unit)?`. Change the signature or have the controller capture the simulation.

Better: add an overload `Simulation.run(endTime, controller: SimulationController)` that calls `controller.beforeEvent(this)` each iteration. This avoids reaching into `SimulationContextHolder`.

Refined controller:

```kotlin
class SimulationController {
    // ... fields unchanged ...

    /** Hook invoked by [Simulation.run] before each event. */
    suspend fun beforeEvent(simulation: Simulation) {
        if (stepsRequested > 0) {
            stepsRequested--
            if (stepsRequested == 0) paused = true
            return
        }

        while (paused) {
            delay(10)
        }

        applyThrottle(simulation.time())
    }

    private suspend fun applyThrottle(simTime: Double) {
        if (throttleFactor == 0.0) return
        if (simTimeAtStart == 0.0 && simTime > 0.0) {
            // First event after start: record baseline.
            wallClockStart = TimeSource.Monotonic.markNow()
            simTimeAtStart = simTime
        }
        val wallElapsed = wallClockStart.elapsedNow().inWholeMilliseconds / 1000.0
        val simElapsed = simTime - simTimeAtStart
        val targetWall = simElapsed / throttleFactor
        val lagMs = (targetWall - wallElapsed) * 1000
        if (lagMs > 5) delay(lagMs.toLong())
    }
}
```

Use `var wallClockStart` (not val) so it can be reset.

- [ ] **Step 2: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationController.kt
git commit -m "feat: add SimulationController for pause/step/throttle (#25)"
```

---

## Task 2: Wire controller into Simulation

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`

**Interfaces:**
- Consumes: `SimulationController.beforeEvent(Simulation)`
- Produces: `Simulation.run(endTime, controller: SimulationController)`

- [ ] **Step 1: Add convenience overload**

Inside `Simulation`, add after `run(endTime, beforeEvent)`:

```kotlin
/**
 * Runs the simulation under external [controller].
 */
suspend fun run(endTime: Double, controller: SimulationController) {
    run(endTime) { controller.beforeEvent(this) }
}
```

- [ ] **Step 2: Add paused-state query**

Add to `Simulation`:

```kotlin
/** True if the simulation has been requested to stop. */
fun isStopRequested(): Boolean = context.stopRequested
```

- [ ] **Step 3: Run core tests**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt
git commit -m "feat: integrate SimulationController into Simulation.run (#25)"
```

---

## Task 3: Add control API tests

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/SimulationControlTest.kt`

**Interfaces:**
- Consumes: `SimulationController`, `Simulation.run(endTime, controller)`

- [ ] **Step 1: Create the test file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SimulationControlTest {

    @Test
    fun pauseBeforeNextEvent() = runTest {
        val controller = SimulationController()
        val log = mutableListOf<Double>()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    log.add(time())
                    hold(1.0)
                    log.add(time())
                }
            })
        }
        controller.pause()
        launch { sim.run(10.0, controller) }
        assertThat(controller.isPaused()).isTrue()
        assertThat(log).isEmpty()
    }

    @Test
    fun stepAdvancesOneEvent() = runTest {
        val controller = SimulationController()
        val log = mutableListOf<Double>()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    repeat(3) {
                        log.add(time())
                        hold(1.0)
                    }
                }
            })
        }
        controller.pause()
        launch { sim.run(10.0, controller) }
        controller.step()
        assertThat(log).containsExactly(0.0)
        controller.step()
        assertThat(log).containsExactly(0.0, 1.0)
        controller.resume()
    }

    @Test
    fun throttleApproximatesRealTimeFactor() = runTest {
        val controller = SimulationController()
        val sim = Simulation.create {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    repeat(3) { hold(1.0) }
                }
            })
        }
        controller.setThrottle(1.0)
        val start = kotlin.system.measureTimeMillis {
            sim.run(10.0, controller)
        }
        // 3 events each holding ~1.0s sim time -> ~3s wall time at factor 1.0
        assertThat(start).isBetween(2500L, 4500L)
    }
}
```

- [ ] **Step 2: Run control tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.SimulationControlTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/SimulationControlTest.kt
git commit -m "test: pause, step, resume, and throttle (#25)"
```

---

## Self-Review

- **Spec coverage:** #25 requirements covered: pause/resume/step, throttle, time queries, beforeEvent hook usage.
- **Placeholders:** None.
- **Type consistency:** `SimulationController.beforeEvent(Simulation)` is called by `Simulation.run(endTime, controller)`.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-25-headless-control.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
