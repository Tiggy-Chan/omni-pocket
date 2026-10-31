# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket is an Android studio for on-device text conversations, decisions, and image and video generation. Image and video inference run on the phone; music still uses an external YuE2 service while its on-device engine is in progress.

## What runs where

- **Qwen-Image-2.1 on the phone**: MNN with CPU int4 text/vision encoding and OpenCL DiT inference. Text-to-image, one- or two-reference editing, real or Tiny VAE, and the official 8-step Turbo schedule. Includes queued generation, cooperative cancellation, model verification, and persistent results.
- **MOSS-Image on the phone**: a separate `libmoss_cli.so` runs SFT through stable-diffusion.cpp with long prompts, native JSON layouts, Euler, the discrete scheduler, and negative-branch layer-22 skipping. Layout coordinates use MOSS's `abs_centralized` / `yxyx` format.
- **YuE2 music on a workstation**: the app submits lyrics, style, BPM, seed, duration, CoT mode, ODE steps, and guidance to an external YuE2 service. The lyrics sheet offers text-only style templates; generated tracks support playback, seeking, sharing, and ABC score inspection in the app. There is currently no on-device YuE2 inference engine.
- **Wan2.2 video on the phone**: `libsd_cli` with a Wan2.2-TI2V-5B-Turbo GGUF DiT, UMT5 text encoder, and TAE decoder. Text-to-video and first-frame image-to-video use the actual seed, FPS, frame count, and step count shown in the UI. Local output is WebM. Local generation is limited to 25 frames at 480×832; 4 steps and 16 FPS are the current verified defaults.

- **Elephant-1 decisions on the phone**: llama.cpp runs a GGUF build and returns option probabilities and confidence for choice, noul, and score questions. It does not generate free text.

The interface follows a sidebar, conversation, and work-directory layout. The sidebar (a drawer on phones, permanent at 840dp and wider) switches between the Conversation, Decision, Image, Music, and Video workspaces and the Work directory, Models, and Settings pages. Each workspace is a stream of requests and results, with parameters in sheets next to the composer. The work directory lists real outputs only; an image can be edited further or used as a video's first frame. Light and dark themes follow the system and can be overridden in Settings. Glass is limited to the floating composer and sheets: backdrop blur on Android 12/API 31+, an opaque surface on older versions.

## Build and test

Requirements: JDK 17, Android SDK 35, and NDK `28.2.13676358`.

