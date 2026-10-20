# ProGallery

A Material 3 photo and video gallery with built-in photo and video editors, for Android 10+.

Created by Shane BlackbuRn.

**Status:** v0.1.0 — gallery, viewer and video player. Editors are in progress.
See `CHANGELOG.md`, `docs/ROADMAP.md` and `docs/TESTING.md` (test report and known limitations).

## Installing on your phone
1. Download `app-release.apk` (from the GitHub Actions run's *Artifacts*, or the
   `ci-results` branch).
2. Copy it to your phone and tap it. If asked, allow your browser/file manager to
   *install unknown apps*.
3. Open **ProGallery** and allow photo and video access.

Verify the download (optional): `sha256sum app-release.apk` should match `SHA256SUMS.txt`.

**Updating:** install the newer APK over the old one; settings, favourites and hidden
albums are kept. **Rolling back:** uninstall, then install the older APK (this resets
ProGallery's own settings — your photos are never affected).

### Signing note
CI signs release builds with the GitHub runner's debug key, which can change between
runs. If an update refuses to install ("package conflicts"), uninstall first. To get
stable updates, create your own key and add it as repository secrets (planned: see
ROADMAP) — or build locally with a `keystore.properties` file.

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
