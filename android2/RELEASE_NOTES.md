Open-source thermal camera app for Android phones — one app for several USB thermal modules:
the Magnity MAG160 / MAG-Mx, the InfiRay Tiny2C and, in testing, the Hikvision TB-4117.

![MagViewer on a Xiaomi 14T Pro](https://raw.githubusercontent.com/delphicchen/MAG160_ThermalCam/main/docs/android_screenshot.jpg)

## New in 2.8
- **Runs on Android 10 and later** (2.7 needed Android 13; older phones showed “There was a
  problem parsing the package”). Huawei phones on HarmonyOS 2–4 run Android apps and may work
  too (not tested); HarmonyOS NEXT / 5 does not install Android APKs. Before Android 13 the
  app follows the system language.
- **Thermal SR model size: Full / Lite** — under Thermal SR in the menu. The built-in **Lite**
  model does about a quarter of the network work per frame: the phone stays cooler and the
  battery lasts longer, with a slightly softer picture (on 200 held-out thermal frames:
  0.45 dB below Full, the same rate of invented hot spots). When Thermal SR cannot keep up
  with the camera, the app switches to Lite first and to Anime4K only if even Lite is too
  slow. **Load SR model (.zip)** files a model under its size by itself.
- **Minimum span (auto range)**, 1.0 °C by default, adjustable 0.2–3 °C in the menu with an
  explanation. A nearly uniform scene no longer gets stretched over the whole palette, which
  painted the sensor's own noise as blotches and false texture; it now sits in the middle of
  the palette. Recorded `.mgt` captures play back with the same range. Manual range is not
  limited.
- **Hikvision TB-4117-3/S (USB, `2BDF:0101`) — diagnostic support.** Recognised as
  *HIK 4117*; the temperature image comes from the module's radiometric snapshot (120×160 °C,
  a few frames per second). **Run stream test** in the menu records what the module can stream
  — please send the copied report if you have one. Emissivity and FFC are not wired up yet.
- MAG160 on Android 10–12: the factory SDK's USB-permission listener is registered the way
  those versions understand. Geo-tag on Android 10 no longer crashes when location is turned
  on or off.

Installs over 2.7 (same signing key). The APK is ~79 MB because the Tiny2C's AC020 SDK
brings its own native libraries.

## 2.8 新功能（中文摘要）
- **支援 Android 10 以上**（2.7 要 Android 13）。鴻蒙 2–4 理論上可用（未測），鴻蒙 NEXT / 5 無法安裝 APK。
- **超分模型大小：完整 / 輕量**，內建輕量模型：每幀運算約 1/4，比較不燙、省電，畫面略軟。跟不上相機時先改用輕量，再不行才用 Anime4K。
- **最小溫寬（自動範圍）**，預設 1.0 °C，選單可調 0.2–3 °C 並附說明：溫差很小的場景不再把雜訊撐成斑塊和假紋理。
- **海康 TB-4117（USB）診斷支援**：可顯示溫度畫面（每秒數幀），選單「執行串流測試」可產生報告，歡迎回傳。
- 可直接覆蓋安裝 2.7（簽名相同）。
