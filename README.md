<div align="center">

# 🔥 MAG160 ThermalCam

### Your Magnity thermal camera, reborn on modern Android.

**English** · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

<p align="center"><img src="docs/android_screenshot.jpg" width="320" alt="Live thermal view on a Xiaomi 14T Pro"></p>

---

## 😤 The problem

The Magnity **MAG-Mx / MAG-Cx** USB thermal camera (VID `0x833C`) is great hardware — but its
official apps **stopped working on Android 15+**. Plug it into a new phone and you get
nothing.

## 💡 The fix

**MAG160 ThermalCam** is a brand-new Android app for this camera, rebuilt from the ground up
by reverse-engineering the vendor SDK. Plug in, open, measure. On the phone you already own.

## ✨ Android app features

| | |
|---|---|
| 📱 **Works on Android 15+** | Runs where the factory MAG-Cx / MAG-Mx apps no longer do. |
| 🌡️ **Real absolute temperature** | Straight from the factory SDK — fully corrected, no manual calibration. |
| 🎯 **MIN / MAX / SPOT markers** | Find the hottest and coldest point instantly, plus a live colour bar and range. |
| 🎚️ **Adjustable emissivity** | Set ε from 0.10 to 1.00, with one-tap presets for skin, matte paint and wood. |
| 🚀 **4× AI super-resolution** | A thermal-tuned Real-ESRGAN model on the GPU via **ncnn + Vulkan** turns 160×120 into a crisp 640×480. |
| ✨ **Anime4K GPU upscaling** | Sharp, clean display with a real-time CNN shader. |
| 📸 **Snapshots** | One tap saves a PNG. |
| 🎬 **Video recording** | Hardware **HEVC/H.265** encoding with automatic H.264 fallback. |
| 🌡️ **Temperature captures** | Snapshots and recordings can also save a `.mgt` file holding every pixel's °C — reopen it in the app and read any position, in any frame. |
| 🔀 **Visible-light fusion (beta)** | Overlay the phone camera's edges on the thermal image (MSX-style), blend, or search in the wide visible view — with alignment calibrated at several distances, auto distance from centre-weighted lens focus (with a crosshair showing the patch it measures), and calibration export/import. |
| 📍 **Optional geo-tag** | Stamp precise GPS location into snapshots and videos — off until you turn it on. |
| 💾 **Remembers your setup** | Palette, range, rotation, upscaler, emissivity and more survive restarts. |
| 🚫 **No watermark** | Your images are yours. |
| 👍 **Compact portrait UI** | Designed for one-handed use in the field. |

## 🚀 Get started

1. **Download the APK** from [Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases) (arm64, Android 13+), or build it yourself:
   ```sh
   cd android2
   JAVA_HOME=/path/to/android-studio/jbr ./gradlew :app:assembleDebug
   ```
2. Connect the camera to your phone with a **USB-C OTG adapter**.
3. Open the app, accept the USB permission — you're streaming.

> **Note:** the vendor SDK (`libcoresdk.so` / the AAR files under `android2/lib/`) is **not**
> included in this repository. Supply it from your own copy of the vendor software.

## 🧰 Also in this repository

- **🐧 Linux desktop viewer** (`viewer.py`) — live 160×120 @ 15 fps in pure Python, bit-exact
  factory NUC, flat-field / bad-pixel / denoise pipeline, Planck-based °C readout.
  Quick start:
  ```bash
  pip install pyusb numpy pillow PySide6 matplotlib
  sudo cp 99-magnity-thermal.rules /etc/udev/rules.d/ && sudo udevadm control --reload-rules
  python3 viewer.py
  ```
- **🧠 Train your own thermal SR model** — a Colab pipeline in [`sr_train/`](sr_train/README.md).
- **🔬 Reverse-engineering notes** — the full USB protocol in [`PROTOCOL.md`](PROTOCOL.md) and
  the SDK / calibration write-up in
  [`android2/REVERSE_ENGINEERING.md`](android2/REVERSE_ENGINEERING.md).
- **`android/`** — an earlier Android port that runs the fully open, SDK-free pipeline.

## 🗒️ Changelog

Full notes and the APK for each version are in
[Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases).

### 2.4 — 2026-09-26
- **New built-in Thermal SR model (v4)** — trained on a °C sensor view with L1 + spectrum +
  gradient loss and no GAN. On 38 real MAG160 frames it invents fewer hot spots (5.0 vs 5.9
  per frame) and the worst one is far weaker (52 vs 129 levels); flat walls no longer grow
  fake texture.
- **Load SR model (.zip)…** in the drawer imports a trained model package without
  rebuilding the app; *Use built-in* goes back. The status line says which one is running.
- Runs both 1-channel and 3-channel SR models; faster native glue, frame timing moved to a
  Debug overlay.
- Faster visible-light fusion: table-driven, parallel, one frame copy.

### 2.3 — 2026-09-24
- **Temperature captures** — snapshots and recordings can also write a `.mgt` file holding
  every pixel's °C; *Open temperature capture…* replays it: scrub the frames, tap any
  position to read it ([format](android2/docs/THERMAL_CAPTURE.md)).
- **Fusion crosshair** over the visible camera's AF window — the patch the Auto object
  distance is measured on.
- Leaving the calibrated distances now **offers an Align button** instead of opening the
  alignment panel by itself, and the range test allows ±0.5 m instead of a fixed step in 1/Z.
- Alignment is **interpolated between the saved distances**; centre-weighted AF, focus-driven
  auto distance, calibration export/import, and a thermal-aware gate for the edge overlay.

### 2.2 — 2026-09-18
- **Visible-light fusion (beta)** — MSX edges, Blend and Wide search, with the alignment
  calibrated at several distances and object distance from auto focus / manual / ∞.
- Bilateral denoise 4.7× faster; spatial denoise switches off while Thermal SR is on.

### 2.1 — 2026-09-17
- **4× AI super-resolution** — a thermal-tuned Real-ESRGAN model bundled in the APK, run on
  the GPU with ncnn + Vulkan.
- Adjustable emissivity, optional geo-tag, and settings that survive restarts.

### 2.0 — 2026-09-16
- First release: live 15 fps absolute temperature, MIN / MAX / SPOT, colour bar with auto or
  manual range, snapshots and HEVC recording, Anime4K GPU upscaling, 8 palettes, rotation /
  mirror, temporal + spatial denoise and on-demand FFC.

## 📜 Licence

**Free for noncommercial use** — personal, research, educational and hobby use. Commercial use
needs a separate written licence; see [`LICENSE`](LICENSE). Third-party components keep their
own terms ([`android2/THIRD_PARTY_NOTICES.md`](android2/THIRD_PARTY_NOTICES.md)). The Magnity /
Elo SDK is proprietary and is neither licensed nor redistributed here.

Temperature readings are informational only; this is not a calibrated instrument.

## ⭐ Brought your camera back to life?

**Give the repository a star** — it's how I decide whether to keep polishing it.
Issues and pull requests are welcome.

---

## 🔗 友鏈

**友鏈:** [https://linux.do](https://linux.do)

非常感谢 LINUX DO 社区提供的交流平台 / Many thanks to the LINUX DO community for the great discussion platform.
