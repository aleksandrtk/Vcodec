# Specification 02: Video Processing Pipeline

## 1. Input/Output Format Support
* **Input Containers**: MP4 (primary).
* **Input Video Codecs**:
  - H.264 (AVC) - Standard 8-bit.
  - H.265 (HEVC) - 8-bit & 10-bit (HDR10, HDR10+, Dolby Vision).
  - AV1 - 8-bit & 10-bit (supported by S24 Ultra hardware decoder).
* **Output Container**: MP4.
* **Output Video Codec**: H.265 (HEVC) - 8-bit or 10-bit (preserving source color profile).
* **Output Audio Codec**: AAC (Advanced Audio Coding) - Stereo, custom user-selected bitrate (default 128 Kbps) or direct stream copy if audio re-encoding is disabled.

---

## 2. Media3 Transformer Integration
Android Media3 Transformer simplifies raw `MediaCodec` and `MediaMuxer` implementations by providing an integrated editing pipeline while preserving fully accelerated hardware performance.

```
Source File (Uri)
       │
       ▼
┌──────────────┐      ┌────────────────────────┐      ┌────────────────────────┐
│MediaExtractor│ ───> │ Hardware Video Decoder │ ───> │ Video Frame Processor  │
└──────────────┘      │(Qualcomm OMX/C2 HEVC)  │      │(Scale/Format filters)  │
                      └────────────────────────┘      └────────────────────────┘
                                                                  │
                                                                  ▼
┌──────────────┐      ┌────────────────────────┐      ┌────────────────────────┐
│  MediaMuxer  │ <─── │ Hardware Video Encoder │ <─── │ Color & HDR Converter  │
│  (MP4 Box)   │      │ (Qualcomm OMX/C2 HEVC) │      │ (10-bit BT2020 PQ/HLG) │
└──────────────┘      └────────────────────────┘      └────────────────────────┘
```

### 2.1 Transformer Construction
```kotlin
val request = TransformationRequest.Builder()
    .setVideoMimeType(MimeTypes.VIDEO_H265)
    .setAudioMimeType(MimeTypes.AUDIO_AAC)
    .setHdrMode(TransformationRequest.HDR_MODE_KEEP_HDR) // Dynamic HDR handling
    .build()

val transformer = Transformer.Builder(context)
    .setTransformationRequest(request)
    .setEncoderFactory(DefaultEncoderFactory.Builder(context).build())
    .build()
```

---

## 3. High Dynamic Range (HDR) Pipeline Details
Samsung Galaxy S24 Ultra and S21 capture premium HDR videos in HDR10, HDR10+, and HLG. Preserving this dynamic range during compression requires explicit configuration of the color transfer characteristics and profiles.

### 3.1 10-bit Color Profiles configuration
To transcode HDR content without severe color banding or conversions, the pipeline forces the use of **HEVC Main 10 Profile**:
* **Codec Profile**: `HEVCProfileMain10`
* **Color Standard**: `MediaFormat.COLOR_STANDARD_BT2020` (wide color gamut).
* **Color Transfer**: 
  - `MediaFormat.COLOR_TRANSFER_ST2084` (for HDR10 / PQ)
  - `MediaFormat.COLOR_TRANSFER_HLG` (for Hybrid Log-Gamma)
* **Color Format**: `CodecCapabilities.COLOR_FormatYUVP010` (for direct YUV 10-bit hardware decoder-to-encoder pipeline) or `CodecCapabilities.COLOR_Format32bitABGR2101010` (if processing frames via OpenGL surface shaders).

### 3.2 Dynamic HDR10+ Metadata
For videos containing HDR10+ (dynamic metadata containing scene-by-scene brightness tables), the media pipeline extracts the custom SEI (Supplemental Enhancement Information) NAL units from the input stream and routes them to the encoder. If the hardware encoder lacks dynamic metadata injection support, the pipeline falls back to HDR10 static mapping (using BT.2020 PQ parameters) to maintain overall contrast details.

---

## 4. Hardware Accelerator Mapping
The application explicitly binds hardware-accelerated codecs provided by the Snapdragon chipset:

| Phase | S24 Ultra (Snapdragon 8 Gen 3) | S21 5G (Snapdragon 888) |
| :--- | :--- | :--- |
| **AVC Decoding** | `c2.qti.avc.decoder` (Hardware) | `OMX.qcom.video.decoder.avc` (Hardware) |
| **HEVC Decoding** | `c2.qti.hevc.decoder` (Hardware) | `OMX.qcom.video.decoder.hevc` (Hardware) |
| **AV1 Decoding** | `c2.qti.av1.decoder` (Hardware) | N/A (Software Fallback / CPU) |
| **HEVC Encoding** | `c2.qti.hevc.encoder` (Hardware) | `OMX.qcom.video.encoder.hevc` (Hardware) |
| **Dynamic Bitrate Control** | Supports B-frames, CABAC, and advanced CQ / VBR modes | Basic VBR / CBR, no B-frame encoding support |
