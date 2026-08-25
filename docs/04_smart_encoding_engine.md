# Specification 04: Smart Encoding Heuristic Engine

## 1. Concept: CRF Emulation in Hardware
Unlike software encoders (e.g., x265), hardware encoders (`MediaCodec`) do not natively support Constant Rate Factor (CRF). They support Variable Bitrate (VBR) or Constant Quality (CQ), but CQ is poorly implemented and inconsistent across different Android vendors.

To achieve quality parity with HandBrake/x265 presets while running at hardware speeds, Smart Encoder uses a **Predictive Bitrate Selection Engine**. It analyzes the input file parameters and selects a customized target VBR before starting the encoder.

---

## 2. Fast Analytical Bitrate Model (Current Implementation)

The production engine uses an instant, zero-decode analytical model to calculate target VBR without draining battery:

$$\text{Target Bitrate} = \text{Base Bitrate}(\text{Res}) \times \text{Factor}_{\text{FPS}} \times \text{Factor}_{\text{HDR}}$$

### 2.1 Base Bitrate Table ($\text{Base Bitrate}$)
Baseline target bitrates for SDR H.265 at 30 FPS:

| Resolution | Target Pixels | Base Bitrate (SDR, 30fps) |
| :--- | :--- | :--- |
| **4K (2160p)** | $3840 \times 2160$ | **12,000,000 bps** (12 Mbps) |
| **1080p** | $1920 \times 1080$ | **4,000,000 bps** (4.0 Mbps) |
| **720p** | $1280 \times 720$ | **2,000,000 bps** (2.0 Mbps) |
| **SD** | Under $1280 \times 720$ | **1,000,000 bps** (1.0 Mbps) |

### 2.2 Dynamic Modifiers
* **$\text{Factor}_{\text{FPS}}$**: $\times 1.4$ for high framerate videos ($> 40$ FPS, e.g. 60fps/120fps).
* **$\text{Factor}_{\text{HDR}}$**: $\times 1.25$ for 10-bit HDR (BT.2020 / HLG / PQ) to prevent color gradient banding.
* **Codec Discount Rules**:
  * **H.264 $\rightarrow$ H.265**: Target 50% of original bitrate, clamped to base target.
  * **H.265 $\rightarrow$ H.265**: Target 75% of original bitrate (or 60% for AV1).
* **Safety Clamps**: Never exceed 85% of original bitrate; minimum floor enforced at 800 Kbps.

---

## 3. Presets and Custom Mode
* **HIGH_QUALITY (Quality)**: $\text{Target} = \min(1.5 \times \text{Suggested}, 0.9 \times \text{Original})$.
* **MAX_COMPRESSION (Space)**: $\text{Target} = \max(0.6 \times \text{Suggested}, 500\,\text{Kbps})$.
* **CUSTOM**: Direct manual slider selection from 0.5 Mbps to 30.0 Mbps.

---

## 4. [Roadmap] Visual Quality Assurance (VMAF / SSIM Probe)
*Note: Planned for future iterations.*
An optional closed-loop probe will transcode a 5–10 second sample in memory and compute SSIM/VMAF against the source. If visual quality drops below threshold (VMAF < 88 / SSIM < 0.93), the target bitrate will be automatically bumped. If space savings are under 10%, the file will be bypassed with `SKIPPED_EFFICACY_LOW`.
