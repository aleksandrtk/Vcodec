# VCodec System Optimizations & CI/CD Dev Releases Specification

**Date:** 2026-08-25  
**Branch:** `feature/optimizations-and-dev-releases`  
**Status:** Approved for Implementation  

---

## 1. Executive Summary

This specification outlines comprehensive, non-breaking improvements to the **VCodec (Smart Video Encoder)** project across four core areas:
1. **Automated Testing & CI/CD Channels**: Adding Unit Tests for core heuristics and configuring GitHub Actions to publish `dev` artifacts/prereleases for all `feature/*` and non-main branches.
2. **Media Pipeline & Concurrency**: Enabling active task pause/cancellation, worker process failure recovery, and robust `moov` container box positioning.
3. **Clean Architecture & UI Decomposition**: Breaking down monolithic files (`MainUi.kt` 2172 lines and `MainViewModel.kt` 1066 lines) into modular feature components and use cases with zero visual or functional regressions.
4. **Documentation & Release Accuracy**: Aligning specs with real code (clarifying VMAF/SSIM as a planned roadmap item) and fixing OTA repository coordinates.

---

## 2. CI/CD & Release Strategy

### 2.1 Branch & Channel Resolution Logic
In `.github/workflows/release.yml`:
* **Main Branch (`refs/heads/main`) / Release Tags (`v*`)**:
  * Channel: `stable`
  * APK Artifact: `VCodec-v{version}-stable.apk`
  * GitHub Release: Published as standard Release (not prerelease).
* **Feature Branches (`feature/*`) & other non-main branches**:
  * Channel: `dev`
  * APK Artifact: `VCodec-v{version}-dev.apk`
  * GitHub Release: If triggered/published, marked as `prerelease: true` with title `"VCodec v{version} (dev)"` and tag `v{version}-dev`.

---

## 3. Test Harness (Unit Tests)

Location: `app/src/test/java/com/vcodec/smartencoder/`

### 3.1 `VideoAnalyzerTest.kt`
* **Target Dimensions**:
  * Landscape 4K to 1080p, 720p, Original.
  * Portrait 4K (height > width or rotation = 90°/270°) to 1080p, 720p.
  * Ensures width and height are strictly even integers (`w % 2 == 0`, `h % 2 == 0`).
* **Bitrate Heuristic (`calculateSuggestedBitrate`)**:
  * 4K SDR 30fps -> 12 Mbps base.
  * 60fps scaling (`fpsFactor = 1.4`).
  * 10-bit HDR scaling (`hdrFactor = 1.25`).
  * Codec discount: H.264 -> 50%, HEVC -> 75%, AV1 -> 60%.
  * Safety clamp: never exceed 85% of original, minimum floor 800 kbps.

### 3.2 `MetadataRestorerTest.kt`
* **Filename Date Parsing (`parseDateFromFileName`)**:
  * Standard Android camera patterns: `VID_20240710_220630.mp4`, `20240710_220630.mp4`.
  * Date-only patterns: `20240710.mp4`, `2024-07-10.mp4`.
  * Malformed / non-date names: `test_video.mp4` -> returns null.

### 3.3 `MediaStorageManagerTest.kt`
* **Path Sanitization (`sanitizeRelativePath`)**:
  * Valid roots: `DCIM/Camera/` -> `DCIM/Camera/`, `Movies/Clips` -> `Movies/Clips/`.
  * Deep full paths: `storage/emulated/0/DCIM/Camera` -> `DCIM/Camera/`.
  * Fallback for unknown roots -> `Movies/SmartEncoder/`.

---

## 4. Media Pipeline & Concurrency Enhancements

### 4.1 Active Task Control (Pause & Cancel)
* **`TaskRepository.kt`**:
  * Allow transitioning `PROCESSING` and `ANALYZING` tasks to `PAUSED` or cancelling them.
* **`VideoTranscodeWorker.kt`**:
  * Ensure `monitorJob` detects `PAUSED` and immediately cancels the active `Transformer` job.
  * Clean up temporary `.mp4` files from cache without touching source files.
* **`MainUi` (Queue Screen)**:
  * Add Pause and Delete/Cancel buttons directly on the active transcoding card.

### 4.2 Worker Failure Recovery
* **`SmartEncoderApp.kt` / `TaskRepository.kt`**:
  * On application startup, detect any tasks stuck in `PROCESSING` or `ANALYZING` state (left over from an OS kill or process termination).
  * Automatically reset these tasks to `PENDING` so they can be picked up when the queue runs.

### 4.3 Native Metadata Restorer (`metadata-restorer.cpp`)
* Add safety handling for cases where `moov` is placed at the beginning of the file (streaming / fast-start MP4s).

---

## 5. Architecture & UI Decomposition

Maintain 100% UI fidelity while organizing the codebase into clean, maintainable units:

```
app/src/main/java/com/vcodec/smartencoder/
├── data/
│   ├── AppDatabase.kt
│   ├── TaskDao.kt
│   ├── TaskRepository.kt
│   └── TranscodeTask.kt
├── domain/
│   ├── ScanMediaUseCase.kt          // Logic for querying DocumentsContract tree & MediaStore
│   ├── RepairDatesUseCase.kt        // Logic for fixing MediaStore & SAF dates
│   └── OtaUpdateManager.kt          // OTA GitHub release check & download
├── pipeline/
│   └── VideoTranscoder.kt
├── analyzer/
│   └── VideoAnalyzer.kt
├── metadata/
│   └── MetadataRestorer.kt
├── worker/
│   └── VideoTranscodeWorker.kt
├── ui/
│   ├── MainActivity.kt
│   ├── MainViewModel.kt             // Thin state coordinator
│   ├── screens/
│   │   ├── scanner/
│   │   │   ├── ScannerScreen.kt
│   │   │   └── ScannerControls.kt
│   │   ├── queue/
│   │   │   ├── QueueScreen.kt
│   │   │   ├── ActiveTaskCard.kt
│   │   │   └── QueueItemCard.kt
│   │   └── history/
│   │       ├── HistoryScreen.kt
│   │       ├── HistoryItemCard.kt
│   │       └── StorageStatsCard.kt
│   ├── components/
│   │   ├── UpdateDialog.kt
│   │   ├── RepairDatesDialog.kt
│   │   └── BitrateSlider.kt
│   └── theme/
│       ├── Color.kt
│       └── Theme.kt
```

---

## 6. Documentation & Roadmap Accuracy

1. **`docs/04_smart_encoding_engine.md`**:
   * Clarify that the current engine employs a fast, analytical header-based heuristic (`calculateSuggestedBitrate`) for instant analysis.
   * Section 4 (VMAF/SSIM Closed-Loop Verification) is explicitly demarcated as **[Planned Roadmap / Phase 2 Engine Feature]**.
2. **`README.md`**:
   * Update descriptions to accurately reflect the real-time thermal monitoring, 2-phase transactional replace, and current bitrate formulas.
3. **`OtaUpdater.kt`**:
   * Update or configure default repository coordinates to prevent misleading network requests.
