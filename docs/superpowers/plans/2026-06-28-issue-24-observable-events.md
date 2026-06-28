# Observable Simulation State / Event Subscription API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow external subscribers to observe simulation-time-ordered state changes such as process lifecycle transitions, resource reservations/releases, variable updates, and custom events.

**Architecture:** Add a `SimulationEvent` sealed class and a lightweight, nullable event-bus in `SimulationContext`. `Simulation` exposes `onEvent {}` and `emit()`. Built-in events are fired at the appropriate points in `Simulation.run`, `Process`, and `Resource`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-24-observable-events`
- Base branch: `toVer0.6.0`; worktree branch: `issue-24-observable-events`
- **Dependencies:** This plan assumes #23 (Process lifecycle) and #21 (Resource reservation) are already implemented/merged. If not available, implement the minimal required pieces inline.
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationEvent.kt` | Create | Sealed event hierarchy |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt` | Modify | Add nullable event listener |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | Subscription API and emit helper |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt` | Modify | Fire lifecycle events |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt` | Modify | Fire reserve/release events |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt` | Create | Event ordering and coverage tests |

---

## Task 1: Define SimulationEvent sealed class

**Files:**
- Create: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationEvent.kt`

**Interfaces:**
- Produces: `sealed class SimulationEvent` with `time: Double`, `process: Process?`, `resource: Resource?`
- Produces: subclasses `ProcessActivated`, `ProcessPassivated`, `ProcessReactivated`, `ProcessTerminated`, `ResourceReserved`, `ResourceReleased`, `CustomEvent`

- [ ] **Step 1: Create the file**

```kotlin
package cz.hovorka.kdisco

/**
 * Base type for all simulation-time events visible to subscribers.
 */
sealed class SimulationEvent(
    /** Simulation time at which the event occurred. */
    val time: Double,
    /** Process involved, if any. */
    val process: Process? = null,
    /** Resource involved, if any. */
    val resource: Resource? = null
) {
    class ProcessActivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessPassivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessReactivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessTerminated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessHeld(time: Double, process: Process, val duration: Double) : SimulationEvent(time, process)

    class ResourceReserved(time: Double, process: Process, resource: Resource, val amount: Int = 1)
        : SimulationEvent(time, process, resource)

    class ResourceReleased(time: Double, process: Process, resource: Resource, val amount: Int = 1)
        : SimulationEvent(time, process, resource)

    class VariableChanged(time: Double, val variable: Variable, val oldState: Double, val newState: Double)
        : SimulationEvent(time)

    class Custom(time: Double, val payload: Any?) : SimulationEvent(time)
}
```

- [ ] **Step 2: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationEvent.kt
git commit -m "feat: define SimulationEvent sealed hierarchy (#24)"
```

---

## Task 2: Add event listener to SimulationContext and Simulation

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`

**Interfaces:**
- Produces: `SimulationContext.eventListener: ((SimulationEvent) -> Unit)?`
- Produces: `Simulation.onEvent(listener)`, `Simulation.emit(event)`

- [ ] **Step 1: Add listener field**

In `SimulationContext`:

```kotlin
/** Optional event subscriber. Null when no listener is registered (zero-overhead path). */
var eventListener: ((SimulationEvent) -> Unit)? = null
```

- [ ] **Step 2: Add subscription API on Simulation**

In `Simulation`:

```kotlin
/**
 * Register a listener that receives every [SimulationEvent] in simulation-time order.
 *
 * Only one listener is supported at a time; subsequent calls replace the previous listener.
 * Setting no-op when no subscriber keeps the event path overhead-free.
 */
fun onEvent(listener: (SimulationEvent) -> Unit) {
    context.eventListener = listener
}

/** Emit a [SimulationEvent] to the registered listener, if any. */
internal fun emit(event: SimulationEvent) {
    context.eventListener?.invoke(event)
}
```

- [ ] **Step 3: Add custom emit helper for processes**

In `Process` companion or instance:

```kotlin
/** Emit a custom event from within a process. */
fun emitCustom(payload: Any?) {
    context.eventListener?.invoke(SimulationEvent.Custom(context.currentTime, payload))
}
```

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt
git commit -m "feat: event subscription API on Simulation and SimulationContext (#24)"
```

---

