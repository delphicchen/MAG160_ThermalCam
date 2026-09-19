# 熱像 × 手機可見光 融合計畫（2026-09-17）

## 條件
- 可見光：手機主鏡頭（視野比熱像大、焦距不同）。
- 基線：兩鏡頭中心相距約 10 cm（直式時大致上下排列）。
- 物距：1.5 m ～ 無窮遠。

## 視差估算（熱像原生 160×120 像素，基線 10 cm）
視差 = f·B/Z。熱像水平視角未知，先以 35°/50°/56° 估：

| 物距 | 1.5 m | 2 m | 3 m | 5 m | 10 m | 30 m |
|---|---|---|---|---|---|---|
| HFOV 50° | 11.4 px | 8.6 | 5.7 | 3.4 | 1.7 | 0.6 |
| HFOV 35° | 16.9 px | 12.7 | 8.5 | 5.1 | 2.5 | 0.8 |

→ 1.5–5 m 內偏差可達畫面寬度 7–10 %（4× 顯示時 40+ px），**必須做物距補償**；
10 m 以外剩 1–2 px，可視為無窮遠。

## 架構（業界標準：出廠校正 + 物距補償，不做即時特徵比對）

對應關係：`H(Z) = K_t · (R − t·nᵀ/Z) · K_v⁻¹`
可拆成 `H(Z) = H∞ + (K_t · t / Z) ⊗ (nᵀ · K_v⁻¹)`：
- `H∞ = K_t R K_v⁻¹`：無窮遠對齊（比例、旋轉、主點差、視野差都在裡面）。
- 視差項只跟 `t / Z` 有關，方向固定，大小隨 1/Z 線性變化。

### P1　校正（電腦端 Python，一次性）
不用棋盤格也能做，因為物距範圍從 1.5 m 起：
1. **無窮遠 H∞**：拍遠處（>30 m）兩邊都看得到的熱點/熱邊界（夜間路燈、日照建築邊、
   屋頂排氣口），在兩張圖點 ≥ 6 組對應點 → `cv2.findHomography(RANSAC)`。
2. **視差方向與比例**：在已知距離（捲尺量，1.5 m、3 m）放熱源（熱水杯、暖暖包），
   各點 ≥ 4 組 → 解 `k`，使 `H(Z) ≈ H∞ + k·v/Z`（v = 像素位移方向）。
   實測 k 取代理論的 f·B，吸收基線量測誤差。
3. 熱像鏡頭畸變：先忽略；若畫面邊緣殘差 > 2 px，再用加熱棋盤格
   `cv2.calibrateCamera` 補（發射率對比：霧黑塗漆方格 + 拋光鋁方格，加熱）。
4. 輸出 `fusion_calib.json`：`H_inf`（3×3）、`k`、`v`、`rgb_size`、`thermal_size`、
   鏡頭 id（手機多鏡頭時綁定主鏡頭）、校正時的熱像旋轉/鏡像設定。
   工具放 `sr_train` 同層的 `fusion_calib/`：`pick_points.py`（matplotlib 點選配對）、
   `solve.py`（解 H∞ 與 k、印殘差）。
   App 端也提供「點選配對」模式，免電腦完成（P4）。

### P2　可見光取像（沿用 `android/` 的 CameraX 程式）
- 移植 `android/.../camera/RgbCamera.kt`：`ImageAnalysis`、`KEEP_ONLY_LATEST`、
  只取 Y 平面（~640×480），綁私有 LifecycleRegistry，跟著融合開關啟停。
- **鎖定主鏡頭與 1× 變焦**：邏輯相機切換廣角/超廣角會換內參，校正失效。
- CAMERA 權限：開啟融合時才要求（同 geo-tag 流程），拒絕就關回去。
- 對焦距離：`Camera2Interop` 掛 CaptureCallback，讀 `LENS_FOCUS_DISTANCE`（屈光度，
  Z = 1/d）；查 `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`：
  `CALIBRATED/APPROXIMATE` 才信任，`UNCALIBRATED` 一律改用手動。
  注意手機主鏡頭超焦距約 2–4 m，之後對焦距離會停在無窮遠附近 —— 恰好與視差 < 3 px
  的區間重疊，誤差可接受。

### P3　物距補償 + 融合顯示
- 物距來源（選單三選一，設定固化）：**Auto（對焦距離）/ 手動滑桿 / 固定無窮遠**。
  滑桿以 1/Z 均勻分格（1.5 m、2、3、5、10、∞），手感才線性。
  Auto 時對 1/Z 做指數平滑，避免對焦搜尋時畫面抖動。
