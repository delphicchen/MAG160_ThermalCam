# UI v2 plan (2026-09-15)

1. 預設方向：`rotation = 90`、`mirror = true`（手機直插 + 直立 UI；橫向時是 180°+mirror）。
2. 直立版面：manifest `portrait`；`safeDrawingPadding` 避開狀態列/挖孔；
   影像在上（置中、依寬度縮放），色條 + 讀值 + 控制區在下。
3. 色條圖例：影像正下方，palette LUT 漸層 + lo / mid / hi 溫度標籤。
4. 繪圖效率：
   - Bitmap 在背景處理執行緒用 `setPixels` 一次產生（原本主執行緒逐點 `setPixel`）。
   - SDK 迴圈改為「有新幀才處理」，不再在處理後額外 `delay(period)`。
   - 狀態列顯示每幀處理 ms。
5. MAX / MIN ROI 各自可開關（標記旁顯示溫度）。
6. 截圖 / 錄影：`media/Capture.kt`
   - 合成畫面（影像 + ROI + SPOT + 色條）→ PNG 存 `Pictures/MagViewer`。
   - MediaCodec H.264（Surface 輸入）+ MediaMuxer → MP4 存 `Movies/MagViewer`，
     在背景處理執行緒送幀。
7. 解析度：感測器實體 160×120，無法憑空增加真實細節。
   做法：在溫度場（上色前）做 bicubic 放大 1× / 2× / 4×（4× = 640×480），
   畫面與截圖/錄影都用放大後影像；溫度統計與 SPOT 仍取原生格點。

## 第二階段（2026-09-16）
8. 時間域降噪 `pipeline/TemporalDenoise.kt`：移植 enhance.py 的 motion-adaptive IIR，
   但零配置：雜訊 σ 每 8 幀用 1/16 稀疏取樣算 MAD（不再每幀全排序），exp 改查表，
   history buffer 重用。只作用在顯示場（MIN/MAX/SPOT 仍取原始幀）。
   順序：temporal → bilateral → 放大。方向/來源改變時重置。
9. Anime4K GPU 放大 `pipeline/Anime4kGpu.kt`：
   - assets 放原版 `Anime4K_Upscale_CNN_x2_M.glsl`（MIT），runtime 解析 mpv hook
     標頭（BIND/SAVE/WIDTH/HEIGHT RPN），自動產生 GLES 3.0 fragment shader。
   - 專屬 GL 執行緒 + EGL pbuffer；輸入為上色前的正規化溫度場（灰階），
     2× = 1 次、4× = 2 次，最後在 shader 內查 palette LUT，glReadPixels 回 Bitmap。
     → 截圖、錄影、ROI 標記流程不變。
   - 初始化或 shader 失敗 → 自動退回 bicubic 並提示。
   - 抽屜：放大方式 Bicubic / Anime4K；Temporal denoise 開關 + 強度。
   - 致謝：THIRD_PARTY_NOTICES.md + assets/anime4k/LICENSE。
