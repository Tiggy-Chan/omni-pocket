# Omni Pocket

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

<p align="center">
  <strong>面向 Android 平台的端侧全模态 AIGC 随身创作工坊</strong><br>
  集成本地 Diffusion 图像绘制与 48kHz 全乐章人声音乐生成，基于 MNN 与 OpenCL 硬件加速。
</p>

---

## 🌟 项目简介

**Omni Pocket** 是一款面向现代 Android 设备（`arm64-v8a`）的开源端侧全模态生成式 AI 创作工坊。项目旨在让移动设备离线具备前沿多模态大模型的创作能力，将两大旗舰开源模型融合至统一的应用架构中：

1. **🎨 绘卷 · 生图工坊 (Image Studio)**：基于 **Qwen-Image-2.1**（通过阿里 MNN 框架量化为 Q4_K_M DiT 模型，结合 Adreno/Mali OpenCL 硬件加速与官方 8 步 Turbo 采样调度）。
2. **🎵 乐坊 · 音乐工坊 (Music Studio)**：基于 **YuE2-3B**（30 亿参数 AR–NAR 混合架构音乐大模型，输出 48 kHz 双声道高保真立体声全乐章人声演唱，支持 ABC 符号乐理规划）。

---

## 🚀 核心功能特性

### 🎨 绘卷 · 生图工坊 (`Qwen-Image-2.1`)
- **文生图与图生图智能编辑**：支持 128×128 至 1024×1024 全分辨率生成，支持单图及双参考图特征迁移与局部重绘。
- **官方 8 步 Turbo 原生加速**：集成官方 Turbo 调度算法，相比 20 步标准采样提速超 2.5 倍，兼顾卓越画质与移动端极速出图。
- **轻量化硬件协同**：CPU int4 运行文本与视觉编码器，OpenCL GPU 原生加速 DiT 去噪扩散网络，配合 Tiny VAE FP16 原生显存级解码。

### 🎵 乐坊 · 音乐工坊 (`YuE2-3B`)
- **48 kHz 双声道超高清立体声**：原生输出完整全乐章人声歌唱与丰富器乐编曲，彻底告别传统移动端 TTS 机械拼接。
- **YuE2 独创符号乐理规划（Symbolic CoT）**：
  - `melody`（推荐）：先推演符号旋律线与节拍时值，汉字/假名发音音节精准绑定，保障长曲演唱绝不漏词。
  - `full`：同步规划旋律线与和弦级数走向，提供丰富的管弦乐与电声编曲织体。
  - `off`：直接进行声学语义自回归生成，适合快速音乐小样试听。
- **结构化歌词排版**：支持 `[Intro]`, `[Verse]`, `[Pre-Chorus]`, `[Chorus]`, `[Bridge]`, `[Outro]` 乐段标记一键插入、BPM 节拍滑块调节与实时歌词字数统计。
- **内置高保真播放器与简谱检视**：动态波形跳动动效、进度条拖拽控制、高保真立体声播放，以及正规 ABC 乐理简谱检视与一键剪贴板导出。
- **双模引擎架构**：
  - **工作站直连桥接模式**：通过 USB（`adb reverse tcp:8999 tcp:8999`）或局域网 Wi-Fi 直连运行真实 YuE2-3B 的本地电脑或服务器，流式回传真实 Token 日志并接收 48kHz 完整歌声母带。
  - **端侧离线模型**：面向未来高内存移动设备全量离线量化与算子移植设计。

### 🧭 全局液态流光视觉与架构
- **左侧全局抽屉栏 (Navigation Drawer)**：平滑在生图工坊与音乐工坊之间一键切换。
- **生命周期内存互斥保护**：切换创作业务时自动释放非活跃模型资源，峰值内存严控在系统安全阈值内，杜绝 Android LMK 闪退。
- **持久化极客控制台**：推理日志实时滚动，生成完毕后原地保留，完整展示各阶段耗时、采样步数与落盘文件，支持随时回溯查验。

---

## 📱 硬件要求与设备兼容性

Omni Pocket 面向各主流厂商的现代 Android 移动设备设计：
- **操作系统**：Android 9.0 或更高版本（API 28+，推荐 Android 13+）。
- **架构支持**：`arm64-v8a`（64 位 ARM 处理器）。
- **硬件加速**：支持 OpenCL 2.0+ 的移动 GPU（Qualcomm Adreno、ARM Mali 等）。
- **运行内存建议**：
  - 生图模块：建议 6 GB 以上可用物理 RAM（8GB / 12GB / 16GB / 24GB 设备均可顺畅运行）。
  - 音乐模块：工作站桥接模式适配所有网络互通机型；端侧全量大模型运算推荐旗舰大内存机型。

---

## 🛠️ 编译与安装指南

### 环境准备
- JDK 17（如 OpenJDK 17）
- Android SDK 35（Platform Tools 及 Build-Tools 35.0.0+）
- Android NDK `28.2.13676358`（或 NDK 26+）

### 编译生成 APK

```bash
# 克隆仓库
git clone https://github.com/Tiggy-Chan/omni-pocket.git
cd omni-pocket

# 编译 Debug APK
./gradlew :app:assembleDebug

# 通过 ADB 安装至已连接的手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 模型转换与导入说明

### Qwen Image 2.1 (MNN 格式)
在 PC / Mac 端将 GGUF 权重转换为 MNN 模型：

```bash
git submodule update --init --depth 1 third_party/MNN
python3 -m pip install -r export/requirements.txt
scripts/build_mnnconvert_mac.sh

# 转换 UC Q4_K_M 基础模型
QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh

# 转换官方 8 步 Turbo 模型
scripts/convert_official_turbo.sh
```

通过 ADB 将模型推送至手机内部存储：
```bash
adb push models/qwen_image21_uc_q4km /sdcard/Android/data/com.tiggy.qwenimage/files/models/
```

### YuE2-3B 音乐推理工作站搭建
若需在本地电脑运行全精度 YuE2-3B 并为手机 App 提供后端推理：

```bash
# 在已配置好 YuE2 环境的终端运行
python3 -m pip install fastapi soundfile
python3 /path/to/yue2/server.py

# 通过 USB 为手机建立反向端口映射
adb reverse tcp:8999 tcp:8999
```

在 Omni Pocket 手机端打开“音乐工坊”的高级设置，确认工作站地址为 `http://127.0.0.1:8999`（或电脑的局域网 IP `http://192.168.x.x:8999`）即可畅享真正的全乐章人声演唱生成。

---

## 📜 上游鸣谢与开源许可

- **MNN 计算框架**：由阿里巴巴集团开源 ([MNN GitHub](https://github.com/alibaba/MNN))，采用 Apache-2.0 协议。
- **Qwen-Image 移动端运行时**：参考并基于 [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21)，采用 Apache-2.0 协议；模型权重遵守 Qwen Research License。
- **YuE2-3B 音乐大模型**：由 Multimodal Art Projection 团队研发 ([YuE2 GitHub](https://github.com/multimodal-art-projection/YuE))，采用 CC-BY-NC-4.0 许可。
- **Omni Pocket 应用代码**：遵循 **Apache-2.0 开源协议**。
