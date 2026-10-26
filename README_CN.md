# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket 是一个 Android 图像、音乐和视频创作应用。界面会明确区分本机推理与工作站请求，不把工作站任务写成端侧生成。

## 各模态运行位置

- **Qwen-Image-2.1 · 本机**：使用 MNN、CPU int4 文本/视觉编码与 OpenCL DiT 推理。支持文生图、单/双参考图编辑、完整/Tiny VAE 和官方 8 步 Turbo。支持队列、协作式取消、模型校验、真实输出尺寸与作品恢复。
- **MOSS-Image · 本机 / ComfyUI 工作站**：本机通过独立的 `libmoss_cli.so` 和 stable-diffusion.cpp 运行 SFT，支持长提示词与原生 JSON 布局，使用 Euler、discrete schedule 和负分支第 22 层跳过。工作站支持 SFT/Base、可选提示词增强及相同布局编辑器；提示词增强不属于本机路径。布局使用官方 `abs_centralized` / `yxyx` 格式。
- **YuE2 音乐 · 工作站**：向外部 YuE2 服务提交标题、风格、歌词、BPM、种子、时长、CoT、ODE 步数和 guidance。应用内支持生成曲目与示例曲目的播放、拖动、分享及 ABC 乐谱查看。当前没有端侧 YuE2 推理引擎。
- **Wan2.2 视频 · 本机**：通过 `libsd_cli` 运行 Wan2.2-TI2V-5B-Turbo、UMT5 文本编码器和 TAE 解码器。文生视频与首帧图生视频会使用界面真实的 seed、FPS、帧数和步数，本机输出 WebM。当前本机限制为 480×832、最多 25 帧；已验证默认值为 4 步、16 FPS。
- **MobileWan · 工作站**：通过外部桥接进行文生视频。桥接不接收首帧，选择首帧但本机模型未就绪时，App 会在提交前明确提示，不会静默丢弃图片。

应用使用“图像 / 音乐 / 视频”三个全局入口、独立任务状态与默认折叠的高级参数和日志。Android 12/API 31+ 的悬浮导航使用背景玻璃效果，旧版本使用高不透明降级材质；重推理时降低装饰效果开销。

## 编译与验证

环境：JDK 17、Android SDK 35、NDK `28.2.13676358`。

```bash
git submodule update --init --depth 1 third_party/stable-diffusion.cpp
git -C third_party/stable-diffusion.cpp submodule update --init --depth 1 ggml
bash scripts/build_moss_android.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

支持 Android 9/API 28+、`arm64-v8a`。MOSS 本机建议使用至少 16 GB RAM 的设备；主要真机验证目标为 24 GB RAM。

## Qwen 模型准备

```bash
git submodule update --init --depth 1 third_party/MNN
python3 -m pip install -r export/requirements.txt
scripts/build_mnnconvert_mac.sh

QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh

# 添加官方 Turbo DiT；仅转换不代表已通过真机验证。
scripts/convert_official_turbo.sh

# 只有转换结果实际通过真机回归后才设置此标记。
QWEN_TURBO_VALIDATED=1 scripts/convert_official_turbo.sh

adb push models/qwen_image21_uc_q4km \
  /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

Qwen 生成依赖有效的 `manifest.json`。应用会校验文件大小和 SHA-256，校验完成前不会开放生成。MNNConvert 返回失败、产物缺失或产物未更新都会中止转换脚本。

## Wan 模型准备

将以下文件放入 `models/wan22_turbo`：

- `Wan2_2-TI2V-5B-Turbo-Q4_0.gguf`
- `umt5-xxl-encoder-Q3_K_S.gguf`
- `taew2_2.safetensors`

然后推送：

```bash
adb push models/wan22_turbo \
  /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

## 手机本地运行 MOSS-Image

本机模式不需要 ComfyUI 或工作站。当前安装 SFT 的 Q4_0 DiT、Qwen3.5-9B Q4_K_M 文本编码器、FLUX.2 small VAE 和官方 tokenizer，默认 512×512、CFG 3；原生 CPU 后端使用 Euler/discrete，固定负分支第 22 层跳过。

```bash
bash scripts/download_moss_models.sh
bash scripts/push_moss_models.sh <serial>  # 序列号换成你的设备
```

下载脚本支持断点续传和逐文件 SHA-256 校验，模型约 9GB；可用 `MOSS_HF_ROOT` 指定镜像源根地址，哈希约束不变。推送后在 App 的模型页点击“校验模型”，再选择 **MOSS · 本机**。也可以在图像页或模型页直接下载/导入。本机与工作站分别保存尺寸、步数和 CFG；提示词增强仅在工作站提供。

## 使用 ComfyUI 运行 MOSS-Image

需要 ComfyUI 0.42 或更新版本，并包含原生 MOSS 节点。App 会在提交前检查所需节点与所选权重文件。

```text
ComfyUI/models/
├── diffusion_models/
│   ├── moss_image_sft_int8_convrot.safetensors
│   └── moss_image_base_int8_convrot.safetensors  # 可选 Base
├── text_encoders/
│   └── qwen3.5_9b_w4a8_gptq.safetensors
└── vae/
    └── flux2-vae_small.safetensors
```

启动 ComfyUI 后，建立 USB 反向映射：

```bash
adb reverse tcp:8188 tcp:8188
```

在图像页选择 **MOSS · 工作站**。USB 使用 `http://127.0.0.1:8188`，局域网则填写电脑地址。客户端提交原生 ComfyUI API 工作流，读取真实队列和历史，并从同源下载图片；服务器没有进度流时只显示不确定状态，不虚构采样百分比。

官方资料：[MOSS-Image](https://github.com/OpenMOSS/MOSS-Image)、[SFT 模型卡](https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT)、[ComfyUI 教程](https://docs.comfy.org/tutorials/image/moss-image/moss-image)、[Comfy-Org 权重](https://huggingface.co/Comfy-Org/MOSS-Image)。

## 外部音乐与视频服务

YuE2 和 MobileWan 服务端不在本仓库中。App 使用现有的 `/health`、`/generate` 接口，并下载同源返回的 `audioUrl` 或 `videoUrl`。下载有大小限制、媒体校验与原子落盘。

```bash
adb reverse tcp:8999 tcp:8999   # YuE2
adb reverse tcp:8998 tcp:8998   # MobileWan
```

只有服务器提供真实进度时才展示对应进度；否则显示不确定工作状态，不伪造步骤。

## 转换与部署检查

```bash
python3 -m unittest discover -s export -p 'test_conversion_utils.py'
bash -n scripts/push_models.sh scripts/convert_official_turbo.sh
```

`scripts/push_models.sh` 使用 SHA-256 判断内容是否一致，不再把同大小视为同文件；上传校验通过后才替换远端文件。数值导出测试具有明确误差阈值。CI 在打包 APK/AAR 前运行 Android 单元测试。

## 上游与许可

- [MNN](https://github.com/alibaba/MNN)：Apache-2.0。
- [Qwen-Image 运行时](https://github.com/scsonic/libQwenImage21)：Apache-2.0；模型权重遵循各自许可。
- [MOSS-Image](https://github.com/OpenMOSS/MOSS-Image)：Apache-2.0。
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) 与 [ggml](https://github.com/leejet/ggml)：MIT。
- [YuE2](https://github.com/multimodal-art-projection/YuE)：CC-BY-NC-4.0。
- [Haze](https://github.com/chrisbanes/haze)：Apache-2.0。
- Omni Pocket 应用代码：Apache-2.0。
