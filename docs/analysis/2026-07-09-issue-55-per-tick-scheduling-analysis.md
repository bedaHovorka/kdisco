# kDisco Per-Tick Scheduling Cost Analysis (Issue #55)

**Date:** 2026-07-09 (revised 2026-07-10: re-baselined on `linuxX64` native + recalibrated to `shuntingLoop 300`)
**Companion to:** interlockSim SP0.13 ([bedaHovorka/interlockSim#738](https://github.com/bedaHovorka/interlockSim/issues/738)) — see the [instrumentation proposal comment](https://github.com/bedaHovorka/interlockSim/issues/738#issuecomment-4931473954) for how the real 300-run event count will be captured
**kDisco version analysed:** 0.6.0 (the exact version pinned at both the interlockSim
baseline `c3eb0f66` and the regressed tip — the version confound is already ruled out)

## 1. Purpose

interlockSim's `fast-sim example shuntingLoop 300` regressed ~0.2 s → ~0.6 s wall time
(the CI-standard run; `shuntingLoop 60` is a shorter variant). This report is the
kDisco-side preparation for SP0.13: a deep static analysis of every kDisco code path
executed per fast-sim tick, plus micro-benchmark measurements that bound the
kDisco-scheduling share of that regression — so that SP0.13's Phase-1 tick-level
instrumentation numbers can be compared against a known engine baseline.

## 2. TL;DR / Verdict

**On the native `linuxX64` target (the fast-sim CLI's actual runtime — no JIT), kDisco
per-tick scheduling cost is ~1.9–5.5 µs per event** across the measured shapes, vs
~0.5–1.1 µs on the JVM (~3–5× no-JIT penalty). For a `shuntingLoop 300` run, projecting
onto a **pessimistic ~50 000 engine-event bound** (5× linear scale of the 60-scenario's
10 000 floor — the real 300-run count is not yet measured; see the #738 instrumentation
proposal), the kDisco share is **~94–275 ms** (realistic, lower event count ~36–72 ms),
versus the ~400 ms regression. That is **sub-regression but no longer negligible** — at
the pessimistic bound it is on the order of a quarter to two-thirds of the regression,
not "two orders of magnitude below" as the earlier JVM-only, `shuntingLoop 60` framing
suggested.

This is still consistent with the SP0.7 suspicion (eager per-tick observation building on
the interlockSim side: two full `snapshot()` calls + six unconditional
`findNextReservationTarget` graph walks per tick) being the *largest* share, but it means
the kDisco engine share cannot be ruled out as immaterial without the measured count.

**Disposition for issue #55 remains blocked on SP0.13 Phase-1** (interlockSim#738), per
the issue's "do not start until" clause: the projection here uses a *projected* event
count, and the native per-tick numbers show the share is sensitive enough to that count
that the close-vs-optimize call must wait for the real #738 number. §6 lists the concrete
engine hotspots to attack, in priority order, *if* a material share is confirmed.

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
  allocation** + re-schedule. Two unconditional `removeAll` calls per call, each
  allocating a capturing lambda even when the lists are empty (the scans themselves are
  0-iteration on an empty list) or the process is not queued. This is the least efficient
  per-tick primitive — ≈4 µs/tick native in the reactivate-churn pattern (§4).

### `Continuous` bookkeeping (only if interlockSim starts any `Continuous`)

When `firstCont != null`, every loop iteration calls
`ContinuousMonitor.integrateUntil(nextEventTime)` (`Monitor.kt:50–88`). With the default
`dtMax = 1.0` and a 1.0-time-unit tick, that is ≥1 full RKF45 step per tick: **7
`computeDerivatives()` evaluations** (k1 pre-integrate + 5 stages + 1 final recompute)
and ~14 intrusive-list sweeps over active `Variable`s per accepted step (more if a step
is rejected and retried; `RKF45.kt:58–207`). The integrator itself is allocation-free
(all `k1..k6` are fields on `Variable`). Measured overhead: ~1.3 µs/tick extra natively
(continuous pattern 3 180 ns vs single-ticker 1 872 ns, §4).
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

Micro-benchmark: `kdisco-core/src/commonTest/kotlin/cz/ksimulantenbande/kdisco/TickSchedulingBenchmark.kt`
(run with `./gradlew :kdisco-core:linuxX64Test -PrunBenchmarks=true --tests "cz.ksimulantenbande.kdisco.TickSchedulingBenchmark" --info`;
`--info` / the `runBenchmarks`-gated `testLogging.showStandardStreams` surfaces the per-pattern
`ns/tick` lines to the console).

Environment: measured on the maintainer's hardware (Intel Arrow Lake, Fedora, Kotlin
2.1.10, kDisco 0.6.0). **The primary target is `linuxX64` native** (the fast-sim CLI's
actual runtime — no JIT); `jvmTest` is retained as a JIT-warmed comparison point. The
earlier draft of this report quoted JVM-only figures from a GitHub CI EPYC runner
(~1.5–4 µs/tick); those are superseded — the Arrow Lake JVM numbers below are ~3× lower
than the CI EPYC JVM figures (hardware difference), and the native numbers are the
authoritative basis for the verdict. Warm-up pass of 50 000 ticks before measurement.

| Pattern (models fast-sim shape) | Ticks | Native ns/tick (linuxX64) | JVM ns/tick |
|---|---|---|---|
| Single ticker, `hold(1.0)` per tick | 1 000 000 | **1 872** | 502 |
| 10 concurrent processes, `hold(1.0)` each | 1 000 000 | **2 065** | 589 |
| Ticker spawning 1 short-lived process per tick (`activate` churn) | 500 000 | **5 502** | 1 112 |
| Ticker waking a passivated worker per tick (`reactivate` churn) | 500 000 | **4 022** | 1 078 |
| Ticker with 1 active `Continuous` + `Variable` (RKF45 per tick) | 50 000 | **3 180** | 880 |
| Deep queue, N=100 resident processes (`removeFirst` depth-scaling) | 200 000 | **2 255** | 465 |
| Deep queue, N=1 000 resident processes | 200 000 | **2 580** | 520 |
| Deep queue, N=10 000 resident processes | 200 000 | **4 975** | 1 030 |

The deep-queue pattern (§3 step 5 / §6 candidate #1) holds the event queue at depth ~N
throughout and pops via *resume* from `hold` (no per-tick coroutine launch), so the
`ns/tick` isolates the O(depth) `removeFirst()`+`schedule()` array-shift cost. It rises
with N (native 2 255 → 2 580 → 4 975 at depth 100 → 1 000 → 10 000): the O(n) shift is
real and grows, but it stays comparable to the fixed `hold()` cost until depth ~10 000 —
i.e. `removeFirst` is a secondary term at shallow fast-sim depths and only dominates when
the queue is genuinely deep.

Note: single-run `measureTime` figures — treat as order-of-magnitude (±20 %), sufficient
for the share question. Native (no JIT) shows ~3–5× higher per-tick cost than JVM for the
allocation-heavy paths (`activate`/`reactivate`/`hold`), which is the key correction to
the earlier JVM-only verdict.

### Projection onto the fast-sim regression

`fast-sim example shuntingLoop 300` ⇒ ~300 main driver ticks (300 sim-time units at
1.0-per-tick), each dispatching trains / InOutWorkers, so the engine-event count is a
multiple of 300 — the **real total is not yet measured** (it is the deliverable of the
interlockSim#738 instrumentation proposal linked in the header). As a clearly-labeled
**projected upper bound**, take the 60-scenario's pessimistic 10 000 engine-event floor
and scale 5× → **~50 000 engine events** for the 300-run; a realistic lower estimate is
~300–18 000 events (300 main ticks × a few events each).

Using **native** per-tick cost (the fast-sim CLI runs native):

- Pessimistic (most expensive native shape, `activate`-churn 5.5 µs/event × 50 000):
  **≈ 275 ms** kDisco share.
- Realistic (single-ticker hold + occasional activate/reactivate, ~2–4 µs/event ×
  ~18 000): **≈ 36–72 ms**.
- Lower bound (pure hold 1.9 µs/event × 50 000): **≈ 94 ms**.

vs. the ~400 ms regression. So the projected kDisco share spans **~36–275 ms** depending
on the (unmeasured) event count and per-tick shape — sub-regression in all cases, but at
the pessimistic bound on the order of a quarter to two-thirds of the regression, **not**
the "≥ 90–99 % non-kDisco" conclusion the earlier JVM-only / `shuntingLoop 60` framing
gave. The SP0.7 eager-observation hypothesis remains the likely *largest* share, but the
kDisco share is sensitive enough to the real event count that the close-vs-optimize
disposition must wait for the #738 measurement rather than be read off this projection.

## 5. How to read SP0.13 Phase-1 numbers against this baseline

SP0.13 wraps `iteration()` phases with cumulative timers. Interpretation guide:

- The kDisco-scheduling share is bounded by *(engine events per run)* × *(native
  ≈1.9–5.5 µs/event)*. The per-run **total** engine-event count must come from
  instrumenting the `beforeEvent` hook (count invocations ≈ engine events + ≤2 terminal
  iterations) — exactly the interlockSim#738 proposal linked in the header.
- **Precision note on the kDisco count APIs:** `Simulation.scheduledEventCount()`
  (`Simulation.kt:237–239`) and `activeProcessCount()` (`:241–245`) return
  **instantaneous** queue/process depth at a moment in time, **not** a cumulative per-run
  total. They are useful for sampling the *peak* queue depth (e.g. the `peakQueueDepth`
  in the #738 patch), but they do not sum to the total events processed — that requires
  the `beforeEvent` counter. Concretely: activations made before `run()` starts live in
  `pendingActivations` (not the event queue), so pre-run `activeProcessCount() == N`
  while `scheduledEventCount() == 0` for N pending processes — the two APIs measure
  different sets, neither a run total.
- Compare the #738-measured real 300-run event count × the native per-tick cost against
  the ~400 ms regression. There is **no fixed "exonerated below X ms" threshold** — the
  §4 projection (native × a projected ~50 000-event bound) already lands at ~94–275 ms,
  so the disposition cannot be read off the projection; it depends on the real count.
- If the real share turns out material (tens to hundreds of ms), first check for
  accidental engine-event inflation on the interlockSim side (per-tick `reactivate`
  fan-out, `waitUntil` polling loops, or an unintentionally started `Continuous`), then
  use §6. Final disposition (optimize vs. close as not-applicable) stays **blocked on
  SP0.13 Phase-1** per issue #55's "do not start until" clause.

## 6. kDisco-side optimization candidates (only if SP0.13 shows a material share)

Priority-ordered, with expected effect at high event counts:

1. **`EventQueue.removeFirst()` O(n) shift** (`EventQueue.kt:40–42`):
   `ArrayList.removeAt(0)` shifts the whole backing array on every event. Replace with a
   binary min-heap or an index-based ring/deque. Biggest win when many processes keep
   the queue deep. **Now measured** by the deep-queue pattern (§4): native per-pop cost
   rises from 2 255 ns at depth 100 to 4 975 ns at depth 10 000 — the O(depth) term is
   real but stays secondary to the fixed `hold()` cost until depth ~10 000, so this is
   only worth attacking if the #738 measurement shows the fast-sim queue running
   genuinely deep (thousands), not at the 1–10-deep range.
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

None of these are worth landing *speculatively* — the §4 projection (native, ~50 000-event
bound) already puts the kDisco share at ~94–275 ms, so any optimization here should wait
for the #738 real measurement to confirm which term dominates at the actual fast-sim depth
and event count, rather than guessing.

## 7. Reproduction

The primary target is `linuxX64Test` (native, the fast-sim CLI's runtime — no JIT);
`jvmTest` is a JIT-warmed comparison point. `--info` (and the `runBenchmarks`-gated
`testLogging.showStandardStreams` in `build.gradle.kts`) surface the per-pattern `ns/tick`
lines to the console:

```bash
./gradlew :kdisco-core:linuxX64Test -PrunBenchmarks=true --tests "cz.ksimulantenbande.kdisco.TickSchedulingBenchmark" --info
# jvmTest comparison point (same command, swap the task):
./gradlew :kdisco-core:jvmTest      -PrunBenchmarks=true --tests "cz.ksimulantenbande.kdisco.TickSchedulingBenchmark" --info
```

The six benchmark patterns intentionally mirror the interlockSim fast-sim shapes: single
driver tick loop, co-scheduled entities, per-tick spawn, per-tick wake of a passivated
worker, a tick loop with continuous integration active, and a deep-queue sweep
(N=100/1 000/10 000 resident processes) isolating the `removeFirst()` depth-scaling. The
benchmark is universal — pure commonTest, public APIs only — so it runs on every KMP
target (JVM/JS/Native); it is excluded from default `build`/`test`/`allTests` (including
CI) on every target and opt-in via `-PrunBenchmarks=true` because its iteration counts
(up to 1 M ticks) are too slow/flaky under Kotlin/JS and prone to CI timing variance.

## 8. Acceptance mapping (issue #55)

- [x] Deep analysis of kDisco per-tick scheduling paths (event loop, `hold(1.0)`,
  `Process.activate`, `Continuous` bookkeeping) — §3.
- [x] Quantified engine baseline for comparison with SP0.13 Phase-1 instrumentation —
  §4/§5. **Re-baselined on `linuxX64` native** (not JVM-only); verdict softened from
  "close as not-applicable" to "blocked on SP0.13" after the native re-baseline moved
  the projected share from ~40 ms to ~94–275 ms.
- [x] `removeFirst()` O(n) depth-scaling **measured** (deep-queue pattern) — §4/§6.
- [x] Instrumentation proposal to obtain the real `shuntingLoop 300` event count —
  posted on [interlockSim#738](https://github.com/bedaHovorka/interlockSim/issues/738#issuecomment-4931473954).
- [x] Specific, prioritized kDisco changes on file *if* a material share is shown — §6.
- [x] Final disposition — **resolved 2026-07-11**, see §9. SP0.13 Phase-1 landed a real
  `shuntingLoop 300` event count of 739 (peak queue depth 7), two orders of magnitude below
  this doc's ~50 000-event projection. Measured kDisco-scheduling share: **~0.4%** of wall
  time — exonerated, not the "~94–275 ms, sub-regression but not negligible" this doc
  projected. Closed as not-applicable to scheduling; the actual dominant kDisco-side cost
  turned out to be continuous integration, tracked separately.

## 9. Resolution (2026-07-11)

The SP0.13 Phase-1 instrumentation this doc was blocked on has landed
([issue #55 comment](https://github.com/bedaHovorka/kdisco/issues/55#issuecomment-4948269481)):

- Real `shuntingLoop 300` engine-event count: **739** iterations, peak queue depth **7** (vs.
  this doc's projected ~18 000–50 000-event bound in §4/§5).
- Real kDisco-scheduling share of wall time: **~0.4%** (739 events × ~1.9–2.2 µs/event ≈
  1.4–1.6 ms out of ~342 ms total) — the scheduler is exonerated.
- The interlockSim regression that motivated issue #55 **did not reproduce at all** on a
  same-workload rerun (SP0.4 baseline 307–329 ms vs. tip 341 ms) and was closed
  not-reproducible upstream.
- Profiling did find kDisco *is* the dominant engine-side cost in this workload — just not
  in scheduling. `Continuous.derivatives()` is called **~3.5 million** times per
  `shuntingLoop 300` run (vs. 739 discrete events), because kDisco has no state-event /
  zero-crossing root-finding and interlockSim is forced to pin `dtMax = 1e-3` to keep
  block-boundary overshoot small. That is the real ~60–70% of wall time.
- Filed as **[issue #67](https://github.com/bedaHovorka/kdisco/issues/67)** and resolved by
  **PR #68**, which adds `Process.waitCrossing` state-event detection with bisection
  root-finding so models can leave `dtMax` at its natural value.

This benchmark and the static per-tick breakdown in §3–§6 remain valid and useful as the
kDisco scheduling baseline — they are simply no longer the bottleneck in this particular
workload.
