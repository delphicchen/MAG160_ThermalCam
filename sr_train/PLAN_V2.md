# Thermal SR v2 — 64/8, single channel, new data（2026-09-24）

## 為什麼重訓
- 64/16 在手機上 up 75–92 ms（Thermal SR，錄影時 92），跑不到 15 fps；64/8 算量約 52 %，
  預估 40–48 ms。
- 條紋雜訊已改成與安裝方向無關（commit caffcc4），要重訓才生效。
- 資料：拿掉 Roboflow 匯出的兩份（`.rf.` = 拉伸成 640×640），加入 CIDIS。

## 這次改什麼
| # | 項目 | 檔案 |
|---|---|---|
| 1 | num_conv 16 → 8 | `options/01_*.yml`、`options/02_*.yml`、notebook |
| 2 | 單通道輸入輸出（1 → 1）：資料流維持 3 通道（DiffJPEG 需要），在模型邊界切成 1 通道；感知損失前再複製回 3 通道 | `thermal_arch/thermal_degradation.py`、兩個 yml |
| 3 | 預訓練權重轉換：G 第一層沿輸入通道加總、最後一層每組 RGB 取平均；D 的 `conv0` 加總。1 通道模型輸出 = 原本 3 通道輸出的平均（數學上等價） | `scripts/truncate_pretrained.py`、`scripts/netd_single_channel.py` |
| 4 | 修 `truncate_pretrained.py` off-by-one：最後一層在 `2·num_conv+2`，原本算成 `2·num_conv`，輸出層等於隨機初始化 | 同上 |
| 5 | 匯出、轉換、檢查腳本從權重推斷結構（num_conv、通道數），不再寫死；匯出時輸出加 clamp(0,1)（量化範圍固定，App 本來就會 clamp，結果不變） | `export_onnx.py`、`convert_ncnn.sh`、`hallucination_check.py` |
| 6 | 資料：FLIR_aligned + CIDIS train 700（GitHub，免帳號）；驗證用 CIDIS val 200 張縮小 4 倍，不進訓練 | notebook cell 6、25 |
| 7 | `.mgt` → 驗證用 160×120 PNG（選用） | `scripts/mgt_to_png.py` |
| 8 | App：ncnn 從 `.param` 讀出輸入通道數，1 / 3 通道模型都能跑；輸出依實際通道數平均 | `NcnnUpscaler.kt`、`ncnn_upscaler.cpp` |
| 9 | 致謝：CIDIS（無授權檔，README 要求引用） | `THIRD_PARTY_NOTICES.md`、`sr_train/README.md` |

## 刻意不做（留給下一次）
- 雜訊實測（FPN 用 °C 量、場景溫差分布）— 使用者決定先不做。
- 損失與增強（FFT/SSIM、溫度縮放、拼貼）— 一次只改一組變因。
- 16-bit 原始資料（FLIR ADAS 官方版，需註冊）。
- INT8 / NPU 的 PTQ 轉換腳本（這次只先做好單通道和輸出 clamp 兩項準備）。

## 驗證
- 權重轉換：CPU torch 上比對「1 通道模型輸出」與「3 通道模型輸出取平均」。
- App：編譯；舊的 3 通道內建模型照常能跑（通道數判斷走 3 的分支）。
- 訓練本身只能在 Colab 跑，本機沒有 basicsr。

## 進度
- 2026-09-24　1–7、9 完成（訓練端可以開跑）。CPU torch 上驗證：舊截斷腳本確實漏掉
  `body.33/34`、`body.32` 形狀不合被略過；新版 16/8 層、3/1 通道都能嚴格載入，輸出層就是
  預訓練那層；灰階 G 與 RGB G 三通道平均最大差 5e-6，灰階 D 與 RGB D 最大差 6e-5；
  匯出（結構自動推斷、clamp、inputshape）→ pnnx → ncnn 走通，`.param` 第一層 `6=576`
  （= 64·1·3·3，App 可據此判斷通道數），FLOPs 11.7 G（舊 64/16 RGB 約 23.8 G）。
  未訓練的截斷模型 fp16 verify 最大差 3.2 階（門檻 1.5 階、平均 0.2 階過關）：未訓練權重的
  中間值很大，fp16 誤差跟著放大；訓練後的模型由 notebook cell 28 重驗。
- 2026-09-24　8 完成：`NcnnUpscaler` 從 `.param` 算出輸入通道（內建舊模型 → 3、pnnx 轉出的
  1 通道模型 → 1，讀不到 → 3），native 端依此填入 1 或 3 個通道、輸出依實際通道數平均。
  App 編譯通過；新模型推到手機時用這版 App。
