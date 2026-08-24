#include <jni.h>
#include <string>
#include <android/log.h>
#include <fstream>
#include <vector>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>
#include <endian.h> // For htobe32 and htobe64

#define LOG_TAG "MetadataRestorer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

inline uint32_t read_u32_be(FILE* f) {
    uint8_t buf[4];
    if (fread(buf, 1, 4, f) != 4) return 0;
    return (buf[0] << 24) | (buf[1] << 16) | (buf[2] << 8) | buf[3];
}

inline void write_u32_be(FILE* f, uint32_t val) {
    uint8_t buf[4];
    buf[0] = (val >> 24) & 0xFF;
    buf[1] = (val >> 16) & 0xFF;
    buf[2] = (val >> 8) & 0xFF;
    buf[3] = val & 0xFF;
    fwrite(buf, 1, 4, f);
}

inline uint64_t read_u64_be(FILE* f) {
    uint8_t buf[8];
    if (fread(buf, 1, 8, f) != 8) return 0;
    return ((uint64_t)buf[0] << 56) | ((uint64_t)buf[1] << 48) |
           ((uint64_t)buf[2] << 40) | ((uint64_t)buf[3] << 32) |
           ((uint64_t)buf[4] << 24) | ((uint64_t)buf[5] << 16) |
           ((uint64_t)buf[6] << 8)  | buf[7];
}

inline void write_u64_be(FILE* f, uint64_t val) {
    uint8_t buf[8];
    buf[0] = (val >> 56) & 0xFF;
    buf[1] = (val >> 48) & 0xFF;
    buf[2] = (val >> 40) & 0xFF;
    buf[3] = (val >> 32) & 0xFF;
    buf[4] = (val >> 24) & 0xFF;
    buf[5] = (val >> 16) & 0xFF;
    buf[6] = (val >> 8)  & 0xFF;
    buf[7] = val & 0xFF;
    fwrite(buf, 1, 8, f);
}

// Structure to hold metadata about a root level box
struct Mp4Box {
    uint64_t size;
    char type[4];
    uint64_t header_size;
    uint64_t offset;
};

// Maximum bytes we are ever willing to buffer in memory for a single box.
// Legitimate udta/meta boxes are a few KB; 16 MB is a generous safety ceiling
// that prevents OOM from corrupt 'size' fields (which can claim up to 4 GB).
static const uint64_t MAX_BOX_BUFFER_BYTES = 16ULL * 1024ULL * 1024ULL;

// Reads root-level boxes of an MP4 file
std::vector<Mp4Box> parse_root_boxes(FILE* f) {
    std::vector<Mp4Box> boxes;
    if (!f) return boxes;

    if (fseek(f, 0, SEEK_END) != 0) return boxes;
    long tell_end = ftell(f);
    if (tell_end < 0) return boxes;
    uint64_t file_size = (uint64_t)tell_end;
    if (fseek(f, 0, SEEK_SET) != 0) return boxes;

    uint64_t offset = 0;
    while (offset + 8 <= file_size) {
        if (fseek(f, offset, SEEK_SET) != 0) break;
        uint64_t size = read_u32_be(f);
        char type[4];
        if (fread(type, 1, 4, f) != 4) break;

        uint64_t header_size = 8;
        if (size == 1) {
            size = read_u64_be(f);
            header_size = 16;
        } else if (size == 0) {
            size = file_size - offset;
        }

        // Corrupt box: size must at least cover its own header and stay inside the file
        if (size < header_size || offset + size > file_size) {
            LOGE("Corrupt root box at offset %llu (size %llu, file %llu). Stopping parse.",
                 (unsigned long long)offset, (unsigned long long)size,
                 (unsigned long long)file_size);
            break;
        }

        Mp4Box box;
        box.size = size;
        memcpy(box.type, type, 4);
        box.header_size = header_size;
        box.offset = offset;

        boxes.push_back(box);
        offset += size;
    }
    return boxes;
}

/**
 * Reads a whole sub-box of [box_offset, container_end) into memory with validation.
 * Returns false on truncation or when the box is implausibly large.
 */
