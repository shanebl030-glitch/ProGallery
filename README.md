# ProGallery

A Material 3 photo and video gallery with built-in photo and video editors, for Android 10+.

Created By Shane BlackbuRn.

**Status:** early development — see `CHANGELOG.md` and `docs/ROADMAP.md`.

## Building

- **CI (recommended):** every push to `main` builds debug and release APKs in GitHub
  Actions. Download them from the run's *Artifacts*, or from the `ci-results` branch.
- **Locally:** install Android Studio (or the command-line SDK with platform 35), then
  `./gradlew :core:test :app:assembleDebug`. The APK lands in `app/build/outputs/apk/debug/`.
- **Core tests without the Android SDK:** `tools/core-test.sh` (needs a JDK and kotlinc).

## Project layout

| Module | Purpose |
|---|---|
| `core` | Pure Kotlin: media model, sorting, search, grouping, edit maths. Unit-tested on the JVM. |
| `app`  | Android app: Compose UI, MediaStore access, viewer, players, editors. |
