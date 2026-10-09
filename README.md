# Qwen Pocket

面向 Snapdragon 8 Gen 3 / 24GB RAM 的 Qwen-Image-2.1 安卓本地生图应用。使用 Kotlin、Compose 和 MNN/OpenCL；目标源权重为 [abenzerps/Qwen-Image-2.1-Uncensored-GGUF](https://huggingface.co/abenzerps/Qwen-Image-2.1-Uncensored-GGUF) 的 UC Q4_K_M。

## 截图与样例来源

目前首页不展示生图样例或 App 截图。项目尚未保存我们自己的完整真机生成结果，也没有当前 Compose 界面的正式截图。

最初 README 的 `sample_coffee_shop.png`、`turbo_t2i_kimono.png`、`turbo_edit_yukata.png` 和 `demo_app.png` 均随 [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21/tree/2f30bc9019dfc423a9ff496d1103881443d61390) 源码一起引入，文件内容与上游相同。其中 `demo_app.png` 是上游 Java/XML demo 的界面，不是 Qwen Pocket 的 Compose 界面。PNG 没有记录对应构建提交，无法确认截图属于哪一次上游打包。

`docs/` 中保留的图库、速度、量化和 VAE 对比文档都是**上游作者的样例与测试记录**，不能作为本项目或用户手机的实测结果。之后加入本项目截图时，会标注 APK 版本、Git 提交、设备、参数和测试日期。

## 当前验证状态

- 本地与 GitHub Actions 的 APK/AAR 构建通过。
- OnePlus PJX110 / Android 16：安装了旧版 App，目标 MNN 模型包已传入并通过手机端 SHA-256 校验。
- 一次 512×512、20 步测试确认执行到 DiT 第 6 步；随后用户拔下 USB。未取回最终 PNG，不能据此声称完整生成已通过，也不能把掉线判定为 OOM 或重启。
- 当前源码版本为 `0.1.1`，已加入暗色玻璃风格界面；手机拔线后尚未安装这个新版。
- 负向提示词目前转换为 `Avoid` 文本指令。它不是独立负向条件，不等价于 Mac WebUI 的 CFG 双条件采样。
- Turbo 未安装、未完成本项目回归验证，因此关闭对应选项。

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

## 上游与许可

- Android MNN 库、转换工具和参考 demo 基于 [scsonic/libQwenImage21](https://github.com/scsonic/libQwenImage21)，引入提交 `2f30bc9019dfc423a9ff496d1103881443d61390`。
- MNN 源码位于 `third_party/MNN`；当前 Git 子模块提交与预编译 `libMNN.so` 的接口差异仍需对齐验证。
- 代码采用 Apache-2.0；模型依 Qwen Research License，Tiny VAE 及其他组件保留各自许可。
