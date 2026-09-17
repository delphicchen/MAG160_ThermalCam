<div align="center">

# 🔥 MAG160 ThermalCam

### 讓你的 Magnity 熱像儀，在新版 Android 上重獲新生。

[English](README.md) · **繁體中文** · [简体中文](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

---

## 😤 痛點

Magnity **MAG-Mx / MAG-Cx** USB 熱像儀（VID `0x833C`）硬體很好，但原廠 App
**在 Android 15 以上已經無法使用**。插上新手機，什麼都沒有。

## 💡 解法

**MAG160 ThermalCam** 是專為這台熱像儀全新打造的 Android App，透過逆向工程原廠 SDK
從零重寫。插上、打開、量測——用你手上現有的手機就行。

## ✨ Android App 功能

| | |
|---|---|
| 📱 **支援 Android 15+** | 原廠 MAG-Cx / MAG-Mx 跑不動的地方，它可以。 |
| 🌡️ **真實絕對溫度** | 直接取自原廠 SDK，已完整校正，免手動校準。 |
| 🎯 **MIN / MAX / SPOT 標記** | 一眼找出最熱、最冷點，搭配即時色條與溫度範圍。 |
| 🚀 **4× AI 超解析** | 針對熱影像微調的 Real-ESRGAN 模型，透過 **ncnn + Vulkan** 在 GPU 上執行，把 160×120 變成清晰的 640×480。 |
| ✨ **Anime4K GPU 放大** | 即時 CNN shader，畫面銳利乾淨。 |
| 📸 **拍照** | 一鍵存成 PNG。 |
| 🎬 **錄影** | 硬體 **HEVC/H.265** 編碼，不支援時自動改用 H.264。 |
| 🚫 **無浮水印** | 你的影像就是你的。 |
| 👍 **精簡直式介面** | 為現場單手操作而設計。 |

## 🚀 開始使用

1. 編譯 App：
   ```sh
   cd android2
   JAVA_HOME=/path/to/android-studio/jbr ./gradlew :app:assembleDebug
   ```
2. 用 **USB-C OTG 轉接頭**把熱像儀接到手機。
3. 打開 App、允許 USB 權限——畫面就出來了。

> **注意：**原廠 SDK（`libcoresdk.so` 及 `android2/lib/` 下的 AAR 檔）**不包含**在本
> repository 中，請從你自己持有的原廠軟體取得。

## 🧰 本專案還包含

- **🐧 Linux 桌面版**（`viewer.py`）——純 Python 即時 160×120 @ 15 fps、與原廠逐位元一致的
  NUC、平場 / 壞點 / 降噪處理、基於 Planck 的 °C 讀值。快速開始：
  ```bash
  pip install pyusb numpy pillow PySide6 matplotlib
  sudo cp 99-magnity-thermal.rules /etc/udev/rules.d/ && sudo udevadm control --reload-rules
  python3 viewer.py
  ```
- **🧠 訓練你自己的熱影像超解析模型**——Colab 流程在 [`sr_train/`](sr_train/README.md)。
- **🔬 逆向工程筆記**——完整 USB 協定見 [`PROTOCOL.md`](PROTOCOL.md)，SDK 與校正分析見
  [`android2/REVERSE_ENGINEERING.md`](android2/REVERSE_ENGINEERING.md)。
- **`android/`**——較早期的 Android 移植版，使用完全開源、不依賴 SDK 的處理流程。

## 📜 授權

**非商業用途免費**——個人、研究、教育與業餘用途皆可。商業用途需另行取得書面授權，詳見
[`LICENSE`](LICENSE)。第三方元件依其各自授權條款
（[`android2/THIRD_PARTY_NOTICES.md`](android2/THIRD_PARTY_NOTICES.md)）。Magnity / Elo SDK
為專有軟體，本專案不授權也不散布。

溫度讀值僅供參考，本軟體並非經校正的量測儀器。

## ⭐ 讓你的熱像儀復活了嗎？

**幫這個 repository 按個星星**——這是我決定要不要繼續打磨它的依據。
歡迎提 Issue 和 Pull Request。
