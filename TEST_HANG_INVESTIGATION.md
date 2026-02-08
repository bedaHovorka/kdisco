# Test Hang Investigation & Recommendations

**Issue**: Tests hang during execution, particularly continuous simulation tests
**Observed on**: Development machine during kDisco implementation (2026-02-08)
**Status**: Requires investigation on another machine

---

## Observed Behavior

### What Hangs:
- ✅ **Compilation**: Works fine (both main and test code)
- ✅ **Discrete-event tests**: Generally pass quickly
- ❌ **Continuous simulation tests**: Hang indefinitely
- ❌ **Full test suite**: Never completes

### Specific Hanging Test:
```kotlin
@Test
fun `continuous simulation with exponential decay`() {
    class Decay : Continuous() {
        val y = Variable(100.0)
        val decayRate = -0.1

        override fun actions() {
            y.start()  // Start continuous integration
            hold(50.0)
            y.stop()   // Stop integration
        }

        override fun derivatives() {
            y.rate = decayRate * y.state
        }
    }

    val decay = Decay()
    runSimulation(endTime = 50.0) {
        Process.activate(decay)
    }

    assertThat(decay.y.state).isCloseTo(0.67, 0.1)
}
```

**Hangs at**: The test executor starts but never completes

---

## Possible Causes

### 1. Variable Integration Not Stopping
**Theory**: Variable.start() without proper stop() could leave integration running

**Test**:
```kotlin
@Test
fun `variable lifecycle - start and stop work`() {
    class SimpleTest : Continuous() {
        val v = Variable(42.0)

        override fun actions() {
            v.start()
            assertThat(v.isActive()).isTrue()
            hold(1.0)  // Short duration
            v.stop()
            assertThat(v.isActive()).isFalse()
        }

        override fun derivatives() {
            v.rate = 0.0  // No change
        }
    }

    runSimulation(endTime = 2.0) {  // Short simulation
        Process.activate(SimpleTest())
    }
}
```

---

### 2. Thread Deadlock in jDisco
**Theory**: jDisco's thread-per-process model might deadlock on certain systems

**Recommendations**:
- Test on different JVM versions
- Test on different OS (Linux, macOS, Windows)
- Add timeouts to all tests

**Example with timeout**:
```kotlin
@Test(timeout = 5000)  // 5 second timeout
fun `continuous simulation with timeout`() {
    // ... test code
}
```

Or with JUnit 5:
```kotlin
@Test
@Timeout(value = 5, unit = TimeUnit.SECONDS)
fun `continuous simulation with timeout`() {
    // ... test code
}
```

---

### 3. Integration Step Size Too Small
**Theory**: RK4 with very small steps could take extremely long

**Test**: Try larger integration tolerances or step sizes
```kotlin
// If jDisco exposes integration parameters:
@Test
fun `continuous with larger steps`() {
    class Decay : Continuous() {
        val y = Variable(100.0)

        override fun actions() {
            y.start()
            hold(10.0)  // Shorter duration
            y.stop()
        }

        override fun derivatives() {
            y.rate = -0.1 * y.state
        }
    }

    runSimulation(endTime = 15.0) {
        Process.activate(Decay())
    }
}
```

---

### 4. Missing Simulation Termination
**Theory**: Simulation.run() might not be terminating properly

**Test**: Add explicit simulation termination
```kotlin
@Test
fun `simulation terminates correctly`() {
    var completed = false

    class TestProcess : Process() {
        override fun actions() {
            hold(1.0)
            completed = true
        }
    }

    runSimulation(endTime = 5.0) {
        Process.activate(TestProcess())
    }

    assertThat(completed).isTrue()
}
```

---

## Recommended Test Strategy

### Phase 1: Minimal Tests (Should NOT hang)
Run these first to verify basic functionality:

```kotlin
@Test
fun `simple process with hold`() {
    var executed = false
    class Simple : Process() {
        override fun actions() {
            hold(1.0)
            executed = true
        }
    }

    runSimulation(endTime = 2.0) {
        Process.activate(Simple())
    }

    assertThat(executed).isTrue()
}

@Test
fun `process terminate works`() {
    var terminated = false
    var afterTerminate = false

    class Terminating : Process() {
        override fun actions() {
            terminated = true
            terminate()
            afterTerminate = true
        }
    }

    runSimulation(endTime = 1.0) {
        Process.activate(Terminating())
    }

    assertThat(terminated).isTrue()
    assertThat(afterTerminate).isFalse()
}

@Test
fun `process passivate and reactivate`() {
    var stage = 0

    class Worker : Process() {
        override fun actions() {
            stage = 1
            passivate()
            stage = 2
        }
    }

    class Manager(val worker: Worker) : Process() {
        override fun actions() {
            hold(1.0)
            Process.reactivate(worker)
        }
    }

    val worker = Worker()
    runSimulation(endTime = 5.0) {
        Process.activate(worker)
        Process.activate(Manager(worker))
    }

    assertThat(stage).isEqualTo(2)
}
```

### Phase 2: Simple Continuous (May hang - investigate)
```kotlin
@Test
@Timeout(value = 10, unit = TimeUnit.SECONDS)
fun `minimal continuous test`() {
    class MinimalContinuous : Continuous() {
        val x = Variable(1.0)

        override fun actions() {
            x.start()
            hold(0.1)  // Very short duration
            x.stop()
        }

        override fun derivatives() {
            x.rate = 0.0  // No change
        }
    }

    runSimulation(endTime = 1.0) {
        Process.activate(MinimalContinuous())
    }
}
```

