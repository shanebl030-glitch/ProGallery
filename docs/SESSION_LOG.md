# Development session log

## 2026-10-17 — Session 1 (setup)
- Environment: cloud workspace with JDK 21 and Gradle, but **no access to Google Maven or
  the Android SDK** (network policy). Kotlin compiler fetched from JetBrains' GitHub release.
- Decision: pure-Kotlin `core` module for all testable logic, run locally via
  `tools/core-test.sh`; Android build + tests run in GitHub Actions.
- Built `core`: model, album builder, natural sort, filter/sort specs, date grouping,
  search query language, formatters, file-name utilities, recent list. 20 tests pass.
- Android scaffolding: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01.

## 2026-10-21 — Session 2 (Milestone 1)
- GitHub repo access granted (after reconnecting the GitHub connector with repo selection).
- CI workflow added; first run green on all stages (validated dependency versions).
- CI publishes logs + APK to the `ci-results` branch so builds can be inspected from the
  workspace via git.
- Implemented Milestone 1 (see CHANGELOG 0.1.0).
