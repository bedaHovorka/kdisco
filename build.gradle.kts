plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
}

allprojects {
    group = "cz.ksimulantenbande.kdisco"

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // TickSchedulingBenchmark drives up to 1M simulated ticks per pattern — fine as a
    // JVM micro-benchmark but too slow/flaky under Kotlin/JS (browser+node) and prone
    // to timing variance in CI. Exclude it from default test runs on every target;
    // opt in with -PrunBenchmarks=true.
    tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask> {
        if (project.findProperty("runBenchmarks")?.toString()?.toBoolean() != true) {
            filter.excludeTestsMatching("cz.ksimulantenbande.kdisco.TickSchedulingBenchmark")
        } else {
            // Opt-in benchmark run: surface the benchmark's per-pattern ns/tick println
            // output to the console (otherwise Gradle buries test stdout in the JUnit
            // XML report). Gated on runBenchmarks so default build/test/CI is unaffected.
            testLogging {
                showStandardStreams = true
                events("passed", "skipped", "failed", "standardOut", "standardError")
            }
        }
    }
}
