# MagViewer 2.8-alpha4 (Lite Thermal SR model, Android 10+, HIK 4117 diagnostics)
**Alpha — a diagnostic build for testing, not a stable release.** Built by GitHub Actions
from the `ccr-1a3608d0-s97tvm` branch.

**This APK is “MagViewer β”** (`com.magnity.viewer.beta`, signed by the build server): it
installs **next to** MagViewer 2.7, not over it, with its own settings. If a β you built
yourself is installed, uninstall that first. It installs over 2.8-alpha1 … alpha3.

## New in 2.8-alpha4
- **Thermal SR model size: Full / Lite** — choose it under Thermal SR in the menu. Lite does
  about a quarter of the network work per frame: the phone stays cooler and the battery
  lasts longer, with a slightly softer picture (on 200 held-out thermal frames: 0.4 dB
  below Full, the same rate of invented hot spots). It is built in — nothing to download.
  (Medium is greyed out until that model is trained.)
- When Thermal SR cannot keep up with the camera (a Tiny2C on a mid-range phone), the app
  switches to Lite first and to Anime4K only if even Lite is too slow; pick a size again to
  keep it.
- **Load SR model (.zip)** files a model under its size by itself and switches to it.

## From 2.8-alpha3
- MAG160 on Android 10–12: the USB-permission listener of the factory SDK is registered the
  way those versions understand (it used a flag that only exists from Android 13).
- Code clean-up from the build checks (lint now runs on every build and must pass); no
  other change in behaviour.

## From 2.8-alpha2
- **Runs on Android 10 and later** (2.7 and 2.8-alpha1 needed Android 13). On older phones
  those showed “There was a problem parsing the package” / “解析包时出现问题”.
  Huawei phones on HarmonyOS 2–4 run Android apps and may now work too (not tested);
  HarmonyOS NEXT / 5 does not install Android APKs.
- Before Android 13 the app follows the system language (Android has no per-app language
  setting there), so the language choice is not shown.
- Geo-tag on Android 10: no crash when the location provider is switched on or off.

## From 2.8-alpha1
- **Hikvision TB-4117-3/S** (USB UVC firmware, `2BDF:0101`) is recognised as *HIK 4117* —
  a first look, to find out what the module can stream:
  - The temperature image comes from the module's radiometric snapshot (120×160 °C,
    **about 1 frame per second**) and goes through the usual palettes / Thermal SR.
  - On connecting, a report records the USB speed, the formats the module offers,
    firmware info and its settings.
  - **Run stream test** in the menu (~1 min) tries each thermal stream mode of the module
    with each USB format and records what it sends. If one of them carries temperature
    every frame, the 4117 can become a live camera like the others.
  - Changes to the module: body-temperature compensation (which adds ~3 °C to skin) is off
    while connected and back on when it disconnects; the stream test puts the stream mode
    back when it ends (or on the next connect, if it was interrupted).
  - Not in this build: emissivity (the module keeps its own, shown under Debug) and FFC
    (the module runs its own).
- The MAG160 and Tiny2C work as in 2.7.

## For testers
1. Install, plug in the 4117, allow **Camera** and then **USB** access.
2. Tap **Debug** and take a screenshot.
3. Open ☰ → **Run stream test** → wait about a minute → **Copy report**, and send the text.

## 测试步骤（简体中文）
1. 需要 Android 10 或以上。安装后插上 4117，先允许“相机”权限，再允许 USB 访问。
   应出现温度画面（约每秒 1 帧）。
2. 点 **Debug**，截一张全屏图。
3. 打开 ☰ 菜单 →“运行串流测试”→ 等约 1 分钟 →“复制报告”，把文字发回来。
4. 如果仍显示“解析包时出现问题”：请告知手机型号和 Android / 鸿蒙版本，并确认下载的文件
   完整（约 79 MB）。

The APK is ~79 MB because the Tiny2C's AC020 SDK brings its own native libraries.
