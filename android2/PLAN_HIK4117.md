# 海康 TB-4117（USB UVC 韌體）支援 — 計畫（2026-10-24）

## 已知（社群逆向，未在本專案實機驗證）

來源：[vilicvane/hik-tb4117](https://github.com/vilicvane/hik-tb4117) 的
`docs/hcusb-uvc-protocol.md`（從原廠 `libHCUSBSDK.so` 逆向，TB-4117-3/S 實機驗證）。
該 repo 沒有授權條款 → 只參考協議事實，程式自己寫。LiThermal（GPL-3.0）走 NCM 網路模式，
Android 一般 App 無法給 usb0 設固定 IP，不採用。

- USB `2BDF:0101`，UVC。視訊：bulk 端點，**永遠是 240×320 MJPEG**（相機自己上色、帶 OSD），
  不管 probe 要哪個格式；描述元另外宣稱 YUY2 / H264。感測器 160×120。
- 指令：UVC XU unit 10（`wIndex 0x0a00`）。selector 5 = 選命令 `{組, sub}`，組號 selector
  （1 system · 2 image · 3 thermal）收送資料；selector 6 = 錯誤碼。GET_LEN 回 4 bytes。
  每次插上要先做版本探測（GET_LEN + GET_CUR selector 4 = `"2.0"`）才解鎖；只在視訊串流時回應。
- 溫度：**2046**（組 3 sub 9，DoubleGet）→ JPEG + 120×160 f32 °C，與 OSD 一致；
  控制傳輸每次 ≤512 B、每片 5 B 片頭 → 一張約 1–2 s。2047 = ROI 最高溫（小熱點會被稀釋）。
- 2044/2045 體溫補償：預設開，皮膚段抬高約 3 °C → 要真實表面溫度須關。

## 方案

| | 即時畫面 | 溫度 | 狀態 |
|---|---|---|---|
| A | 相機的 MJPEG（25–30 fps） | 2046 每 1–2 s | 確定可行；MagViewer 的色盤 / DDE / Thermal SR 用不上 |
| B | 每幀溫度串流 | 同 MAG160 | **未知** — 2038/2039（熱成像流參數 `byVideoCodingType`）或 2048/2049（P2P）也許能切 |
| C | 白熱灰階 + 快照擬合 | 近似 | 備案 |

B 可行的話：160×120 與 MAG160 同尺寸，內建 SR 模型直接可用（`thermal_120x160`）。

## 第 1 步 — 診斷版 2.8-alpha1（本分支）

`usb/Hik4117Source.kt`，不用原廠 SDK（`UsbDeviceConnection` 直接收 bulk、下 XU）：

- 連線時寫進報告：bcdUSB、端點（bulk 封包 512 = 高速 / 64 = 全速）、XU、VS 格式與幀；
  probe/commit 回應（maxFrame / maxPayload）；第一幀的大小與類型；版本探測；
  唯讀查詢 2011 / 2036 / 4051 / 2030 / 2032 / 2038 / 2048 / 2044。
- 溫度畫面：持續跑 2046（約 1 fps），走 MagViewer 正常管線（120×160，可用 Thermal SR）。
  `stallRecovery = false`：快照慢、測試時暫停，不被當成斷線。
- **串流測試**（抽屜按鈕，約 1 分鐘）：`byVideoCodingType` 0..8 × 每個宣稱的格式，
  各量 1.5 s：fps、每幀大小、JPEG / H264 / raw、raw 的 u16 範圍與 f32 是否像 °C、KB/s。
  結束還原 2038 與 MJPEG；原值先存 prefs，中途當掉下次連線會還原。
- 寫入相機的只有：體溫補償（連線期間關、斷線還原）、測試中的 2038（測完還原）。
  發射率、FFC 這版不接（ε 顯示相機自己的值）。
- 報告在抽屜，「複製報告」= 報告 + Debug 即時診斷行。
- JVM 上用模擬的 4117（照協議文件）跑過：連線、2046、測試、還原、raw/無旗標 MJPEG 切幀。

### 給測試者

1. 裝 `MagViewer-2.8-alpha2-beta.apk`（「MagViewer β」，與正式版並存；2.8-alpha2 起 Android 10 以上）。
2. 插上 4117 → 允許「相機」權限 → 允許 USB。應出現溫度畫面（約 1 fps）。
3. 按 Debug 截圖一張；開抽屜 →「執行串流測試」→ 等約 1 分鐘 →「複製報告」傳回來。

## 下一步（看報告決定）

- 某個 coding 值讓串流變成 raw 且 f32 / u16 範圍像溫度、fps 接近 25 → 做方案 B（解析該格式）。
- 都還是 JPEG → 方案 A：MJPEG 即時顯示 + 2046 疊溫度（`ThermalSource` 要加「只有畫面」的路徑）。
- 2046 太慢 / 失敗 → 看錯誤碼與時間再議。
