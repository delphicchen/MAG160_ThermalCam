<div align="center">

# 🔥 MAG160 ThermalCam

### 让你的 Magnity 热像仪，在新版 Android 上重获新生。

[English](README.md) · [繁體中文](README.zh-TW.md) · **简体中文**

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

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
| 🚀 **4× AI 超分辨率** | 针对热成像微调的 Real-ESRGAN 模型，通过 **ncnn + Vulkan** 在 GPU 上运行，把 160×120 变成清晰的 640×480。 |
| ✨ **Anime4K GPU 放大** | 实时 CNN shader，画面锐利干净。 |
| 📸 **拍照** | 一键保存为 PNG。 |
| 🎬 **录像** | 硬件 **HEVC/H.265** 编码，不支持时自动改用 H.264。 |
| 🚫 **无水印** | 你的图像就是你的。 |
| 👍 **精简竖屏界面** | 为现场单手操作而设计。 |

## 🚀 开始使用

1. 编译 App：
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

## 📜 许可

**非商业用途免费**——个人、研究、教育与业余用途均可。商业用途需另行取得书面许可，详见
[`LICENSE`](LICENSE)。第三方组件遵循其各自许可条款
（[`android2/THIRD_PARTY_NOTICES.md`](android2/THIRD_PARTY_NOTICES.md)）。Magnity / Elo SDK
为专有软件，本项目不授权也不分发。

温度读数仅供参考，本软件并非经过校准的测量仪器。

## ⭐ 让你的热像仪复活了吗？

**给这个仓库点个 Star**——这是我决定要不要继续打磨它的依据。
欢迎提 Issue 和 Pull Request。
