plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.sonarqube") version "7.3.1.8318"
}

allprojects {
    group = "cz.ksimulantenbande.kdisco"

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    // Sonar adopts the Java compile tasks' `options.encoding` for `sonar.sourceEncoding`;
    // a stale platform default makes it read every KDoc en-dash and arrow as mojibake.
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
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

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("config/detekt/baseline.xml")
        basePath = rootProject.projectDir.absolutePath
        // Kotlin Multiplatform has no `main`/`test` source sets, so detekt's default
        // source would be empty. Point the single `detekt` task at `src` so every
        // source set is analysed exactly once (the per-target `detekt<Target><SourceSet>`
        // tasks the KMP integration registers are not wired into `check`).
        source.setFrom(files("src"))
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "11"
        reports {
            xml.required.set(true)
            html.required.set(true)
            sarif.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        jvmTarget = "11"
    }
}

// Aggregate Kover coverage over both modules. Only the JVM target is instrumented —
// `commonMain` is compiled into it and `commonTest` runs there, so this covers
// essentially the whole engine; the per-platform `actual`s are the known blind spot.
dependencies {
    kover(project(":kdisco-core"))
    kover(project(":kdisco-koin"))
}

kover {
    reports {
        filters {
            excludes {
                // Benchmarks are not product code: they must neither inflate coverage
                // nor count as debt.
                classes(
                    "cz.ksimulantenbande.kdisco.TickSchedulingBenchmark*",
                    "cz.ksimulantenbande.kdisco.ScaleBenchmark*",
                )
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "bedaHovorka_kdisco")
        property("sonar.organization", "bedahovorka")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.projectName", "kdisco")
        property("sonar.sourceEncoding", "UTF-8")
        // Aggregate Kover XML report, produced by the root `:koverXmlReport`.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/kover/report.xml").get().asFile.absolutePath,
        )
        // Benchmarks are excluded from analysis as well as from coverage.
        property("sonar.exclusions", "**/TickSchedulingBenchmark.kt,**/ScaleBenchmark.kt")
        // The root project holds no sources; every path is declared per module (see
        // each module's build.gradle.kts) so that no file is indexed twice.
        property("sonar.sources", "")
        property("sonar.tests", "")
    }
}

tasks.named("sonar") {
    dependsOn(tasks.named("koverXmlReport"))
    dependsOn(subprojects.map { "${it.path}:sonarJavaLibraries" })
}

// `sonarResolver` serializes the project properties, so the classpath files must
// already be on disk by the time it runs.
tasks.named("sonarResolver") {
    dependsOn(subprojects.map { "${it.path}:sonarJavaLibraries" })
}
