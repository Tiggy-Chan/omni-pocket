# Omni Pocket

[English](README.md) | [简体中文](README_CN.md)

Omni Pocket 是一个 Android 本机对话、决策与图像、音乐、视频创作应用。所有模型都在手机上运行，推理不需要工作站，也不需要网络。

## 各模态运行位置

- **Qwen-Image-2.1 · 本机**：使用 MNN、CPU int4 文本/视觉编码与 OpenCL DiT 推理。支持文生图、单/双参考图编辑、完整/Tiny VAE 和官方 8 步 Turbo。支持队列、协作式取消、模型校验、真实输出尺寸与作品恢复。
- **MOSS-Image · 本机**：通过独立的 `libmoss_cli.so` 和 stable-diffusion.cpp 运行 SFT，支持长提示词与原生 JSON 布局，使用 Euler、discrete schedule 和负分支第 22 层跳过。布局使用官方 `abs_centralized` / `yxyx` 格式。
- **YuE2 音乐 · 本机**：`libyue2_cli.so` 是 [audio.cpp](https://github.com/0xShug0/audio.cpp) 的 CPU 版本，运行 YuE2-3B Q4_0 和配套 VAE。输入风格、歌词、BPM、种子、目标时长、CoT（旋律 / 旋律与和弦 / 直接生成）和 ODE 步数，输出 48 kHz 立体声 WAV；开启 CoT 时会附带生成的 ABC 乐谱。歌词面板提供纯文字风格模板。手机上的生成速度尚未实测。
- **Wan2.2 视频 · 本机**：通过 `libsd_cli` 运行 Wan2.2-TI2V-5B-Turbo、UMT5 文本编码器和 TAE 解码器。文生视频与首帧图生视频会使用界面真实的 seed、FPS、帧数和步数，本机输出 WebM。当前本机限制为 480×832、最多 25 帧；已验证默认值为 4 步、16 FPS。

- **Elephant-1 决策 · 本机**：通过 llama.cpp 运行 GGUF，对 choice / noul / score 三类问题输出选项概率与置信度，不生成自由文本。

界面采用“侧栏 + 会话 + 工作目录”结构：侧栏（手机为抽屉，840dp 以上常驻）切换对话、决策、图像、音乐、视频五个工作区，以及工作目录、模型和设置页。每个工作区是请求与结果的会话流，参数收在输入框旁的面板里；工作目录只列出真实作品，图像可直接继续编辑或作为视频首帧。浅色与深色主题跟随系统，也可在设置中指定。玻璃效果只用于悬浮的输入区和面板，Android 12/API 31+ 使用背景模糊，旧版本使用不透明材质。

## 编译与验证

环境：JDK 17、Android SDK 35、NDK `28.2.13676358`。

```bash
git submodule update --init --depth 1 third_party/stable-diffusion.cpp third_party/llama.cpp
git -C third_party/stable-diffusion.cpp submodule update --init --depth 1 ggml
bash scripts/build_moss_android.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

支持 Android 9/API 28+、`arm64-v8a`。MOSS 本机建议使用至少 16 GB RAM 的设备；主要真机验证目标为 24 GB RAM。

## 本机 Qwen3.5 对话

对话复用现有 MNN，支持来自 `taobao-mnn` 的固定版本 Qwen3.5 0.8B / 2B / 4B 模型包。第一版仅开放文本，不冒充图片理解、联网、RAG、命令执行或通用 Agent。默认使用 4 个 CPU 线程、4096 token 总上下文和最多 512 token 输出；空间不足时丢弃旧轮次。思考内容独立展示，不送入下一轮。语言模型与其他重推理引擎互斥，切换时释放另一方的常驻模型。

```bash
bash scripts/download_llm_models.sh qwen35-0.8b
bash scripts/push_llm_models.sh qwen35-0.8b
```

打开侧栏的 **模型** 页，可直接下载，或校验通过 USB 推送的模型。下载支持断点续传、大小和 SHA-256 校验以及原子启用；推送脚本不会伪造手机端校验标记。可运行真实主机测试，覆盖中文多轮、流式取消/重试、真实思考输出和上下文裁剪：

```bash
bash scripts/test_llm_host.sh
```

采样参考官方文本生成建议，显式设置 presence/repetition penalty。[0.8B 官方模型卡](https://huggingface.co/Qwen/Qwen3.5-0.8B) 明确提示该规格容易陷入思考循环。App 会限制输出长度，并在思考超限且没有答案时明确显示，不编造回退答案。主机测试会区分正常完成的思考与正确受限的循环，不把触发上限当作完整回答。

当前只有 0.8B 具备主机推理回归。Android 真机内存、性能与运行行为仍需实机验收；提供 2B/4B 下载不等于宣称兼容所有设备。

## 本机 Elephant-1 决策

决策工作区接入 [`baselabs/elephant-1`](https://huggingface.co/baselabs/elephant-1)（Qwen3.5-9B 微调，MIT，固定提交 `b3f8a934`）。用户提供的 `elephant-1base` 地址不可访问，公开发布的是该仓库。权重使用社区量化 `prithivMLmods/elephant-1-GGUF` 的 Q4_K_M（5.63 GB，非官方量化），由 llama.cpp `2280f0fd` 运行，全注意力层使用块双向掩码，在答案位置对选项标签 logits 做 softmax。

- 三类问题：choice（最多 2048 个选项，超过 256 个 slot 时按 OneJev 规则分块并加权合并）、noul（是/否）、score（1–10 级，给出期望值与置信度）。
- 单次 1–32 个问题共享同一状态前缀；状态和单个问题各限 128 KiB，单个分支最多 16384 token，超限直接拒绝，不静默截断。
- 推理上下文按请求实际长度分配；取消在加载、前缀计算和每个分支之间都会生效。

模型需在电脑上准备并通过 USB 导入，App 不提供 GGUF 直接下载：

```bash
bash scripts/prepare_elephant_model.sh
bash scripts/push_llm_models.sh elephant-1
bash scripts/test_decision_host.sh
```

推送后在 **模型** 页校验。主机回归使用真实模型，与同一 GGUF 的 FP32 KV 参考相比，三类问题概率最大误差为 0.0053（阈值 0.05），并覆盖 300 选项分块、重复调用、问题重排、状态隔离和取消；16 GB 主机峰值 RSS 约 6.2 GB。在 OnePlus PJX110（24 GB）上实测：校验约 8 秒，加载约 5 秒，示例三问共约 14 秒，应用常驻内存约 9.3 GB；切到对话后决策模型会被释放（约 0.9 GB）。尚未与官方 BF16 权重对齐，建议使用 16 GB 以上内存的设备。

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

- `Wan2_2-TI2V-5B-Turbo-Q4_0.gguf`，来自 [hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF](https://huggingface.co/hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF)
- `umt5-xxl-encoder-Q3_K_S.gguf`，来自 [city96/umt5-xxl-encoder-gguf](https://huggingface.co/city96/umt5-xxl-encoder-gguf)
- `taew2_2.safetensors`，来自 [lightx2v/Autoencoders](https://huggingface.co/lightx2v/Autoencoders)

然后推送。脚本会在上传前后校验 sha256，手机上已有的文件会跳过：

```bash
bash scripts/push_video_models.sh <serial>
```

三个文件大小都正确后，视频工作区会显示“Wan2.2 · 本机”；否则会列出缺少的文件。本机生成限制为 480×832、最多 25 帧、6 步。

## 手机本地运行 MOSS-Image

当前安装 SFT 的 Q4_0 DiT、Qwen3.5-9B Q4_K_M 文本编码器、FLUX.2 small VAE 和官方 tokenizer，默认 512×512、CFG 3；原生 CPU 后端使用 Euler/discrete，固定负分支第 22 层跳过。

```bash
bash scripts/download_moss_models.sh
bash scripts/push_moss_models.sh <serial>  # 序列号换成你的设备
```

下载脚本支持断点续传和逐文件 SHA-256 校验，模型约 9GB；可用 `MOSS_HF_ROOT` 指定镜像源根地址，哈希约束不变。推送后在 App 的模型页点击“校验模型”，再选择 **MOSS · 本机**。也可以在图像页或模型页直接下载/导入。

官方资料：[MOSS-Image](https://github.com/OpenMOSS/MOSS-Image)、[SFT 模型卡](https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT)。

## 手机本地运行 YuE2 音乐

先编译 CLI（audio.cpp 固定在 `3a0dd44`，仅 CPU，ARMv8.6 + i8mm），再推送模型。推送脚本会从 [audio-cpp/Yue2-3B-GGUF](https://huggingface.co/audio-cpp/Yue2-3B-GGUF) 补齐缺失文件，并在上传前后校验 sha256：

```bash
ANDROID_HOME=<sdk> bash scripts/build_yue2_android.sh
bash scripts/push_music_models.sh <serial>
```

模型为 `yue2-3b-q4_0.gguf`（2.67 GB）、`yue2-vae-f16.gguf`（0.27 GB）和 4 个配置文件，存放在 `Android/data/com.tiggy.qwenimage/files/models/yue2`。音乐工作区会列出缺少的文件。CLI 只在每个阶段结束时报告，所以进度按阶段推进：规划、语义、声学合成、VAE 解码。YuE2 权重为 CC BY-NC 4.0，不打包进 APK。

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
- [YuE2](https://github.com/multimodal-art-projection/YuE)：CC-BY-NC-4.0（仅限非商业）；GGUF 权重来自 [audio-cpp/Yue2-3B-GGUF](https://huggingface.co/audio-cpp/Yue2-3B-GGUF)。
- [audio.cpp](https://github.com/0xShug0/audio.cpp)：Apache-2.0；附带的许可证见 `app/src/main/assets/licenses/audio.cpp.txt`。
- [Haze](https://github.com/chrisbanes/haze)：Apache-2.0。
- [Qwen3.5 MNN 模型包](https://huggingface.co/taobao-mnn/Qwen3.5-0.8B-MNN)：Apache-2.0；固定提交记录于 `app/src/main/assets/llm_models.json`，MNN 固定为 `24c6cb21081a657c66db4ffe1e7a2c9d3d20e823`。
- [llama.cpp](https://github.com/ggml-org/llama.cpp)：MIT；固定为 `2280f0fd163b03d4331d49e08460f66502e35854`，附带 `patches/llama-elephant-decision.patch`。
- [Elephant-1](https://huggingface.co/baselabs/elephant-1)：MIT；[OneJev](https://github.com/OmniJev/OneJev) 协议：Apache-2.0。
- Omni Pocket 应用代码：Apache-2.0。
