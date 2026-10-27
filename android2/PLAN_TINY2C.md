# Tiny2C（InfiRay AC020）支援 — 計畫（2026-10-17）

## 目標

MagViewer 同一支 APK 同時支援 MAG160 與 Tiny2C（256×192，AC020 UVC），插哪台自動辨識。
超分模型依幀尺寸自動從 GitHub release 下載。

SDK 原檔：`../Tiny-2C/SDK/Android/ANDROID_AC020_SDK_USB_IR_UVC_1.1.5_20241022/`
（範例 `simple/usbir/.../camera/CameraPreviewManager.java` 是主要參考）。

## Tiny2C 超分模型（2026-10-24 記錄，10-26 更新）

**2026-10-26：輕量 32/10 已訓練完成並內建**（`app/src/main/assets/model-lite/`，2.8-alpha4 起），
抽屜「超分模型大小」選「輕量」即可，Tiny2C 開完整版太慢時速度保護也會自動降到輕量。
在 200 張未參與訓練的 CIDIS 熱像上：PSNR 比完整版低 0.45 dB、假熱點數相當、CPU 上約 21 vs 32 ms
（細節見該資料夾 README）。**2026-10-27：中量 48/10 也已內建**（`assets/model-medium/`，2.8 起）：
PSNR 比完整版低 0.17 dB、CPU 約完整版的 0.68 倍，但假熱點約多 20%（10-26 第一次用到合併前的
notebook，練出來是 64 寬，沒採用）。**還沒做**：在 Tiny2C 實機量 Debug 的 `net` 時間與發熱。

以下為 10-24 時的紀錄：

- 已備好：訓練設定與 Run-all notebook（`sr_train/colab/train_thermal_sr_lite.ipynb`）、
  從內建 v4 剪枝的暖啟動（`truncate_pretrained.py --num-feat 32`）、打包腳本。
- 還沒做：Colab 訓練（80k iter）→ 匯出 256×192 / 192×256 → 實機看 Debug 的 `net` 時間 →
  發成 `sr-models` 的 `thermal_ncnn_256x192.zip` **v2**（目前 release 上只有 v1 = 內建 v4 權重）。
- 現況：Tiny2C 開 Thermal SR 跑的是內建 64/10，測試者手機約 12–15 fps，速度保護會退回
  Anime4K（見下方待辦 A）。
- 剩下步驟：`sr_train/README.md` 的「Lite 32/10 and medium 48/10」第 1–4 步（中量 48/10 同一套流程，
  notebook 設 `MODEL = 'medium'`）。→ 10-26 輕量、10-27 中量皆已完成並內建。
- App 端（2.8，已完成）：抽屜「超分模型大小」完整 / 中量 / 輕量讓使用者選，未安裝的是灰色；
  匯入或下載的模型依寬度自動歸檔並切過去；速度保護先降一檔，最輕仍太慢才退回 Anime4K。

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

## 2.7-alpha5（2026-10-22）— 內建 SR 模型通用所有尺寸

- alpha4 Debug 截圖：Tiny2C 正常（25 fps、cb=frames、len 正確、Δ≈0.3 °C、raw 隨場景變），
  但 `NCNN ×1 → bicubic 192×256`：選 768×1024 時找不到 192×256 模型 → 退回 Anime4K（使用者看到
  「跳回第一個」）。下載按鈕沒出現 → 對方在中國，抓 GitHub 的 models.json 多半失敗（失敗只記 log）。
- 修：ncnn 圖不綁尺寸，`NcnnUpscaler` 找不到該尺寸模型時用內建 160×120 那份；下載只在 release
  有比內建新（version > 1）的套件時才提示。
- 調色盤：截圖是 `gray`，下方「gray」按鈕就是調色盤選單（待對方確認換了有沒有變）。
- 溫度存疑：手指 11.9 °C、手比背景冷 → 待對方測額頭 / 熱水確認（可能是未做暖機補償，或手真的冰）。

## 2.7-alpha4（2026-10-21）— 診斷資訊

- 對方用 alpha3 有連上，但回報「超分無效、pseudocolor 無效」；程式碼看不出尺寸相關的錯，
  懷疑是畫面沒更新 / 溫度整片一樣，或 256×192 模型沒下載（退回 Anime4K）。
- Debug 浮層加：調色盤 · 超分設定 → 實際繪圖路徑與輸出尺寸；溫度範圍、顯示範圍、與上一幀的
  平均差 Δ；來源幀數；Tiny2C 另加 SDK 回呼數 / 過短幀 / 幀長、溫度原始值範圍、YUYV 的 Y 範圍。
- 下一步：依對方開 Debug 的截圖判斷。

## 2.7-alpha3（2026-10-21）— UVC 需要 CAMERA 權限

- 對方用 alpha2：等待畫面顯示 `3474:4321 UVC Camera`，狀態停在初始「未连接」→ connect() 從沒被呼叫。
- 原因（UsbManager 官方文件）：targetSdk ≥ P 的 app，USB_CLASS_VIDEO 裝置的 USB 權限只在持有
  CAMERA 時才會給，否則 requestPermission 直接拒絕、不跳視窗。MAG160 不是 UVC，所以沒遇過。
