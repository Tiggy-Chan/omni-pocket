# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket 是一个全端本地运行的 Android 多模态 AI 创作与对话应用（支持 `arm64-v8a`、Android 9 / API 28+）。对话、决策、图像、音乐、视频全部在手机本机推理，无需工作站或云端服务。

## 功能与本机推理引擎

- **对话（Conversation）**：
  - **内置 MNN 模型**：固定版本 `taobao-mnn` Qwen3.5 0.8B / 2B / 4B，通过 `libMNN.so` 运行。
  - **Hugging Face 社区 GGUF / MNN 模型**：基于内置 `llama.cpp`（`-O3`、`armv8.2-a+dotprod`）直接运行从 Hugging Face / `hf-mirror` 搜索下载的任意单文件 GGUF 对话模型（Qwen2.5/3/3.5、Gemma 2/3、Llama 3、DeepSeek-R1-Distill、Phi、MiniCPM 等），也支持社区 MNN 转换包。
  - 支持流式输出、`<think>` 深度思考折叠展示、可调 CPU 线程 / 上下文长度（2048–8192）/ 最大输出（512–2048）、KV 前缀复用、复制与重新生成。
- **决策（Decision）**：
  - 通过 `llama.cpp` 运行 [`baselabs/elephant-1`](https://huggingface.co/baselabs/elephant-1)（`prithivMLmods/elephant-1-GGUF` Q4_K_M，5.63 GB），采用块双向全注意力掩码，对选择（choice）、判断（noul）、评分（score）输出概率分布与置信度。
  - 现已支持直接在 App「模型」页一键下载（内置 19 KB 固定元数据 + 通过 HF / `hf-mirror` 直下 GGUF），亦兼容电脑 USB 推送。
- **图像（Image）**：
  - **Qwen-Image-2.1**：MNN 运行时（CPU int4 文本/视觉编码 + OpenCL DiT），支持文生图、单/双参考图编辑、完整/Tiny VAE、官方 8 步 Turbo。
  - **MOSS-Image SFT**：统一 `libsd_cli.so`（`stable-diffusion.cpp`），支持长提示词与官方 `abs_centralized` / `yxyx` JSON 布局。
  - **社区单文件扩散模型**：可在「模型 → 发现模型」搜索下载单文件 `.safetensors` / `.gguf` / `.ckpt` 权重（如 SD 1.5、SDXL、SDXL-Turbo 等），在图像工作区直接切换并本机生成（支持 CPU 与可选 OpenCL GPU）。
- **音乐（Music）**：
  - `libyue2_cli.so`（[audio.cpp](https://github.com/0xShug0/audio.cpp)，`armv8.2-a+dotprod` CPU 兼容构建）运行 YuE2-3B Q4_0 与配套 VAE，输出 48 kHz 立体声 WAV 及可选 ABC 乐谱。支持在「模型」页直接下载（权重为 CC BY-NC 4.0，仅限非商业使用，不随 APK 打包）。
- **视频（Video）**：
  - 统一 `libsd_cli.so`（编译启用 `SD_WEBM=ON`，`diffusion=GPUOpenCL,te=CPU,vae=CPU`）运行 Wan2.2-TI2V-5B-Turbo GGUF DiT、UMT5 文本编码器与 TAE 解码器，支持文生视频与首帧图生视频（输出 `.webm`，480×832、最多 25 帧）。支持在「模型」页直接下载与校验。

## 模型源切换（Hugging Face / `hf-mirror`）与后台下载

- **国内镜像无缝切换**：在 **模型 → 切换源** 或 **设置 → 模型下载镜像** 中可一键切换 `Hugging Face (https://huggingface.co)`、`HF-Mirror 国内镜像 (https://hf-mirror.com)` 或自定义 HTTPS 镜像地址，支持填写 Access Token（访问受限仓库）以及延迟测速。中国大陆地区语言环境默认启用 `HF-Mirror`。
- **社区模型搜索与发现**：在 **模型 → 发现模型** 中按分类（`GGUF 对话` / `MNN 对话` / `图像模型`）搜索关键词，或直接输入 `owner/repo` 仓库名浏览可下载文件，自动展示量化等级、文件大小与建议内存，一键加入下载队列。
- **后台断点续传与校验**：通过 `ModelDownloadService`（`dataSync` 前台服务）在后台稳定下载数 GB 大文件，通知栏实时展示进度与速度，支持断点续传、严格 `Content-Range` 校验及 SHA-256 / LFS OID 完整性校验。

## 编译与测试

环境要求：JDK 17+、Android SDK 35、NDK `28.2.13676358`、CMake、Ninja。

```bash
git submodule update --init --depth 1 third_party/stable-diffusion.cpp third_party/llama.cpp
git -C third_party/stable-diffusion.cpp submodule update --init --depth 1 ggml

# 编译统一的 stable-diffusion.cpp（同时驱动 Wan2.2、MOSS 与通用 SD）及 audio.cpp（YuE2 音乐）
ANDROID_HOME=<sdk> JOBS=4 bash scripts/build_sd_android.sh
ANDROID_HOME=<sdk> JOBS=4 bash scripts/build_yue2_android.sh

# 运行单元测试并编译 Debug APK
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用包名：`com.tiggy.omnipocket`。手机端模型目录：`/sdcard/Android/data/com.tiggy.omnipocket/files/models/`。

## 构建与发布正式签名 Release 安装包

正式 Release 必须使用固定的签名 keystore，确保后续版本可直接覆盖升级而不会因卸载清空手机中已下载的模型文件。

### 本地签名打包

```bash
OMNI_KEYSTORE_PATH=/path/to/release.jks \
OMNI_KEYSTORE_PASSWORD='...' \
OMNI_KEY_ALIAS='...' \
OMNI_KEY_PASSWORD='...' \
./gradlew :app:testDebugUnitTest :app:assembleRelease :qwenimage21:assembleRelease
```

### GitHub Actions 自动发布 Release（推送 `v*` 标签）

在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置以下 4 个 Secrets：

- `RELEASE_KEYSTORE_BASE64`：`base64 -i release.jks | tr -d '\n'`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

推送版本标签（例如 `v0.1.0`）后，[`.github/workflows/build.yml`](.github/workflows/build.yml) 会自动编译全部原生推理引擎（`libsd_cli.so`、`libyue2_cli.so`、`libomni_llm.so`、`libomni_decision.so`、`libMNN.so`）、执行测试、产出签名版 `omni-pocket-<tag>-arm64-v8a.apk` 与 `SHA256SUMS.txt` 并上传至 GitHub Release。

## 电脑端辅助脚本（可选 USB 推送）

所有模态均支持在手机 App 内直接下载；如需在电脑端下载再通过 USB 推送，国内可加 `HF_ENDPOINT=https://hf-mirror.com`：

```bash
HF_ENDPOINT=https://hf-mirror.com bash scripts/download_llm_models.sh qwen35-0.8b
bash scripts/push_llm_models.sh qwen35-0.8b

HF_ENDPOINT=https://hf-mirror.com bash scripts/download_moss_models.sh
bash scripts/push_moss_models.sh <serial>

HF_ENDPOINT=https://hf-mirror.com bash scripts/push_music_models.sh <serial>
bash scripts/push_video_models.sh <serial>
```

主机与导出校验：

```bash
python3 -m unittest discover -s export -p 'test_conversion_utils.py'
bash scripts/test_llm_host.sh
bash scripts/test_decision_host.sh
```

## 上游项目与许可

- [MNN](https://github.com/alibaba/MNN)：Apache-2.0（`24c6cb21`）。
- [Qwen-Image 运行时](https://github.com/scsonic/libQwenImage21)：Apache-2.0。
- [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image)：Apache-2.0。
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) 与 [ggml](https://github.com/leejet/ggml)：MIT。
- [llama.cpp](https://github.com/ggml-org/llama.cpp)：MIT（`2280f0fd` + `patches/llama-elephant-decision.patch`）。
- [audio.cpp](https://github.com/0xShug0/audio.cpp)：Apache-2.0（`3a0dd44`）；[YuE2](https://github.com/multimodal-art-projection/YuE) 权重：CC-BY-NC-4.0（仅限非商业）。
- [Elephant-1](https://huggingface.co/baselabs/elephant-1)：MIT；[OneJev](https://github.com/OmniJev/OneJev) 协议：Apache-2.0。
- [Haze](https://github.com/chrisbanes/haze)：Apache-2.0。
- Omni Pocket 应用代码：Apache-2.0。
