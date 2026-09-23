<div align="center">

# 🔥 MAG160 ThermalCam

### 让你的 Magnity 热像仪，在新版 Android 上重获新生。

[English](README.md) · [繁體中文](README.zh-TW.md) · **简体中文**

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

<p align="center"><img src="docs/android_screenshot.jpg" width="320" alt="在 Xiaomi 14T Pro 上的实时热像画面"></p>

---

## 😤 痛点

Magnity **MAG-Mx / MAG-Cx** USB 热像仪（VID `0x833C`）硬件很好，但原厂 App
**在 Android 15 及以上已经无法使用**。插上新手机，什么都没有。

## 💡 解决方案

**MAG160 ThermalCam** 是专为这台热像仪全新打造的 Android App，通过逆向工程原厂 SDK
从零重写。插上、打开、测温——用你手上现有的手机就行。

## ✨ Android App 功能

| | |
|---|---|
| 📱 **支持 Android 15+** | 原厂 MAG-Cx / MAG-Mx 跑不动的地方，它可以。 |
| 🌡️ **真实绝对温度** | 直接取自原厂 SDK，已完整校正，免手动标定。 |
| 🎯 **MIN / MAX / SPOT 标记** | 一眼找出最热、最冷点，配合实时色条与温度范围。 |
| 🎚️ **发射率可调** | ε 0.10–1.00 自由设置，皮肤、哑光涂漆、木材一键套用。 |
| 🚀 **4× AI 超分辨率** | 针对热成像微调的 Real-ESRGAN 模型，通过 **ncnn + Vulkan** 在 GPU 上运行，把 160×120 变成清晰的 640×480。 |
| ✨ **Anime4K GPU 放大** | 实时 CNN shader，画面锐利干净。 |
| 📸 **拍照** | 一键保存为 PNG。 |
| 🎬 **录像** | 硬件 **HEVC/H.265** 编码，不支持时自动改用 H.264。 |
| 🌡️ **温度码流** | 拍照与录像可另存 `.mgt` 温度文件，记录每个像素的 °C；事后在 App 内打开，任一帧、任一点都能读出温度。 |
| 🔀 **可见光融合（beta）** | 把手机相机的轮廓叠到热像上（MSX 风格）、混合显示，或在大视野可见光画面中搜索目标；可在多个距离校准对齐，并以十字标出可见光测距的位置。 |
| 📍 **可选 geo-tag** | 截图与录像写入精确 GPS 位置——默认关闭，由你决定。 |
| 💾 **记住你的设置** | 色盘、温度范围、旋转、放大方式、发射率等，重开 App 无需重设。 |
| 🚫 **无水印** | 你的图像就是你的。 |
| 👍 **精简竖屏界面** | 为现场单手操作而设计。 |

## 🚀 开始使用

1. **从 [Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases) 下载 APK**（arm64、Android 13 及以上），或自行编译：
   ```sh
   cd android2
   JAVA_HOME=/path/to/android-studio/jbr ./gradlew :app:assembleDebug
   ```
2. 用 **USB-C OTG 转接头**把热像仪接到手机。
3. 打开 App、允许 USB 权限——画面就出来了。

> **注意：**原厂 SDK（`libcoresdk.so` 及 `android2/lib/` 下的 AAR 文件）**不包含**在本
> 仓库中，请从你自己持有的原厂软件获取。

## 🧰 本项目还包含

- **🐧 Linux 桌面版**（`viewer.py`）——纯 Python 实时 160×120 @ 15 fps、与原厂逐位一致的
  NUC、平场 / 坏点 / 降噪处理、基于 Planck 的 °C 读数。快速开始：
  ```bash
  pip install pyusb numpy pillow PySide6 matplotlib
  sudo cp 99-magnity-thermal.rules /etc/udev/rules.d/ && sudo udevadm control --reload-rules
  python3 viewer.py
  ```
- **🧠 训练你自己的热成像超分辨率模型**——Colab 流程在 [`sr_train/`](sr_train/README.md)。
- **🔬 逆向工程笔记**——完整 USB 协议见 [`PROTOCOL.md`](PROTOCOL.md)，SDK 与校正分析见
  [`android2/REVERSE_ENGINEERING.md`](android2/REVERSE_ENGINEERING.md)。
- **`android/`**——较早期的 Android 移植版，使用完全开源、不依赖 SDK 的处理流程。

## 🗒️ 版本记录

完整说明与各版本 APK 都在
[Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases)。

### 2.3 — 2026-09-24
- **温度码流** —— 拍照与录像可另存 `.mgt` 文件，记录每个像素的 °C；用 "Open temperature
  capture…" 打开回放：拖时间轴选帧、点画面任一点读出温度（[格式说明](android2/docs/THERMAL_CAPTURE.md)）。
- **融合十字** 标出可见光相机的 AF 窗口 —— 也就是 Auto 物距实际测量的那一块。
- 焦点离开已标定距离时，改为**显示一个 Align 按钮**，不再自动弹出对齐面板；范围判定
  也从 1/Z 的固定步长改成 **±0.5 米**。
- 对齐值会在**已保存的各个距离之间插值**；中央加权对焦、对焦距离自动带入物距、标定文件
  导出／导入，以及轮廓叠加的热梯度门控。

### 2.2 — 2026-09-18
- **可见光融合（beta）** —— MSX 轮廓、混合、大视野搜索；可在多个距离标定对齐，物距可用
  自动对焦／手动／∞。
- 双边滤波加速 4.7 倍；开启 Thermal SR 时自动关闭空间降噪。

### 2.1 — 2026-09-17
- **4× AI 超分辨率** —— 针对热成像微调的 Real-ESRGAN 模型内置于 APK，通过 ncnn + Vulkan 在
  GPU 上运行。
- 发射率可调、可选 geo-tag，设置重开 App 不会丢失。

### 2.0 — 2026-09-16
- 首个版本：15 fps 实时绝对温度、MIN / MAX / SPOT、可自动或手动调整范围的色条、拍照与
  HEVC 录像、Anime4K GPU 放大、8 种色盘、旋转／镜像、时域＋空间降噪与手动 FFC。

## 📜 许可

**非商业用途免费**——个人、研究、教育与业余用途均可。商业用途需另行取得书面许可，详见
[`LICENSE`](LICENSE)。第三方组件遵循其各自许可条款
（[`android2/THIRD_PARTY_NOTICES.md`](android2/THIRD_PARTY_NOTICES.md)）。Magnity / Elo SDK
为专有软件，本项目不授权也不分发。

温度读数仅供参考，本软件并非经过校准的测量仪器。

## ⭐ 让你的热像仪复活了吗？

**给这个仓库点个 Star**——这是我决定要不要继续打磨它的依据。
欢迎提 Issue 和 Pull Request。
