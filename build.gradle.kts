plugins {
    kotlin("multiplatform") version "2.1.10" apply false
    kotlin("jvm") version "2.1.10" apply false
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
        if (!project.hasProperty("runBenchmarks")) {
            filter.excludeTestsMatching("cz.hovorka.kdisco.TickSchedulingBenchmark")
        }
    }
}
