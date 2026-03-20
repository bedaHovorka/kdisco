plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "cz.hovorka.kdisco"
version = "0.3.0-SNAPSHOT"

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
    macosX64()
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
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
//     namespace = "cz.hovorka.kdisco"
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
