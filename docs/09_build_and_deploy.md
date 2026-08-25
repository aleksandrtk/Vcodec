# Build & Deploy (CI/CD)

> This project is built **exclusively via GitHub Actions**. There is no requirement for a local
> Android SDK/NDK toolchain — every push compiles, tests, signs and publishes a ready-to-install APK.

Workflow file: [`.github/workflows/release.yml`](../.github/workflows/release.yml)
("Build & Publish Release APK").

---

## 1. Triggers

| Event | Result |
|---|---|
| Push to **any branch** | Build runs. `main` → **stable**, any other branch → **dev** |
| Push of a tag (`v*`, `release*`) | Build + **GitHub Release published** (stable) |
| Manual `workflow_dispatch` with tag name (e.g. `v2.6.3`) | Build + Release published |
| Manual `workflow_dispatch` without tag | Dev build only |

## 2. Pipeline Steps

1. **Checkout** (full history).
2. **Version & channel resolution**: reads `versionName` from `app/build.gradle.kts`;
   channel = `stable` (main / tags / dispatch+tag) or `dev` (everything else).
3. **Toolchain**: JDK 17 (Temurin), Gradle with caching, Android SDK + NDK 25.2 + CMake 3.22.
4. **Signing**: decodes `KEYSTORE_BASE64` repo secret into a keystore.
   If the secret is missing → warning; APK is signed with an ephemeral debug key
   (⚠ such an APK **cannot update** an already installed app).
5. **Unit tests**: `./gradlew testDebugUnitTest` (build fails if tests fail).
6. **Build**: `./gradlew assembleRelease`.
7. **Artifact**: renamed to `VCodec-release-<version>-<channel>.apk`
   and always uploaded as a workflow artifact `VCodec-APK-<version>-<channel>` —
   downloadable from the run page on **every** push (testable builds on every fix).
8. **Release publishing** (stable only): GitHub Release created/updated via
   `softprops/action-gh-release` with generated release notes and the attached APK.
   Re-pushing an existing tag refreshes that release.

## 3. Failure Handling

* Job summary gets a structured failure report (errors + log tails) with annotations.
* An issue **"CI build failure"** with full logs is auto-created in the repo.

## 4. How to Get the APK

1. Open the repo → **Actions** → latest run for your branch.
2. Download artifact `VCodec-APK-<version>-<channel>`.
3. Install `VCodec-release-*.apk` on the device (or via `adb install -r ...`).

For stable releases: **Releases** page → latest release → download the APK asset
(the in-app OTA updater also picks up new stable versions automatically).

## 5. Releasing a New Version

```bash
# 1. Bump versionName in app/build.gradle.kts
# 2. Commit and push
git push origin main          # stable build + GitHub Release v<version>
```

Or manually: Actions → "Build & Publish Release APK" → Run workflow → enter tag (e.g. `v2.6.3`).

## 6. Required Secrets

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded release keystore for signed, updatable release APKs |
| `GITHUB_TOKEN` | Automatic — used for Releases / failure issues |

## 7. Local Commands (optional)

Local builds are not required, but if a JDK 17 + Android SDK environment exists:

```bash
./gradlew assembleDebug        # debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew spotlessApply        # code style
```
