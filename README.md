# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket is an Android studio for on-device text conversations, structured decisions, and image, music, and video generation. Every model runs locally on the phone (`arm64-v8a`, Android 9 / API 28+); inference needs no workstation or cloud server.

## Capabilities & Local Runtimes

- **Conversation (`对话`)**:
  - **Built-in MNN**: pinned `taobao-mnn` Qwen3.5 0.8B / 2B / 4B packages running on `libMNN.so`.
  - **Hugging Face GGUF & MNN Hub**: bundled `llama.cpp` (`-O3`, `armv8.2-a+dotprod`) runs arbitrary single-file GGUF chat models (Qwen2.5/3/3.5, Gemma 2/3, Llama 3, DeepSeek-R1-Distill, Phi, MiniCPM, etc.) searched and downloaded directly inside the app, alongside community MNN bundles.
  - Supports streaming output, separated `<think>` reasoning, configurable CPU threads, context window (2048–8192), max output tokens (512–2048), KV prefix reuse, copy, and regeneration.
- **Decision (`决策`)**:
  - Runs [`baselabs/elephant-1`](https://huggingface.co/baselabs/elephant-1) (`prithivMLmods/elephant-1-GGUF` Q4_K_M, 5.63 GB) via `llama.cpp` with block-bidirectional full-attention masks, returning option probabilities and confidence for `choice`, `noul`, and `score` questions.
  - Can be downloaded directly in the app (bundled metadata assets + pinned GGUF via Hugging Face / `hf-mirror`) or pushed over USB.
- **Image (`图像`)**:
  - **Qwen-Image-2.1**: MNN with CPU int4 text/vision encoding and OpenCL DiT inference (text-to-image, 1–2 reference image editing, real/Tiny VAE, official 8-step Turbo).
  - **MOSS-Image SFT**: unified `libsd_cli.so` (`stable-diffusion.cpp`) with long prompts and native JSON layouts (`abs_centralized` / `yxyx`).
  - **Community Single-File Diffusion**: download single-file `.safetensors` / `.gguf` / `.ckpt` checkpoints (e.g., SD 1.5, SDXL, SDXL-Turbo) from Hugging Face / `hf-mirror` and run them locally via `libsd_cli.so` (CPU or optional OpenCL GPU).
- **Music (`音乐`)**:
  - `libyue2_cli.so` ([audio.cpp](https://github.com/0xShug0/audio.cpp), CPU `armv8.2-a+dotprod`) runs YuE2-3B Q4_0 with its VAE, producing 48 kHz stereo WAV and optional ABC score. Downloadable directly in the Models page (CC BY-NC 4.0 weights, not bundled in the APK).
- **Video (`视频`)**:
  - Unified `libsd_cli.so` (built with `SD_WEBM=ON`, OpenCL + CPU hybrid `diffusion=GPUOpenCL,te=CPU,vae=CPU`) runs Wan2.2-TI2V-5B-Turbo GGUF DiT, UMT5 text encoder, and TAE decoder for text-to-video and first-frame image-to-video (`.webm`, up to 25 frames at 480×832). Downloadable directly in the Models page.

## Model Hub, Mirror Switching & Background Downloads

- **Hugging Face & `hf-mirror` support**: open **模型 → 切换源** (or **设置 → 模型下载镜像**) to switch between `Hugging Face (https://huggingface.co)`, `HF-Mirror (https://hf-mirror.com)`, or a custom HTTPS mirror, set an optional Access Token for gated repositories, and test latency. China (`CN`) locales default to `HF-Mirror`.
- **In-app discovery**: open **模型 → 发现模型** to search Hugging Face / `hf-mirror` by category (`GGUF 对话`, `MNN 对话`, `图像模型`) or enter an exact `owner/repo`, inspect file sizes, quantization tags, and RAM recommendations, and download with one tap.
- **Reliable foreground downloads**: multi-GB downloads run via `ModelDownloadService` (`dataSync` foreground service) with live speed/progress notifications, HTTP `Range` resume, strict `Content-Range` validation, and SHA-256 / LFS OID verification.

## Build & Test

Requirements: JDK 17+, Android SDK 35, NDK `28.2.13676358`, CMake, Ninja.

```bash
git submodule update --init --depth 1 third_party/stable-diffusion.cpp third_party/llama.cpp
git -C third_party/stable-diffusion.cpp submodule update --init --depth 1 ggml

# Build unified stable-diffusion.cpp (Wan2.2 + MOSS + generic SD) and audio.cpp (YuE2)
ANDROID_HOME=<sdk> JOBS=4 bash scripts/build_sd_android.sh
ANDROID_HOME=<sdk> JOBS=4 bash scripts/build_yue2_android.sh

# Run unit tests and assemble Debug APK
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package ID: `com.tiggy.omnipocket`. On-device model storage root: `/sdcard/Android/data/com.tiggy.omnipocket/files/models/`.

## Building & Publishing a Signed Release APK

Official releases must use a persistent signing keystore so users can upgrade in place without uninstalling (which deletes downloaded models).

### Local signed release

```bash
OMNI_KEYSTORE_PATH=/path/to/release.jks \
OMNI_KEYSTORE_PASSWORD='...' \
OMNI_KEY_ALIAS='...' \
OMNI_KEY_PASSWORD='...' \
./gradlew :app:testDebugUnitTest :app:assembleRelease :qwenimage21:assembleRelease
```

### Automated GitHub Release (on `v*` tags)

Configure four repository secrets in GitHub Settings → Secrets and variables → Actions:

- `RELEASE_KEYSTORE_BASE64`: `base64 -i release.jks | tr -d '\n'`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Then push a version tag (for example `v0.1.0`). [`.github/workflows/build.yml`](.github/workflows/build.yml) builds all native runtimes (`libsd_cli.so`, `libyue2_cli.so`, `libomni_llm.so`, `libomni_decision.so`, `libMNN.so`), runs unit and conversion tests, signs `omni-pocket-<tag>-arm64-v8a.apk`, generates `SHA256SUMS.txt`, and attaches them to the GitHub Release.

## Desktop Helper Scripts (Optional USB Push)

All models can be downloaded directly on the phone, or prepared on a computer and pushed via USB. Set `HF_ENDPOINT=https://hf-mirror.com` when downloading from Mainland China:

```bash
HF_ENDPOINT=https://hf-mirror.com bash scripts/download_llm_models.sh qwen35-0.8b
bash scripts/push_llm_models.sh qwen35-0.8b

HF_ENDPOINT=https://hf-mirror.com bash scripts/download_moss_models.sh
bash scripts/push_moss_models.sh <serial>

HF_ENDPOINT=https://hf-mirror.com bash scripts/push_music_models.sh <serial>
bash scripts/push_video_models.sh <serial>
```

Host verification scripts:

```bash
python3 -m unittest discover -s export -p 'test_conversion_utils.py'
bash scripts/test_llm_host.sh
bash scripts/test_decision_host.sh
```

## Upstream & Licenses

- [MNN](https://github.com/alibaba/MNN), Apache-2.0 (`24c6cb21`).
- [Qwen-Image runtime](https://github.com/scsonic/libQwenImage21), Apache-2.0.
- [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image), Apache-2.0.
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) and [ggml](https://github.com/leejet/ggml), MIT.
- [llama.cpp](https://github.com/ggml-org/llama.cpp), MIT (`2280f0fd` + `patches/llama-elephant-decision.patch`).
- [audio.cpp](https://github.com/0xShug0/audio.cpp), Apache-2.0 (`3a0dd44`); [YuE2](https://github.com/multimodal-art-projection/YuE) weights, CC-BY-NC-4.0.
- [Elephant-1](https://huggingface.co/baselabs/elephant-1), MIT; [OneJev](https://github.com/OmniJev/OneJev) protocol, Apache-2.0.
- [Haze](https://github.com/chrisbanes/haze), Apache-2.0.
- Omni Pocket application code, Apache-2.0.
