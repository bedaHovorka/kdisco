plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "cz.ksimulantenbande.kdisco"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        testRuns["test"].executionTask.configure {
            maxParallelForks = 1
            // No forkEvery needed — kdisco-core avoids shared mutable static state;
            // simulation state is per-run and cleaned up after each execution
        }
    }

    // Uncomment when Android SDK is available:
    // androidTarget {
    //     compilerOptions {
    //         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    //     }
    // }

    js(IR) {
        browser()
        nodejs()
    }

    // TODO: enable wasmJs when toolchain configured
    // @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    // wasmJs {
    //     browser()
    //     nodejs()
    // }

    // Desktop native targets
    linuxX64()
    // macosX64() is deprecated in the Kotlin toolchain (Intel Macs are no longer
    // a supported target tier); use macosArm64() instead.
    macosArm64()
    // TODO: enable mingwX64 when building on Windows
    // mingwX64()

    // TODO: enable iOS targets when building on macOS
    // iosArm64()
    // iosX64()
    // iosSimulatorArm64()

    // Shared source set hierarchy for native targets
    applyDefaultHierarchyTemplate()

    sourceSets {
        val nonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        jsMain.get().dependsOn(nonJvmMain)
        nativeMain.get().dependsOn(nonJvmMain)

        commonMain {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("coroutines.version")}")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.property("coroutines.version")}")
                implementation("com.willowtreeapps.assertk:assertk:${project.property("assertk.version")}")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")
            }
        }
    }
}

// Uncomment when Android SDK is available:
// android {
//     namespace = "cz.ksimulantenbande.kdisco"
//     compileSdk = 34
//     defaultConfig {
//         minSdk = 21
//     }
// }

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/bedaHovorka/kdisco")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as String?
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SonarCloud
// ---------------------------------------------------------------------------
// kdisco-core is Kotlin Multiplatform and has no `java` source sets, so the Sonar
// Gradle plugin cannot auto-detect `sonar.java.libraries`. Without it every Kotlin
// rule that needs type resolution silently degrades. Resolve the classpath from this
// module's own context (resolving it inside the `sonar {}` block would re-introduce
// cross-project configuration resolution) and hand Sonar the resulting file list.
val sonarJavaLibrariesFile = layout.buildDirectory.file("sonar/java-libraries.txt")

val sonarJavaLibraries by tasks.registering {
    val jvmCompileClasspath = configurations.named("jvmCompileClasspath")
    val jvmTestCompileClasspath = configurations.named("jvmTestCompileClasspath")
    val output = sonarJavaLibrariesFile
    outputs.file(output)
    doLast {
        val jars = (jvmCompileClasspath.get().files + jvmTestCompileClasspath.get().files)
            .filter { it.exists() }
            .distinct()
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(jars.joinToString(",") { it.absolutePath })
        }
    }
}

fun existingPaths(vararg paths: String): String =
    paths.filter { file(it).exists() }.joinToString(",")

sonar {
    properties {
        property(
            "sonar.sources",
            existingPaths(
                "src/commonMain/kotlin",
                "src/nonJvmMain/kotlin",
                "src/jvmMain/kotlin",
                "src/jsMain/kotlin",
                "src/nativeMain/kotlin",
            ),
        )
        property(
            "sonar.tests",
            existingPaths(
                "src/commonTest/kotlin",
                "src/jvmTest/kotlin",
                "src/linuxX64Test/kotlin",
            ),
        )
        property("sonar.java.binaries", "build/classes/kotlin/jvm/main")
        property("sonar.java.test.binaries", "build/classes/kotlin/jvm/test")
        property("sonar.sourceEncoding", "UTF-8")
        property(
            "sonar.java.libraries",
            sonarJavaLibrariesFile.get().asFile.takeIf { it.isFile }?.readText().orEmpty(),
        )
    }
}