- 修：MainActivity 對 UVC 裝置先要 CAMERA 再要 USB；被拒 / USB 被拒時黃字顯示原因。
- 實際 id `0x3474:0x4321` 加進 KNOWN + device_filter（插上自動開 app）。

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

## 待辦建議（2026-10-23 記錄，使用者要求先記下，尚未實作）

### A. Tiny2C 開 Thermal SR 只有 12–15 fps

- 原因：alpha5 起 SR 也跑 256×192（像素 2.56× MAG160），處理迴圈是串行的 → fps = 1000 / 每幀 ms。
  alpha4 的 25 fps 是因為當時 SR 沒在跑。
- 內建模型 = SRVGGNetCompact：1→64 conv、10 層 64→64 conv、64→16 conv、PixelShuffle ×4 + nearest ×4
  殘差。每個**輸入**像素 ≈ 378k MAC，其中 10 層 body 佔 97%，與放大倍率無關。
  - 256×192 ≈ 18.6 GMAC/幀；160×120 ≈ 7.3 GMAC/幀。
  - **×2 模型只省 ~2% 網路計算**（最後一層 64→4 取代 64→16），只有後處理（調色盤＋拷貝、Bitmap）
    從 1024×768 變 512×384 省 4×。不值得單獨做 ×2。
  - 真正有效：**輕量模型**（num_feat 64→32 → body 計算量 ¼，約與 160×120 現況同級；或 num_conv 10→6）。
    要用 `sr_train/` 重訓 256×192 版，放 `sr-models` release。
- App 端（小改動）：SR 每幀耗時持續超過幀週期（例如 1.3× 1000/fps，取幾秒平均）→ 自動退回
  Anime4K 並跳提示；使用者可再手動切回。
- 實測（對方手機，2026-10-23，SR Vulkan 1-ch imported）：in 7.0 · dn 0.9 · rng 0.8 · **up 55.0
  （net 47.0 / post 7.7）** · bmp 2.5 → 約 66 ms/幀 ≈ 15 fps。瓶頸是網路本身（71 %）；×2 輸出只省
  post＋bmp 約 8 ms（→ ~17 fps），輕量模型（body ¼）估 net ~12 ms → 可達相機的 25 fps。
  2.7 的速度保護門檻 = 1000 / (25 × 0.65) ≈ 61.5 ms，這組數字會觸發退回 Anime4K。
- 2026-10-23 訓練設定已備好（未訓練）：使用者決定**只給 Tiny2C、沿用 MAG160 雜訊設定**。Colab 設
  `MODEL = 'lite'` → 32/10（98 k 參數、4.8 GMAC @256×192，約 64/10 的 ¼）；暖啟動 = v4 64/10
  （`android2/thermal_cam_model_20260925-2/thermal_sr_progress.zip` 裡的 net_g_80000）用
  `truncate_pretrained.py --num-feat 32` 依濾波器 L1 剪成 32 通道（本機實測 strict load OK，未微調前
  對 v4 PSNR ~19 dB）；匯出 256×192 / 192×256 → `thermal_ncnn_L1FFT_lite32.zip`；發布用
  `make_sr_package.py --size 256x192 --version 2`。步驟在 `sr_train/README.md`「Lite 32/10 and medium 48/10」。
- 現成替代：Anime4K 選 ×2 只跑一道 → 512×384，計算量小很多。

### B. MAG160 Mx（1.25 mm 四針）`calibration download failed: -2`

- 反組譯 `CNetComm::GetCaliFile`（arm64 `libcoresdk.so` 0x790ac）：回呼碼 −1 = 送指令失敗或大小不合理；
  **−2 = EP 0x84 的 `libusb_bulk_transfer` 中途回錯**（第二參數 = 失敗時進度 %）；−4 = 寫檔例外。
  → 不是 App 逾時，是 1.8 MB 校正檔傳到一半 USB 斷/錯。
- 推測：裸線無屏蔽的 1.25 四針線材 > OTG 供電 > 該型韌體不從 EP 0x84 給檔（此時進度會是 0）。
- 改法（小改動）：`MagDeviceWrapper.open` 的錯誤訊息帶上失敗時的進度 %；−2 時 dislink + 重新
  link/prepare 自動重試 2 次。
- 繞路：同一手機＋相機成功下載一次後就快取在 `<files>/cali/`，之後不再下載 → 換短的屏蔽線或有供電
  的 hub 成功一次即可。

## 階段 5 — 收尾

- README ×3（en / zh-CN / zh-TW）：支援機型、網路權限說明、Tiny2C SDK 列入 credits / THIRD_PARTY_NOTICES。
- 版號 2.7，照 release 流程發 `android-v2.7`；另建 `sr-models` release。

## 執行順序

1 → 2（拿到實機才能驗）→ 4 可與 2 平行（模型匯出 + 下載器用 MAG160 160×120 先驗）→ 3 → 5
