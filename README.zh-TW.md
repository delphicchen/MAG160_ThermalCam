<div align="center">

# 🔥 MAG160 ThermalCam

### 讓你的 Magnity 熱像儀，在新版 Android 上重獲新生。

[English](README.md) · **繁體中文** · [简体中文](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

<p align="center"><img src="docs/android_screenshot.jpg" width="320" alt="在 Xiaomi 14T Pro 上的即時熱像畫面"></p>

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
| 🎚️ **發射率可調** | ε 0.10–1.00 自由設定，皮膚、霧面塗漆、木材一鍵套用。 |
| 🚀 **4× AI 超解析** | 針對熱影像微調的 Real-ESRGAN 模型，透過 **ncnn + Vulkan** 在 GPU 上執行，把 160×120 變成清晰的 640×480。 |
| ✨ **Anime4K GPU 放大** | 即時 CNN shader，畫面銳利乾淨。 |
| 📸 **拍照** | 一鍵存成 PNG。 |
| 🎬 **錄影** | 硬體 **HEVC/H.265** 編碼，不支援時自動改用 H.264。 |
| 🌡️ **溫度碼流** | 拍照與錄影可另存 `.mgt` 溫度檔，記下每個像素的 °C；事後在 App 內開啟，任一幀、任一點都能讀出溫度。 |
| 🔀 **可見光融合（beta）** | 把手機相機的輪廓疊到熱像上（MSX 風格）、混合顯示，或在大視野可見光畫面中搜尋目標；可在多個距離校正對齊，並以十字標出可見光測距的位置。 |
| 📍 **可選 geo-tag** | 截圖與錄影寫入精確 GPS 位置——預設關閉，由你決定。 |
| 💾 **記住你的設定** | 色盤、溫度範圍、旋轉、放大方式、發射率等，重開 App 不必重設。 |
| 🚫 **無浮水印** | 你的影像就是你的。 |
| 👍 **精簡直式介面** | 為現場單手操作而設計。 |

## 🚀 開始使用

1. **從 [Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases) 下載 APK**（arm64、Android 13 以上），或自行編譯：
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

## 🗒️ 版本紀錄

完整說明與各版本 APK 都在
[Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases)。

### 2.4 — 2026-09-26
- **內建新的 Thermal SR 模型（v4）** —— 以 °C 感測器視角訓練，L1 + 頻譜 + 梯度損失，不用
  GAN（2.3 內建的是 GAN 訓練版）。在 38 張真實 MAG160 畫面上，憑空多出的熱點比上一輪 v3
  訓練少（每張 5.0 vs 5.9），最嚴重的一個也弱很多（52 vs 129 階）。
- 側邊選單 **Load SR model (.zip)…** 可匯入訓練好的模型包，不必重新編譯 App；
  *Use built-in* 切回內建。狀態列會顯示目前使用哪一個。
- 支援單通道與三通道 SR 模型；原生層加速，幀時間改放在 Debug 疊加層。
- 可見光融合加速：查表、平行處理、只複製一次畫面。

### 2.3 — 2026-09-24
- **溫度碼流** —— 拍照與錄影可另存 `.mgt` 檔，記下每個像素的 °C；用「Open temperature
  capture…」開回來：拖時間軸選幀、點畫面任一點讀出溫度（[格式說明](android2/docs/THERMAL_CAPTURE.md)）。
- **融合十字** 標出可見光相機的 AF 視窗 —— 也就是 Auto 物距實際量的那一塊。
- 焦點離開已校正距離時，改成**顯示一顆 Align 按鈕**，不再自己跳出對齊面板；範圍判定
  也從 1/Z 的固定級距改成 **±0.5 公尺**。
- 對齊值會在**已存的各個距離之間內插**；中央加權對焦、對焦距離自動帶入物距、校正檔
  匯出／匯入，以及輪廓疊圖的熱梯度遮罩。

### 2.2 — 2026-09-18
- **可見光融合（beta）** —— MSX 輪廓、混合、大視野搜尋；可在多個距離校正對齊，物距可用
  自動對焦／手動／∞。
- 雙邊濾波加速 4.7 倍；開啟 Thermal SR 時自動關閉空間降噪。

### 2.1 — 2026-09-17
- **4× AI 超解析** —— 針對熱影像微調的 Real-ESRGAN 模型內建於 APK，以 ncnn + Vulkan 在
  GPU 上執行。
- 發射率可調、可選 geo-tag，設定重開 App 不會消失。

### 2.0 — 2026-09-16
- 首個版本：15 fps 即時絕對溫度、MIN / MAX / SPOT、可自動或手動調整範圍的色條、拍照與
  HEVC 錄影、Anime4K GPU 放大、8 種色盤、旋轉／鏡像、時域＋空間降噪與手動 FFC。

## 📜 授權

**非商業用途免費**——個人、研究、教育與業餘用途皆可。商業用途需另行取得書面授權，詳見
[`LICENSE`](LICENSE)。第三方元件依其各自授權條款
（[`android2/THIRD_PARTY_NOTICES.md`](android2/THIRD_PARTY_NOTICES.md)）。Magnity / Elo SDK
為專有軟體，本專案不授權也不散布。

溫度讀值僅供參考，本軟體並非經校正的量測儀器。

## ⭐ 讓你的熱像儀復活了嗎？

**幫這個 repository 按個星星**——這是我決定要不要繼續打磨它的依據。
歡迎提 Issue 和 Pull Request。
