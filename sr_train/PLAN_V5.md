# Thermal SR v5 — 蒸餾 + 重參數化（2026-11-14）

## 目標
在**不改 App、不增加手機運算**的前提下，讓三種模型（完整 64/10、中量 48/10、輕量 32/10）
都比 v4 好：PSNR 更高、細紋理不再被抹成平塊（水彩感），而且**捏造熱點不增加**。

做法是把 A100 的算力花在訓練上，而不是模型大小上：

| | 推論（手機） | 訓練（Colab） |
|---|---|---|
| v4 | SRVGGNetCompact 64/48/32 × 10 | 同一個網路，L1 + 頻譜 + 梯度 |
| v5 | **完全相同的網路與 ncnn 圖** | 大老師（RRDBNet 64/23）+ 重參數化學生 |

## 為什麼這兩招
- **知識蒸餾**：老師 16.7 M 參數，約學生的 40 倍，看同樣的退化資料。學生除了學 GT，也學老師在
  「同一張輸入」上的輸出——老師的輸出代表「從這張輸入能還原到什麼程度」，對小網路是比 GT 更好學的
  目標。v3 的經驗是 GAN 會捏造熱點（9 倍），所以老師只用 L1 路線（RealESRNet_x4plus 起步，不用
  GAN 版的 RealESRGAN_x4plus），學生的蒸餾損失也是 L1 + 頻譜 + 梯度，不加感知或對抗損失。
- **重參數化（RepVGG / ECBSR）**：訓練時每個 3×3 卷積拆成 3×3 + 1×1 + 恆等三條路，訓練後線性合併
  回單一 3×3。推論的網路一模一樣，只是訓練時優化比較容易走到好的解。

兩者可以疊加：學生就是重參數化的網路。

## 流程（`colab/train_thermal_sr_v5.ipynb`，Run all）
1. 安裝、下載資料：沿用 v4（FLIR aligned + CIDIS train，CIDIS val 不進訓練）。
2. 老師起點：`RealESRNet_x4plus.pth` 的 RGB 摺成單通道（`scripts/grey_teacher_init.py`，
   輸出 = RGB 三通道平均，無損）。
3. 學生起點：App 內建的完整 / 中量 / 輕量模型（ncnn → .pth，`scripts/ncnn_to_pth.py`），
   展開成重參數化格式（`scripts/rep_fold.py --init`：3×3 = W − I、1×1 = 0），第一步的輸出就等於 v4。
4. 老師訓練：`options/03_teacher_rrdb_x4.yml`，30k iter，學習率 1e-4（微調）。
5. 學生蒸餾：`options/04_distill_rep_x4.yml`，`model_type: ThermalDistillModel`，
   損失 = L1FFT(學生, GT) + 0.5 × L1FFT(學生, 老師)，每個大小 60k iter，學習率 1e-4。
   三個學生共用同一個老師，依 `MODELS` 順序訓練。
6. 合併：`scripts/rep_fold.py` 把學生合併回 SRVGGNetCompact（自我檢查，兩個網路輸出相對誤差 < 1e-4）。
7. 驗收：`scripts/eval_psnr.py`（CIDIS val 200 張，4 倍縮小再放大）比較 v4 / v5 / 老師；
   `scripts/hallucination_check.py` 比較 v4 與 v5 的捏造熱點。
8. 匯出：沿用 v4 的 ONNX → ncnn fp16 → 驗證 → zip。完整：160×120 / 120×160；中量、輕量：
   256×192 / 192×256（與 v4 相同慣例）。

## 驗收標準（每個大小分別判斷）
- PSNR 高於 App 內建的同大小模型（CIDIS val，預期 +0.2–0.5 dB，未實測）。
- 捏造熱點數不高於同大小的 v4（真實 160×120 畫面優先，沒有就用 CIDIS val 20 張）。
- 兩項都過才放進 App；只過 PSNR、捏造變多就不採用（v3 GAN 的教訓）。

## 預算
- v4 第一階段 80k iter 在 A100 上約 5–6 小時，瓶頸是資料前處理（GPU 上的退化），不是模型。
- 老師（RRDBNet 23 塊）每 iter 的運算比學生大很多，30k iter 預估數小時；學生每 iter 多一次老師前向。
- 三個學生 × 60k 是最大的部分。實際速度看 log 裡的 `eta`；運算單元不夠就縮減 `MODELS`，
  或降低 `STUDENT_ITER`。
- **先跑 `SMOKE = True`**（每階段 300 iter，約 15 分鐘）確認整本 notebook 跑得完，再正式跑。

## 本機已驗證
- `RepSRVGG` 合併 / 展開：隨機權重，相對誤差約 2e-6；App 內建的完整、中量、輕量模型
  展開再合併回來，權重完全一致。
- 兩個 yml 可解析，退化與感測器參數與 v4 相同。
- notebook 各 code cell 語法正確。

## 未驗證（要靠 Colab 的 SMOKE 跑）
- `ThermalDistillModel` 在 BasicSR 裡實際訓練（本機沒有 CUDA，Real-ESRGAN 的退化直接呼叫 `.cuda()`）。
- 老師在 A100 上的速度與記憶體（batch 12、gt 256）。
- `grey_teacher_init.py` 的輸出能否 strict 載入 RRDBNet 1 通道。

## 刻意不做（留給之後）
- Tiny（256×192）專用的退化參數與模型：先看 v5 在 MAG160 設定下的效果。
- 多幀（時域）超分：效果可能最大，但 App 處理流程要改。
- GAN / 感知損失：v3 已證明會捏造熱點。

## 檔案
| 檔案 | 用途 |
|---|---|
| `thermal_arch/thermal_v5.py` | `RepSRVGG`、合併 / 展開函式、`ThermalDistillModel` |
| `options/03_teacher_rrdb_x4.yml` | 老師設定 |
| `options/04_distill_rep_x4.yml` | 學生（蒸餾 + 重參數化）設定 |
| `scripts/rep_fold.py` | 合併（訓練後）/ 展開（`--init`，訓練前），自我檢查 |
| `scripts/grey_teacher_init.py` | RealESRNet_x4plus → 單通道老師起點 |
| `scripts/eval_psnr.py` | 多個模型的 PSNR 比較表 |
| `colab/train_thermal_sr_v5.ipynb` | 一鍵 Run all 的 Colab notebook |