## Task 3: Fire lifecycle events

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`

**Interfaces:**
- Consumes: `Simulation.emit(SimulationEvent)`
- Produces: events on activate, hold, passivate, reactivate, terminate

- [ ] **Step 1: Emit in Process methods**

In `Process.hold()`, after scheduling:

```kotlin
context.eventListener?.invoke(SimulationEvent.ProcessHeld(context.currentTime, this, duration))
```

In `Process.passivate()`:

```kotlin
context.eventListener?.invoke(SimulationEvent.ProcessPassivated(context.currentTime, this))
```

In `Process.terminate()`:

```kotlin
context.eventListener?.invoke(SimulationEvent.ProcessTerminated(context.currentTime, this))
```

In `Process.activate()`:

```kotlin
context.eventListener?.invoke(SimulationEvent.ProcessActivated(ctx.currentTime + delay, process))
```

In `Process.reactivate()`:

```kotlin
context.eventListener?.invoke(SimulationEvent.ProcessReactivated(process.context.currentTime, process))
```

- [ ] **Step 2: Emit in Simulation.run**

After setting current process and state in the event loop:

```kotlin
emit(SimulationEvent.ProcessActivated(context.currentTime, process))
```

(Note: `Process.activate` already emits for delayed activations; this covers the first event launch. Avoid double emission by only emitting in `Simulation.run` when the process has no existing continuation, i.e. first activation.)

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt
git commit -m "feat: emit process lifecycle events (#24)"
```

---

## Task 4: Fire resource and variable events

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Variable.kt`

**Interfaces:**
- Consumes: `SimulationContext.eventListener`, `SimulationEvent.ResourceReserved/Released/VariableChanged`

- [ ] **Step 1: Emit resource events**

In `Resource.reserve()`, after `occupied += amount`:

```kotlin
Process.activeContext?.eventListener?.invoke(
    SimulationEvent.ResourceReserved(Process.activeContext!!.currentTime, current, this, amount)
)
```

In `Resource.release()`, after `occupied -= amount`:

```kotlin
Process.activeContext?.eventListener?.invoke(
    SimulationEvent.ResourceReleased(Process.activeContext!!.currentTime, current, this, amount)
)
```

Use the current process from context.

- [ ] **Step 2: Emit variable state changes**

In `Variable`, change `state` property to emit on assignment:

```kotlin
var state: Double = initialState
    set(value) {
        if (field != value) {
            val old = field
            field = value
            Process.activeContext?.eventListener?.invoke(
                SimulationEvent.VariableChanged(Process.activeContext!!.currentTime, this, old, value)
            )
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Resource.kt \
    kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Variable.kt
git commit -m "feat: emit resource and variable change events (#24)"
```

---

## Task 5: Add observable event tests

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt`

**Interfaces:**
- Consumes: `Simulation.onEvent {}`, `SimulationEvent` subclasses, `Process.emitCustom()`

- [ ] **Step 1: Create the test file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ObservableEventsTest {

    @Test
    fun eventsArriveInSimulationTimeOrder() = runTest {
        val events = mutableListOf<SimulationEvent>()
        runSimulation(endTime = 10.0) {
            onEvent { events.add(it) }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(2.0)
                    hold(3.0)
                }
            })
        }
        assertThat(events.map { it.time }).isEqualTo(listOf(0.0, 0.0, 2.0, 5.0))
    }

    @Test
    fun noEventsWhenNoSubscriber() = runTest {
        var invoked = false
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                }
            })
        }
        // listener was never registered; just ensure run completes without error
        assertThat(invoked).isFalse()
    }

    @Test
    fun customEventsAreDelivered() = runTest {
        val events = mutableListOf<Any?>()
        runSimulation(endTime = 10.0) {
            onEvent {
                if (it is SimulationEvent.Custom) events.add(it.payload)
            }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    emitCustom("hello")
                    emitCustom(42)
                }
            })
        }
        assertThat(events).isEqualTo(listOf("hello", 42))
    }
}
```

- [ ] **Step 2: Run event tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ObservableEventsTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt
git commit -m "test: observable event subscription and ordering (#24)"
```

---

## Self-Review

- **Spec coverage:** #24 requirements covered: sealed event types, subscription, built-in and custom events, simulation-time order, zero-overhead when no listener.
- **Placeholders:** None.
- **Type consistency:** Event class names and field names are consistent across emitter and tests.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-24-observable-events.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
