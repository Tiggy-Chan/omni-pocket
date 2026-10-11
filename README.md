# Qwen Pocket

面向 Snapdragon 8 Gen 3 / 24GB RAM 的 Qwen-Image-2.1 安卓本地生图应用。使用 Kotlin、Compose 和 MNN/OpenCL；目标源权重为 [abenzerps/Qwen-Image-2.1-Uncensored-GGUF](https://huggingface.co/abenzerps/Qwen-Image-2.1-Uncensored-GGUF) 的 UC Q4_K_M。

## 截图与样例来源

当前首页使用本项目自己的真机证据：新版 Compose 界面截图见 [0.1.2 UI](docs/device-evidence/qwen-pocket-0.1.2-pjx110-ui.png)，真实手机生图见 [0.1.2 输出](docs/device-evidence/qwen-pocket-0.1.2-pjx110-redapple-512.png)。

最初 README 的 `sample_coffee_shop.png`、`turbo_t2i_kimono.png`、`turbo_edit_yukata.png` 和 `demo_app.png` 均随 [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21/tree/2f30bc9019dfc423a9ff496d1103881443d61390) 源码一起引入，文件内容与上游相同。其中 `demo_app.png` 是上游 Java/XML demo 的界面，不是 Qwen Pocket 的 Compose 界面。PNG 没有记录对应构建提交，无法确认截图属于哪一次上游打包。

`docs/` 中保留的图库、速度、量化和 VAE 对比文档都是**上游作者的样例与测试记录**，不能作为本项目或用户手机的实测结果。之后加入本项目截图时，会标注 APK 版本、Git 提交、设备、参数和测试日期。

## 当前验证状态

- 本地与 GitHub Actions 的 APK/AAR 构建通过。
- OnePlus PJX110 / Android 16：目标 MNN 模型包已传入并通过手机端 SHA-256 校验，应用进程可正常启动。
- 已从手机取回一份完整的 512×512、20 步、seed 42 真机输出：[qwen-pocket-0.1.1-pjx110-512.png](docs/device-evidence/qwen-pocket-0.1.1-pjx110-512.png)。参数为 Q4_K_M、Tiny VAE、文生图；原始 prompt 是 `1 girl`，负向提示词为应用默认的人体/画质排除词。该文件是设备生成结果，不是上游样例。
- 当前源码版本为 `0.1.2`：重做了 Compose 首页的材质层、渐变光晕、半透明高光卡片、悬浮玻璃导航和透明系统栏。新版真机界面截图：[qwen-pocket-0.1.2-pjx110-ui.png](docs/device-evidence/qwen-pocket-0.1.2-pjx110-ui.png)。
- `0.1.2` 已在同一台手机完成第二轮 512×512、20 步文生图，原生日志记录总耗时 382.0 秒，输出为 [qwen-pocket-0.1.2-pjx110-redapple-512.png](docs/device-evidence/qwen-pocket-0.1.2-pjx110-redapple-512.png)；详细参数、路径和 SHA-256 见 [真机记录](docs/device-evidence/qwen-pocket-0.1.2-pjx110-redapple.txt)。
- 负向提示词目前转换为 `Avoid` 文本指令。它不是独立负向条件，不等价于 Mac WebUI 的 CFG 双条件采样。
- 官方 Qwen-Image-2.1-Turbo（8 步、`dit_official_turbo.mnn`）已完成真机回归验证：OnePlus PJX110 端到端耗时 151.2 秒（DiT 8 步约 125.7 秒，~15.7 秒/步；相比 20 步的 382.0 秒提速 2.53 倍），真机输出与界面证据见 [qwen-pocket-0.1.2-pjx110-turbo-redapple-512.png](docs/device-evidence/qwen-pocket-0.1.2-pjx110-turbo-redapple-512.png) 与 [qwen-pocket-0.1.2-pjx110-turbo-ui.png](docs/device-evidence/qwen-pocket-0.1.2-pjx110-turbo-ui.png)；详细参数、日志及 SHA-256 见 [Turbo 真机记录](docs/device-evidence/qwen-pocket-0.1.2-pjx110-turbo-redapple.txt)。

## 使用与构建

使用 JDK 17、Android SDK 35、NDK `28.2.13676358`：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

主应用包名为 `com.tiggy.qwenimage`。`demo/` 是保留的上游参考应用，包名为 `com.scsonic.qwenimage21.demo`；它与主应用是两个不同的 APK。

主应用提供文生图、单/双参考图编辑、串行队列、模型下载/导入和运行时检查。默认 512×512、20 步、Tiny VAE，关闭模型常驻以减少内存峰值。编辑能力和最新界面仍需继续真机验收。

## 模型转换与导入

原始 GGUF 在电脑转换为 MNN，手机不执行模型转换：

```bash
git submodule update --init --depth 1 third_party/MNN
python3 -m pip install -r export/requirements.txt
scripts/build_mnnconvert_mac.sh
QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh
```

Q4_K_M 包含 Q4_K、Q6_K 和 BF16 张量。转换器保留 Q4_K 的 int4 块，Q6_K 投影重新量化到 int8；不能将整个转换描述为无损。配套使用 Qwen3-VL MNN int4 文本/视觉编码器、Tiny VAE FP16，以及可选的 Real VAE。

App 从含 `manifest.json` 的 Hugging Face MNN 仓库下载，或通过系统文件选择器导入完整转换目录。ADB 也可将目录推送到：

```text
/sdcard/Android/data/com.tiggy.qwenimage/files/models/qwen_image21_uc_q4km
```

App 发现完整文件后进行 SHA-256 校验；只有校验通过才启用生成。权重不包含在 APK 或 Git 仓库中。

官方 Turbo 使用同一套共享文本编码器和 VAE，另行转换官方 checkpoint 派生的 Q4_K_M GGUF：

```bash
scripts/convert_official_turbo.sh
```

转换结果为 `dit_official_turbo.mnn` / `.weight`，MNN 使用 int4 主权重、int8 辅助投影，运行时固定采用官方 `sample_sigmas` 的 8 步 schedule；它与 UC 基础 DiT 共存，不覆盖 `dit.mnn`。

## 上游与许可

- Android MNN 库、转换工具和参考 demo 基于 [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21)，引入提交 `2f30bc9019dfc423a9ff496d1103881443d61390`。
- MNN 源码位于 `third_party/MNN`，当前子模块提交为 `24c6cb2`；官方 Turbo 的最小运行时扩展保存在 `patches/mnn-qwen-image-official-turbo.patch`，构建脚本会自动应用。
- 代码采用 Apache-2.0；模型依 Qwen Research License，Tiny VAE 及其他组件保留各自许可。
