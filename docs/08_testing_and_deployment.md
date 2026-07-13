# Specification 08: Local Testing and On-Device Deployment

This document provides a step-by-step guide on how to test the application locally during development and how to build, install, and verify it on physical devices (such as the Samsung Galaxy S24 Ultra or S21 5G).

> [!IMPORTANT]
> **Do you need to install Android Studio?**
> * **NO**, if you only want to test the pre-compiled application. The AI agent (or a CI/CD system) compiles the ready-made `.apk` package, which you can simply download and install on your phone.
> * **YES**, if you plan to write, modify, debug, or build the source code yourself on your local machine. Android Studio will install the necessary JDK, Android SDK, and C++ NDK required for compilation.

---

## 1. Local Testing (Developer PC & Android Emulator)

Testing a media application on an emulator has specific limitations, primarily because emulators lack physical Qualcomm Snapdragon hardware encoders.

### 1.1 Emulator Limitations & Fallbacks
* **Hardware Acceleration**: The emulator will fall back to Android's default software codec implementations (`c2.android.hevc.encoder` and `c2.android.hevc.decoder`). While the compression logic and workflows remain identical, transcoding speeds will be significantly slower than on a physical phone.
* **HDR rendering**: Standard emulators cannot display true HDR colors. Color banding or tone mapping checks might display differently.

### 1.2 Preparing Test Media on the Emulator
To test folder scanning and compression, you need to push video samples to the emulator's virtual storage:
1. **Drag-and-drop**: Drag video files directly onto the emulator window to copy them to the `Downloads` directory.
2. **ADB Command line**: Push files to the DCIM directory:
   ```bash
   adb push test_video.mp4 /sdcard/DCIM/
   ```
3. **Trigger Media Scan**: Force Android's media indexer to register the new files:
   ```bash
   adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/test_video.mp4
   ```

### 1.3 Running Automated Tests
The codebase supports two test suites in Android Studio:
1. **Unit Tests (`test/` folder)**:
   - Evaluates the bitrate heuristic formulas inside `VideoAnalyzer` on JVM.
   - Run via command line:
     ```bash
     ./gradlew testDebugUnitTest
     ```
2. **Instrumented Tests (`androidTest/` folder)**:
   - Runs integration tests on active emulators or devices.
   - Tests native JNI interactions in `MetadataRestorer` (passing mock files via temp File Descriptors to verify that metadata boxes parse and write correctly).
   - Run via command line:
     ```bash
     ./gradlew connectedAndroidTest
     ```

### 1.4 Debugging JNI & C++ Logs
To monitor raw logs output by the native C++ metadata module, run `adb logcat` filtered by the JNI tag:
```bash
adb logcat -s MetadataRestorer:I *:S
```

### 1.5 Testing Compiled APKs Locally on PC (Without Physical Phone)
If you have a compiled `.apk` file and want to run it on your macOS computer, you must use a virtual Android environment (Emulator):

#### Method A: Official Android Emulator (Recommended for Apple Silicon M1/M2/M3/M4)
1. Install Android Studio (needed to manage virtual devices).
2. Open Android Studio, navigate to **Tools** -> **Device Manager**.
3. Click **Create Device**, select a profile (e.g., Pixel 8 or a custom tablet), and choose an arm64 system image matching your Mac's chip (e.g., API 34).
4. Launch the emulator. A phone frame interface will boot on your computer desktop.
5. **Install the APK**: Drag and drop the `app-debug.apk` file from Mactintosh Finder directly onto the active emulator screen. The emulator installs it in 2 seconds.
6. Alternatively, open a Terminal window and run:
   ```bash
   adb install -r /path/to/app-debug.apk
   ```

#### Method B: Lightweight Third-Party Emulators (Genymotion)
If you want an emulator without full Android Studio overhead:
1. Download **Genymotion Desktop** (choose the macOS Apple Silicon version if using an M-series Mac).
2. Install a virtual device running a modern Android version (Android 11 or newer is recommended).
3. Drag and drop the `.apk` file into the Genymotion device screen to install.

---

## 2. On-Device Installation & Physical Testing (Samsung)

To test the full capability of the Snapdragon HEVC hardware encoders and verify dynamic HDR metadata, the application must run on a physical Samsung S24 Ultra or S21 5G.

### 2.1 Enabling Developer Options on Samsung
Before connecting the device:
1. Open **Settings** on your phone.
2. Go to **About phone** -> **Software information**.
3. Tap **Build number** seven times consecutively until you see the prompt: *"Developer mode has been turned on."*
4. Go back to the main Settings menu, open the new **Developer options** menu, and enable **USB debugging**.