- 每幀流程（處理執行緒，不進 UI）：
  1. 取最新 Y 平面（背景 executor 已旋轉到畫面方向）。
  2. `H(Z)` 反推：對熱像輸出格點的每個像素取樣可見光（查表：H 不變時快取 map，
     只在 Z 或設定改變時重建）。
  3. 模式（移植 `android/.../pipeline/Fusion.kt`）：
     - **MSX**：可見光 Sobel 邊緣 soft threshold，亮度加到熱像色盤上。
     - **Blend**：灰階可見光與熱像 α 混合。
     - **Wide search**：保留整個可見光畫面，熱像依 H(Z)⁻¹ 嵌入並描框。
  4. 輸出仍是 Bitmap → 截圖 / 錄影 / ROI 標記流程不變。
- 解析度：在 4× 顯示格（640×480）上合成；可見光邊緣比熱像細，4× 才看得出效果。
- 溫度讀值永遠取熱像原始格點，融合只作用在顯示層。

### P4　App 內校正（免電腦）
- 「校正融合」模式：上下並排熱像與可見光，使用者依序點同一點（放大鏡輔助），
  收 ≥ 6 組（遠景）+ ≥ 4 組（已知距離，輸入公尺）→ 裝置端解 H∞ 與 k（純 Kotlin
  DLT + RANSAC，點數少不需 OpenCV）→ 存 prefs。顯示每點殘差，> 3 px 標紅可刪。
- 快速修正：Wide search 模式下拖曳熱像框微調平移，寫回 H∞ 的平移項。

### P5（選用）自動殘差微調
- 只在 P3 結果附近 ±6 px 搜平移（可加 ±3 % 縮放），指標用**梯度方向一致性**或
  **互資訊**（跨感測器可用；不用 SIFT/ORB）。
- 背景每秒 1–2 次，縮小到 160×120 計算；場景梯度能量不足（平牆、天空）時不更新。
- 結果平滑後疊加到手動/Auto 的平移上；提供開關，預設關。

## 驗收
- 校正殘差：遠景點平均 < 1.5 px、已知距離點 < 2 px（熱像原生像素）。
- 1.5 m / 3 m / 10 m 各放熱源，MSX 輪廓與熱斑中心偏差 < 2 px。
- 處理迴圈維持 15 fps（融合開啟時每幀增加 < 15 ms，Dimensity 9200）。
- 融合開/關、旋轉、鏡像切換不閃退；校正資料在旋轉/鏡像改變時提示失效。

## 執行順序
P2 → P3（先用手動 H∞：比例/平移/旋轉滑桿，等同舊版）→ P1 → P4 → P5。
先讓畫面動起來，再把對齊做準。

## 目標手機：Xiaomi 14T Pro（Dimensity 9300+）
- 主鏡頭 23 mm 等效（水平視角約 75°，熱像約 50° → 可見光較寬，符合預期），有 OIS。
- 另有超廣角與 2.6× 長焦：CameraX 必須選實體主鏡頭並鎖 1×，否則廠商相機 HAL 可能在
  近距離/低光自動切鏡頭，校正失效。
- **OIS 會讓畫面相對熱像微幅漂移**：擷取時請求 `LENS_OPTICAL_STABILIZATION_MODE_OFF`
  （HAL 不一定接受，實測；不接受時殘差交給 P5）。
- 對焦距離校正等級未知，接上手機後查：
  `adb shell dumpsys media.camera | grep -iE "focusDistanceCalibration|minimumFocusDistance"`
  （0 = UNCALIBRATED、1 = APPROXIMATE、2 = CALIBRATED）。

## 待確認
- 熱像鏡頭實際 HFOV（校正時一併得出）。
- 主鏡頭的 `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` 等級（決定 Auto 物距可行性）。

## 進度
- 2026-09-17　P2 + P3（手動對齊）完成於分支 `feature/fusion-beta`，`./gradlew :app:assembleBeta`
  產出 `MagViewer β`（applicationId `com.magnity.viewer.beta`，可與正式版並存）。
  - `camera/RgbCamera.kt`、`pipeline/Fusion.kt` 自 `android/` 移植；加 OIS off 請求、1× 鎖定。
  - 設定固化（`fusion_*` prefs）；App 進背景即釋放相機。
  - 手動對齊：主畫面 ZOOM / X / Y 滑桿（選單「Align on live image」開啟），鏡頭旋轉在選單。
  - Wide 模式：`FrameResult.inset` 帶熱像位置，標記、SPOT 點擊、截圖/錄影都經它換算。
  - 未做：物距補償、點選校正（P1/P4）、自動微調（P5）。尚未實機測試。
