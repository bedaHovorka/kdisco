# Multi-Process Scheduling API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide first-class support and documented semantics for running many concurrent `Process` instances with deterministic event interleaving.

**Architecture:** Add small convenience APIs to query scheduler state from a process-safe context, and add a multi-process example/test demonstrating 5+ concurrent processes.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-20-multi-process-scheduling`
- Base branch: `toVer0.6.0`; worktree branch: `issue-20-multi-process-scheduling`
- **Dependencies:** This plan assumes #23 (Process lifecycle) and #22 (Deterministic reproducibility) are available.
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | Add scheduler-query helpers |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt` | Modify | Document deterministic scheduling semantics |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/MultiProcessTest.kt` | Create | 5+ concurrent process test |

---

## Task 1: Add scheduler query helpers

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt`

**Interfaces:**
- Produces: `Simulation.scheduledEventCount(): Int`, `Simulation.activeProcessCount(): Int`
- Produces: `Process.Companion.scheduledEventCount()`

- [ ] **Step 1: Add Simulation helpers**

In `Simulation`:

```kotlin
/** Number of events currently waiting in the event queue. */
fun scheduledEventCount(): Int {
    // EventQueue is internal; expose count via a new method or property.
    return context.eventQueue.size()
}

/** Number of processes that are active (running or scheduled) plus passivated. */
fun activeProcessCount(): Int {
    return context.pendingActivations.size + context.eventQueue.size() +
            (if (context.currentProcess != null) 1 else 0)
}
```

This requires adding a `size()` method to `EventQueue`:

```kotlin
fun size(): Int = events.size
```

- [ ] **Step 2: Add Process companion helper**

In `Process.companion`:

```kotlin
/** Number of events currently scheduled in the active simulation. */
fun scheduledEventCount(): Int {
    val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
    return ctx.eventQueue.size()
}
```

- [ ] **Step 3: Document deterministic scheduling in Process KDoc**

Add to the class-level KDoc:

```kotlin
/**
 * ... existing text ...
 *
 * When multiple processes are activated at the same simulation time, the engine
 * orders them deterministically by activation order (FIFO for normal activations).
 * Paired with a fixed [Random] seed, repeated runs produce identical event logs.
 */
```

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/EventQueue.kt
git commit -m "feat: scheduler query helpers and deterministic scheduling docs (#20)"
```

---

## Task 2: Add 5+ concurrent process test

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/MultiProcessTest.kt`

**Interfaces:**
- Consumes: `runSimulation(seed)`, `Process.random()`, `Process.isActive()`, `Simulation.scheduledEventCount()`

- [ ] **Step 1: Create the test file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MultiProcessTest {

    @Test
    fun fiveConcurrentWorkersAdvanceDeterministically() = runTest {
        val seed = 12345L
        val logs = mutableListOf<List<String>>()

        repeat(10) {
            val log = mutableListOf<String>()
            runSimulation(endTime = 50.0, seed = seed) {
                repeat(5) { id ->
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(4) {
                                log.add("W$id-${time()}")
                                hold(random().uniform(1.0, 5.0))
                            }
                        }
                    }, delay = id * 0.5)
                }
            }
            logs.add(log)
        }

        val first = logs.first()
        for (log in logs) {
            assertThat(log).isEqualTo(first)
        }
        assertThat(first.size).isEqualTo(20)
    }

    @Test
    fun schedulerReportsMultipleActiveProcesses() = runTest {
        var peak = 0
        runSimulation(endTime = 10.0) {
            repeat(5) { id ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        peak = maxOf(peak, Process.scheduledEventCount() + 1)
                        hold(5.0)
                    }
                }, delay = id * 0.1)
            }
        }
        assertThat(peak).isGreaterThanOrEqualTo(3)
    }
}
```

- [ ] **Step 2: Run multi-process tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.MultiProcessTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/MultiProcessTest.kt
git commit -m "test: deterministic multi-process scheduling (#20)"
```

---

## Self-Review

- **Spec coverage:** #20 requirements covered: scheduler query API, deterministic ordering, 5+ concurrent process test.
- **Placeholders:** None.
- **Type consistency:** `EventQueue.size()` is added and used by both `Simulation` and `Process`.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-20-multi-process-scheduling.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
