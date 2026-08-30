# GitHub Copilot Instructions for kDisco

**See [CLAUDE.md](CLAUDE.md) for comprehensive development guidance.**

## Quick Reference

- **Language**: Kotlin 2.1.10 (Multiplatform: JVM, JS, Native)
- **Build**: `./gradlew build` (all modules) or `./gradlew :kdisco-core:build`
- **Test**: `./gradlew test` or `./gradlew :kdisco-core:jvmTest`
- **Quality gates**: ktlint + detekt + SonarCloud (all three must pass before merge), Kover for coverage — `./gradlew ktlintCheck detekt koverXmlReport`
- **Key Convention**: Use **assertK ONLY** for test assertions, never kotlin.test

## Architecture

Pure-Kotlin multiplatform simulation engine with Koin DI integration:
- **commonMain**: Core simulation logic (all platforms)
- **jvmMain/jsMain/nativeMain**: Platform-specific implementations
- **kdisco-koin**: Optional dependency injection module
