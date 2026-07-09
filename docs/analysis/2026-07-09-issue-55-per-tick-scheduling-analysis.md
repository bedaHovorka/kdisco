# kDisco Per-Tick Scheduling Cost Analysis (Issue #55)

**Date:** 2026-07-09
**Companion to:** interlockSim SP0.13 ([bedaHovorka/interlockSim#738](https://github.com/bedaHovorka/interlockSim/issues/738))
**kDisco version analysed:** 0.6.0 (the exact version pinned at both the interlockSim
baseline `c3eb0f66` and the regressed tip — the version confound is already ruled out)

## 1. Purpose

interlockSim's `fast-sim example shuntingLoop 60` regressed ~0.2 s → ~0.6 s wall time.
This report is the kDisco-side preparation for SP0.13: a deep static analysis of every
kDisco code path executed per fast-sim tick, plus micro-benchmark measurements that
quantify the *maximum possible* kDisco-scheduling share of that regression — so that
SP0.13's Phase-1 tick-level instrumentation numbers can be compared against a known
engine baseline.

## 2. TL;DR / Verdict

**kDisco per-tick scheduling cost is ~1.5–4 µs per tick on the JVM.** For a fast-sim run
in the shuntingLoop-60 range (order of 10²–10⁴ `hold(1.0)` ticks), the entire kDisco
engine share is **well under ~40 ms even in the most pessimistic pattern** — roughly two
orders of magnitude below the observed ~400 ms regression. Unless interlockSim performs
tens of thousands of `activate`/`reactivate` calls *per tick*, kDisco scheduling cannot
be a material share of the regression. This is consistent with the SP0.7 suspicion
(eager per-tick observation building on the interlockSim side: two full `snapshot()`
calls + six unconditional `findNextReservationTarget` graph walks per tick).

**Expected SP0.13 outcome for issue #55:** close as not-applicable, using the numbers in
§4 as the exonerating engine baseline — unless Phase-1 instrumentation shows a kDisco
share ≥ tens of milliseconds, in which case §6 lists the concrete engine hotspots to
attack, in priority order.

## 3. Static analysis: what kDisco executes per tick

A fast-sim tick is one `hold(1.0)` cycle of a driver process. Per scheduler-loop
iteration (`Simulation.run`, `Simulation.kt:113–179`) the engine performs:

| # | Step | Code | Cost class |
|---|------|------|-----------|
| 1 | `ensureActive()` cancellation check | `Simulation.kt:114` | O(1), no alloc |
| 2 | `beforeEvent?.invoke()` | `Simulation.kt:115` | O(1) null check (fast-sim passes none) |
| 3 | `eventQueue.peek()` | `Simulation.kt:118` | O(1) |
| 4 | Continuous-integration guard `firstCont != null` | `Simulation.kt:126` | O(1) when no `Continuous` active |
| 5 | `eventQueue.removeFirst()` → `ArrayList.removeAt(0)` | `EventQueue.kt:40–42` | **O(n) array shift** (n = queued events) |
| 6 | Resume continuation `cont.resumeWith(...)` | `Simulation.kt:151` | O(1) + coroutine state-machine dispatch |
| 7 | `checkWaitNotices()` | `SimulationContext.kt:63–79` | O(1) when no `waitUntil` in use (isEmpty guard) |

And inside `hold(duration)` (`Process.kt:70–86`), once per tick:

| # | Step | Code | Cost class |
|---|------|------|-----------|
| 8 | `require(duration >= 0.0)` | `Process.kt:71` | O(1) |
| 9 | `suspendCancellableCoroutine` | `Process.kt:72` | **1 `CancellableContinuationImpl` allocation per hold** |
| 10 | `eventQueue.schedule(...)` binary-search insert | `EventQueue.kt:27–32` | O(log n) search + O(n) insert shift + **1 `ScheduledEvent` allocation** |
| 11 | Event-listener guard | `Process.kt:76` | O(1) when no listeners (`isEmpty` fast path) |
| 12 | `cont.invokeOnCancellation { ... }` | `Process.kt:80` | **1 lambda + handler-node allocation per hold** |

### Per-tick allocation profile (pure discrete, no listeners)

- 1× `ScheduledEvent` (24–32 B)
- 1× `CancellableContinuationImpl` (~48–64 B)
- 1× `invokeOnCancellation` lambda + internal handler node

≈ 3–4 small short-lived objects per tick. At fast-sim tick counts (≤10⁴) this is
nursery-GC noise; it only matters at 10⁶+ events.

### `Process.activate` / `Process.reactivate` (per call, if used per tick)

- `activate` (`Process.kt:208–220`): O(log n) queue insert + first-run `simScope.launch`
  coroutine creation (~1–2 µs, dominated by coroutine machinery — measured in §4).
- `reactivate` (`Process.kt:229–240`): `waitNotices.removeAll { ... }` **O(w) scan with
  lambda allocation** + `eventQueue.remove(process)` **O(n) `removeAll` scan with lambda
  allocation** + re-schedule. Two full-list scans per call even when the lists are empty
  or the process is not queued. This is the least efficient per-tick primitive, but still
  only ~3.2 µs measured.

### `Continuous` bookkeeping (only if interlockSim starts any `Continuous`)

When `firstCont != null`, every loop iteration calls
`ContinuousMonitor.integrateUntil(nextEventTime)` (`Monitor.kt:50–88`). With the default
`dtMax = 1.0` and a 1.0-time-unit tick, that is ≥1 full RKF45 step per tick: **6 stages ×
`computeDerivatives()`** plus 6 intrusive-list sweeps over active `Variable`s
(`RKF45.kt:58–207`). The integrator itself is allocation-free (all `k1..k6` are fields on
`Variable`). Measured overhead: ~2.5 µs/tick extra over the pure-discrete ticker (§4).
If interlockSim's fast-sim does **not** use `Continuous`/`Variable` (expected), this path
is a single null check per tick — zero share.

### Non-costs verified

- **Event listeners** (`SimulationEvent`): every emit site is guarded by
  `eventListeners.isEmpty()` — zero overhead when interlockSim registers no listeners.
- **`waitUntil` notices**: `checkWaitNotices()` returns immediately on empty list.
- **`SimulationContextHolder`**: read once per `activate`/static helper via JVM
  `ThreadLocal`; the scheduler loop itself uses direct `context` references.
- **Time comparison / clock**: plain `Double` field reads.

## 4. Measured numbers

Micro-benchmark: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/TickSchedulingBenchmark.kt`
(run with `./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.TickSchedulingBenchmark"`).

Environment: OpenJDK Temurin 17.0.19, AMD EPYC 7763 (4 vCPU, GitHub-hosted runner),
Kotlin 2.1.10, kDisco 0.6.0. Warm-up pass of 50 000 ticks before measurement.

| Pattern (models fast-sim shape) | Ticks | Wall | ns/tick | ticks/s |
|---|---|---|---|---|
| Single ticker, `hold(1.0)` per tick | 1 000 000 | 1 544 ms | **1 544** | ~648 k |
| 10 concurrent processes, `hold(1.0)` each | 1 000 000 | 1 681 ms | **1 681** | ~595 k |
| Ticker spawning 1 short-lived process per tick (`activate` churn) | 500 000 | 1 838 ms | **3 676** | ~272 k |
| Ticker waking a passivated worker per tick (`reactivate` churn) | 500 000 | 1 594 ms | **3 188** | ~314 k |
| Ticker with 1 active `Continuous` + `Variable` (RKF45 per tick) | 50 000 | 203 ms | **4 060** | ~246 k |

Note: single-run `measureTime` figures on a shared CI runner — treat as order-of-magnitude
(±20 %), which is sufficient for the share question below.

### Projection onto the fast-sim regression

`fast-sim example shuntingLoop 60` ⇒ order of 60–3 600 ticks (60 sim-time units at
1.0-per-tick, possibly with sub-ticks/multiple processes). Worst-case projection using the
most expensive measured pattern (4.1 µs/tick) **and** a pessimistic 10 000 engine events
per run: **≈ 41 ms total kDisco share** — vs. a ~400 ms regression. Realistic share
(single ticker, ~60–3 600 events): **0.1–6 ms**. The regression is therefore ≥ 90–99 %
attributable to non-kDisco (interlockSim-side) per-tick work, matching the SP0.7
snapshot/graph-walk hypothesis.

## 5. How to read SP0.13 Phase-1 numbers against this baseline

SP0.13 wraps `iteration()` phases with cumulative timers. Interpretation guide:

- The kDisco-scheduling share is bounded by *(engine events per run)* × *(≈1.5–4 µs)*.
  Compute engine events as: driver `hold`s + `activate`s + `reactivate`s + wait-until
  wakeups per run (`Simulation.scheduledEventCount()` / `activeProcessCount()` can help
  audit this live).
- If the timer bracketing the `hold(1.0)`/scheduler boundary reports **< ~40 ms**
  cumulative, kDisco is exonerated → close kdisco#55 as not-applicable, citing this
  report.
- If it reports **≥ tens of ms**, first check for accidental engine-event inflation on
  the interlockSim side (per-tick `reactivate` fan-out, `waitUntil` polling loops, or an
  unintentionally started `Continuous`), then use §6.

## 6. kDisco-side optimization candidates (only if SP0.13 shows a material share)

Priority-ordered, with expected effect at high event counts:

1. **`EventQueue.removeFirst()` O(n) shift** (`EventQueue.kt:40–42`):
   `ArrayList.removeAt(0)` shifts the whole backing array on every event. Replace with a
   binary min-heap or an index-based ring/deque. Biggest win when many processes keep
   the queue deep; irrelevant for a 1–10-deep fast-sim queue.
2. **`EventQueue.remove(process)` / `contains` linear scans with lambda `removeAll`**
   (`EventQueue.kt:34–38`): called by every `reactivate`, `terminate`, and hold-cancel.
   An explicit index loop (or a per-process "queued" flag/back-reference) removes both
   the O(n) scan and the per-call lambda allocation. Directly cuts the measured 3.2 µs
   reactivate cost.
3. **`Process.reactivate` unconditional `waitNotices.removeAll`** (`Process.kt:237`):
   guard with `waitNotices.isEmpty()` (same zero-overhead pattern already used for
   listeners) to skip the scan + lambda in the common no-waitUntil case.
4. **`hold()` allocation trio**: `suspendCancellableCoroutine` +
   `invokeOnCancellation` + `ScheduledEvent` per hold. Could drop to plain
   `suspendCoroutine` with engine-side cleanup on `simScope.cancel()`, and/or pool
   `ScheduledEvent`s. Only worth it at ≥10⁶ events/run; measurable GC-pressure win, not a
   fast-sim win.
5. **`Continuous` guard is already optimal** (single null check) — no action unless
   fast-sim actually starts a `Continuous`, in which case tune `dtMax` upward so RKF45
   takes one step per multi-tick span instead of ≥1 per tick.

None of these are worth landing *speculatively* for fast-sim tick counts — at ≤10⁴
events the total engine time is single-digit milliseconds either way.

## 7. Reproduction

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.TickSchedulingBenchmark"
# per-pattern ns/tick figures are printed to the test stdout
```

The five benchmark patterns intentionally mirror the interlockSim fast-sim shapes:
single driver tick loop, co-scheduled entities, per-tick spawn, per-tick wake of a
passivated worker, and a tick loop with continuous integration active.

## 8. Acceptance mapping (issue #55)

- [x] Deep analysis of kDisco per-tick scheduling paths (event loop, `hold(1.0)`,
  `Process.activate`, `Continuous` bookkeeping) — §3.
- [x] Quantified engine baseline for comparison with SP0.13 Phase-1 instrumentation — §4/§5.
- [x] Specific, prioritized kDisco changes on file *if* a material share is shown — §6.
- [ ] Final disposition (optimize vs. close as not-applicable) — **blocked on SP0.13
  Phase-1 numbers**, per the issue's "do not start until" clause. This report is the
  preparation so that disposition is a table lookup once those numbers land.