### 2.2 Connecting to your Computer
* **USB Connection**: Connect the phone using a high-speed USB-C cable and accept the debugging permission dialog on the phone's screen.
* **Wireless Debugging (Wi-Fi)**:
  1. Ensure both your PC and phone are on the same Wi-Fi network.
  2. In **Developer options**, enable **Wireless debugging**.
  3. Click "Pair device with pairing code" to obtain the IP, port, and code.
  4. Pair via terminal:
     ```bash
     adb pair IP:PORT CODE
     adb connect IP:PORT
     ```

### 2.3 Compiling and Installing the App
Choose one of the following methods to install the build:

#### Method A: Via Android Studio (Recommended)
1. Select your Samsung device from the target device dropdown at the top of the interface.
2. Click the green **Run** button (or press `Shift + F10`).
3. Android Studio compiles the C++ libraries, compiles Kotlin classes, packs the APK, and deploys it directly to the phone.

#### Method B: Via Command Line (Gradle & ADB)
1. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
2. Install the generated APK onto the connected device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

#### Method C: Direct APK Transfer & Manual Installation
If you don't want to use command-line debugging tools, you can simply transfer the compiled APK file directly to your phone:
1. Build the APK on your PC using Android Studio (`Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`) or via Gradle: `./gradlew assembleDebug`.
2. Locate the compiled APK file (usually at `app/build/outputs/apk/debug/app-debug.apk`).
3. Transfer the APK file to your Samsung phone using any method:
   * **USB Cable**: Drag and drop the file to the `Downloads` folder on your phone.
   * **Quick Share / Nearby Share**: Send the file directly from your laptop to your Samsung phone.
   * **Messengers / Clouds**: Send it to yourself via Telegram, Google Drive, etc.
4. On your Samsung phone, open the **My Files** (Мои файлы) app.
5. Go to the **Downloads** (Загрузки) or **Installation files (APKs)** category.
6. Tap on the `app-debug.apk` file.
7. If prompted with a security warning, tap **Settings** (Настройки) and toggle on **Allow from this source** (Разрешить установку из этого источника) for the "My Files" app.
8. Tap **Install** (Установить). Once completed, the app is ready for testing.

---

## 3. Linting & Code Quality Control

To ensure professional-grade code stability and formatting conformity, the project enforces automated static analysis tools:

### 3.1 Kotlin Formatting & Style (Spotless & ktlint)
The project uses the Spotless Gradle plugin with `ktlint` configuration rules.
* **Verify Code Style**:
  ```bash
  ./gradlew spotlessCheck
  ```
* **Auto-format Code**:
  ```bash
  ./gradlew spotlessApply
  ```

### 3.2 Android Lint (Static Code Analysis)
Enforces API checks, security best practices (e.g., targetSdk compatibility, Scoped Storage boundaries), performance, and accessibility checks:
* **Run Lint Scan**:
  ```bash
  ./gradlew lintDebug
  ```
  This creates an HTML report detailing errors and warnings under `app/build/reports/lint-results-debug.html`.

### 3.3 Native C++ Code Quality (Clang-Tidy & Clang-Format)
The NDK C++ compiler is configured with standard clang-tidy flags to inspect memory management and pointer usage in `metadata-restorer.cpp`:
* **Clang-Tidy verification**: Automatically executed during NDK compilation (`./gradlew compileDebugNdk`). Check compilation console logs for any memory safety warnings.

---

## 4. Verification Testing on Physical Device

Once the app is running, run these tests to verify execution and metadata recovery:

### 4.1 Verify Metadata Integrity
Ensure that after transcoding, dates and GPS parameters are identical to the source:
1. Compress a video (e.g. `VID_20260710.mp4`).
2. Open Samsung's default **Gallery app** and check the details of both the original and compressed videos.
3. Verify that **Date Taken**, **Location Coordinates**, and **Camera Model** match exactly.
4. Download both files to your PC and compare headers using `exiftool` to verify that custom container boxes are preserved:
   ```bash
   exiftool original.mp4 > original_meta.txt
   exiftool compressed.mp4 > compressed_meta.txt
   diff original_meta.txt compressed_meta.txt
   ```

### 4.2 Verify HDR Preservation
1. Compress a 10-bit HDR10+ video.
2. Play the compressed file in the Samsung Gallery.
3. Look for the **"HDR" or "HDR10+" badge** in the video player UI to confirm that color profiles and dynamics were not stripped or mapped to SDR.

### 4.3 Verify Space Reclaim & Quality
1. Verify the final file size. H.264 -> H.265 conversions should demonstrate a **40-60% size reduction**.
2. Play both files side-by-side to visually inspect for compression artifacts or blockiness in complex scenes.
