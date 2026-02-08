plugins {
    kotlin("multiplatform")
    `maven-publish`
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        // jDisco uses global static state (SQS, processSet, Coroutine.main).
        // A failed test that leaves a simulation running can corrupt jDisco for
        // subsequent test classes. forkEvery=1 isolates each test class in its
        // own JVM so that static state never leaks between classes.
        testRuns["test"].executionTask.configure {
            maxParallelForks = 1
            forkEvery = 1
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("com.willowtreeapps.assertk:assertk:${project.property("assertk.version")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                api("dk.ruc.keld:jdisco:1.2.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")
                implementation("com.willowtreeapps.assertk:assertk-jvm:${project.property("assertk.version")}")
                // SLF4J for jDisco logging
                implementation("org.slf4j:slf4j-simple:1.7.36")
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
