# 深度圖逐點視差補償（方案 D）計畫（2026-10-09）

> **2026-10-12 已放棄**，改走雙鏡頭立體測距（`PLAN_STEREO.md`）。App 內的深度模型程式與
> assets 已移除。實測結論留存如下：
> - ncnn 在 Mali（天璣 9300+）跑 ViT 很慢：Vulkan fp16 ≈ 1.8 s、fp32 ≈ 9 s；CPU fp16 ≈ 0.2–0.27 s。
>   （原本注意力的 MatMul 沒有 Vulkan kernel，改成 MultiHeadAttention 後仍慢。）
> - 公尺版模型的尺度綁定訓練時的焦距（像素）：Hypersim 縮到 518 → fx ≈ 598 px，
>   我們的 196×266 輸入 fx ≈ 175 px → 讀數約高估 3.4 倍（地磚實測 1.2/1.8/2.4 m 吻合，
>   0.6 m 偏大）。可用 `Z × fx_輸入 / 598` 修正，但單眼精度（±10–20 %）仍不如光學測距。


## 目標
現行融合整張畫面只用一個物距 Z（`FusionCalibration.Fit.at(invZ)` → 單一 zoom/dx/dy），
近距離、斜面、前後景混合時必然錯位。改用**公尺版**單眼深度模型直接估出可見光畫面的
**公尺深度圖**，逐點套用同一份校正擬合。

（2026-10-10 改向：主鏡頭 LENS_FOCUS_DISTANCE 是 UNCALIBRATED，讀值時好時壞，
不適合當尺度錨點，所以從「相對深度 + 對焦錨定」改成「公尺版模型、不需錨定」。）

- 做成新的**物距來源「深度」**（與 自動 / 手動 / ∞ 並列）；切回其他來源 = 完全回到現行行為。
- 校正資料、擬合模型不變（`dx(Z) = dx∞ + kx/Z` 照用，只是 Z 變成逐點）。
- 超分不另做開關；開關旁提示「建議融合時關閉超分（GPU 負載）」。

## 模型
- **Depth Anything V2 Metric Small**（ViT-S，24.8M 參數）：室內版 Hypersim（max 20 m）為主，
  室外版 VKITTI（max 80 m）備選。輸出直接是公尺。
- 授權：官方 repo 註明「Depth-Anything-V2-Small model is under the Apache-2.0」，Metric Small
  列在同一列，但 HF 頁面沒標授權 → 發佈前再確認；Base/Large 為 CC-BY-NC，不用。
- 精度預期：單眼公尺深度約 5–10 % 相對誤差，0.5 m 時約數公分。
- 固定輸入尺寸（patch 14 的倍數），暫定 **266×196**（直立 4:3 的橫向）與 **196×266**（直式），
  依 `fusionRotation` 後的直立畫面方向選一個；模型對方向敏感，**輸入一定要轉正**。
- 可見光目前只有 Y 平面 → 灰階複製成 3 通道 + ImageNet mean/std 正規化。
  （深度模型對灰階尚可；若不足再擴充 `RgbCamera` 取低解析 UV。）
- fp16 ncnn 約 50 MB。

## 深度 → 1/Z
- `invZ(p) = 1 / depth_m(p)`，不需錨定。
- 中央視窗中位數當這一幀的「全域物距」，顯示在距離標籤，並供 SEARCH 模式用。
- 選配：一個尺度修正係數（預設 1.0），用已存校正點的實際距離與模型讀值比對自動求出，
  吸收模型在這支手機、這種場景的系統偏差。
- 逐點 invZ clamp 到 [0, 1/0.2 m]；深度圖做時間 EMA 平滑避免閃爍。

## 套用到合成（`Fusion.kt`）
深度在**可見光座標**，但合成是「顯示座標 → 可見光座標」，所以用定點迭代：
1. 顯示畫面上取粗網格節點（~40×30）。
2. 每節點：`u0 = map(n, invZ_anchor)` → 查深度得 `invZ1` → `u1 = map(n, fit.at(invZ1))` → 再迭代一次。
3. 得到每節點的可見光座標 (u, v) → `warp` 網格。
- `Fusion.compose` 增加可選參數 `warp: FloatArray?`：null 走現行的可分離查表（零效能變化）；
  非 null 時逐像素雙線性內插 (u,v) 再經 rotation 轉 raw index（rows 照舊 parallel）。
