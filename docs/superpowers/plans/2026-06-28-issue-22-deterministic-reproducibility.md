# Deterministic Reproducibility and Seed Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make kDisco simulations fully reproducible when given the same seed and expose a seeded random generator to all processes.

**Architecture:** Wire the existing cross-platform `Random` class into `SimulationContext` and expose it through `Simulation` and `Process`. Keep all existing DSL source-compatible by adding optional seed/deterministic parameters.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-22-deterministic-reproducibility`
- Base branch: `toVer0.6.0`; worktree branch: `issue-22-deterministic-reproducibility`
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt` | Modify | Add seeded `Random` field |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | Expose `random`, `deterministic`, seed-aware creation |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt` | Modify | Add `random()` convenience |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/EventQueue.kt` | Modify | Document stable equal-time ordering |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt` | Modify | Add optional `seed` parameter |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/DeterminismTest.kt` | Create | 100-run reproducibility test |

---

## Task 1: Add seeded Random to SimulationContext

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt:7-12`

**Interfaces:**
- Produces: `SimulationContext.random: Random`

- [ ] **Step 1: Add the random field**

Inside `SimulationContext`, after `stopRequested`:

```kotlin
var random: Random = Random()
```

- [ ] **Step 2: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt
git commit -m "feat: add seeded Random to SimulationContext (#22)"
```

---

## Task 2: Expose random and deterministic mode on Simulation

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`

**Interfaces:**
- Consumes: `SimulationContext.random`
- Produces: `Simulation.random`, `Simulation.deterministic`, seed-aware `create`

- [ ] **Step 1: Add public properties**

After the integrator property:

```kotlin
/** The random generator used by this simulation. Processes should use this for reproducible draws. */
val random: Random get() = context.random

/** True when this simulation was created with an explicit seed for reproducibility. */
var deterministic: Boolean = false
    internal set
```

- [ ] **Step 2: Add seed-aware factory**

Change `Simulation.create` to accept an optional seed:

```kotlin
fun create(seed: Long? = null, setup: Simulation.() -> Unit): Simulation {
    val simulation = Simulation()
    if (seed != null) {
        simulation.context.random = Random(seed)
        simulation.deterministic = true
    }
    val previousContext = Process.activeContext
    Process.activeContext = simulation.context
    try {
        simulation.setup()
    } finally {
        Process.activeContext = previousContext
    }
    return simulation
}
```

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt
git commit -m "feat: expose random and deterministic mode on Simulation (#22)"
```

---

## Task 3: Update DSL and Process convenience

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt`

**Interfaces:**
- Consumes: `Simulation.create(seed, setup)`
- Produces: `simulation(seed)`, `runSimulation(endTime, seed)`, `Process.random()`

- [ ] **Step 1: Update DSL functions**

In `Dsl.kt`:

```kotlin
fun simulation(seed: Long? = null, setup: Simulation.() -> Unit): Simulation =
    Simulation.create(seed, setup)

suspend fun runSimulation(
    endTime: Double = Double.MAX_VALUE,
    seed: Long? = null,
    setup: Simulation.() -> Unit
) {
    simulation(seed, setup).run(endTime)
}
```

- [ ] **Step 2: Add Process.random()**

Inside `Process`, after `time()`:

```kotlin
/** Returns the simulation's shared random generator. */
fun random(): Random = context.random
```

- [ ] **Step 3: Document stable event ordering**

In `EventQueue.kt`, update the KDoc header to explicitly state:

```kotlin
/**
 * Event queue. Maintains scheduled events sorted by time.
 *
 * Ordering is fully deterministic: equal-time normal events are ordered by
 * ascending insertion counter (FIFO), and equal-time priority events by
 * descending insertion counter (LIFO). No thread-scheduling dependency exists
 * because the engine runs on a single coroutine dispatcher.
 */
```

- [ ] **Step 4: Run core tests**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/EventQueue.kt
git commit -m "feat: seed-aware DSL and Process.random() (#22)"
```

---

## Task 4: Add 100-run determinism test

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/DeterminismTest.kt`

**Interfaces:**
- Consumes: `runSimulation(endTime, seed)`, `Process.random()`

- [ ] **Step 1: Create the test file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DeterminismTest {

    @Test
    fun seededMultiProcessSimulationIsReproducible() = runTest {
        val seed = 42L
        val logs = mutableListOf<List<String>>()

        repeat(100) {
            val log = mutableListOf<String>()
            runSimulation(endTime = 20.0, seed = seed) {
                class Worker(private val id: Int) : Process() {
                    override suspend fun actions() {
                        repeat(3) {
                            log.add("$id-${time()}-${random().uniform(0.0, 1.0).format(6)}")
                            hold(random().uniform(1.0, 3.0))
                        }
                    }
                }
                Process.activate(Worker(0))
                Process.activate(Worker(1), delay = 0.5)
                Process.activate(Worker(2), delay = 1.0)
            }
            logs.add(log)
        }

        val first = logs.first()
        for (log in logs) {
            assertThat(log).isEqualTo(first)
        }
    }

    private fun Double.format(digits: Int): String = this.asDynamic().toFixed(digits)
}
```

Wait — `asDynamic()` is JS-only. Replace formatting with `String.format` is JVM-only. For KMP use `kotlin.math.round` or compare raw doubles directly:

```kotlin
log.add("$id-${time()}-${random().uniform(0.0, 1.0)}")
```

Since the exact same double sequence is expected, comparing raw doubles is fine.

Corrected test body:

```kotlin
@Test
fun seededMultiProcessSimulationIsReproducible() = runTest {
    val seed = 42L
    val logs = mutableListOf<List<String>>()

    repeat(100) {
        val log = mutableListOf<String>()
        runSimulation(endTime = 20.0, seed = seed) {
            class Worker(private val id: Int) : Process() {
                override suspend fun actions() {
                    repeat(3) {
                        log.add("$id-${time()}-${random().uniform(0.0, 1.0)}")
                        hold(random().uniform(1.0, 3.0))
                    }
                }
            }
            Process.activate(Worker(0))
            Process.activate(Worker(1), delay = 0.5)
            Process.activate(Worker(2), delay = 1.0)
        }
        logs.add(log)
    }

    val first = logs.first()
    for (log in logs) {
        assertThat(log).isEqualTo(first)
    }
}
```

- [ ] **Step 2: Run the new test**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.DeterminismTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL` with 79+ tests passing.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/DeterminismTest.kt
git commit -m "test: 100-run seeded simulation reproducibility (#22)"
```

---

## Self-Review

- **Spec coverage:** #22 requirements covered: seeded Random, deterministic mode, stable equal-time ordering, 100-run test.
- **Placeholders:** None; all code is concrete.
- **Type consistency:** `Random` is the existing expect/actual class; seed API is consistent across `Simulation.create`, `simulation()`, `runSimulation()`.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-22-deterministic-reproducibility.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
