plugins {
    kotlin("multiplatform") version "2.1.10" apply false
    kotlin("jvm") version "2.1.10" apply false
}

allprojects {
    group = "cz.hovorka.kdisco"

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
}
