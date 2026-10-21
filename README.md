# ProGallery

A personal Material 3 photo and video gallery with built-in photo and video editors, for
Android 10 and newer. Personal use only — no ads, no accounts, no internet access.

Created by Shane BlackbuRn.

**Status:** v0.2.0 — gallery, viewer, video player, photo editor, video editor.
See `CHANGELOG.md`, `docs/USER_GUIDE.md` and `docs/TESTING.md` (test results and known limitations).

## Installing on your phone
1. Get `app-release.apk` (sent directly, or from the `ci-results` branch on GitHub).
2. Copy it to your phone (or download it there) and tap it. If Android asks, allow your
   browser / file manager to **install unknown apps**.
3. Open **ProGallery** and allow photo and video access.

Optional check: the file's SHA-256 should match `SHA256SUMS.txt`.

**Updating:** install the newer APK over the old one — this only works when both are
signed with the same key (see Signing). Settings, favourites and hidden albums are kept.
**Rolling back:** uninstall, then install the older APK (resets ProGallery's own settings;
your photos and videos are never affected).

**From v0.1.0 to v0.2.0 (one time only):** v0.1.0 was signed with a temporary CI key that
no longer exists, so Android will refuse to install 0.2.0 over it ("App not installed" /
"conflicts with an existing package"). Uninstall v0.1.0 first, then install 0.2.0. This
resets ProGallery's own settings, favourites and hidden-album list; photos and videos are
untouched. The 0.2.0 APK delivered by hand is signed with the permanent key, so every
later version signed with that key installs over it in place.

## Signing (needed for in-place updates)
Android installs an update over an existing app only if both APKs carry the same signing
certificate. CI signs release APKs with a permanent personal key when these two GitHub
repository secrets exist (Settings → Secrets and variables → Actions):

| Secret | Contents |
|---|---|
| `PROGALLERY_KEYSTORE_B64` | the keystore file, base64-encoded |
| `PROGALLERY_KEYSTORE_PASSWORD` | the keystore password (also the key password; alias `progallery`) |

Permanent key certificate SHA-256: `B0:0A:A0:37:DC:05:40:40:84:BC:96:0F:55:53:14:4B:D3:B5:7E:B4:7B:5A:74:35:4E:95:96:3D:EB:AC:78:4C`.
Each CI run records the certificate actually used in `SIGNING.txt` and `signing=` in
`SUMMARY.txt`. Without the secrets, builds use a temporary key and can't update each other.
Keep a private backup of the keystore and password — they can't be recovered if lost.

## Building
- **CI:** every push to `main` runs unit tests, builds debug and release APKs, runs lint,
  and runs the device test suite on an Android 14 emulator (with screenshots). Results,
  the release APK and screenshots are published to the `ci-results` branch.
- **Locally:** Android Studio (or command-line SDK with platform 35), then
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease`.
  Device tests: `./gradlew :app:connectedDebugAndroidTest` with a device/emulator attached.
- **Core tests without the Android SDK:** `tools/core-test.sh` (needs a JDK and kotlinc).

## Project layout
| Path | Purpose |
|---|---|
| `core` | Pure Kotlin: media model, sorting, search, grouping, photo-edit pipeline, video timeline maths. JVM unit tests. |
| `app` | Android app: Compose UI, MediaStore, viewer, video player, photo editor, video editor. |
| `app/src/androidTest` | Device tests (UI flows, rendering/saving/export engines, icon). |
| `docs/icon` | Icon source design and the scripts that generate the launcher layers. |
