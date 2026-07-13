# Specification 05: Queue & State Management

## 1. Local Database Model
The application maintains absolute state consistency using a Room database. This guarantees that if the application crashes, gets killed in the background, or if the device restarts, the queue status is preserved and can be resumed cleanly.

### 1.1 Room Entity: `TranscodeTask`
```kotlin
@Entity(tableName = "transcode_tasks")
data class TranscodeTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,          // SAF content URI of input
    val sourcePath: String?,        // Absolute path fallback (for C++ NDK)
    val destUri: String?,           // Target output directory URI
    val destPath: String?,          // Output path fallback
    val fileName: String,           // Cached file name
    val originalSize: Long,         // In bytes
    val compressedSize: Long = 0L,  // In bytes (updated on completion)
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0.0f,     // 0.0 to 1.0
    val speedFps: Float = 0.0f,     // Transcoding speed (FPS)
    val cpuTemp: Float = 0.0f,      // Temperature log
    val targetBitrate: Int = 0,     // In bps
    val originalBitrate: Int = 0,   // In bps
    val originalCodec: String = "",
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val isHdr: Boolean = false,
    val errorMessage: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val startedTimestamp: Long = 0L,
    val finishedTimestamp: Long = 0L
)
```

### 1.2 State Enumeration: `TaskStatus`
* `PENDING`: Task is added to database and waiting in queue.
* `ANALYZING`: File is currently being read by pre-analyzer to estimate complexity.
* `PROCESSING`: Media3 Transformer is actively writing the transcoded file.
* `PAUSED`: User paused a pending task.
* `COMPLETED`: Transcoding and native metadata injection succeeded.
* `FAILED`: Task failed; contains diagnostic error message.

---

## 2. Background Queue Execution
Background compression runs using **Jetpack WorkManager** bound to a **Foreground Service** notification.

```
       Task Added (UI)
              │
              ▼
   Enqueue Unique Work Request
   (ExistingWorkPolicy.KEEP)
              │
              ▼
    WorkManager Scheduler
              │
              ▼
┌───────────────────────────┐
│  CoroutineWorker running  │
│  as a Foreground Service  │ ──> Promotes process to Foreground status
└───────────────────────────┘     (Prevents OS termination)
              │
              ▼
 ┌─────────────────────────┐
 │ Fetch Next PENDING Task │ <─── Query Database loop
 └─────────────────────────┘
              │
              ▼
 ┌─────────────────────────┐
 │   Transcode & Inject    │
 └─────────────────────────┘
```

### 2.1 Sequential Processing Constraint
To prevent the Snapdragon chip from overheating and locking up, the application strictly processes tasks **sequentially (one by one)**. This is enforced using WorkManager's `enqueueUniqueWork` API with `ExistingWorkPolicy.KEEP`:
* If a transcode job is already running, new items added to the database are marked `PENDING` and are picked up automatically by the active worker thread on completion of the current task.
* Enforcing a single-thread pipeline reduces concurrent memory footprints (critical when processing 4K streams).

---

## 3. Foreground Service Notification
To comply with Android 14+ background execution limits, the `CoroutineWorker` creates a persistent status notification:
* **Channel ID**: `video_compress_channel`
* **Notification Priority**: `IMPORTANCE_LOW` (runs silently without blocking sound).
* **Updates**: The progress bar and ETA within the system notification are updated every 1% of transcoding progress.

---

## 4. Temperature & Hardware Monitoring
The system reads SoC thermal sensors directly via JNI or local sysfs lookups to display telemetry metrics on the active encoding panel:
* **File Path**: `/sys/class/thermal/thermal_zone0/temp` (fallback to system sensor properties).
* **Scanning Interval**: Telemetry is parsed every 2 seconds during active encoding.
* **Thermal Threshold Indicators**:
  - **Normal** ($<40^{\circ}\text{C}$): Green chip indicator.
  - **Warm** ($40^{\circ}\text{C}$ to $47^{\circ}\text{C}$): Orange chip indicator.
  - **Critical** ($>47^{\circ}\text{C}$): Red chip indicator (indicates device thermal throttling is active).
* **No Active Throttling**: The application does not attempt to throttle the GPU/CPU workload, as Qualcomm's kernel space throttling manages thermal profiles safely. The metrics are displayed purely for professional monitoring and diagnostics.

---

## 5. Control Interactivity (Pause, Resume, Cancel)
* **Pause**: Updates a `PENDING` task to `PAUSED` in Room database. If a task is paused, the background worker skips it when scanning the queue.
* **Resume**: Updates status back to `PENDING` and schedules WorkManager to resume processing.
* **Cancel/Delete**: If the cancelled task is currently active (`PROCESSING`), the worker calls `Transformer.cancel()` to abort writing, deletes the partial target file, and updates status to `FAILED` or removes it from Room database.
