# Specification 07: Summary, Design, and Engineering Compromises

## 1. Core Feature Summary
The **Smart Encoder** is structured around a sequential, automated queue. The primary features are:
1. **Scoped Storage Folder Scanning**: Uses Android's Storage Access Framework (SAF) to recursively crawl selected directories, filtering files to compile a list of video candidates without requesting full storage access permissions.
2. **Predictive Smart Encoding (CRF Emulation)**: Estimates video complexity (motion, details, framerate, and dynamic range) prior to encoding, setting a dynamic target bitrate for the hardware HEVC encoder.
3. **High Dynamic Range (HDR) Preservation**: Automatically configures the `MediaCodec` pipeline to HEVC 10-bit Profile (`Main10` or `Main10HDR10`) and retains BT.2020 color configurations.
4. **Binary Container Metadata Patching**: A post-processing JNI native C++ step scans the original MP4, extracts user-data (`udta`), metadata (`meta`), GPS coordinates, and Samsung camera tags, and injects them back into the transcoded MP4 container.
5. **Timestamp Attributes Synchronization**: Copies the original file creation and modification timestamps, ensuring the file remains in chronological order in galleries.
6. **Hardware Telemetry Monitor**: Displays active encoding FPS, CPU/GPU temperatures (using sysfs thermal data), and whether hardware encoders are active.
7. **Over-The-Air (OTA) Updates**: An in-app check that queries the GitHub Releases API and launches the local package installer.

---

## 2. Visual Design & Theme Integration
* **Visual Palette**: Charcoal surfaces (`#1E293B`) overlaying a deep navy background (`#0F172A`). Bright accents of electric cyan (`#06B6D4`) and emerald green (`#10B981`) indicate focus states and positive compression efficiency.
* **Component Styling**: Cards utilize subtle borders and 12-16dp rounded corners to match modern Samsung OneUI components.
* **Dynamic System Theme**: Seamlessly binds to Android's Material You (`dynamicDarkColorScheme`) on supported Samsung devices, blending the app elements with the system's dynamic color accent.

---

## 3. Engineering Compromises & Architecture Trade-offs

### 3.1 Hardware vs. Software Encoding Quality (MediaCodec vs. x265)
* **Compromise**: Hardware encoders on Qualcomm chipsets (`OMX.qcom.video.encoder.hevc` or `c2.qti.hevc.encoder`) prioritize encoding speed and battery efficiency over mathematical compression density. Software encoders like `x265` produce slightly smaller file sizes at matching quality levels, but run extremely slow on mobile CPUs (2-5 FPS vs 60-120 FPS on S24 Ultra), which causes rapid battery depletion and heavy hardware heating.
* **Resolution**: The app uses hardware acceleration exclusively for transcoding. To bridge the quality gap, it performs the **pre-analysis pass** to estimate motion and detail levels, setting optimized target bitrates dynamically rather than using generic static presets.

### 3.2 Metadata Extraction (NDK Post-Process vs. FFmpeg Muxer)
* **Compromise**: Android's native `MediaMuxer` is closed and strips custom container boxes (like GPS or Samsung-specific motion configs).
* **Trade-off Options**:
  1. *Use FFmpeg to mux the output*: Requires routing the entire transcoding stream through FFmpeg's C-bindings, complicating the integration of hardware accelerators.
  2. *Write a native container patcher*: Let Media3/MediaCodec encode the video quickly using standard system muxers, and run a fast post-processing tool in native C++ to copy over the binary boxes.
* **Resolution**: We chose **Option 2**. It separates the transcoding pipeline (which remains clean and native Android) from the metadata copying step. If the C++ post-process fails, the transcoded video is still preserved, resulting in a more robust architecture.

### 3.3 Scoped Storage File Operations (File Descriptors vs. Temp Cache)
* **Compromise**: Under scoped storage, raw C++ libraries cannot write or read files on external storage using traditional string file paths.
* **Trade-off**: Copying large 5-10GB videos to the internal cache folder for processing consumes duplicate storage and is slow.
* **Resolution**: We pass native File Descriptors (`ParcelFileDescriptor.detachFd()`) from Kotlin to C++ via JNI. The C++ parser uses `fdopen` to read and write directly to external files, avoiding data duplication.

### 3.4 Visual Quality Scoring (SSIM/PSNR vs. On-Device VMAF)
* **Compromise**: Calculating VMAF (Video Multi-Method Assessment Fusion) on-device requires massive CPU processing, causing lag and overheating.
* **Resolution**: The application calculates **Structural Similarity (SSIM)** or **PSNR** on 10-second sample transcodes in C++ to approximate quality scores, rather than running a full, heavy VMAF model on the phone.

### 3.5 Queue Control vs. Passive Temperature Telemetry
* **Compromise**: Snapdragon 888 and 8 Gen 3 manage thermal states at the kernel level.
* **Trade-off**: Implementing active temperature control inside the app (like automatically pausing queue processing when hot) would interrupt overnight batch compression and create a bad user experience.
* **Resolution**: The application lets the operating system manage hardware temperatures. The UI focuses on displaying telemetry data and flags warning colors if temperature values are high.

### 3.6 MediaStore Date Synchronization under Scoped Storage
* **Compromise**: Android 11+ prevents background applications from updating `MediaStore` database fields (such as `DATE_ADDED` and `DATE_MODIFIED`) for files they do not explicitly own, meaning an in-place "Replace" operation would cause files to lose their original dates and jump to the top of the gallery timeline.
* **Trade-off Options**:
  1. *In-Place Replace (`rwt`)*: Saves space, keeps original database `_ID` (preserving album assignments), but corrupts the timeline position because the OS forces the modification date to "now".
  2. *Delete & Recreate*: Deletes the original file and creates a fresh copy with the exact same name. The application becomes the owner of the new `MediaStore` row, allowing it to perfectly backdate the file to its original chronological position, but changing the `_ID` (removing it from custom albums).
* **Resolution**: We chose **Option 2 (Delete & Recreate)**. For the vast majority of users, the primary Camera Roll chronological timeline is significantly more important than custom album assignments. We guarantee timeline integrity at the cost of `_ID` reassignment.
