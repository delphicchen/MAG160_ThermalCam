<div align="center">

# 🔥 MAG160 ThermalCam

### 開源的手機熱成像程式——一個 App 支援多款熱成像模組。

[English](README.md) · **繁體中文** · [简体中文](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Sensor](https://img.shields.io/badge/sensor-160×120%20%40%2015%20fps-orange)
![GPU](https://img.shields.io/badge/AI%20upscale-ncnn%20%2B%20Vulkan-purple)
![License](https://img.shields.io/badge/license-noncommercial-blue)

</div>

<p align="center"><img src="docs/android_screenshot.jpg" width="320" alt="在 Xiaomi 14T Pro 上的即時熱像畫面"></p>

---

## 🎯 目標

**MAG160 ThermalCam** 的目標是成為**開源的手機熱成像程式**，盡可能支援多款 USB 熱成像
模組：把模組插上你現有的手機，打開同一個 App 就能量測——不論哪款模組，都有一樣的色盤、
標記、超解析、錄影與融合功能。

## 📷 支援的模組

| 模組 | 解析度 | 狀態 |
|---|---|---|
| Magnity **MAG160 / MAG-Mx / MAG-Cx** | 160×120 | ✅ 支援（原廠 SDK） |
| InfiRay **Tiny2C**（AC020） | 256×192 | ✅ 支援（原廠 AC020 SDK） |
| 海康 **TB-4117-3/S**（USB） | 160×120 | 🧪 診斷支援（2.8） |

## 🤝 一起支援更多模組

每款模組的通訊協定都不一樣。有原廠的 **Android SDK** 或**協定文件**，就能很快加上支援；
沒有的話得先逆向分析，要花上很多倍的時間。如果你是模組廠商或經銷商，或手上有某款模組的
SDK、文件或可供測試的實機，歡迎[開 issue](https://github.com/delphicchen/MAG160_ThermalCam/issues) 聯繫——這是讓更多模組獲得支援最直接
的方式。

## 😤 緣起

Magnity **MAG-Mx / MAG-Cx** USB 熱像儀（VID `0x833C`）硬體很好，但原廠 App
**在 Android 15 以上已經無法使用**。插上新手機，什麼都沒有。於是透過逆向工程原廠 SDK，
為它從零重寫了這個 App，之後再擴展到其他模組。

## ✨ Android App 功能

| | |
|---|---|
| 📱 **支援 Android 15+** | 原廠 MAG-Cx / MAG-Mx 跑不動的地方，它可以。 |
| 🌡️ **真實絕對溫度** | 直接取自原廠 SDK，已完整校正，免手動校準。 |
| 📷 **一個 App 多款模組** | Magnity MAG160 / MAG-Mx、InfiRay **Tiny2C**（256×192），以及測試中的海康 **TB-4117** — 插哪台都會自動辨識。 |
| 🎯 **MIN / MAX / CTR / SPOT 標記** | 一眼找出最熱、最冷點，中心十字直接讀溫，搭配即時色條與平滑跟隨的自動範圍。 |
| 🔍 **細節增強（DDE）** | 可選的商用熱像儀風格：帶出局部對比與邊緣、平坦區更乾淨 — 只影響畫面，不影響讀值。 |
| 🎚️ **發射率可調** | ε 0.10–1.00 自由設定，皮膚、霧面塗漆、木材一鍵套用。 |
| 🚀 **4× AI 超解析** | 針對熱影像微調的 Real-ESRGAN 模型，透過 **ncnn + Vulkan** 在 GPU 上執行，把 160×120 變成清晰的 640×480。 |
| ✨ **Anime4K GPU 放大** | 即時 CNN shader，畫面銳利乾淨。 |
| 📸 **拍照** | 一鍵存成 PNG。 |
| 🎬 **錄影** | 硬體 **HEVC/H.265** 編碼，不支援時自動改用 H.264。 |
| 🌡️ **溫度碼流** | 拍照與錄影可另存 `.mgt` 溫度檔，記下每個像素的 °C；事後在 App 內開啟，任一幀、任一點都能讀出溫度。 |
| 🔀 **可見光融合（beta）** | 把手機相機的輪廓疊到熱像上（MSX 風格）、混合顯示，或在大視野可見光畫面中搜尋目標；可用校正精靈在多個實量距離校正對齊（直線擬合），並以十字標出可見光測距的位置。 |
| 📍 **可選 geo-tag** | 截圖與錄影寫入精確 GPS 位置——預設關閉，由你決定。 |
| 🌐 **介面語言** | English、繁體中文、简体中文，在側邊選單切換（也可跟隨系統）。 |
| 💾 **記住你的設定** | 色盤、溫度範圍、旋轉、放大方式、發射率等，重開 App 不必重設。 |
| 🚫 **無浮水印** | 你的影像就是你的。 |
| 👍 **精簡直式介面** | 為現場單手操作而設計。 |

## 🚀 開始使用

1. **從 [Releases](https://github.com/delphicchen/MAG160_ThermalCam/releases) 下載 APK**（arm64；2.8 起 Android 10 以上，2.7 要 Android 13 以上），或自行編譯：
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

### 2.8 — 2026-10-27
- **支援 Android 10 以上**（2.7 要 Android 13）。鴻蒙 2–4 可能可用（未測），鴻蒙 NEXT / 5 無法安裝
  APK。Android 13 以前介面語言跟隨系統。
- **超分模型大小：完整 / 中量 / 輕量**（選單內）— 內建中量與輕量，每幀運算約 1/2、1/4：手機比較不燙、
  省電，畫面略軟。Thermal SR 跟不上時逐級降一檔，最輕仍不行才改用 Anime4K。**載入 SR 模型（.zip）**
  會自動依大小歸檔。
- **最小溫寬（自動範圍）**，預設 1.0 °C，選單可調 0.2–3 °C 並附說明：溫差很小的場景不再把感測器雜訊
  撐成斑塊和假紋理。錄下的 `.mgt` 回放也套用同樣範圍。
- **海康 TB-4117-3/S（USB）診斷支援**：用模組的測溫快照顯示溫度畫面（每秒數幀），選單「執行串流測試」
  可產生報告回傳。
- 修正 MAG160 在 Android 10–12 的 USB 權限；Android 10 開關定位時地理標記不再閃退。Debug 資訊顯示
  App 的 CPU 使用率，以及手機有開放時的 GPU 使用率。

### 2.7 — 2026-10-23
- **支援 InfiRay Tiny2C**（AC020 UVC，256×192），與 MAG160 同一個 App，透過原廠 AC020 Android
  SDK 1.1.5：溫度、FFC 與發射率。UVC 相機需要先給 **相機** 權限才能取得 USB 存取 — 兩個都要允許。
  尚未完成：原廠 30 秒暖機補償、高低增益切換（固定高增益，−15…150 °C）。
- **Thermal SR 支援任何畫面尺寸** — 內建模型也能跑 Tiny2C。跟不上相機（低於約 ⅔ 幀率）時會自動
  改用 Anime4K 一次並說明原因；再選一次 Thermal SR 就會保留。
- **細節增強（DDE）**（側邊選單）— 帶出局部對比與邊緣、平坦區更乾淨。只影響畫面；開啟時顏色不再與
  溫度成正比。
- **CTR** — 中心十字與該點溫度（中心像素平均），顯示在讀數列，也會畫進截圖與錄影。點畫面下方的
  CTR 可隱藏。
- **自動範圍更穩** — 色條範圍約 0.4 秒平滑跟上，不再每幀跳動，熱物體進出畫面時不會整張閃。
- **MAG-Mx 校正檔下載自動重試** — 首次連線下載中斷時（錯誤 −2：USB 傳輸失敗，常見於細的四針線材
  或 OTG 供電不足），會重新連線再試兩次，錯誤訊息也會顯示下載到幾 %。成功一次後就會快取，不再下載。
- Debug 疊加層顯示每幀的繪製路徑、尺寸、幀間變化與 Tiny2C SDK 計數；簡體中文介面的色盤與放大
  方式改用中文名稱。

### 2.6 — 2026-10-12
- **校正精靈** —— 一步步完成融合校正：遠處一點，再 1 m、0.5 m、0.3 m。先輸入實量距離，X / Y
  會自動擺到直線預測的位置，只需微調；ZOOM 由手機鏡頭資料自動算出。結果頁會標出偏離直線超過
  3 px 的點供重做，按「完成」才會取代舊校正。
- **近距離疊圖更穩** —— 校正有 3 個以上距離時，疊圖改用最佳擬合直線（對 1/Z），不再逐點穿過
  每個儲存點，單一點沒對好不會再把整條曲線拉歪；既有校正同樣適用。
- 與其他點矛盾的鏡頭對焦讀值（有些鏡頭在太近或素面目標時會讀錯）不再放進自動對焦的距離對照表。
- 抽屜新增對焦診斷：AF 狀態、相機實際使用的對焦區（畫在畫面上）與目前使用的實體鏡頭；
  除錯按鈕可一次存 10 幀融合資料。

### 2.5.2 — 2026-10-10
- **首次連線修正** —— 手機第一次連某台熱像儀時會出現 `startProcessImage failed`：相機的校正檔
  （約 1.8 MB）還在下載，App 就開始取像。現在會等下載完成，並在狀態列顯示進度。

### 2.5.1 — 2026-09-29
- **閃退修正** —— 串流中拔掉熱像儀不再閃退（SDK 與 App 可能重複釋放同一個 USB 連線）；
  插著相機啟動 App、快速拔插時舊連線尚未關閉就重新連線，也不再閃退。
- 快門（FFC）或快速轉動後，畫面不再拖影約 2 秒。

### 2.5 — 2026-09-27
- **介面語言** —— English、繁體中文、简体中文，可在側邊選單或 Android 的 App 語言設定切換；
  選「跟隨系統」則依手機語言。切換立即生效，相機不會斷線。量測與產品名詞（MIN / MAX /
  CTR / SPOT、FFC、ε、Thermal SR、Anime4K、MSX…）在各語言都保留英文。

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
