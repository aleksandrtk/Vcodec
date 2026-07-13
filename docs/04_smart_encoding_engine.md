# Specification 04: Smart Encoding Heuristic Engine

## 1. Concept: CRF Emulation in Hardware
Unlike software encoders (e.g., x265), hardware encoders (`MediaCodec`) do not natively support Constant Rate Factor (CRF). They support Variable Bitrate (VBR) or Constant Quality (CQ), but CQ is poorly implemented and inconsistent across different Android vendors.

To achieve quality parity with HandBrake/x265 presets while running at hardware speeds, Smart Encoder uses a **Predictive Bitrate Selection Engine**. It analyzes the input file parameters and selects a customized target VBR before starting the encoder.

---

## 2. Dynamic Bitrate Calculation Formula
The target bitrate for the H.265 (HEVC) hardware encoder is calculated as:

$$\text{Target Bitrate} = \text{Base Bitrate}(\text{Res}, \text{FPS}) \times C_{\text{motion}} \times C_{\text{noise}} \times C_{\text{hdr}}$$

### 2.1 Base Bitrate Table ($\text{Base Bitrate}$)
The baseline target bitrates are established for standard HEVC compression (assuming average complexity and 30 FPS):

| Resolution | Aspect Ratio | Target Pixels | Base Bitrate (SDR, 30fps) |
| :--- | :--- | :--- | :--- |
| **4K (2160p)** | 16:9 | $3840 \times 2160$ | **12,000,000 bps** (12 Mbps) |
| **1080p** | 16:9 | $1920 \times 1080$ | **3,800,000 bps** (3.8 Mbps) |
| **720p** | 16:9 | $1280 \times 720$ | **1,800,000 bps** (1.8 Mbps) |
| **SD** | Various | Under $1280 \times 720$ | **800,000 bps** (800 Kbps) |

### 2.2 Complexity Scaling Factors

#### A. Motion Factor ($C_{\text{motion}}$)
Calculated during a quick 5-second sample pass from the center of the video:
* **Algorithm**: Compute structural differences between adjacent keyframes (I-frames) or P-frames.
* **Coefficients**:
  - **Low Motion** (e.g., static interviews, screen recordings): $C_{\text{motion}} = 0.6$ to $0.8$
  - **Medium Motion** (e.g., standard vlog, walk and talk): $C_{\text{motion}} = 1.0$
  - **High Motion** (e.g., action camera, sports, rapid camera pans): $C_{\text{motion}} = 1.4$ to $1.8$

#### B. Noise & Detail Factor ($C_{\text{noise}}$)
Evaluates high-frequency data (dark scenes, grain, high texture complexity):
* **Algorithm**: Extract variance of high-frequency components in the DCT/FFT domain of selected frames.
* **Coefficients**:
  - **Low Detail / Clean**: $C_{\text{noise}} = 0.9$
  - **Standard**: $C_{\text{noise}} = 1.0$
  - **High Detail / Grainy / Night Scene**: $C_{\text{noise}} = 1.2$ to $1.4$ (increases bitrate to prevent blockiness).

#### C. Color Depth Factor ($C_{\text{hdr}}$)
* **Coefficients**:
  - **SDR (8-bit)**: $C_{\text{hdr}} = 1.0$
  - **HDR (10-bit BT.2020)**: $C_{\text{hdr}} = 1.25$ (reserves extra bandwidth for finer color gradients to avoid banding).

---

## 3. Transition Rules & Pre-Analysis Logic

### 3.1 Codec Transcoding Rules

#### A. If Input Codec is H.264 (AVC):
* Suggest transcode to H.265 (HEVC).
* Apply full predictive bitrate model.
* The target size saving is expected to be **50% to 60%** with negligible quality loss.

#### B. If Input Codec is H.265 (HEVC):
* Compare original file bitrate with the predictive target bitrate.
* If original bitrate is significantly higher than target:
  - Offer **Re-compression (HEVC -> HEVC)**.
  - Set target bitrate to: $\text{Target} = \text{min}(\text{Original} \times 0.70, \text{Calculated Target})$.
* If original bitrate is already close to or below target:
  - Recommend skipping the file to avoid quality degradation.

---

## 4. Visual Quality Assurance (VMAF/SSIM Check)
To ensure the hardware encoder does not introduce massive artifacts, the engine performs a short verification loop:
1. **Sample Encoding**: Transcode a 10-second segment from the middle of the source video.
2. **Analysis**:
   - Calculate Structural Similarity (SSIM) or Peak Signal-to-Noise Ratio (PSNR) of the transcoded sample frames against the original.
   - If the estimated quality score drops below the safe threshold (equivalent to VMAF < 88 or SSIM < 0.93):
     - Increase the $C_{\text{motion}}$ multiplier by 15%.
     - Recalculate target bitrate and re-run sample test.
3. **Threshold Check**: If the resulting file savings is estimated to be **less than 10%** of the original size, the file is automatically bypassed with a status code `SKIPPED_EFFICACY_LOW`.