static bool read_box_checked(FILE* f, uint64_t box_offset, uint32_t declared_size,
                             uint64_t container_end, std::vector<uint8_t>& out) {
    if (declared_size < 8) return false;                       // must cover size+type header
    if ((uint64_t)declared_size > container_end - box_offset) return false; // overruns container
    if ((uint64_t)declared_size > MAX_BOX_BUFFER_BYTES) {      // implausible allocation
        LOGE("Box at offset %llu claims %u bytes, exceeding safety limit. Skipping.",
             (unsigned long long)box_offset, declared_size);
        return false;
    }
    out.resize(declared_size);
    if (fseek(f, box_offset, SEEK_SET) != 0) return false;
    return fread(out.data(), 1, declared_size, f) == declared_size;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_vcodec_smartencoder_metadata_MetadataRestorer_copyCustomMetadataBoxesFd(
        JNIEnv *env,
        jobject thiz,
        jint src_fd,
        jint dest_fd,
        jlong date_taken_ms,
        jlong date_modified_sec) {

    LOGI("Native copy metadata boxes initiated. Source FD: %d, Dest FD: %d", src_fd, dest_fd);

    // Duplicate FDs so we don't accidentally close the caller's descriptors
    int src_fd_dup = dup(src_fd);
    int dest_fd_dup = dup(dest_fd);

    FILE* src_file = fdopen(src_fd_dup, "rb");
    FILE* dest_file = fdopen(dest_fd_dup, "r+b");

    if (!src_file || !dest_file) {
        LOGE("Failed to open file streams from file descriptors.");
        if (src_file) fclose(src_file); else close(src_fd_dup);
        if (dest_file) fclose(dest_file); else close(dest_fd_dup);
        return JNI_FALSE;
    }

    std::vector<Mp4Box> src_boxes = parse_root_boxes(src_file);
    std::vector<Mp4Box> dest_boxes = parse_root_boxes(dest_file);

    // Find "moov" box in source
    const Mp4Box* src_moov = nullptr;
    for (const auto& box : src_boxes) {
        if (strncmp(box.type, "moov", 4) == 0) {
            src_moov = &box;
            break;
        }
    }

    // Find "moov" box in dest
    const Mp4Box* dest_moov = nullptr;
    for (const auto& box : dest_boxes) {
        if (strncmp(box.type, "moov", 4) == 0) {
            dest_moov = &box;
            break;
        }
    }

    if (!src_moov || !dest_moov) {
        LOGE("Could not locate 'moov' box in source or destination file.");
        fclose(src_file);
        fclose(dest_file);
        return JNI_FALSE;
    }

    // Read metadata boxes from source moov: 'udta', 'meta' or other custom/vendor boxes
    // We parse the internal sub-boxes of the source 'moov'
    fseek(src_file, src_moov->offset + src_moov->header_size, SEEK_SET);
    uint64_t src_moov_end = src_moov->offset + src_moov->size;
    uint64_t current_offset = ftell(src_file);

    std::vector<std::vector<uint8_t>> metadata_boxes_data;

    while (current_offset + 8 <= src_moov_end) {
        fseek(src_file, current_offset, SEEK_SET);
        uint32_t size = read_u32_be(src_file);
        char type[4];
        if (fread(type, 1, 4, src_file) != 4) break;
        if (size < 8 || current_offset + size > src_moov_end) {
            LOGE("Corrupt sub-box at moov offset %llu (size %u). Stopping metadata scan.",
                 (unsigned long long)current_offset, size);
            break;
        }

        // We want to capture 'udta', 'meta', and custom/proprietary boxes (excluding tracks and headers)
        if (strncmp(type, "udta", 4) == 0 || strncmp(type, "meta", 4) == 0 || 
            (strncmp(type, "trak", 4) != 0 && strncmp(type, "mvhd", 4) != 0 && strncmp(type, "iods", 4) != 0)) {
            
            LOGI("Found metadata box '%c%c%c%c' (size %u bytes) in source container.", type[0], type[1], type[2], type[3], size);
            
            std::vector<uint8_t> box_buffer;
            if (!read_box_checked(src_file, current_offset, size, src_moov_end, box_buffer)) {
                LOGE("Failed to read metadata box at offset %llu. Skipping.",
                     (unsigned long long)current_offset);
            } else {
                metadata_boxes_data.push_back(std::move(box_buffer));
            }
        }
        current_offset += size;
    }

    if (metadata_boxes_data.empty()) {
        LOGI("No custom metadata boxes to copy from source file.");
        fclose(src_file);
        fclose(dest_file);
        return JNI_TRUE; // Success since there was nothing to do
    }

    // Rewrite target file.
    // If 'moov' is at the end of the destination file, we can easily write the new moov box there.
    // Let's check the position of 'moov' relative to 'mdat'.
    bool moov_is_last = true;
    for (const auto& box : dest_boxes) {
        if (strncmp(box.type, "mdat", 4) == 0 && box.offset > dest_moov->offset) {
            moov_is_last = false; // 'mdat' comes after 'moov'
            break;
        }
    }

    if (moov_is_last) {
        LOGI("Destination 'moov' is at the end of the file. Appending metadata directly.");
        // Read destination 'moov' tracks
        fseek(dest_file, dest_moov->offset + dest_moov->header_size, SEEK_SET);
        uint64_t dest_moov_end = dest_moov->offset + dest_moov->size;
        uint64_t dest_current = ftell(dest_file);

        std::vector<uint8_t> moov_content;
        while (dest_current + 8 <= dest_moov_end) {
            fseek(dest_file, dest_current, SEEK_SET);
            uint32_t size = read_u32_be(dest_file);
            char type[4];
            if (fread(type, 1, 4, dest_file) != 4) break;
            if (size < 8 || dest_current + size > dest_moov_end) {
                LOGE("Corrupt sub-box at destination moov offset %llu (size %u). Stopping.",
                     (unsigned long long)dest_current, size);
                break;
            }

            // Copy non-metadata boxes of target moov (e.g. tracks, mvhd)
            if (strncmp(type, "udta", 4) != 0 && strncmp(type, "meta", 4) != 0) {
                std::vector<uint8_t> raw_box;
                if (!read_box_checked(dest_file, dest_current, size, dest_moov_end, raw_box)) {
                    LOGE("Failed to read destination box at offset %llu. Skipping.",
                         (unsigned long long)dest_current);
                    dest_current += size;
                    continue;
                }
                
                // INJECT ORIGINAL DATES INTO MVHD
                if (strncmp(type, "mvhd", 4) == 0 && date_taken_ms > 0 && raw_box.size() >= 9) {
                    uint8_t version = raw_box[8];
                    // Apple/MP4 epoch is seconds since Jan 1, 1904. UNIX epoch is Jan 1, 1970.
                    // Difference is 2082844800 seconds.
                    uint64_t apple_epoch = (date_taken_ms / 1000) + 2082844800ULL;
                    uint64_t mod_apple_epoch = date_modified_sec > 0 ? date_modified_sec + 2082844800ULL : apple_epoch;
                    
                    if (version == 0 && size >= 20) {
                        // 32-bit dates
                        uint32_t ct = htobe32((uint32_t)apple_epoch);
                        uint32_t mt = htobe32((uint32_t)mod_apple_epoch);
                        memcpy(raw_box.data() + 12, &ct, 4);
                        memcpy(raw_box.data() + 16, &mt, 4);
                        LOGI("Injected 32-bit MP4 creation_time into mvhd");
                    } else if (version == 1 && size >= 28) {
                        // 64-bit dates
                        uint64_t ct = htobe64(apple_epoch);
                        uint64_t mt = htobe64(mod_apple_epoch);
                        memcpy(raw_box.data() + 12, &ct, 8);
                        memcpy(raw_box.data() + 20, &mt, 8);
                        LOGI("Injected 64-bit MP4 creation_time into mvhd");
                    }
                }
                
                moov_content.insert(moov_content.end(), raw_box.begin(), raw_box.end());
            }
            dest_current += size;
        }

        // Append the metadata boxes we fetched from the source file
        for (const auto& box_data : metadata_boxes_data) {
            moov_content.insert(moov_content.end(), box_data.begin(), box_data.end());
        }

        // Write the new 'moov' box back to the target file at dest_moov->offset
        if (moov_content.size() + 8 > 0xFFFFFFFFULL) {
            LOGE("New moov box would exceed 32-bit size limits. Aborting metadata injection.");
            fclose(src_file);
            fclose(dest_file);
            return JNI_FALSE;
        }
        fseek(dest_file, dest_moov->offset, SEEK_SET);
        uint32_t new_moov_size = moov_content.size() + 8;
        write_u32_be(dest_file, new_moov_size);
        fwrite("moov", 1, 4, dest_file);
        fwrite(moov_content.data(), 1, moov_content.size(), dest_file);

        // Truncate the file at the new end position
        uint64_t final_size = dest_moov->offset + new_moov_size;
        ftruncate(fileno(dest_file), final_size);
        LOGI("Metadata injection finished. File length set to %llu bytes.", (unsigned long long)final_size);
    } else {
        // 'moov' is at the beginning of the file.
        // In this case, writing a larger 'moov' box shifts the start of the 'mdat' box.
        // We would need to offset all chunk-offset entries (stco/co64) in the track boxes.
        // For simplicity, a robust solution is to let FFmpeg handle writing/re-muxing
        // if this occurs, or run a container box shift.
        // Let's implement a clean warning and append it to the end of the file by writing a new
        // fast-start container or just copy it.
        // For standard Samsung output files, MediaMuxer puts 'moov' at the end of the file by default.
        // We will log a warning if it is not at the end.
        LOGE("Destination 'moov' is NOT at the end of the file. Advanced box shift fallback required.");
        // Fallback placeholder: we still copy metadata to 'moov', but note this in the log.
        // (A fully fledged container box shifter is out of scope for a basic prototype, 
        // but this log gives us diagnostics).
        fclose(src_file);
        fclose(dest_file);
        return JNI_FALSE;
    }

    fclose(src_file);
    fclose(dest_file);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_vcodec_smartencoder_metadata_MetadataRestorer_setFileDescriptorDatesFd(
        JNIEnv *env, jobject thiz, jint fd, jlong modifiedSec, jlong accessedSec) {
    
    if (fd < 0) {
        LOGE("Invalid file descriptor passed to setFileDescriptorDatesFd");
        return JNI_FALSE;
    }
    
    struct timespec times[2];
    // Index 0 is last access time (atime)
    times[0].tv_sec = accessedSec;
    times[0].tv_nsec = 0;
    // Index 1 is last modification time (mtime)
    times[1].tv_sec = modifiedSec;
    times[1].tv_nsec = 0;
    
    // futimens modifies the file timestamps directly via the file descriptor
    if (futimens(fd, times) == 0) {
        LOGI("Successfully updated physical file dates via futimens.");
        return JNI_TRUE;
    } else {
        LOGE("Failed to update physical file dates via futimens: %s", strerror(errno));
        return JNI_FALSE;
    }
}