- 2026-09-17　P3 物距補償完成（同一分支，未實機測試）。
  - 多距離校正：對已知距離物體用 ZOOM / X / Y 滑桿對齊，Align 面板「Save…」輸入公尺（或 ∞）
    存成樣本 `(invZ, zoom, dx, dy)`，JSON 存 `fusion_calib` pref；選單列出每筆殘差
    （換算成熱像原生像素）可單筆刪除或「Clear all」；同距離重存會覆蓋舊樣本。
  - 擬合在 `pipeline/FusionCalibration.kt`（純 Kotlin、無 Android 型別，可離機單測）：
    zoom 取樣本平均，dx/dy 對 invZ 做最小平方直線（單筆或同距離 → 常數）；
    無樣本時行為與舊版手動對齊完全一致。`fuse()` 每幀依目前 invZ 取擬合值，
    截圖/錄影自動沿用（對齊中仍顯示手動滑桿值，所見即所存）。
  - 物距來源三選一（選單，固化）：手動滑桿（1/Z 線性，1.5/2/3/5/10/∞ 刻度）／
    固定 ∞／Auto（鏡頭對焦距離）。實際距離顯示在主畫面 ε 旁（`≈3.0 m` / `∞`）。
  - 對焦診斷：`RgbCamera` 以 Camera2Interop 請求 `CONTROL_AF_MODE_CONTINUOUS_VIDEO`
    （analysis-only 預設不一定開），逐幀讀 `LENS_FOCUS_DISTANCE` 與 AF 狀態，
    綁定後讀 `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` / `MINIMUM_FOCUS_DISTANCE`；
    選單一行顯示 `Focus: APPROXIMATE · 0.33 D (3.0 m) · AF on` 或 `not reported`。
  - 信任條件：僅 APPROXIMATE/CALIBRATED 允許 Auto（UNCALIBRATED 時選項停用並註明原因）；
    對焦距離未回報時降回手動並在選單顯示原因。Auto 對 1/Z 做指數平滑（α=0.15/幀）
    避免 AF 拉鋸抖動。
  - 未做：P1/P4 點選校正、P5 自動微調；Auto 物距在 Xiaomi 14T Pro 的對焦校正等級
    與回報品質仍需實機確認（plan 內的 adb 查法）。

### P6　對焦體驗（2026-09-19 計畫）
問題：選單 Focus 數值一直跳；Auto focus 選項按不了；校正只能存在 App 內。
- **中央對焦 + 穩定讀值**（`RgbCamera`）：綁定後以 `Camera2CameraControl` 設
  `CONTROL_AF_REGIONS` = 畫面中央 20% 方框（`CONTROL_MAX_REGIONS_AF` > 0 才設）；
  AF 狀態為 PASSIVE/ACTIVE_SCAN（正在搜尋）時丟棄 `LENS_FOCUS_DISTANCE`，
  其餘讀值取最近 7 筆中位數再輸出。選單顯示搜尋中（`focusing…`）。
- **Auto 按不了的原因**：按鈕只在鏡頭回報 APPROXIMATE/CALIBRATED 時啟用；
  鏡頭若回報 UNCALIBRATED（讀值單位不是真實 1/m），按鈕一律停用 —— 與「有沒有做全距離校正」無關。
  改法：每筆校正樣本同時記下存檔當下的鏡頭讀值 `focusD`；UNCALIBRATED 鏡頭在
  ≥2 筆不同 `focusD` 的樣本後，用樣本做「鏡頭讀值 → 1/Z」分段線性映射（兩端線性外插）。
  Auto 按鈕只要有對焦讀值就可選；尚未學到映射時直接把讀值當屈光度用
  （實機 UNCALIBRATED 但讀值相當準），學到後改用映射。
- **超出校正範圍自動提示**：物距來源為 Auto 且開啟「自動提示」時，若鏡頭讀值
  （可信鏡頭用 1/Z；否則用 `focusD`）落在已存樣本範圍 ±0.1 之外並持續 1.5 s，
  自動在影像下方開啟 ZOOM/X/Y 對齊面板（以目前擬合值為起點），Save… 預填對焦推得的距離。
  按 Done 不存 → 暫停提示直到讀值回到範圍內。
- **校正匯出／匯入**：選單 Export… / Import…（SAF，JSON 檔）：
  `{"version":1,"rotation":…,"zoom":…,"dx":…,"dy":…,"samples":[…]}`；
  匯入會覆蓋目前樣本與旋轉／手動對齊值。
- 2026-09-19　P6 完成（未實機測試）：以上四項照計畫實作；樣本 JSON 多一個可選欄位
  `focusD`（舊資料相容）。選單 Focus 行顯示 `focusing…`／`centre AF`，未信任鏡頭只在
  學到映射後才顯示換算距離。匯出預設檔名 `fusion_calibration.json`。
- 2026-09-19　多距離內插：樣本數本就不限（同距離覆蓋），但套用原為整體最小平方直線 +
  zoom 平均，中間樣本無法精確對上。改為 `Fit.at(invZ)` 在相鄰樣本間分段線性內插
  zoom/dx/dy（每個存過的距離都精確吻合）；超出最近/最遠樣本時 zoom 固定、
  dx/dy 沿最小平方視差斜率延伸。選單殘差仍對直線計算，用來看哪筆樣本對歪。
