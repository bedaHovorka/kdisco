plugins {
    kotlin("multiplatform")
    `maven-publish`
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        testRuns["test"].executionTask.configure {
            maxParallelForks = 1
            // No forkEvery needed — simulation state is per-run and cleaned up after each
            // execution; no cross-test leaking static state requires process isolation
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":kdisco-core"))
                implementation("io.insert-koin:koin-core:${project.property("koin.version")}")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.insert-koin:koin-test:${project.property("koin.version")}")
                implementation("com.willowtreeapps.assertk:assertk:${project.property("assertk.version")}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.property("coroutines.version")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")
            }
        }
    }
}

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
// See the equivalent block in kdisco-core: this module is Kotlin Multiplatform too,
// so `sonar.java.libraries` has to be supplied explicitly from the module's own
// resolved classpath. Paths are declared per module so nothing gets indexed twice.
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
        property("sonar.sources", existingPaths("src/commonMain/kotlin", "src/jvmMain/kotlin"))
        property("sonar.tests", existingPaths("src/commonTest/kotlin", "src/jvmTest/kotlin"))
        property("sonar.java.binaries", "build/classes/kotlin/jvm/main")
        property("sonar.java.test.binaries", "build/classes/kotlin/jvm/test")
        property("sonar.sourceEncoding", "UTF-8")
        property(
            "sonar.java.libraries",
            sonarJavaLibrariesFile.get().asFile.takeIf { it.isFile }?.readText().orEmpty(),
        )
    }
}
