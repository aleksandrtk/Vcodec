# Project-Scoped Rules for VCodec

This document contains rules, guidelines, and context specific to the VCodec workspace. Antigravity and other AI agents must strictly adhere to these rules when working in this repository.

---

## 1. Directory and File Operations
* **Optimized SAF Querying**: Never use `DocumentFile.listFiles()` recursive loops to scan directories or look for files, as it creates severe UI lag and blocks threads. Always query the `contentResolver` directly using `DocumentsContract.buildChildDocumentsUriUsingTree` to fetch documents incrementally and perform ultra-fast single database queries.

---

## 2. Video Processing with Media3 Transformer
* **Enforcing Re-encoding**: Media3 Transformer automatically defaults to *transmuxing* (passthrough) if the input and output formats match. To force compression (bitrate reduction) even when codecs match:
  * Configure custom `VideoEncoderSettings` with the target bitrate.
  * Register the custom `VideoEncoderSettings` on the `DefaultEncoderFactory`.
* **Preserving Aspect Ratio & Video Orientation**:
  * Always read the video's rotation metadata (`"rotation-degrees"`) using `MediaFormat`.
  * If the video has a rotation of `90` or `270` degrees (portrait video), **swap** the width and height to obtain the *logical* rotated dimensions.
  * Calculate target scaling sizes based on logical dimensions to prevent portrait videos from being letterboxed/pillarboxed inside landscape frames.
* **Even Dimensions Constraints**: When scaling video dimensions (e.g. for `1080p` or `720p`), always round the calculated width and height to the nearest even integer (divisible by 2). Passing odd dimensions causes hardware encoders to crash.
* **OpenGL / Emulator Fallbacks**:
  * Standard Android Emulator GPUs do not support `GL_EXT_YUV_target` (required for 10-bit YUV/HDR texture processing).
  * Only apply the OpenGL `Presentation` effect if the target resolution differs from the original.
  * If transcoding fails with a `GL_EXT_YUV_target` or `Video frame processing error` on an emulator or low-end GPU, catch the exception and fallback to `Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR` to transcode successfully in 8-bit SDR.

---

## 3. Background Services & WorkManager
* **Foreground Service Special Use (Android 14+)**: When running background transcoding using WorkManager, always declare `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` in the `ForegroundInfo` constructor and catch configuration exceptions to satisfy Android 14+ background compliance.

---

## 4. MediaStore and SAF (Scoped Storage) Operations
* **In-Place File Replacement (`rwt`) Anti-Pattern**: NEVER overwrite an existing video file in-place using `openOutputStream("rwt")` if you need to preserve chronological sorting in Samsung Gallery or other apps. Android 11+ MediaProvider and FUSE aggressively reset `DATE_MODIFIED` to "now" when a file is closed, and Scoped Storage explicitly blocks `contentResolver.update()` calls for files the app does not own.
* **The "Delete & Recreate" Strategy for Replace**: To implement a true "Replace" that preserves original dates, you MUST:
  1. Read original `FileDates` and custom metadata.
  2. Transcode to a `tempFile`.
  3. Copy custom metadata to the `tempFile` via JNI (`restoreAllMetadata`).
  4. **Delete** the original document entirely (`DocumentsContract.deleteDocument`).
  5. **Insert** a brand-new MediaStore row with the exact original `DISPLAY_NAME` and `RELATIVE_PATH` (`IS_PENDING = 1`).
  6. Copy `tempFile` data to the new URI.
  7. Use `update()` on the new URI to set `DATE_ADDED`, `DATE_MODIFIED`, and remove `IS_PENDING`. Because the app created the new row, ownership is granted, and the OS will perfectly accept the backdated timestamps, keeping the video in its original position in the gallery timeline.
