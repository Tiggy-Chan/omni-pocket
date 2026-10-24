# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket is an Android studio for local and workstation-backed image, music, and video generation. The app separates execution backends explicitly: it does not present a workstation request as on-device inference.

## What runs where

- **Qwen-Image-2.1 on the phone**: MNN with CPU int4 text/vision encoding and OpenCL DiT inference. Text-to-image, one- or two-reference editing, real or Tiny VAE, and the official 8-step Turbo schedule. Includes queued generation, cooperative cancellation, model verification, and persistent results.
- **MOSS-Image on a ComfyUI workstation**: SFT and Base checkpoints, long prompts, optional prompt upsampling, and native JSON layout control. The layout editor supports text/object elements, draggable and resizable boxes, and exact export to MOSS's `abs_centralized` / `yxyx` format. This path does not use the Qwen MNN runtime and is not advertised as on-device inference.
- **YuE2 music on a workstation**: the app submits lyrics, style, BPM, seed, duration, CoT mode, ODE steps, and guidance to an external YuE2 service. Generated tracks, bundled examples, playback, seeking, sharing, and ABC score inspection work in the app. There is currently no on-device YuE2 inference engine.
- **Wan2.2 video on the phone**: `libsd_cli` with a Wan2.2-TI2V-5B-Turbo GGUF DiT, UMT5 text encoder, and TAE decoder. Text-to-video and first-frame image-to-video use the actual seed, FPS, frame count, and step count shown in the UI. Local output is WebM. Local generation is limited to 25 frames at 480×832; 4 steps and 16 FPS are the current verified defaults.
- **MobileWan on a workstation**: text-to-video through the external bridge. The bridge does not accept a first frame, and the app rejects that combination before submission instead of silently dropping the image.

The interface uses three global destinations (Image, Music, Video), short task states, per-modality logs, and optional advanced controls. Floating navigation uses backdrop glass on Android 12/API 31+ with an opaque fallback on older supported versions. Expensive visual effects are reduced during heavy inference.

## Build and test

Requirements: JDK 17, Android SDK 35, and NDK `28.2.13676358` (or a compatible NDK 26+).

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app supports Android 9/API 28+ on `arm64-v8a`. A flagship device with OpenCL support and at least 12 GB RAM is recommended for the current local model sizes; the main device validation target has 24 GB RAM.

## Qwen model preparation

```bash
git submodule update --init --depth 1 third_party/MNN
python3 -m pip install -r export/requirements.txt
scripts/build_mnnconvert_mac.sh

QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh

# Add the official Turbo DiT. Conversion alone does not mark it as device-validated.
scripts/convert_official_turbo.sh

# Only set this after the converted output actually passes device regression.
QWEN_TURBO_VALIDATED=1 scripts/convert_official_turbo.sh

adb push models/qwen_image21_uc_q4km \
  /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

The app requires a valid `manifest.json` and verifies file sizes and SHA-256 hashes before enabling Qwen generation. MNNConvert failures, missing outputs, and stale outputs now stop the conversion scripts.

## Wan model preparation

Place these files in `models/wan22_turbo`:

- `Wan2_2-TI2V-5B-Turbo-Q4_0.gguf`
- `umt5-xxl-encoder-Q3_K_S.gguf`
- `taew2_2.safetensors`

Then push the directory:

```bash
adb push models/wan22_turbo \
  /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

## MOSS-Image with ComfyUI

Use ComfyUI 0.42 or later with native MOSS support. The app checks required nodes and selected model files before submitting a workflow.

```text
ComfyUI/models/
├── diffusion_models/
│   ├── moss_image_sft_int8_convrot.safetensors
│   └── moss_image_base_int8_convrot.safetensors  # optional Base checkpoint
├── text_encoders/
│   └── qwen3.5_9b_w4a8_gptq.safetensors
└── vae/
    └── flux2-vae_small.safetensors
```

Start ComfyUI, then allow the phone to reach it:

```bash
adb reverse tcp:8188 tcp:8188
```

Select **MOSS · Workstation** in Image Studio and use `http://127.0.0.1:8188` over USB, or the workstation's LAN address. The client submits a native ComfyUI API workflow, polls the real queue/history, and downloads the resulting image from the same origin. It does not invent sampling percentages when the server has no progress stream.

Official references: [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image), [SFT model card](https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT), [ComfyUI guide](https://docs.comfy.org/tutorials/image/moss-image/moss-image), and [Comfy-Org weights](https://huggingface.co/Comfy-Org/MOSS-Image).

## External music and video services

The YuE2 and MobileWan server implementations are external to this repository. The app expects the existing `/health` and `/generate` contracts, followed by `audioUrl` or `videoUrl` from the same origin. Downloads are size-limited, validated, and atomically stored.

```bash
adb reverse tcp:8999 tcp:8999   # YuE2
adb reverse tcp:8998 tcp:8998   # MobileWan
```

Server-reported progress is shown only when it exists. Otherwise the UI shows an indeterminate working state rather than fabricated steps.

## Conversion and deployment checks

```bash
python3 -m unittest discover -s export -p 'test_conversion_utils.py'
bash -n scripts/push_models.sh scripts/convert_official_turbo.sh
```

`scripts/push_models.sh` compares SHA-256 hashes instead of treating equal file sizes as identical. Uploads are verified before replacing the remote file. Numerical export tests use explicit error thresholds. CI runs the Android unit tests before assembling the APK and AAR.

## Upstream and licenses

- [MNN](https://github.com/alibaba/MNN), Apache-2.0.
- [Qwen-Image runtime](https://github.com/scsonic/libQwenImage21), Apache-2.0; model weights have their own licenses.
- [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image), Apache-2.0.
- [YuE2](https://github.com/multimodal-art-projection/YuE), CC-BY-NC-4.0.
- [Haze](https://github.com/chrisbanes/haze), Apache-2.0.
- Omni Pocket application code, Apache-2.0.
