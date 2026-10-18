# Omni Pocket

<p align="center">
  <b>English</b> | <a href="README_CN.md">简体中文</a>
</p>

<p align="center">
  <strong>On-Device Multimodal Generative AI Studio for Android</strong><br>
  Local Diffusion Image Synthesis & 48kHz Full-Song Music Generation powered by MNN and OpenCL.
</p>

---

## 🌟 Overview

**Omni Pocket** is an open-source, on-device multimodal generative creative studio designed for modern Android devices (`arm64-v8a`). It brings two frontier generative foundation models into a single, unified mobile application:

1. **🎨 Image Studio (绘卷 · 生图工坊)**: Powered by **Qwen-Image-2.1** (4-bit quantized DiT via Alibaba's MNN framework with Adreno/Mali OpenCL hardware acceleration and official 8-step Turbo schedule).
2. **🎵 Music Studio (乐坊 · 音乐工坊)**: Powered by **YuE2-3B** (3-billion parameter AR–NAR Mixture-of-Transformers producing 48 kHz stereo lossless vocal songs with symbolic ABC music theory planning).
3. **🎬 Video Studio (影坊 · 动效工坊)**: Powered by **MobileWan** (Qualcomm AI Research 3-Step Decoupled DMD Diffusion Transformer producing 81-frame 480×832 cinematic mobile video clips with Recurrent Hybrid Attention).

---

## 🚀 Key Features

### 🎨 Image Studio (`Qwen-Image-2.1`)
- **Text-to-Image & Image-to-Image Editing**: Full-resolution generation (128×128 to 1024×1024) with single and dual reference image conditioning.
- **Turbo 8-Step Acceleration**: Integrated official Qwen-Image-2.1-Turbo schedule, accelerating generation by over 2.5× while maintaining high visual fidelity.
- **Low-Footprint Hardware Acceleration**: Uses CPU int4 for text encoding and OpenCL GPU acceleration for the DiT backbone with Tiny VAE FP16 decoding.

### 🎵 Music Studio (`YuE2-3B`)
- **48 kHz High-Fidelity Stereo Audio**: Native 48 kHz stereo music generation with genuine singing vocals and full instrumental arrangements, breaking away from traditional speech-spliced TTS solutions.
- **Symbolic Music Theory Planning (CoT)**:
  - `melody`: Deduces a symbolic ABC melody line before acoustic synthesis, ensuring exact lyric-to-note alignment and preventing dropped words.
  - `full`: Complete melody and harmonic chord progression planning for rich orchestral accompaniment.
  - `off`: Direct acoustic autoregression for rapid sketching.
- **Structured Lyrics & Arrangement**: Supports section division tags (`[Intro]`, `[Verse]`, `[Pre-Chorus]`, `[Chorus]`, `[Bridge]`, `[Outro]`), BPM control, and real-time lyric statistics.
- **Built-in Player & Score Inspector**: High-fidelity stereo playback with live audio waveform animation, timeline scrubbing, and raw ABC music notation inspection with one-tap clipboard export.
- **Dual-Mode Engine Architecture**:
  - **Workstation Bridge Mode**: Connects directly to a local workstation or computer running YuE2-3B via USB (`adb reverse tcp:8999 tcp:8999`) or Wi-Fi local network, streaming genuine token-by-token logs and returning authentic 48kHz vocal master tracks.
  - **On-Device Offline Model**: Offline model architecture designed for local quantization and deployment on high-memory mobile hardware.

### 🎬 Video Studio (`MobileWan / Wan2.2-TI2V`)
- **Native On-Device GPU Acceleration**: Directly executes on mobile GPUs (Qualcomm Adreno 750 / OpenCL 3.0) and multi-threaded CPU cores, lifting driver single-buffer memory caps and boosting per-step sampling speed by nearly 3× over pure CPU inference.
- **4-Step Turbo Distillation**: Powered by the Wan2.2-TI2V 5B Turbo diffusion backbone and UMT5-XXL text encoder, generating crisp and cohesive 480×832 vertical video clips in just 4 steps.
- **Thread Starvation Prevention & Zero ANR**: Lowers engine CPU priority via `/system/bin/nice -n 10` and offloads coroutines to `Dispatchers.Default`, ensuring the Android main thread and Compose UI never stutter or drop frames.
- **Workstation Bridge Mode**: Connects directly to a workstation running MobileWan via USB (`adb reverse tcp:8998 tcp:8998`) or Wi-Fi local network.
- **Integrated Video Player & Export**: In-app hardware-accelerated looping video playback with timeline controls and native Android sharing.

### 🧭 Global Fluid UI & Navigation
- **Left Navigation Drawer**: Smoothly switch between Image Studio, Music Studio, and System Management.
- **Mutual-Exclusion Memory Architecture**: Inactive models are safely closed and deallocated when switching domains, keeping active memory footprint well within system limits and preventing low-memory kills.
- **Persistent Engineering Console**: Live terminal logs detailing model initialization, tokenizer counts, ODE flow matching steps, and VAE decoding remain visible and scrollable upon completion.

---

## 📱 Hardware Requirements & Device Compatibility

Omni Pocket is built to support a wide range of modern Android devices:
- **Operating System**: Android 9.0+ (API level 28 or higher; recommended Android 13+).
- **Architecture**: `arm64-v8a` (64-bit ARM).
- **GPU Acceleration**: OpenCL 2.0+ compatible GPU (Qualcomm Adreno, ARM Mali, etc.).
- **RAM**:
  - Image Generation: 6 GB+ RAM recommended (works comfortably on 8GB/12GB/16GB/24GB devices).
  - Music Generation: Standalone workstation bridge works on any network-connected device; local heavy model inference requires flagship unified memory tiers.

---

## 🛠️ Build and Installation

### Prerequisites
- JDK 17 (e.g. OpenJDK 17)
- Android SDK 35 (Platform Tools, Build-Tools 35.0.0+)
- Android NDK `28.2.13676358` (or NDK 26+)

### Build APK

```bash
# Clone the repository
git clone https://github.com/Tiggy-Chan/omni-pocket.git
cd omni-pocket

# Build debug APK
./gradlew :app:assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 Model Preparation & Conversion

### Qwen Image 2.1 (MNN)
Original GGUF weights can be converted to MNN format on your computer:

```bash
git submodule update --init --depth 1 third_party/MNN
python3 -m pip install -r export/requirements.txt
scripts/build_mnnconvert_mac.sh

# Convert UC Q4_K_M model
QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh

# Convert Official 8-step Turbo
scripts/convert_official_turbo.sh
```

Push converted weights to device storage via ADB:
```bash
adb push models/qwen_image21_uc_q4km /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

### YuE2-3B Music Workstation Bridge
To run the high-precision YuE2-3B PyTorch inference workstation locally and serve the mobile app:

```bash
# In your local YuE2 environment
python3 -m pip install fastapi soundfile
python3 /path/to/yue2/server.py

# Forward port 8999 to Android device over USB
adb reverse tcp:8999 tcp:8999
```

In the Omni Pocket app, open the **Advanced Parameters** in the Music Studio and verify the workstation address is set to `http://127.0.0.1:8999` (or your computer's local Wi-Fi IP address `http://192.168.x.x:8999`).

---

## 📜 Upstream & Licenses

- **MNN Framework**: Developed by Alibaba Group ([MNN GitHub](https://github.com/alibaba/MNN)), Apache-2.0 License.
- **Qwen-Image Runtime**: Based on [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21), Apache-2.0 License. Model weights subject to Qwen Research License.
- **YuE2-3B**: Developed by Multimodal Art Projection ([YuE2 GitHub](https://github.com/multimodal-art-projection/YuE)), CC-BY-NC-4.0 License.
- **Omni Pocket Application Code**: Licensed under the **Apache-2.0 License**.
