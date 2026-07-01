# Resource Reservation / Block Occupancy Primitive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a simulation-time-aware resource primitive so multiple processes can atomically reserve and release shared units, with deterministic wait-queue ordering.

**Architecture:** Implement a `Resource` class backed by a `Head` FIFO wait queue and integer capacity/occupied counters. Blocked processes passivate and are reactivated in queue order when units are released.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-21-resource-reservation`
- Base branch: `toVer0.6.0`; worktree branch: `issue-21-resource-reservation`
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt` | Create | Resource class with reserve/release |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ResourceTest.kt` | Create | Resource behavior tests |

---

## Task 1: Implement Resource class

**Files:**
- Create: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt`

**Interfaces:**
- Produces: `class Resource(capacity: Int)`
- Produces: `Resource.reserve(amount: Int = 1)`, `Resource.release(amount: Int = 1)`, `Resource.isAvailable(amount: Int = 1): Boolean`
- Produces: `Resource.capacity`, `Resource.occupied`

- [ ] **Step 1: Create the file**

```kotlin
package cz.hovorka.kdisco

/**
 * A simulation-time-aware resource with a fixed capacity.
 *
 * Processes call [reserve] to occupy units. If enough units are not available,
 * the calling process is passivated and placed in a FIFO wait queue. When a
 * process calls [release], waiters are reactivated in order until no more units
 * can be handed out.
 *
 * @param capacity total number of units this resource provides
 */
class Resource(capacity: Int = 1) {

    init {
        require(capacity > 0) { "Resource capacity must be positive, got $capacity" }
    }

    /** Total capacity of this resource. */
    val capacity: Int = capacity

    /** Number of units currently occupied. */
    var occupied: Int = 0
        private set

    /** FIFO queue of processes waiting for units. */
    private val waiters = Head()

    /**
     * Returns true if [amount] units can be reserved right now.
     */
    fun isAvailable(amount: Int = 1): Boolean {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        return occupied + amount <= capacity
    }

    /**
     * Atomically occupy [amount] units. If unavailable, the current process is
     * passivated and placed in the wait queue.
     */
    suspend fun reserve(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= capacity) { "Amount $amount exceeds capacity $capacity" }
        val ctx = Process.activeContext ?: throw DiscoException("Not inside a simulation")
        val current = ctx.currentProcess as? Process
            ?: throw DiscoException("No current process")

        if (isAvailable(amount)) {
            occupied += amount
            return
        }

        current.into(waiters)
        current.passivate()

        // After reactivation the process re-enters reserve() and tries again.
        reserve(amount)
    }

    /**
     * Release [amount] units and reactivate waiting processes as long as units
     * remain available.
     */
    fun release(amount: Int = 1) {
        require(amount > 0) { "Amount must be positive, got $amount" }
        require(amount <= occupied) { "Cannot release $amount, only $occupied occupied" }
        occupied -= amount

        while (occupied < capacity) {
            val next = waiters.first() as? Process ?: break
            next.out()
            Process.reactivate(next)
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :kdisco-core:compileKotlinJvm
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt
git commit -m "feat: add Resource reservation primitive (#21)"
```

---

## Task 2: Add Resource tests

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ResourceTest.kt`

**Interfaces:**
- Consumes: `Resource.reserve()`, `Resource.release()`, `Resource.isAvailable()`

- [ ] **Step 1: Create the test file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ResourceTest {

    @Test
    fun singleProcessReserveAndRelease() = runTest {
        val r = Resource()
        var reservedAt = 0.0
        var releasedAt = 0.0
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    reservedAt = time()
                    hold(5.0)
                    r.release()
                    releasedAt = time()
                }
            })
        }
        assertThat(reservedAt).isEqualTo(0.0)
        assertThat(releasedAt).isEqualTo(5.0)
        assertThat(r.occupied).isEqualTo(0)
    }

    @Test
    fun twoProcessesCannotReserveSimultaneously() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("A-${time()}")
                    hold(5.0)
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("B-${time()}")
                    hold(1.0)
                    r.release()
                }
            })
        }
        assertThat(log).containsExactly("A-0.0", "B-5.0")
    }

    @Test
    fun blockedProcessResumesWhenReleased() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        lateinit var blocker: Process
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    log.add("holder-${time()}")
                    hold(3.0)
                    log.add("releasing-${time()}")
                    r.release()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    blocker = this
                    r.reserve()
                    log.add("waiter-${time()}")
                    r.release()
                }
            })
        }
        assertThat(log).containsExactly("holder-0.0", "releasing-3.0", "waiter-3.0")
    }

    @Test
    fun waitersResumeInFifoOrder() = runTest {
        val r = Resource()
        val log = mutableListOf<String>()
        runSimulation(endTime = 20.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    r.reserve()
                    hold(5.0)
                    r.release()
                }
            })
            repeat(3) { i ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        r.reserve()
                        log.add("W$i-${time()}")
                        hold(1.0)
                        r.release()
                    }
                }, delay = i * 0.1)
            }
        }
        assertThat(log).containsExactly("W0-5.0", "W1-6.0", "W2-7.0")
    }

    @Test
    fun capacityGreaterThanOneAllowsConcurrentHolders() = runTest {
        val r = Resource(capacity = 2)
        val log = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            repeat(3) { i ->
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        r.reserve()
                        log.add("H$i-${time()}")
                        hold(2.0)
                        r.release()
                    }
                })
            }
        }
        assertThat(log).containsExactly("H0-0.0", "H1-0.0", "H2-2.0")
    }
}
```

- [ ] **Step 2: Run Resource tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ResourceTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL` with 83+ tests passing.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ResourceTest.kt
git commit -m "test: Resource reservation behavior (#21)"
```

---

## Self-Review

- **Spec coverage:** #21 requirements covered: atomic reserve, wait queue, FIFO resume, capacity > 1.
- **Placeholders:** None; all code and commands are concrete.
- **Type consistency:** `reserve()` is `suspend`, `release()` is synchronous; signatures match simulation semantics.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-21-resource-reservation.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