```bash
git submodule update --init --depth 1 third_party/stable-diffusion.cpp third_party/llama.cpp
git -C third_party/stable-diffusion.cpp submodule update --init --depth 1 ggml
bash scripts/build_moss_android.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app supports Android 9/API 28+ on `arm64-v8a`. At least 16 GB RAM is recommended for local MOSS; the main device validation target has 24 GB RAM.

## Local Qwen3.5 conversations

Conversation uses the existing MNN runtime and pinned Qwen3.5 0.8B/2B/4B packages from `taobao-mnn`. The first version is text-only: no image input, web access, RAG, command execution, or agent claims. CPU inference uses four threads, a 4096-token total budget, and up to 512 new tokens per response. Old turns are trimmed to fit; reasoning is shown separately and excluded from subsequent prompts. Local LLM and other heavy engines are mutually exclusive and evict each other's resident models.

```bash
bash scripts/download_llm_models.sh qwen35-0.8b
bash scripts/push_llm_models.sh qwen35-0.8b
```

Open the **模型** (Models) page from the sidebar to download in the app or verify a pushed package. Downloads resume partial files, validate size and SHA-256, and activate atomically. The push script does not create a device verification marker. To run the real host smoke test (Chinese multi-turn, streaming cancellation/retry, reasoning, and context trimming):

```bash
bash scripts/test_llm_host.sh
```

Sampling includes explicit presence/repetition penalties based on the official text-generation recommendations. The [0.8B model card](https://huggingface.co/Qwen/Qwen3.5-0.8B) warns that this size can enter thinking loops. The app therefore bounds output and explicitly reports when reasoning reaches the limit without an answer; it does not invent a fallback response. Host checks distinguish completed reasoning from a correctly bounded loop rather than treating the token limit as a complete answer.

Only the 0.8B package has host inference coverage at present. Android device memory, performance, and runtime behavior still require device acceptance. The larger packages remain optional; their presence in the catalog is not a device compatibility claim.

## Local Elephant-1 decisions

The Decision workspace uses [`baselabs/elephant-1`](https://huggingface.co/baselabs/elephant-1) (a Qwen3.5-9B fine-tune, MIT, pinned to `b3f8a934`). The `elephant-1base` address originally suggested is not accessible; this repository is the public release. Weights come from the community `prithivMLmods/elephant-1-GGUF` Q4_K_M build (5.63 GB, not an official quantization) and run on llama.cpp `2280f0fd`. Full-attention layers use a block-bidirectional mask, and answers are a softmax over the option-label logits at the answer position.

- Question types: choice (up to 2048 options, chunked over 256 slots and merged with the OneJev weighting), noul (yes/no), and score (1–10 levels, with expected value and confidence).
- One request holds 1–32 questions sharing one state prefix. The state and each question are limited to 128 KiB and each branch to 16384 tokens; oversized input is rejected, never truncated silently.
- The inference context is sized to each request, and cancellation takes effect during loading, during the prefix pass, and between branches.

Prepare the model on a computer and import it over USB; the app does not offer a direct GGUF download:

```bash
bash scripts/prepare_elephant_model.sh
bash scripts/push_llm_models.sh elephant-1
bash scripts/test_decision_host.sh
```

Verify it on the **模型** page after pushing. The host regression runs the real model: against an FP32-KV reference of the same GGUF, the largest probability error across the three question types is 0.0053 (tolerance 0.05). It also covers 300-option chunking, repeated calls, reordered questions, state isolation, and cancellation. Peak RSS on a 16 GB host is about 6.2 GB. On a OnePlus PJX110 (24 GB), verification takes about 8 s, loading about 5 s, and the three-question example about 14 s, with the app resident at about 9.3 GB; switching to chat releases the decision model (about 0.9 GB). Alignment with the official BF16 weights is not yet verified; a device with at least 16 GB RAM is recommended.

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

- `Wan2_2-TI2V-5B-Turbo-Q4_0.gguf` from [hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF](https://huggingface.co/hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF)
- `umt5-xxl-encoder-Q3_K_S.gguf` from [city96/umt5-xxl-encoder-gguf](https://huggingface.co/city96/umt5-xxl-encoder-gguf)
- `taew2_2.safetensors` from [lightx2v/Autoencoders](https://huggingface.co/lightx2v/Autoencoders)

Then push them. The script checks sha256 before and after upload, and skips files already on the phone:

```bash
bash scripts/push_video_models.sh <serial>
```

The Video workspace switches to "Wan2.2 · 本机" once all three files are present at their exact sizes; otherwise it lists what is missing. Local runs are limited to 480×832, up to 25 frames and 6 steps.

## MOSS-Image on the phone

It installs the SFT Q4_0 DiT, Qwen3.5-9B Q4_K_M text encoder, FLUX.2 small VAE, and official tokenizer. Defaults are 512×512 and CFG 3, with the native CPU backend using Euler/discrete and negative-branch layer-22 skipping.

```bash
bash scripts/download_moss_models.sh
bash scripts/push_moss_models.sh <serial>  # replace with your device serial
```

The downloader resumes partial files and verifies each SHA-256. The models require about 9GB. Set `MOSS_HF_ROOT` to use a mirror root; pinned hashes still enforce identical files. After pushing, tap **Verify model** in the app's model manager, then select **MOSS · Local**. You can also download or import them in the app.

Official references: [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image) and [SFT model card](https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT).

## External music service

The YuE2 server implementation is external to this repository. The app expects the existing `/health` and `/generate` contracts, followed by `audioUrl` from the same origin. Downloads are size-limited, validated, and atomically stored.

```bash
adb reverse tcp:8999 tcp:8999   # YuE2
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
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) and [ggml](https://github.com/leejet/ggml), MIT.
- [YuE2](https://github.com/multimodal-art-projection/YuE), CC-BY-NC-4.0.
- [Haze](https://github.com/chrisbanes/haze), Apache-2.0.
- [Qwen3.5 MNN model packages](https://huggingface.co/taobao-mnn/Qwen3.5-0.8B-MNN), Apache-2.0; pinned revisions are recorded in `app/src/main/assets/llm_models.json`. The MNN runtime is pinned to `24c6cb21081a657c66db4ffe1e7a2c9d3d20e823`.
- [llama.cpp](https://github.com/ggml-org/llama.cpp), MIT; pinned to `2280f0fd163b03d4331d49e08460f66502e35854` with `patches/llama-elephant-decision.patch`.
- [Elephant-1](https://huggingface.co/baselabs/elephant-1), MIT; [OneJev](https://github.com/OmniJev/OneJev) protocol, Apache-2.0.
- Omni Pocket application code, Apache-2.0.
