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
            // No forkEvery needed — kdisco-core has no static state
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
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
