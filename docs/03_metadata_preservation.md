# Specification 03: Metadata & Container Integrity

## 1. File System Metadata Preservation
When a file is transcoded on Android, the operating system assigns a new creation date and modification date. To preserve the original timestamp attributes, a two-layered approach is used:

### 1.1 Direct File System Path (Java NIO)
For files residing in directories with direct file system paths, Java NIO is used to override creation, modification, and access dates:
```kotlin
val attrs = Files.readAttributes(srcFile.toPath(), BasicFileAttributes::class.java)
val view = Files.getFileAttributeView(destFile.toPath(), BasicFileAttributeView::class.java)
view.setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
```

### 1.2 Scoped Storage / ContentResolver Fallback (JNI Fd)
If direct file paths are restricted by Scoped Storage, the system modifies dates by querying the source FileDescriptor statistics and updating the target file descriptor using Android Os bindings:
```kotlin
val srcStat = Os.fstat(srcFd.fileDescriptor)
val timeVal = android.system.StructTimeval.fromMillis(srcStat.st_mtime * 1000)
Os.futimes(destFd.fileDescriptor, arrayOf(timeVal, timeVal))
```
Additionally, database entries in the `MediaStore` table (`DATE_ADDED`, `DATE_MODIFIED`) are updated via `ContentResolver` values to guarantee gallery listings preserve original chronological order.

---

## 2. MP4 Container Metadata Structure
An MP4 container is composed of sequential binary blocks called **boxes** (or **atoms**). Each box has a 4-byte size header and a 4-byte type identifier.

```
┌────────────────────────────────────────────────────────┐
│ ftyp (File Type Box)                                   │
├────────────────────────────────────────────────────────┤
│ mdat (Media Data Box - Video & Audio Frames Payload)   │
├────────────────────────────────────────────────────────┤
│ moov (Movie Container Box - Metadata & Indexes)        │
│  ├── mvhd (Movie Header - Creation Time / Duration)    │
│  ├── trak (Track Container - Video Track Info)         │
│  │    └── mdia -> minf -> stbl (Sample Tables)         │
│  │         └── stco / co64 (Chunk Offset Tables)       │
│  ├── trak (Track Container - Audio Track Info)         │
│  ├── udta (User Data - Location, GPS, Camera Tags)    │
│  └── meta (Metadata - Custom Key-Value Pairs, EXIF)    │
└────────────────────────────────────────────────────────┘
```

Standard Android encoders create a fresh `moov` box that retains basic track details but strips out:
* Location tags (GPS coordinates in `moov.udta.xyz` box).
* Extended EXIF and XMP packets (in `moov.meta`).
* Samsung-specific proprietary metadata (e.g., slow-motion marker offsets `moov.meta.sefd`, dynamic photos, camera details).

---

## 3. Native C++ Box Parsing & Injection
The application leverages a native C++ module via the Android NDK to inspect the source and target MP4 structures.

### 3.1 Metadata Extraction (Source File)
1. Read the root-level boxes of the source file.
2. Locate the `moov` container box.
3. Seek inside the `moov` box and locate metadata sub-boxes (`udta`, `meta`, and any other vendor-specific containers).
4. Parse and buffer the raw content of these boxes into memory.

### 3.2 Metadata Merging (Destination File)
1. Parse the destination file's `moov` box.
2. Filter out any generic `udta` or `meta` boxes created by Android's default encoder.
3. Construct a new `moov` block combining the destination's audio/video track configurations (`trak` boxes) and the source's original metadata boxes.

---

## 4. Chunk Offset (stco/co64) Reconstruction
In an MP4 container, chunk offset boxes (`stco` for 32-bit offsets, `co64` for 64-bit offsets) tell the player the exact byte positions of the compressed media frames inside the file. 

If the modified `moov` box size changes and it is placed **before** the media data (`mdat` box) in the file structure (Fast-start configuration), the absolute positions of all frames shift.

To prevent container corruption:
1. Calculate the delta: $\Delta = \text{size}(\text{new moov}) - \text{size}(\text{old moov})$.
2. If $\Delta \neq 0$ and `moov` is placed before `mdat`:
   - Iterate through every track (`trak`) in the new `moov` box.
   - Access the sample table (`trak.mdia.minf.stbl`).
   - Parse the `stco` (or `co64`) box.
   - Add $\Delta$ to every chunk offset entry stored in the table.
3. Rewrite the target file.

*Note: If the `moov` box is placed at the **end** of the file (Standard MediaMuxer configuration), no offsets need adjustment, because changing the `moov` size does not shift the preceding `mdat` block.*

---

## 5. Media Database Synchronization (Scoped Storage Constraints)
Once native transcoding and metadata merging is complete, the file must be synchronized with Android's `MediaStore` so that Gallery apps (e.g. Samsung Gallery) display the video in the correct chronological order.

### 5.1 Android 11+ In-Place Overwrite Constraint
When overwriting an existing file (e.g., using `openOutputStream("rwt")`), the Android FUSE daemon and `MediaProvider` aggressively reset the file's `DATE_MODIFIED` to the current timestamp upon stream closure. 
Due to Scoped Storage security policies, applications cannot use `ContentResolver.update()` to restore `DATE_ADDED` or `DATE_MODIFIED` for files they do not explicitly own (files created by the Camera app), causing "Replace" operations to jump to the top of the gallery timeline.

### 5.2 The "Delete & Recreate" Strategy
To perfectly preserve chronological order during a "Replace" operation without requiring `MANAGE_EXTERNAL_STORAGE` permissions, the following architecture is strictly enforced:
1. Copy all custom metadata boxes (`moov` atoms) from the original file to the fully processed `tempFile`.
2. **Delete** the original file entirely using `DocumentsContract.deleteDocument`. This removes the restricted database row.
3. **Recreate** a new `MediaStore` row (`MediaStore.Video.Media.EXTERNAL_CONTENT_URI`) with the exact original `DISPLAY_NAME` and `RELATIVE_PATH`, flagged as `IS_PENDING = 1`.
4. Copy the data from `tempFile` into this newly owned URI.
5. Call `finalizePendingUri` on the new URI. Because the application is now the explicit owner of this database row, the system allows the app to flawlessly backdate `DATE_ADDED` and `DATE_MODIFIED` to their original values.

*Note: While this changes the underlying `_ID` in the MediaStore (dropping the file from custom albums), it is the only mathematically guaranteed way to preserve the primary Camera Roll timeline position in modern Android.*
