# Tiny1C / Tiny1B（InfiRay IRUVC）支援 — 計畫（2026-11-11）

## 目標

MagViewer 同一支 APK 支援 InfiRay Tiny1C 與 Tiny1B（256×192 VOx，USB UVC），插上自動辨識。
使用者手上有兩台實機可測。

SDK 原檔（專案根目錄，很大，別整包解）：
- `Tiny1C-SDK.zip` → `SDK/Android/ANDROID_IRUVC_SDK_2407111954_1.4.2_STANDARD.zip`（USB 版，採用）。
  同包的 `ANDROID_SDK_SPI_IR_1.2.6`（SPI 直連）、`RTOS`、`Win_Linux/AC010_256` 與本 app 無關。
- `Tiny1-B_sdk.zip` → 2020 年舊 SDK（`com.zz.infisense` demo + libuvc），只拿來對照：
  ID `0x0BDA:0x3901`、幀 256×384、溫度 raw/16 K、demo 以 192 寬 × 256 高畫圖。

## 做法（已完成，未上實機）

- [x] `app/libs/iruvc/libusbirsdk_1.4.2_2407111936_standard.aar`；Java 套件 `com.energy.iruvc`
      與 AC020 的不衝突。`libomp.so` 兩邊都有且**版本不同** → `pickFirsts`，只打包一份（風險見下）。
- [x] `usb/Tiny1Source.kt`：照 `Tiny2cSource` 的模式；參考 SDK sample `camera/IRUVC.java`。
      UVC 開 256×384（IMAGE_AND_TEMP_OUTPUT，模組預設），下半 192 行是溫度 u16 LE。
      幀末 1 byte = 1 表示 SDK 要求重啟 USB → 丟棄並計數。
- [x] 溫度刻度逐幀自動選：平均 raw ≥ 14000 → ÷64（Tiny1C），否則 ÷16（Tiny1B demo）。
- [x] 幾何：Tiny1C 256×192；Tiny1B 先照 demo 用 192×256。Debug 的 `rowΔ 256:… 192:…`
      較小的那個才是真正的列寬，實測後再定。
- [x] FFC：Tiny1B `tc1bShutterManual()`，其他 `updateOOCOrB(B_UPDATE)`（同 sample）。
- [x] 發射率：`TPD_PROP_EMS` = ε × 128（1..128）。
- [x] 分派：VID 0x0BDA（Realtek 橋接）的 UVC 裝置 → `Tiny1Source`；PID 0x3901 = Tiny1B。
      `device_filter.xml` 加 vendor-id 3034（不限 PID）。

## 實機測試要看的（Debug 疊圖第二段）

1. 是否辨識成 Tiny1C / Tiny1B（左上 VID:PID · 型號）。Tiny1C 的 PID 目前未知。
2. `cb=` 有增加、`short=` / `restart=` 為 0、`len=` 是否 = 196609。
3. `sizes` 清單裡有沒有 256x384。
4. `raw … ÷64 / ÷16` 換出來的溫度是否合理（手、室溫）。
5. Tiny1B：`rowΔ` 哪個小；畫面是否正常（不歪斜）。
6. FFC 有沒有喀一聲、發射率改了溫度有沒有變。
7. **MAG160 / Tiny2C 迴歸**：`libomp.so` 只留一份，要確認 Tiny2C 仍能出圖（若壞掉，改成 IRUVC 動態載入或擇一）。

## 之後

- Tiny1C 實際 PID 回報後寫進註解與 README。
- 高低增益切換、SDK 的 NUC 表溫度修正、sensorTemp（`getCurrentVTemperature`）。
