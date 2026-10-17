# Tiny2C（InfiRay AC020）支援 — 計畫（2026-10-17）

## 目標

MagViewer 同一支 APK 同時支援 MAG160 與 Tiny2C（256×192，AC020 UVC），插哪台自動辨識。
超分模型依幀尺寸自動從 GitHub release 下載。

SDK 原檔：`../Tiny-2C/SDK/Android/ANDROID_AC020_SDK_USB_IR_UVC_1.1.5_20241022/`
（範例 `simple/usbir/.../camera/CameraPreviewManager.java` 是主要參考）。

## 待確認（動手前）

- [ ] 對方模組是 **USB 版**（UVC 出圖），不是 DVP/MIPI 裸機芯。
- [ ] 實機 VID/PID（`adb shell` 插上後 `lsusb` / UsbManager log），寫進 `device_filter.xml`。
- [ ] SDK 授權：AAR 能否放進公開 repo、隨 release 散布。不行的話改成 `android2/lib/` 本地放、
      `.gitignore` 掉，release APK 照包（需廠商同意散布二進位）。

## 階段 1 — 相機抽象層

現況：`ViewerViewModel` 直接持有 `MagDeviceWrapper`，吃「w×h 毫°C `IntArray`」。

- 抽出 `ThermalSource` 介面（照 `MagDeviceWrapper` 現有的 public API 抽，不另起架構）：
  `open / start / stop / close / width / height / fps / awaitTemperatureData /
  getTemperatureData / probe / getFrameStats / sensorTemp / setEmissivity / triggerFfc`
  加 `capabilities`（有無校正下載、factory NUC、增益切換…），UI 依此隱藏不適用的項目。
- `MagDeviceWrapper : ThermalSource`，行為不變。
- `ViewerViewModel` 裡 `MagDeviceWrapper.isMagnity(...)` 的 4 處改成 `ThermalSources.detect(device)`。
- `MagDeviceWrapper.degC()` 的呼叫點（含自我測試 1550–1616 行）改走介面。

## 階段 2 — `Tiny2cSource`

- 引入 3 個 AAR：`libAC020sdk_USB_IR_UVC_1.1.5`、`libcommon_1.2.0`、`libirutils_1.2.1`；
  `abiFilters` 只留 `arm64-v8a`，`packaging.jniLibs` 排除用不到的 MNN/HiAI/dual* 試試看能否瘦身
  （OpenCV 17 MB 可能被 libAC020sdk 依賴，實測決定）。
- 出圖格式 `YUYV_AND_TEMP_OUTPUT`，UVC 串流 256×386：
  `[0, 192)` YUYV 影像（丟棄）、`[192, 194)` 信息行、`[194, 386)` 溫度 Y16。
- 溫度換算：`raw / 64 − 273.15` °C → 乘 1000 存成毫°C，與 MAG 路徑一致。
- 幀回呼在 SDK 執行緒：照 `MagDeviceWrapper` 的 lock + `latestTemp` 模式交給處理迴圈。
- 指令（`IrcmdEngine`，參考 `DeviceIrcmdControlManager.java`）：快門/FFC、發射率、
  高低增益切換（-15~150 / 50~550 °C）、信息行的防灼傷旗標。
- `device_filter.xml` 加 Tiny2C 的 VID/PID。

## 階段 3 — 解析度相關

- 所有寫死 160×120 的地方 grep 出來改成跟著 source 走（`grep -rn "160\|120" app/src/main/java`）。
- 融合校正（`FusionCalibration`）：校正檔按相機型號分開存；Tiny2C FOV 51.3×39.6° 需重新跑校正精靈。
- Mag 專屬功能（校正下載、factory NUC、DDT）依 `capabilities` 隱藏。

## 階段 4 — 超分模型自動下載

### 模型

- SRVGGNetCompact 是全卷積網路，**權重跟輸入尺寸無關**；ncnn 圖只是匯出時固定尺寸。
  所以第一步不用重訓：用現有 checkpoint `export_onnx.py --size 256x192`（和 192x256）重新匯出即可。
- 之後想針對 Tiny2C 的雜訊（NETD ≤50 mK）調優再上 Colab 重訓（notebook 加尺寸參數）。
- 效能注意：4× 輸出 1024×768，運算量是 160×120 的 2.56 倍，手機上要實測 fps；不夠快就改出 2× 模型。

### 發布

- 固定 tag `sr-models`（不跟 app 版本綁），asset：
  - `thermal_ncnn_256x192.zip`（內含 `thermal_256x192_fp16.*` 與 `thermal_192x256_fp16.*`，
    沿用現有 `SrModelFiles.install` 的 zip 格式）
  - `models.json`：`{ "256x192": { "file": "...zip", "version": 1, "sha256": "..." }, ... }`
- 之後新增其他解析度，只要上傳 zip、更新 `models.json`，不用發新 APK。

### App 端 — ✅ 已實作（2026-10-17，待實機驗證）

`pipeline/SrModelDownloader.kt`、`SrModelFiles.install(merge = true)`、VM `checkSrRemote /
downloadSrModel / cancelSrDownload`、抽屜進度條；打包腳本 `sr_train/scripts/make_sr_package.py`。
與下方原始設計的差異：只在抽屜打開時才抓 `models.json`。

`sr-models` release 已建（2026-10-17，`--latest=false`）：`thermal_ncnn_256x192.zip` v1。
ncnn 圖不綁尺寸（Input 無 shape、Interp 用 ×4 scale，兩方向 param/bin 完全相同），所以 v1
就是內建 v4 權重改檔名，不必重訓。MAG160 有內建模型，按鈕不會出現；端到端測試要等 Tiny2C 實機。

- Manifest 加 `INTERNET`（目前完全沒有網路權限 → README 要註明只用於下載模型）。
- 新增 `SrModelDownloader`（`Dispatchers.IO`、`HttpURLConnection`，不加 OkHttp 依賴）：
  1. 幀尺寸確定後，若 `NcnnUpscaler.isInstalled(w, h)` 為 false → 抓 `models.json`
  2. 有對應尺寸 → 抽屜顯示「下載 256×192 超分模型（x MB）」，使用者按了才下載（不偷跑流量）
  3. 下載 → 驗 sha256 → `NcnnUpscaler.importPackage()` → `ncnnReload = true`
  4. 已安裝版本比 `models.json` 舊 → 提示更新
- 進度與錯誤走現有 `notice` / `status` 字串。

## 階段 5 — 收尾

- README ×3（en / zh-CN / zh-TW）：支援機型、網路權限說明、Tiny2C SDK 列入 credits / THIRD_PARTY_NOTICES。
- 版號 2.7，照 release 流程發 `android-v2.7`；另建 `sr-models` release。

## 執行順序

1 → 2（拿到實機才能驗）→ 4 可與 2 平行（模型匯出 + 下載器用 MAG160 160×120 先驗）→ 3 → 5
