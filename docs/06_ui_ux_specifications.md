# Specification 06: UI/UX & OTA System

## 1. Visual Design Language
Smart Encoder is styled to feel like a premium professional workstation utility adapted for Android.
* **Theme**: Deep Navy background (`#0F172A`) and Charcoal Dark surfaces (`#1E293B`) to reduce screen glare and power draw during long nights.
* **Accent Colors**: Electric Cyan (`#06B6D4`) for focus states and progress indicators; Emerald Green (`#10B981`) for completed tasks and space savings metrics.
* **Typography**: Clean, readable sans-serif (Inter or Outfit font families, utilizing system-provided dynamic scaling).

---

## 2. Layout Wireframes & Screens

### 2.1 Scanner Screen
This is the directory parsing dashboard.
```
┌────────────────────────────────────────────────────────┐
│ Smart Encoder                                          │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │  📁 Active Folder: /DCIM/Camera                     │ │
│ │  Total Size: 124.5 GB | 148 Video files            │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ [X] Select All                   [Filters: Size, Date] │
│                                                        │
│  [X] VID_20260710_1205.mp4                 1.4 GB      │
│      HEVC | 4K | 60 FPS                                │
│                                                        │
│  [ ] VID_20260710_1422.mp4                 840 MB      │
│      AVC  | 1080p | 30 FPS                             │
│                                                        │
│  [X] VID_20260710_1830.mp4                 3.2 GB      │
│      HEVC | 4K | 60 FPS | HDR10+                       │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │              ADD 2 VIDEOS TO QUEUE                 │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 2.2 Queue & Active Processing Screen
Displays ongoing transcode progress, temperature stats, and pending queue items.
```
┌────────────────────────────────────────────────────────┐
│ Smart Encoder                                          │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │  COMPRESSING VIDEO...                     [ 41.5°C ] │
│ │  VID_20260710_1830.mp4                               │
│ │  ───────────────────────────────────               │
│ │  ██████████████░░░░░░░░░░░░░░░░░░░░░  45% Completed  │
│ │                                                      │
│ │  Speed: 42 FPS | Target: HEVC @ 14 Mbps              │
│ │  ⚡ Snapdragon HW HEVC 10-bit (HDR) active          │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│  Queue (4 files)                                       │
│  [=] VID_20260710_1205.mp4     1.4 GB   In Queue   [x] │
│  [=] VID_20260710_1422.mp4     840 MB   In Queue   [x] │
│  [=] VID_20260710_1530.mp4     1.2 GB   Paused     [>] │
└────────────────────────────────────────────────────────┘
```

### 2.3 Savings & History Screen
Focuses on metrics and historical accomplishments.
```
┌────────────────────────────────────────────────────────┐
│ Smart Encoder                                          │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │             TOTAL STORAGE RECLAIMED                  │ │
│ │                     74.8 GB                          │ │
│ │          Through 24 compressed files                 │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│  Compression History                                   │
│  [✓] VID_20260709_1001.mp4     4.2 GB -> 1.8 GB  (-57%)│
│  [✓] VID_20260709_1200.mp4     1.5 GB -> 620 MB  (-58%)│
│  [✓] VID_20260709_1845.mp4     8.4 GB -> 3.2 GB  (-61%)│
└────────────────────────────────────────────────────────┘
```

---

## 3. OTA (Over-the-Air) Update Engine
Since the application is distributed outside of Google Play (intended only for you and your friends), it includes an integrated update service checking **GitHub Releases**.

### 3.1 Fetching Releases API
The application uses an asynchronous OkHttp Client to query the repository's latest release:
`GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
* **JSON Properties checked**:
  - `tag_name`: Parsed as a semantic version (e.g. `v1.2.0`). Compared to local `BuildConfig.VERSION_NAME`.
  - `body`: Relates the changelog to show inside an update dialogue.
  - `assets`: Identifies the artifact containing the compiled `.apk` file (e.g. `smart-encoder-release.apk`).

### 3.2 Download & Installation Flow
1. **Update Detected**: An alert dialog appears showing version differences and release notes.
2. **Download APK**: If approved, a download request is routed to Android's `DownloadManager` or written to `context.cacheDir`.
3. **Installation Trigger**:
   - The app verifies permissions: `android.permission.REQUEST_INSTALL_PACKAGES` (required for Android 8.0+).
   - Once downloaded, it executes the system package installer using an Intent:
```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(apkFileUri, "application/vnd.android.package-archive")
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
}
context.startActivity(intent)
```
4. This prompts a system pop-up confirming the update without needing manual APK selection in file managers.