### Phase 3: Full Continuous (If Phase 2 works)
```kotlin
@Test
@Timeout(value = 30, unit = TimeUnit.SECONDS)
fun `exponential decay with timeout`() {
    class Decay : Continuous() {
        val y = Variable(100.0)

        override fun actions() {
            y.start()
            hold(50.0)
            y.stop()
        }

        override fun derivatives() {
            y.rate = -0.1 * y.state
        }
    }

    val decay = Decay()
    runSimulation(endTime = 60.0) {
        Process.activate(decay)
    }

    assertThat(decay.y.state).isCloseTo(0.67, 0.5)  // Wider tolerance
}
```

---

## Test Execution Recommendations

### 1. Run Tests Separately
Don't run all tests at once initially:

```bash
# Run only discrete tests first
./gradlew :kdisco-core-api:jvmTest --tests "*simple*"
./gradlew :kdisco-core-api:jvmTest --tests "*terminate*"
./gradlew :kdisco-core-api:jvmTest --tests "*reactivate*"

# Then try continuous tests individually
./gradlew :kdisco-core-api:jvmTest --tests "*minimal continuous*"
./gradlew :kdisco-core-api:jvmTest --tests "*exponential decay*"
```

### 2. Add Test Timeouts
Add to build.gradle.kts:

```kotlin
tasks.named<Test>("jvmTest") {
    timeout.set(Duration.ofMinutes(5))  // Global timeout

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
```

### 3. Enable Detailed Logging
Add to test resources (`src/jvmTest/resources/logback-test.xml`):

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

### 4. Test on Different Environments

**Try these environments**:
- ✅ Different JVM: OpenJDK vs Oracle JDK
- ✅ Different versions: Java 11, 17, 21
- ✅ Different OS: Linux, macOS, Windows
- ✅ Different machines: Development vs CI server

---

## Debugging Hanging Tests

### 1. Take Thread Dump While Hanging
```bash
# Run test in background
./gradlew :kdisco-core-api:jvmTest &
GRADLE_PID=$!

# Wait for hang, then:
jstack $GRADLE_PID > thread-dump.txt

# Analyze thread-dump.txt to see where threads are stuck
```

### 2. Enable JVM Debugging
```bash
./gradlew :kdisco-core-api:jvmTest \
  -Dorg.gradle.debug=true \
  -Dorg.gradle.daemon=false
```

### 3. Check jDisco Thread Behavior
Add debugging to test:

```kotlin
@Test
fun `debug thread behavior`() {
    println("Test started on thread: ${Thread.currentThread().name}")

    class DebugProcess : Process() {
        override fun actions() {
            println("Process running on thread: ${Thread.currentThread().name}")
            hold(1.0)
            println("Process resumed on thread: ${Thread.currentThread().name}")
        }
    }

    runSimulation(endTime = 2.0) {
        println("Simulation setup on thread: ${Thread.currentThread().name}")
        Process.activate(DebugProcess())
    }

    println("Test completed on thread: ${Thread.currentThread().name}")
}
```

---

## Workarounds Until Root Cause Found

### 1. Separate Test Suites
Create separate test files:

- `DiscreteSimulationTest.kt` - Discrete-event tests only
- `ContinuousSimulationTest.kt` - Continuous tests (may skip on problematic machines)

Mark continuous tests as ignored on problematic systems:
```kotlin
import org.junit.jupiter.api.condition.*

@Test
@DisabledOnOs(OS.LINUX)  // If it hangs on Linux
fun `continuous simulation test`() {
    // ...
}
```

### 2. Manual Testing
Create standalone main methods for manual testing:

```kotlin
// In src/jvmTest/kotlin/ManualContinuousTest.kt
fun main() {
    println("Starting manual continuous test...")

    class Decay : Continuous() {
        val y = Variable(100.0)

        override fun actions() {
            println("Starting integration...")
            y.start()
            println("Variable started")
            hold(50.0)
            println("Integration complete")
            y.stop()
            println("Final value: ${y.state}")
        }

        override fun derivatives() {
            y.rate = -0.1 * y.state
        }
    }

    val decay = Decay()
    runSimulation(endTime = 60.0) {
        Process.activate(decay)
    }

    println("Expected ~0.67, got ${decay.y.state}")
}
```

Run manually:
```bash
./gradlew :kdisco-core-api:jvmTestClasses
java -cp "kdisco-core-api/build/classes/kotlin/jvm/test:..." ManualContinuousTestKt
```

---

## Next Steps

1. ✅ **Run discrete tests first** - Verify basic functionality works
2. ✅ **Add timeouts** - Prevent infinite hangs
3. ✅ **Test on another machine** - Isolate if it's environment-specific
4. ✅ **Minimal continuous test** - Start with simplest possible continuous simulation
5. ✅ **Thread dump analysis** - Understand where it hangs
6. ✅ **Contact jDisco maintainer** - Report if it's a jDisco issue

---

## Expected Outcomes

**If discrete tests pass**: kDisco discrete-event implementation is correct ✅

**If continuous tests hang**: Either:
- jDisco continuous simulation issue on this platform
- Variable lifecycle usage incorrect
- Integration parameters need adjustment
- Thread/timing issue in test environment

**Testing on another machine will clarify** if it's environment-specific or a code issue.

---

**Status**: Investigation ongoing
**Next**: Run tests on another machine
**Document updated**: 2026-02-08