- EDGES 模式：`sampledRect` 改由 warp 網格的外接框決定 `edgesFor` 的 ROI。
- SEARCH（廣角）模式不套用（它是畫熱像框，不是逐點對位）。
- 物體邊緣深度不連續處會有些微拉扯（雙線性跨邊界）；遮擋區無解，屬已知限制。

## 執行緒（照專案慣例：重運算不進主迴圈）
- `DepthEstimator` 有自己的單執行緒 executor。處理迴圈每幀：若 estimator 閒置、可見光 frame id 有新的、
  且距上次 ≥ 200 ms → 丟一個請求；完成後以 mutex 存「最新深度圖 + frame id」，ready flag 通知。
- 處理迴圈只讀最新結果，算 warp 網格（網格很小，每幀算即可）。
- 與 `NcnnUpscaler` 各自一個 `ncnn::Net`，共用 Vulkan device。

## 改動清單
| # | 檔案 | 內容 |
|---|---|---|
| 1 | `depth_export/export_depth_ncnn.py`（新，與 `sr_train/` 同層） | 下載 HF `depth-anything/Depth-Anything-V2-Small` → torch trace 兩種固定尺寸 → pnnx → `depth_<w>x<h>_fp16.param/.bin`，打包 zip；README 說明環境 |
| 2 | `app/src/main/cpp/depth_estimator.cpp`（新）+ `CMakeLists.txt` | JNI：create/destroy/run（灰階 bytes + 旋轉 → 轉正、縮放、正規化 → 推論 → float 圖） |
| 3 | `pipeline/DepthEstimator.kt`（新） | 仿 `NcnnUpscaler`：模型檔位置、匯入 zip、背景 executor、最新結果 |
| 4 | `pipeline/FusionCalibration.kt` | `DistanceSource.DEPTH`、深度 → 逐點 invZ、warp 網格的純函式（可單元測試） |
| 5 | `pipeline/Fusion.kt` | `compose` 的 `warp` 路徑；warp 外接框 → ROI |
| 6 | `ui/ViewerViewModel.kt` | DEPTH 來源：排程請求、建 warp、傳入 compose/edgesFor；模型缺失時退回手動並提示 |
| 7 | `ui/ViewerScreen.kt` | 「深度」選項 + 超分提示 + 模型匯入（沿用 SR 匯入 UI 模式）；除錯用「顯示深度圖」 |
| 8 | `res/values{,-zh-rTW,-zh-rCN}/strings.xml` | 新字串三語 |
| 9 | `THIRD_PARTY_NOTICES.md`、README ×3 | Depth Anything V2 致謝與授權、功能說明、限制 |
| 10 | 單元測試 | 深度 → invZ、定點迭代收斂 |

## 分次執行
1. **P1 轉檔**：#1，先在 PC 上確認 pnnx 轉得出、ncnn 推論結果與 torch 一致。（最大風險：ViT 運算子轉換）
   - ✅ 2026-10-10 完成：`depth_export/out/depth_metric_ncnn.zip`（兩個方向，共 97.7 MB）。
     ncnn 與 torch 相對誤差中位數 0.66 %；PC CPU 約 110 ms/幀，17 GFLOPs。
     轉檔時改寫了 DINOv2 attention（避免跨 batch 的 5-D permute）並把 LayerScale 併入前一層 Linear，
     兩者數學等價，消除 pnnx 的不支援警告。
2. **P2 推論接入**：#2 #3 + 除錯「顯示深度圖」，在 14T Pro 上量延遲。
   - 2026-10-10 程式完成、編譯通過，**待實機測試**：抽屜「深度模型預覽（除錯）」+ 匯入/移除，
     畫面左上角小窗顯示 1/Z 色圖（近 = 亮），說明行顯示中央中位數深度與推論時間。
     匯入共用 `NcnnModelFiles`（由 `SrModelFiles` 泛化），模型放 `files/depth_model/`。
   - 實機（14T Pro）：深度圖合理、中央距離實測準確；但推論約 2000 ms。原因：注意力的 24 個 MatMul
     在 ncnn 沒有 Vulkan kernel，每層 GPU↔CPU 來回。改用 nn.MultiheadAttention 轉成 ncnn
     MultiHeadAttention（有 Vulkan），模型改為內建於 APK（assets/depth_model/）。待重測速度。
3. **P3 補償**：#4 #5 #6 #7 #10，實機以近距離/斜面測試。
4. **P4 收尾**：#8 #9，編譯、發版流程另議。

## 決定
- 模型像 SR 模型一樣**匯入 zip**（APK 不變）；之後若要內建再議。
- 轉檔在本機新建 conda env `depth`（torch CPU + pnnx + ncnn python）。
