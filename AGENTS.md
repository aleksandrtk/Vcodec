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
