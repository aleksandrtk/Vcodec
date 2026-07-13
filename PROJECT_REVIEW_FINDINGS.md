# Project Review Findings

Review date: 2026-07-13

Scope reviewed: Android/Kotlin app sources, Media3 transcode pipeline, WorkManager queue, SAF/MediaStore storage handling, metadata restoration, native MP4 metadata helper, manifest, and Gradle configuration.

Validation run:

```sh
./gradlew assembleDebug
```

Result: passed. Gradle reported deprecated features that will be incompatible with Gradle 10, but no build failure.

## Findings

### High: Replace-original mode can lose the source video if replacement creation or copy fails

The replace path deletes the original source document before proving that the replacement MediaStore entry exists, is writable, has received the transcoded bytes, and was finalized successfully. The critical sequence is in [app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt](app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt#L316-L356): metadata is copied into a temp file, the source is deleted, `createOutputUri(...)` is attempted, and then the temp file is copied to `finalUri` using `openOutputStream(finalUri)?.use { ... }`.

Risk cases:

- `createOutputUri(...)` returns null after the original was deleted, so `finalUri` falls back to the now-deleted `sourceUri`.
- `openOutputStream(finalUri)` returns null or throws after deletion.
- MediaStore insert/finalize fails, leaving no valid replacement even though the original was already removed.
- The task can still compute a size from the temp file fallback and move toward completion even if the final output was not actually written.

Recommended fix: make replace-original a two-phase operation. Create and fully write a pending replacement first, verify that the replacement URI exists and has nonzero expected size, restore metadata, finalize it, and only then delete the original. If deletion must happen first for exact-name constraints, keep the temp file as a rollback source and treat any failure after deletion as a hard failed state with an explicit recovery path. Avoid falling back to `sourceUri` after the source has been deleted.

### High: Worker failure paths can leave the thermal monitor child job running forever

The worker starts `monitorJob` once a task begins processing, but only cancels it on the normal path at [app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt](app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt#L285). If transcoding, fallback transcoding, metadata restoration, copy, delete, or finalize throws before that point, the outer catch marks the task failed and returns, while the monitor loop only exits for `null` or `PAUSED` status at [app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt](app/src/main/java/com/vcodec/smartencoder/worker/VideoTranscodeWorker.kt#L157-L164). It does not exit for `FAILED` or `COMPLETED`.

Because `monitorJob` is a child coroutine of the worker scope, a normal return from the catch can wait on that child instead of finishing the WorkManager job. This can leave failed work stuck, keep foreground work alive, and block subsequent queue work.

Recommended fix: wrap the processing section in `try/finally { monitorJob.cancelAndJoin() }`. Also make the monitor loop break for all terminal statuses (`FAILED`, `COMPLETED`) and when the worker coroutine is no longer active.

### Medium: Active task pause is wired in the worker but not reachable from the UI/repository

The monitor loop is designed to cancel active transcoding when the task becomes `PAUSED`, but [app/src/main/java/com/vcodec/smartencoder/data/TaskRepository.kt](app/src/main/java/com/vcodec/smartencoder/data/TaskRepository.kt#L36-L41) only pauses tasks that are still `PENDING`. The queue UI also only shows a pause button for pending queue items, while the active task card has no pause/cancel control.

User impact: once a long encode starts, the visible queue cannot actually pause it. This matters more because foreground video encoding can be slow and thermally expensive.

Recommended fix: decide whether active pause is a supported feature. If yes, allow `PROCESSING` and possibly `ANALYZING` to transition to `PAUSED`, cancel the active WorkManager work or active transformer cleanly, remove partial outputs, and reset progress/task state consistently. If no, remove the worker-side paused polling to avoid a misleading partial implementation.

### Medium: MediaStore date restoration can update the wrong duplicate filename

Several metadata paths resolve or update MediaStore rows using only `DISPLAY_NAME = ?`, for example [app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt](app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt#L76), [app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt](app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt#L290), and [app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt](app/src/main/java/com/vcodec/smartencoder/metadata/MetadataRestorer.kt#L388). The history repair flow calls these name-based helpers for original and compressed names from [app/src/main/java/com/vcodec/smartencoder/ui/MainViewModel.kt](app/src/main/java/com/vcodec/smartencoder/ui/MainViewModel.kt#L480-L491) and [app/src/main/java/com/vcodec/smartencoder/ui/MainViewModel.kt](app/src/main/java/com/vcodec/smartencoder/ui/MainViewModel.kt#L601-L612).

User impact: common camera names such as `VID_20240710_120000.mp4`, downloads, or chat exports can exist in multiple albums. A name-only update can restore dates on an unrelated video.

Recommended fix: prefer stable MediaStore IDs or the known destination URI. If falling back to a query, include `RELATIVE_PATH`, size, duration, and/or timestamp constraints, and treat multiple matching rows as ambiguous instead of updating the first match.

### Medium: OTA updater appears release-unready while requesting APK install permission

The app requests `REQUEST_INSTALL_PACKAGES` in [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml#L17), and the OTA updater can download and launch an APK installer. However, [app/src/main/java/com/vcodec/smartencoder/ota/OtaUpdater.kt](app/src/main/java/com/vcodec/smartencoder/ota/OtaUpdater.kt#L20-L34) still contains placeholder GitHub repository coordinates (`vcodec/smart-encoder`).

Risk: the update feature is either nonfunctional or points at an unintended release source. The package-install permission also increases review/policy scrutiny for distributed builds.

Recommended fix: either remove the OTA updater and install permission until the release channel is real, or configure the exact trusted repository and verify release assets/signatures before offering installation.

### Medium: Native library is restricted to `arm64-v8a`, limiting test and install coverage

The Gradle config only packages the native metadata library for `arm64-v8a` in [app/build.gradle.kts](app/build.gradle.kts#L26). That matches the comment for modern Samsung devices, but it excludes x86_64 emulators and 32-bit ARM devices.

Impact: emulator QA and broader device testing will fail to install or load native metadata restoration unless using arm64-only environments. This is acceptable for a private device-targeted app, but it is a release constraint that should be explicit.

Recommended fix: keep this if the supported-device policy is intentionally Samsung/arm64 only. Otherwise add `x86_64` for emulator coverage and consider broader ABI packaging.

### Low: There are no automated tests for the highest-risk behavior

No `app/src/test` or `app/src/androidTest` sources were present during review. The riskiest code is around SAF permissions, MediaStore insertion/finalization, WorkManager cancellation, Media3 fallback, and native MP4 metadata mutation, all of which are easy to regress.

Recommended starting tests:

- Unit-test bitrate and target-dimension calculation, especially portrait rotation and even-dimension rounding.
- Instrument replace-original and save-copy flows with fake/sandboxed documents where output creation fails.
- Instrument worker failure paths to confirm failed jobs terminate and the queue continues.
- Add regression coverage for duplicate display names in different MediaStore relative paths.

## Positive Notes

- Folder scanning uses `DocumentsContract.buildChildDocumentsUriUsingTree` and direct `contentResolver.query(...)`, avoiding recursive `DocumentFile.listFiles()` directory scans.
- Media3 Transformer is configured with explicit `VideoEncoderSettings`, which should force re-encoding instead of passthrough transmuxing.
- Portrait rotation is read during analysis and logical dimensions are swapped before target sizing.
- Target dimensions are rounded to even integers before encoding.
- The transcode path only applies the `Presentation` OpenGL effect when dimensions actually change, reducing emulator/GPU failure exposure.
- The debug build currently passes.