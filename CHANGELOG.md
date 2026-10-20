# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

## [Unreleased]

### Added
- `core` module (pure Kotlin): media model, smart categories, album building, natural-order
  sorting, filter specs, date-section grouping, search query language, formatters,
  safe file-name utilities, recently-viewed list. 20 unit tests.
- Android build: Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01,
  Media3 1.5.1, Coil 2.7.0. minSdk 29 (Android 10), target/compile SDK 35.
- GitHub Actions workflow: core + app unit tests, debug/release APKs, lint, results
  published to the `ci-results` branch and as a build artifact.
- Original adaptive launcher icon.
