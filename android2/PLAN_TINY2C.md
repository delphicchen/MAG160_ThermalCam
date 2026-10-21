# Tiny2C（InfiRay AC020）支援 — 計畫（2026-10-17）

## 目標

MagViewer 同一支 APK 同時支援 MAG160 與 Tiny2C（256×192，AC020 UVC），插哪台自動辨識。
超分模型依幀尺寸自動從 GitHub release 下載。

SDK 原檔：`../Tiny-2C/SDK/Android/ANDROID_AC020_SDK_USB_IR_UVC_1.1.5_20241022/`
（範例 `simple/usbir/.../camera/CameraPreviewManager.java` 是主要參考）。

## 待確認（動手前）

- [x] 對方模組是 **USB 版**（使用者確認 2026-10-18）。
- [x] 實機 VID/PID：**`0x3474:0x4321`**（"UVC Camera"，對方用 alpha2 回報，2026-10-21），
      已加進 `Tiny2cSource.KNOWN` 與 `device_filter.xml`；`0x0A0A:0x2602` 留給其他 AC020 模組。
      （原註：廠商 filter 是抓全部 UVC，暫用 `0x0A0A:0x2602`（demo 註解點名，未證實）。）
      請對方用 Windows 裝置管理員或 demo APK 的 USB info 頁查；手動「連線」會連任何 UVC 裝置當備援。
- [x] SDK 授權：使用者決定 AAR 直接進 repo（`app/libs/ac020/`）。

## 階段 1 — 相機抽象層 — ✅ 已實作（2026-10-18）

`usb/ThermalSource.kt`（介面 + `ThermalSources.isKnown/isCandidate/forDevice`）；VM 的 `sdk` 改成
`ThermalSource`，connect 時依裝置換實作；MainActivity ATTACHED 只對 known id 要權限。

原始設計：

現況：`ViewerViewModel` 直接持有 `MagDeviceWrapper`，吃「w×h 毫°C `IntArray`」。

- 抽出 `ThermalSource` 介面（照 `MagDeviceWrapper` 現有的 public API 抽，不另起架構）：
  `open / start / stop / close / width / height / fps / awaitTemperatureData /
  getTemperatureData / probe / getFrameStats / sensorTemp / setEmissivity / triggerFfc`
  加 `capabilities`（有無校正下載、factory NUC、增益切換…），UI 依此隱藏不適用的項目。
- `MagDeviceWrapper : ThermalSource`，行為不變。
- `ViewerViewModel` 裡 `MagDeviceWrapper.isMagnity(...)` 的 4 處改成 `ThermalSources.detect(device)`。
- `MagDeviceWrapper.degC()` 的呼叫點（含自我測試 1550–1616 行）改走介面。

## 階段 2 — `Tiny2cSource` — ✅ 程式完成（2026-10-18），待實機驗證

`usb/Tiny2cSource.kt`。實測確認的事實：溫度 °C = raw/64 − 273.15（LibIRTemp 預設 scale=64）；
EMS = 發射率 × 16384；`libAC020sdk.so` NEEDED 幾乎全部 .so（MNN/OpenCV/HiAI），無法瘦身。
未做：TC2C 暖機補償（demo `TempCompensation`）、高低增益切換、sensorTemp。
實機要驗：VID/PID、initHandle 成功、溫度合理、FFC、發射率（env correct 預設 TA/TU 不明）。

原始設計：

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

## 2.7-alpha2（2026-10-20）— 不知道 VID/PID 也能連

- alpha1 的「手動 Connect 會試任何 UVC」其實沒有 UI 按鈕（`requestConnect` 沒人呼叫）→ 未知 id 連不上。
- 改成：插上 / 開 app 時 UVC 裝置也要 USB 權限（Android 的權限框就是使用者同意），2 s 輪詢會試；
  開不了或 8 s 沒幀的未知裝置記為 rejected，直到重插。
- 第一幀到達就把 VID/PID 記進 `tiny2c_ids`（`ThermalSources.learned`），之後當已知 id。
- 等待畫面大字列出所有 USB 裝置的 VID:PID + 狀態，可一鍵複製；連線後 fps 旁顯示 `USB 0A0A:2602 · Tiny2C`。
- 仍做不到：未知 id 插上時自動開 app（manifest filter 要寫死）。

## 階段 3 — 解析度相關 — ✅ 已實作（2026-10-18）

- 寫死尺寸只剩 UI 的 `?: 160/120` fallback（無幀時），處理管線本身不綁尺寸。
- 熱像焦距改成 `ThermalSource.focalPx`（MAG160 255 實測；Tiny2C 266.6 由規格 51.3°/256 px 推算）。
- 融合對位（zoom/dx/dy/校正樣本）依 `cameraKind` 分開存：換相機時舊的存到 `<key>@<kind>`，
  載入新相機的（首次為預設值）。
- MAG SDK smoke test 只在上次連的是 MAG160 時顯示。

原始設計：

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
